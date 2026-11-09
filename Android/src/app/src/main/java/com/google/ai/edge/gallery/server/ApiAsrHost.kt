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

package com.google.ai.edge.gallery.server

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.gallery.speech.PhononEngine
import com.google.ai.edge.gallery.speech.SPEECH_SAMPLE_RATE_HZ
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriber
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriberResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MSApiAsr"

/** Samples per call of MediaPipe's transcriber (100 ms, as the app's voice input sends them). */
private const val MEDIAPIPE_CHUNK_SAMPLES = 1600

/**
 * The local API's speech recognition: one speech model loaded at a time, run inside the turns of
 * [turns] (one request at a time across chat and speech, the same queue and limits). The speech
 * model and the chat model may stay loaded side by side, each with its own keep-alive.
 *
 * Phonon-2.5-English runs in [PhononEngine], which the app's voice input shares; a MediaPipe model
 * (Parakeet TDT) gets its own AudioTranscriber here.
 */
class ApiAsrHost(
  private val context: Context,
  private val turns: ApiEngineHost,
  private val onStateChanged: () -> Unit,
) {
  private val dispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "ms-api-asr") }.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  @Volatile private var loaded: Loaded? = null
  private var unloadJob: Job? = null
  /** Set when the server stops: a running transcription stops at its next window. */
  @Volatile private var closing = false

  private class Loaded(
    val entry: AsrEntry,
    /** npu, gpu or cpu. */
    val backend: String,
    val backendNote: String,
    /** [AsrKind.MEDIAPIPE] only. */
    val transcriber: AudioTranscriber?,
  ) {
    var expiresAtMs = Long.MAX_VALUE
  }

  /** The model a request runs on. */
  class LoadInfo(
    /** npu, gpu or cpu. */
    val backend: String,
    val backendNote: String,
    /** Milliseconds this request spent loading the model (about 0 when it was loaded). */
    val loadMs: Long,
  )

  class Result(val pieces: List<TranscriptPiece>, val text: String, val inferenceMs: Long)

  /** The loaded speech model and its backend, for /api/ps-like status lines. */
  fun loadedInfo(): Pair<AsrEntry, String>? = loaded?.let { it.entry to it.backend }

  /** Loads [entry] if it is not the loaded model (inside a turn of [turns]). */
  suspend fun load(entry: AsrEntry): LoadInfo {
    unloadJob?.cancel()
    val t0 = SystemClock.elapsedRealtime()
    val l = ensureLoaded(entry)
    return LoadInfo(l.backend, l.backendNote, SystemClock.elapsedRealtime() - t0)
  }

  /**
   * Transcribes [audio] (16 kHz mono) with [entry], inside a turn of [turns], after [load].
   * [onPiece] sees each piece as it ends (a Phonon window or a final MediaPipe segment), on the
   * engine's thread.
   */
  suspend fun transcribe(
    entry: AsrEntry,
    audio: FloatArray,
    onPiece: (TranscriptPiece) -> Unit,
  ): Result {
    unloadJob?.cancel()
    val l = ensureLoaded(entry)
    val job = currentCoroutineContext()[Job]
    val stopped = { closing || job?.isActive == false }
    val t1 = SystemClock.elapsedRealtime()
    val pieces =
      when (entry.kind) {
        AsrKind.PHONON ->
          PhononEngine.run { pipeline ->
            val out = mutableListOf<TranscriptPiece>()
            pipeline.transcribe(audio) { w ->
              val piece = TranscriptPiece(w.text, w.startSeconds, w.endSeconds, w.tokens.map { it.id })
              out.add(piece)
              onPiece(piece)
              if (stopped()) throw CancellationException("The transcription was stopped.")
            }
            out
          }
        AsrKind.MEDIAPIPE ->
          withContext(dispatcher) { mediaPipeTranscribe(l.transcriber!!, audio, stopped, onPiece) }
      }
    return Result(pieces = pieces, text = joinPieces(pieces), inferenceMs = SystemClock.elapsedRealtime() - t1)
  }

  /** Unloads the speech model [DEFAULT_KEEP_ALIVE_MS] after the last request (inside a turn). */
  fun keepAlive() {
    val l = loaded ?: return
    val keep = DEFAULT_KEEP_ALIVE_MS
    l.expiresAtMs = System.currentTimeMillis() + keep
    unloadJob =
      scope.launch {
        delay(keep)
        turns.underTurnLock {
          if (loaded === l && System.currentTimeMillis() >= l.expiresAtMs) {
            unload("keep-alive of ${keep / 1000} s ended")
          }
        }
      }
    onStateChanged()
  }

  /** The server's stop, first step: a running transcription stops at its next window. */
  fun beginShutdown() {
    closing = true
    unloadJob?.cancel()
  }

  /** The server's stop, last step (no turn runs any more): unloads and ends the thread. */
  suspend fun shutdown() =
    withContext(NonCancellable) {
      beginShutdown()
      scope.cancel()
      unload("the local API stopped")
      dispatcher.close()
    }

  private suspend fun ensureLoaded(entry: AsrEntry): Loaded {
    val current = loaded
    if (current != null && current.entry.path == entry.path) return current
    if (current != null) unload("${entry.name} was asked for")
    val t0 = SystemClock.elapsedRealtime()
    val l =
      when (entry.kind) {
        AsrKind.PHONON -> {
          val held =
            try {
              PhononEngine.acquire(context) { Log.i(TAG, it) }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              throw ApiError(500, "Model Shelf could not load ${entry.name}: ${e.message}", "load_failed")
            }
          Loaded(entry, held.backend.label, held.backendNote, null)
        }
        AsrKind.MEDIAPIPE -> loadMediaPipe(entry)
      }
    if (closing) {
      closeLoaded(l)
      throw ApiError(503, STOPPING_MESSAGE, "stopping")
    }
    loaded = l
    Log.i(
      TAG,
      "Loaded ${entry.name} on ${l.backend.uppercase()} (${l.backendNote}) in " +
        "${SystemClock.elapsedRealtime() - t0} ms",
    )
    onStateChanged()
    return l
  }

  private suspend fun loadMediaPipe(entry: AsrEntry): Loaded {
    val errors = mutableListOf<String>()
    for ((delegate, label) in listOf(Delegate.GPU to "gpu", Delegate.CPU to "cpu")) {
      try {
        val transcriber =
          makeOrClose(
            dispatcher,
            make = {
              val cacheDir = File(context.cacheDir, "mediapipe_asr_cache").apply { mkdirs() }
              val options =
                AudioTranscriber.AudioTranscriberOptions.builder()
                  .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(entry.path).setDelegate(delegate).build()
                  )
                  .setSampleRateHz(SPEECH_SAMPLE_RATE_HZ)
                  .setEnableInterimResults(false)
                  .setModelArchitecture(AudioTranscriber.ModelArchitecture.AUTO)
                  .setCacheDir(cacheDir.absolutePath)
                  .build()
              val t = AudioTranscriber.createFromOptions(context, options)
              try {
                // As the app's voice input warms it: the first request then starts at once.
                t.prewarm()
                t.transcribe(FloatArray(MEDIAPIPE_CHUNK_SAMPLES), false)
                t.finishStream()
                t.prewarm()
              } catch (e: Throwable) {
                runCatching { t.close() }
                throw e
              }
              t
            },
            close = { it.close() },
          )
        val note = if (label == "gpu") "GPU (MediaPipe AudioTranscriber)" else "CPU (MediaPipe AudioTranscriber)"
        return Loaded(entry, label, note, transcriber)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Could not load ${entry.name} on ${label.uppercase()}: ${e.message}")
        errors.add("${label.uppercase()}: ${e.message}")
      } catch (e: UnsatisfiedLinkError) {
        errors.add("${label.uppercase()}: ${e.message}")
      }
    }
    throw ApiError(
      500,
      "Model Shelf could not load ${entry.name} (${errors.joinToString("; ").take(400)}).",
      "load_failed",
    )
  }

  /** Feeds [audio] in 100 ms pieces, then ends the stream; the final segments are the transcript. */
  private fun mediaPipeTranscribe(
    t: AudioTranscriber,
    audio: FloatArray,
    stopped: () -> Boolean,
    onPiece: (TranscriptPiece) -> Unit,
  ): List<TranscriptPiece> {
    val out = mutableListOf<TranscriptPiece>()
    fun take(r: AudioTranscriberResult?) {
      for (s in r?.segments().orEmpty()) {
        if (!s.isFinal) continue
        val text = s.transcript().trim()
        if (text.isEmpty()) continue
        val piece = TranscriptPiece(text, s.fromTimestampMs() / 1000.0, s.toTimestampMs() / 1000.0, emptyList())
        out.add(piece)
        onPiece(piece)
      }
    }
    var ended = false
    try {
      var i = 0
      while (i < audio.size) {
        if (stopped()) throw CancellationException("The transcription was stopped.")
        val end = minOf(audio.size, i + MEDIAPIPE_CHUNK_SAMPLES)
        take(t.transcribe(audio.copyOfRange(i, end), false))
        i = end
      }
      take(t.finishStream())
      ended = true
    } finally {
      if (!ended) runCatching { t.finishStream() }
      runCatching { t.prewarm() }.onFailure { Log.w(TAG, "Could not reset the transcriber: ${it.message}") }
    }
    return out
  }

  private suspend fun unload(why: String) {
    val l = loaded ?: return
    loaded = null
    closeLoaded(l)
    Log.i(TAG, "Unloaded ${l.entry.name} ($why)")
    onStateChanged()
  }

  private suspend fun closeLoaded(l: Loaded) {
    when (l.entry.kind) {
      AsrKind.PHONON -> PhononEngine.release("the local API let go of it")
      AsrKind.MEDIAPIPE ->
        withContext(NonCancellable + dispatcher) {
          runCatching { l.transcriber?.close() }
            .onFailure { Log.w(TAG, "Closing the transcriber failed: ${it.message}") }
        }
    }
  }
}
