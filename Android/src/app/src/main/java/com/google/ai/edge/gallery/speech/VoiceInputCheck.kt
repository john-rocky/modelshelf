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
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "MSVoiceInput"

/**
 * The level of the audio voice input hears, in dBFS (0 = full scale), one log line per second and a
 * summary at the end. Only numbers are logged, never what was said.
 */
class VoiceLevelMeter(
  private val source: String,
  private val sampleRateHz: Int = SPEECH_SAMPLE_RATE_HZ,
  private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
  private var samplesTotal = 0L
  private var sumSquaresTotal = 0.0
  private var peakTotal = 0f
  private var secondSamples = 0
  private var secondSumSquares = 0.0
  private var secondPeak = 0f
  private var secondIndex = 0
  private var loudestSecondDb = Double.NEGATIVE_INFINITY
  private var loudestSecondAt = -1
  private var finished = false

  @Synchronized
  fun add(samples: FloatArray, count: Int = samples.size) {
    if (finished) return
    for (i in 0 until count) {
      val s = samples[i]
      val sq = s.toDouble() * s
      sumSquaresTotal += sq
      secondSumSquares += sq
      val a = if (s < 0) -s else s
      if (a > peakTotal) peakTotal = a
      if (a > secondPeak) secondPeak = a
      samplesTotal++
      secondSamples++
      if (secondSamples == sampleRateHz) endSecond()
    }
  }

  private fun endSecond() {
    val rms = dbfs(sqrt(secondSumSquares / secondSamples))
    log(
      "Level $source, second ${secondIndex + 1}: rms ${fmt(rms)} dBFS, peak ${fmt(dbfs(secondPeak.toDouble()))} dBFS",
    )
    if (rms > loudestSecondDb) {
      loudestSecondDb = rms
      loudestSecondAt = secondIndex + 1
    }
    secondIndex++
    secondSamples = 0
    secondSumSquares = 0.0
    secondPeak = 0f
  }

  /** Logs the summary once; later calls do nothing. */
  @Synchronized
  fun finish() {
    if (finished) return
    finished = true
    if (secondSamples > 0) endSecond()
    val seconds = samplesTotal / sampleRateHz.toDouble()
    val rms = if (samplesTotal > 0) dbfs(sqrt(sumSquaresTotal / samplesTotal)) else Double.NEGATIVE_INFINITY
    log(
      "Level $source, summary: ${String.format(Locale.US, "%.2f", seconds)} s, rms ${fmt(rms)} dBFS, peak " +
        "${fmt(dbfs(peakTotal.toDouble()))} dBFS, loudest second ${fmt(loudestSecondDb)} dBFS " +
        "(second $loudestSecondAt)",
    )
  }

  private fun dbfs(v: Double): Double = if (v <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(v)

  private fun fmt(db: Double): String = if (db.isInfinite()) "-inf" else String.format(Locale.US, "%.1f", db)
}

/**
 * Debug builds only (Model Shelf's E2-2 check): a WAV file put in the app's files folder replaces the
 * microphone for voice input, through the recorder's external audio source, so the voice input path
 * can be tested apart from the room. Release builds never look for the file.
 */
object VoiceInputFixture {
  /** In the app's internal files folder (`adb shell run-as <app> cp ... files/`). */
  const val FILE_NAME = "modelshelf_voice_input.wav"

  /** 100 ms of 16 kHz audio, the piece the microphone gives. */
  private const val PIECE_SAMPLES = 1600

  /** The fixture's source when it is there, in a debug build; else null (the microphone is used). */
  fun sourceOrNull(context: Context): (() -> ParcelFileDescriptor?)? {
    if (!BuildConfig.DEBUG) return null
    val file = File(context.filesDir, FILE_NAME)
    if (!file.isFile) return null
    return { open(file) }
  }

  /**
   * Reads [file] as 16 kHz mono audio and writes it as 16-bit PCM into a pipe at the pace of a
   * microphone (100 ms every 100 ms), then closes the pipe: voice input ends by itself, as after
   * Stop. Returns the pipe's read end.
   */
  private fun open(file: File): ParcelFileDescriptor {
    val wav = WavReader.read(file.readBytes())
    val pipe = ParcelFileDescriptor.createPipe()
    Log.i(
      TAG,
      "Debug build: voice input reads ${file.name} (${String.format(Locale.US, "%.2f", wav.seconds)} s, " +
        "${wav.describeSource()}) instead of the microphone",
    )
    Thread(
        {
          val started = SystemClock.elapsedRealtime()
          var written = 0
          try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
              val bytes = ByteArray(PIECE_SAMPLES * 2)
              var piece = 0
              while (written < wav.samples.size) {
                val n = minOf(PIECE_SAMPLES, wav.samples.size - written)
                for (i in 0 until n) {
                  val v = (wav.samples[written + i].coerceIn(-1f, 1f) * 32767f).toInt()
                  bytes[i * 2] = (v and 0xFF).toByte()
                  bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(bytes, 0, n * 2)
                written += n
                piece++
                val due = started + piece * 100L
                val wait = due - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
              }
            }
            Log.i(TAG, "Debug build: ${file.name} ended after ${written} samples")
          } catch (e: IOException) {
            // Voice input stopped first and closed its end.
            Log.i(TAG, "Debug build: ${file.name} stopped after ${written} samples (${e.message})")
          } catch (e: InterruptedException) {
            Log.i(TAG, "Debug build: ${file.name} interrupted after ${written} samples")
          }
        },
        "ms-voice-input-fixture",
      )
      .apply { isDaemon = true }
      .start()
    return pipe[0]
  }
}
