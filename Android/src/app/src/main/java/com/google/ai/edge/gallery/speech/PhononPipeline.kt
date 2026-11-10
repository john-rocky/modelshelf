/*
 * Copyright 2026 John Rocky
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.speech

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File

private const val TAG = "MSPhonon"

/** One window's result. */
class PhononWindow(
  val index: Int,
  /** The graphs' window length, as the files name it ("5" ... "30"). */
  val bucket: String,
  val samples: Int,
  val validFrames: Int,
  val tokens: List<TdtToken>,
  val text: String,
  val melMs: Long,
  val encoderMs: Long,
  val decodeMs: Long,
  val steps: Int,
) {
  val startSeconds: Double
    get() = index * PhononPipeline.WINDOW_SAMPLES / SPEECH_SAMPLE_RATE_HZ.toDouble()

  val endSeconds: Double
    get() = startSeconds + samples / SPEECH_SAMPLE_RATE_HZ.toDouble()
}

/** A whole file's result: the window texts joined with one space, as the card's runner joins them. */
class PhononTranscript(val windows: List<PhononWindow>, val inferenceMs: Long) {
  val text: String
    get() = windows.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

/** The encoder gave values that are not numbers (seen with a broken NPU runtime). */
class PhononNumericException(message: String) : Exception(message)

/**
 * Phonon-2.5-English (litert-community, LiteRT 2.3.0) as the card's "How it works" describes it:
 * 16 kHz audio -> 30 s windows -> mel front end (CPU) -> encoder (NPU on the SM8850 with the
 * ahead-of-time file, else the f16 file on the GPU, else the i8 file on the CPU) -> greedy TDT
 * decoding (int8 decoder on the CPU) -> text.
 *
 * Not thread safe: make, use and close it on one thread ([PhononEngine]).
 */
class PhononPipeline
private constructor(
  val backend: PhononFiles.Backend,
  /** How the encoder runs, for logs and the API's headers. */
  val backendNote: String,
  private val models: List<CompiledModel>,
  private val env: Environment,
  private val buckets: List<Bucket>,
  private val decoder: CompiledModel,
  private val tokenizer: TdtTokenizer,
  /** Every buffer made so far, so a load that fails part way can close them ([loadOn]). */
  made: MutableList<TensorBuffer>,
) : AutoCloseable {

  /** Milliseconds the load and the warm-up window took. */
  var loadMs: Long = 0L
    private set

  private class Bucket(
    val name: String,
    val mel: CompiledModel,
    val encoder: CompiledModel,
    val signature: String,
    made: MutableList<TensorBuffer>,
  ) {
    /** The most valid samples this window length holds: the mel graph's audio length. */
    val numSamples: Int = run {
      val layout: TensorType.Layout? = mel.getInputTensorType("audio", SERVING).layout
      layout?.dimensions?.fold(1) { a, b -> a * b }
        ?: throw IllegalStateException("phonon25en_mel_${name}s.tflite: no audio input size")
    }
    val melIn = listOf("audio", "n_valid").associateWith { mel.createInputBuffer(it, SERVING).also { b -> made.add(b) } }
    val melOut =
      listOf("features", "frame_mask", "mel_mask").associateWith { mel.createOutputBuffer(it, SERVING).also { b -> made.add(b) } }
    val encIn = listOf("features", "mel_mask").associateWith { encoder.createInputBuffer(it, signature).also { b -> made.add(b) } }
    val encOut = mapOf("enc" to encoder.createOutputBuffer("enc", signature).also { b -> made.add(b) })
    val buffers: List<TensorBuffer> = melIn.values + melOut.values + encIn.values + encOut.values
  }

  // predict: inputs c, h, token -> outputs c_out, dec_out, h_out; joint: dec_out, enc_frame -> logits.
  private val tokenBuffer = decoder.createInputBuffer("token", PREDICT).also { b -> made.add(b) }
  private val decOut = decoder.createOutputBuffer("dec_out", PREDICT).also { b -> made.add(b) }
  private val encFrame = decoder.createInputBuffer("enc_frame", JOINT).also { b -> made.add(b) }
  private val jointIn = mapOf("dec_out" to decOut, "enc_frame" to encFrame)
  private val jointOut = mapOf("logits" to decoder.createOutputBuffer("logits", JOINT).also { b -> made.add(b) })
  // The output states of one predict call are the input states of the next one.
  private val states: List<List<TensorBuffer>> =
    listOf(
      listOf("c", "h").map { decoder.createInputBuffer(it, PREDICT).also { b -> made.add(b) } },
      listOf("c_out", "h_out").map { decoder.createOutputBuffer(it, PREDICT).also { b -> made.add(b) } },
    )
  private val predictIn = states.map { (c, h) -> mapOf("c" to c, "h" to h, "token" to tokenBuffer) }
  private val predictOut = states.map { (c, h) -> mapOf("c_out" to c, "dec_out" to decOut, "h_out" to h) }
  private var stateIndex = 0
  private val zeroState = FloatArray(STATE_SIZE)
  private val decoderLoop = TdtGreedyDecoder()

  private inner class Steps(private val encoded: FloatArray) : TdtSteps {
    override fun reset() {
      states[stateIndex].forEach { it.writeFloat(zeroState) }
      predict(BLANK)
    }

    override fun joint(frame: Int): FloatArray {
      encFrame.writeFloat(encoded.copyOfRange(frame * ENCODER_DIM, (frame + 1) * ENCODER_DIM))
      decoder.run(jointIn, jointOut, JOINT)
      return jointOut.getValue("logits").readFloat()
    }

    override fun predict(token: Int) {
      tokenBuffer.writeInt(intArrayOf(token))
      decoder.run(predictIn[stateIndex], predictOut[1 - stateIndex], PREDICT)
      stateIndex = 1 - stateIndex
    }
  }

  /** Transcribes [audio] (16 kHz mono) window by window; [onWindow] sees each window as it ends. */
  fun transcribe(audio: FloatArray, onWindow: ((PhononWindow) -> Unit)? = null): PhononTranscript {
    val t0 = SystemClock.elapsedRealtime()
    val windows = mutableListOf<PhononWindow>()
    var start = 0
    var index = 0
    while (start < audio.size) {
      val end = minOf(audio.size, start + WINDOW_SAMPLES)
      if (end - start >= MIN_WINDOW_SAMPLES) {
        val w = transcribeWindow(audio, start, end, index)
        windows.add(w)
        onWindow?.invoke(w)
      }
      start = end
      index++
    }
    return PhononTranscript(windows, SystemClock.elapsedRealtime() - t0)
  }

  private fun transcribeWindow(audio: FloatArray, from: Int, until: Int, index: Int): PhononWindow {
    val n = until - from
    val bucket = buckets.firstOrNull { n <= it.numSamples } ?: buckets.last()
    val valid = minOf(n, bucket.numSamples)
    val t0 = SystemClock.elapsedRealtime()
    val padded = FloatArray(bucket.numSamples)
    System.arraycopy(audio, from, padded, 0, valid)
    bucket.melIn.getValue("audio").writeFloat(padded)
    bucket.melIn.getValue("n_valid").writeInt(intArrayOf(valid))
    bucket.mel.run(bucket.melIn, bucket.melOut, SERVING)
    val t1 = SystemClock.elapsedRealtime()
    // The two graphs may run on different accelerators: copy, do not share, the buffers.
    for ((name, buffer) in bucket.encIn) buffer.writeFloat(bucket.melOut.getValue(name).readFloat())
    bucket.encoder.run(bucket.encIn, bucket.encOut, bucket.signature)
    val encoded = bucket.encOut.getValue("enc").readFloat()
    val t2 = SystemClock.elapsedRealtime()
    val validFrames = bucket.melOut.getValue("frame_mask").readFloat().sum().toInt()
    var nan = 0
    for (i in 0 until minOf(encoded.size, validFrames * ENCODER_DIM)) if (encoded[i].isNaN()) nan++
    if (nan > 0) {
      throw PhononNumericException(
        "the encoder on ${backend.label.uppercase()} gave $nan values that are not numbers (window ${bucket.name} s)"
      )
    }
    val tokens = decoderLoop.decode(Steps(encoded), validFrames)
    val t3 = SystemClock.elapsedRealtime()
    val text = tokenizer.decode(tokens.map { it.id }, dropIds = setOf(END_TOKEN))
    return PhononWindow(index, bucket.name, valid, validFrames, tokens, text, t1 - t0, t2 - t1, t3 - t2, decoderLoop.lastSteps)
  }

  override fun close() {
    val buffers = buckets.flatMap { it.buffers } + listOf(tokenBuffer, decOut, encFrame) +
      jointOut.values + states.flatten()
    buffers.forEach {
      try {
        it.close()
      } catch (e: Exception) {
        Log.w(TAG, "Closing a buffer failed: ${e.message}")
      }
    }
    models.forEach {
      try {
        it.close()
      } catch (e: Exception) {
        Log.w(TAG, "Closing a graph failed: ${e.message}")
      }
    }
    try {
      env.close()
    } catch (e: Exception) {
      Log.w(TAG, "Closing the LiteRT environment failed: ${e.message}")
    }
  }

  companion object {
    /** 30 s windows of the card (479,840 samples); a shorter last window under 320 samples is dropped. */
    const val WINDOW_SAMPLES = 479_840
    const val MIN_WINDOW_SAMPLES = 320
    private const val SERVING = "serving_default"
    private const val PREDICT = "predict"
    private const val JOINT = "joint"
    private const val ENCODER_DIM = 640
    private const val STATE_SIZE = 2 * 640
    private const val BLANK = 8192
    const val END_TOKEN = 3

    /**
     * Loads the pipeline on the first encoder of [present] that loads and runs a warm-up window:
     * the NPU file (only on its SoC, and only when the app carries LiteRT's Qualcomm dispatch
     * library), then the f16 file on the GPU, then the i8 file on the CPU.
     */
    fun load(context: Context, present: PhononFiles.Present, log: (String) -> Unit = {}): PhononPipeline {
      val errors = mutableListOf<String>()
      val order = present.usable()
      if (order.isEmpty()) {
        throw IllegalStateException(
          "no encoder file this phone can run (the NPU file is for ${PhononFiles.NPU_SOC} phones only)"
        )
      }
      val tokenizer = TdtTokenizer.load(File(present.dir, PhononFiles.TOKENIZER))
      for (b in order) {
        val t0 = SystemClock.elapsedRealtime()
        try {
          val p = loadOn(context, present.dir, b, tokenizer, t0)
          log("Phonon-2.5-English: encoder on ${p.backendNote}; loaded and warmed in ${p.loadMs} ms")
          return p
        } catch (e: Throwable) {
          if (e is VirtualMachineError) throw e
          val why = "${b.label.uppercase()}: ${e.message ?: e.javaClass.simpleName}"
          errors.add(why)
          log("Phonon-2.5-English: could not use $why")
          Log.w(TAG, "Phonon-2.5-English on ${b.label} failed", e)
        }
      }
      throw IllegalStateException(errors.joinToString("; "))
    }

    private fun loadOn(
      context: Context,
      dir: File,
      backend: PhononFiles.Backend,
      tokenizer: TdtTokenizer,
      t0: Long,
    ): PhononPipeline {
      if (backend == PhononFiles.Backend.NPU) {
        // LiteRT 2.3.0 loads an ahead-of-time NPU file without its dispatch library and aborts on
        // the first run (hazard npu-aot-without-dispatch-loads): check the library first.
        val libs = File(context.applicationInfo.nativeLibraryDir).list().orEmpty()
        if (libs.none { it.startsWith("libLiteRtDispatch_") }) {
          throw IllegalStateException("this build carries no LiteRT NPU dispatch library")
        }
      }
      val env =
        if (backend == PhononFiles.Backend.NPU) Environment.create(BuiltinNpuAcceleratorProvider(context))
        else Environment.create()
      val models = mutableListOf<CompiledModel>()
      val made = mutableListOf<TensorBuffer>()
      var built: PhononPipeline? = null
      try {
        fun cpu(threads: Int) =
          CompiledModel.Options(setOf(Accelerator.CPU)).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = threads)
          }
        fun open(name: String, options: CompiledModel.Options): CompiledModel =
          CompiledModel.create(File(dir, name).absolutePath, options, env).also { models.add(it) }

        val encoderOptions =
          when (backend) {
            PhononFiles.Backend.NPU ->
              CompiledModel.Options(setOf(Accelerator.CPU, Accelerator.NPU)).apply {
                // The burst mode, as in the card's NPU runs.
                qualcommOptions =
                  CompiledModel.QualcommOptions(
                    htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST
                  )
              }
            PhononFiles.Backend.GPU ->
              CompiledModel.Options(setOf(Accelerator.CPU, Accelerator.GPU)).apply {
                // The f16 file rescales its LayerNorm inputs so fp16 arithmetic does not overflow; the
                // seven window lengths read one copy of the weights.
                gpuOptions =
                  CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP16,
                    constantTensorSharing = true,
                  )
              }
            PhononFiles.Backend.CPU -> cpu(4)
          }
        val encoder = open(backend.file, encoderOptions)
        val buckets = PhononFiles.WINDOW_NAMES.map { name ->
          Bucket(name, open(PhononFiles.melFile(name), cpu(2)), encoder, "enc$name", made)
        }
        val decoder = open(PhononFiles.DECODER, cpu(1))
        val note =
          when (backend) {
            PhononFiles.Backend.NPU -> "NPU (${PhononFiles.NPU_SOC} Hexagon, QNN HTP burst mode, LiteRT dispatch)"
            PhononFiles.Backend.GPU -> "GPU (fp16, shared constants)"
            PhononFiles.Backend.CPU -> "CPU (int8, 4 threads)"
          }
        val p = PhononPipeline(backend, note, models, env, buckets, decoder, tokenizer, made)
        built = p
        // A warm-up window: the NPU file loads without its runtime and fails only when it runs,
        // and a broken runtime shows as values that are not numbers.
        val noise = FloatArray(2 * SPEECH_SAMPLE_RATE_HZ) { i -> ((i * 7919 % 2003) - 1001) / 100_000f }
        p.transcribe(noise)
        p.loadMs = SystemClock.elapsedRealtime() - t0
        return p
      } catch (e: Throwable) {
        val b = built
        if (b != null) {
          b.close()
        } else {
          // The load failed part way: close the buffers made so far, then the graphs.
          made.forEach {
            try {
              it.close()
            } catch (_: Exception) {}
          }
          models.forEach {
            try {
              it.close()
            } catch (_: Exception) {}
          }
          try {
            env.close()
          } catch (_: Exception) {}
        }
        throw e
      }
    }
  }
}
