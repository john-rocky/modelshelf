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
import com.google.ai.edge.gallery.services.tts.MediaPipeTtsEngine
import com.google.ai.edge.gallery.services.tts.TtsConfig
import com.google.ai.edge.gallery.services.tts.TtsEngineType
import com.google.ai.edge.gallery.services.tts.TtsOutput
import com.google.ai.edge.gallery.services.tts.TtsServiceError
import com.google.ai.edge.gallery.services.tts.TtsServiceProvider
import com.google.ai.edge.gallery.services.tts.TtsServiceStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MSApiTts"

/** The app's text-to-speech engines, for the local API (which is not a Hilt component). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ApiTtsEntryPoint {
  fun ttsServiceProvider(): Optional<TtsServiceProvider>
}

/**
 * The local API's text to speech: the app's own MediaPipe TTS engine (the one that reads chat
 * answers aloud), called inside the turns of [turns] (one request at a time across chat and speech,
 * the same queue and limits). The engine makes, warms, runs and closes its model on one thread of
 * its own, so the GPU context stays on the thread that made it.
 *
 * The engine is shared with the app's read-aloud ([EngineOwnership], last section): each call runs in
 * a job of this host, so when the app stops or releases the engine, only that job ends and the
 * request gets a 503. After [DEFAULT_KEEP_ALIVE_MS] without a request, the model is unloaded if
 * nobody used the engine after the API.
 */
class ApiTtsHost(
  private val context: Context,
  private val turns: ApiEngineHost,
  private val onStateChanged: () -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var unloadJob: Job? = null
  /** The call that runs now, so the server's stop can end it. */
  @Volatile private var work: Job? = null
  /** Set when the server stops: a running synthesis stops at its next piece. */
  @Volatile private var closing = false
  /** The model the API used last and its backend; null when the API holds nothing. */
  @Volatile private var held: Pair<TtsEntry, String>? = null
  /** Wall-clock time [held] unloads at; Long.MAX_VALUE = not set. */
  @Volatile private var expiresAtMs = Long.MAX_VALUE

  /** The model a request runs on. */
  class LoadInfo(
    /** gpu or cpu. */
    val backend: String,
    val backendNote: String,
    /** Milliseconds this request spent loading the model and warming the voice (about 0 when ready). */
    val loadMs: Long,
    /** The voices the loaded model lists. */
    val voices: List<String>,
  )

  class Result(
    /** 24 kHz mono 16-bit little-endian PCM, in the pieces the engine gave (not copied into one). */
    val pieces: List<ByteArray>,
    val pcmBytes: Int,
    val synthMs: Long,
    /** Milliseconds from the start of the synthesis to its first piece of audio. */
    val firstAudioMs: Long,
  ) {
    val audioMs: Long
      get() = speechAudioMs(pcmBytes)
  }

  /**
   * The voice model the API used last while the shared engine still has it loaded, for /api/ps,
   * the notification and the Local API screen.
   */
  fun loadedInfo(): LoadedAudioModel? {
    val (entry, backend) = held ?: return null
    val engine = runCatching { engine() }.getOrNull() ?: return null
    if (!engine.isModelLoaded(entry.engineModelId)) return null
    return LoadedAudioModel(entry.name, "speech", backend, entry.sizeBytes, entry.commit, expiresAtMs)
  }

  /**
   * A turn of [turns] for a request on a voice model. The unload timer stops while [block] runs and
   * is set again when it ends, whether the request succeeded or failed.
   */
  suspend fun <T> withModelTurn(block: suspend (queueMs: Long) -> T): T =
    turns.withTurn { queueMs ->
      unloadJob?.cancel()
      try {
        block(queueMs)
      } finally {
        keepAlive()
      }
    }

  /**
   * Loads [entry] into the shared engine unless it is the loaded model, and warms [voice] (inside
   * [withModelTurn]).
   */
  suspend fun load(entry: TtsEntry, voice: String): LoadInfo {
    val engine = engine()
    val wasLoaded = engine.isModelLoaded(entry.engineModelId)
    val t0 = SystemClock.elapsedRealtime()
    prepare(engine, entry, voice)
    val loadMs = SystemClock.elapsedRealtime() - t0
    val loaded = engine.loadedModelAndDelegate()
    if (loaded == null || loaded.first != entry.engineModelId) {
      throw ApiError(503, "Model Shelf's read-aloud took the voice engine while loading ${entry.name}; try again.", "busy")
    }
    val backend = loaded.second
    val note = "${backend.uppercase()} (MediaPipe TextToSpeech, shared with the app's read-aloud)"
    held = entry to backend
    if (!wasLoaded) {
      Log.i(TAG, "Loaded ${entry.name} on ${backend.uppercase()} ($note) in $loadMs ms")
    }
    EngineOwnership.noteApiTtsUse(engine.useCount)
    onStateChanged()
    return LoadInfo(backend, note, loadMs, engine.loadedVoiceIds(entry.engineModelId) ?: entry.voices)
  }

  /**
   * Speaks [input] with [voice] (inside [withModelTurn], after [load]). Throws [ApiError] 504 when
   * it takes longer than [timeoutMs], 500 when it makes more than [MAX_SPEECH_AUDIO_S] of audio, and
   * 503 when the app took the engine meanwhile.
   */
  suspend fun synthesize(entry: TtsEntry, voice: String, input: String, timeoutMs: Long): Result {
    val engine = engine()
    var attempt = 0
    while (true) {
      attempt++
      var appTookIt = false
      try {
        return isolated("speaking") { speakOnce(engine, entry, voice, input, timeoutMs) }
      } catch (e: TtsServiceError.EngineUnavailable) {
        // The app's read-aloud released the engine or loaded another voice model between load()
        // and this call: load again once.
        if (attempt >= 2 || closing) {
          appTookIt = true
          throw ApiError(503, "Model Shelf's read-aloud took the voice engine; try again.", "busy")
        }
        Log.w(TAG, "The voice engine changed hands before the synthesis (${e.message}); loading ${entry.name} again")
        prepare(engine, entry, voice)
      } catch (e: ApiError) {
        appTookIt = e.code == "busy"
        throw e
      } finally {
        // The API used the engine last, whether the call succeeded, failed or was cancelled, unless
        // the app's read-aloud took it meanwhile: only then may the keep-alive unload it.
        if (!appTookIt) EngineOwnership.noteApiTtsUse(engine.useCount)
      }
    }
  }

  /** The engine's prepare in a job of this host; a model that fails to load is a 500. */
  private suspend fun prepare(engine: MediaPipeTtsEngine, entry: TtsEntry, voice: String) {
    try {
      isolated("loading ${entry.name}") { engine.prepare(config(entry, voice)) }
    } catch (e: TtsServiceError.EngineUnavailable) {
      throw ApiError(500, "Model Shelf could not load ${entry.name}: ${e.message}", "load_failed")
    }
  }

  private suspend fun speakOnce(
    engine: MediaPipeTtsEngine,
    entry: TtsEntry,
    voice: String,
    input: String,
    timeoutMs: Long,
  ): Result {
    val pieces = mutableListOf<ByteArray>()
    var pcmBytes = 0
    var firstAudioMs = -1L
    val t0 = SystemClock.elapsedRealtime()
    val finished =
      withTimeoutOrNull(timeoutMs) {
        when (val output = engine.synthesize(input, config(entry, voice))) {
          is TtsOutput.AudioStream ->
            output.flow.collect { chunk ->
              if (closing) throw CancellationException("The local API stopped.")
              if (chunk.isEmpty()) return@collect
              if (firstAudioMs < 0) firstAudioMs = SystemClock.elapsedRealtime() - t0
              if (chunk.size > MAX_SPEECH_AUDIO_BYTES - pcmBytes) {
                throw ApiError(
                  500,
                  "${entry.name} made more than $MAX_SPEECH_AUDIO_S s of audio for " +
                    "${input.codePointCount(0, input.length)} characters; Model Shelf stopped it.",
                  "audio_too_long",
                )
              }
              pieces.add(chunk)
              pcmBytes += chunk.size
            }
          is TtsOutput.DirectPlayback ->
            throw ApiError(500, "${entry.name} plays its speech itself; the API cannot return it.")
        }
        true
      }
    if (finished == null) {
      throw ApiError(504, "Speaking took longer than the time limit of ${timeoutMs / 1000} s; send a shorter text.")
    }
    return Result(pieces, pcmBytes, SystemClock.elapsedRealtime() - t0, firstAudioMs)
  }

  /** Unloads the shared model [DEFAULT_KEEP_ALIVE_MS] after the last request, if nobody used it since. */
  private fun keepAlive() {
    unloadJob?.cancel()
    if (closing || held == null) return
    val keep = DEFAULT_KEEP_ALIVE_MS
    expiresAtMs = System.currentTimeMillis() + keep
    onStateChanged()
    unloadJob =
      scope.launch {
        delay(keep)
        turns.underTurnLock { unloadIfLast("keep-alive of ${keep / 1000} s ended") }
      }
  }

  /** The server's stop, first step: a running synthesis stops at its next piece. */
  fun beginShutdown() {
    closing = true
    unloadJob?.cancel()
    work?.cancel()
  }

  /** The server's stop, last step (no turn runs any more): unloads the model if the API used it last. */
  suspend fun shutdown() =
    withContext(NonCancellable) {
      beginShutdown()
      unloadIfLast("the local API stopped")
      scope.cancel()
    }

  private suspend fun unloadIfLast(why: String) {
    val h = held ?: return
    held = null
    expiresAtMs = Long.MAX_VALUE
    val engine = runCatching { engine() }.getOrNull()
    if (engine == null) {
      onStateChanged()
      return
    }
    val idle = engine.status.value !is TtsServiceStatus.Synthesizing && engine.status.value !is TtsServiceStatus.Initializing
    if (EngineOwnership.apiMayUnloadTts(engine.useCount) && idle && engine.isModelLoaded(h.first.engineModelId)) {
      try {
        withContext(NonCancellable) { engine.release() }
        Log.i(TAG, "Unloaded ${h.first.name} ($why)")
      } catch (e: Exception) {
        Log.w(TAG, "Unloading ${h.first.name} failed: ${e.message}")
      }
    } else {
      Log.i(TAG, "Left ${h.first.name} to the app ($why): the app's read-aloud used the voice engine after the API")
    }
    onStateChanged()
  }

  /**
   * Runs [block] in a job of this host, not of the request: when the app's read-aloud stops or
   * releases the shared engine, it cancels the job that called the engine, and that must not cancel
   * the request (which then answers 503). A request that is cancelled cancels the job.
   */
  private suspend fun <T> isolated(what: String, block: suspend () -> T): T {
    if (closing) throw ApiError(503, STOPPING_MESSAGE, "stopping")
    val job = scope.async { block() }
    work = job
    try {
      return job.await()
    } catch (e: CancellationException) {
      if (!currentCoroutineContext().isActive) {
        job.cancel()
        throw e
      }
      if (closing) throw ApiError(503, STOPPING_MESSAGE, "stopping")
      Log.w(TAG, "The app stopped the voice engine while $what")
      throw ApiError(503, "Model Shelf's read-aloud took the voice engine while $what; try again.", "busy")
    } catch (e: TtsServiceError.ModelNotFound) {
      throw ApiError(500, "Model Shelf could not find the file of the voice model (${e.message}).", "load_failed")
    } catch (e: TtsServiceError.EngineUnavailable) {
      throw e
    } catch (e: TtsServiceError) {
      throw ApiError(500, "The voice engine failed: ${e.message}", "load_failed")
    } finally {
      if (work === job) work = null
    }
  }

  /** The app's MediaPipe TTS engine. */
  private fun engine(): MediaPipeTtsEngine {
    val provider =
      try {
        EntryPointAccessors.fromApplication(context.applicationContext, ApiTtsEntryPoint::class.java)
          .ttsServiceProvider()
          .getOrNull()
      } catch (e: Exception) {
        Log.w(TAG, "No text-to-speech engines: ${e.message}")
        null
      }
    return provider?.serviceOrNull(TtsEngineType.MEDIAPIPE) as? MediaPipeTtsEngine
      ?: throw ApiError(501, "Text to speech is not in this build of Model Shelf.")
  }

  private fun config(entry: TtsEntry, voice: String) =
    TtsConfig(modelId = entry.engineModelId, voiceId = voice, languageCode = null)
}
