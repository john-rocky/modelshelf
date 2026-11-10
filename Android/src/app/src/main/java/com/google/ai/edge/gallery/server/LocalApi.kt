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
import android.content.Intent
import android.util.Log
import java.net.BindException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MSLocalApi"

/** What the screens and the notification show about the local API. */
data class LocalApiState(
  /** The port is bound and requests are answered. */
  val running: Boolean = false,
  /** A stop is under way: requests get a 503 until the model is unloaded and the port closed. */
  val stopping: Boolean = false,
  /** Why the last start failed, or null. */
  val error: String? = null,
  /** The loaded chat model's API name, or null. */
  val loadedModel: String? = null,
  val loadedBackend: String? = null,
  /** The loaded speech recognition and voice models, as "<name> on <BACKEND>". */
  val loadedAudio: List<String> = emptyList(),
  val requests: Long = 0,
  /** The last start request the service has handled (see [LocalApi.start]). */
  val handledStart: Long = 0,
) {
  /** Every model the API holds, chat model first, as one line; null when none is loaded. */
  fun loadedLine(): String? {
    val all = listOfNotNull(loadedModel?.let { "$it on ${loadedBackend?.uppercase() ?: "?"}" }) + loadedAudio
    return if (all.isEmpty()) null else "Loaded: ${all.joinToString(", ")}"
  }
}

/**
 * Model Shelf's local API: HTTP on 127.0.0.1:11434 inside a foreground service, for other apps on
 * this phone that the user lets in. [start] and [stop] go through the service so the process stays
 * alive while the API is on; the service handles them one at a time, in the order they came.
 */
object LocalApi {
  const val HOST = "127.0.0.1"
  const val PORT = 11434
  const val BASE_URL = "http://$HOST:$PORT"

  /** The connect screen's intent action (ConnectActivity); another app opens it for a result. */
  const val ACTION_CONNECT = "io.github.johnrocky.modelshelf.action.CONNECT"
  const val EXTRA_BASE_URL = "base_url"
  const val EXTRA_TOKEN = "token"
  const val EXTRA_ERROR = "error"

  private val _state = MutableStateFlow(LocalApiState())
  val state: StateFlow<LocalApiState> = _state.asStateFlow()

  @Volatile private var server: ApiServer? = null
  @Volatile private var tokenStore: ApiTokenStore? = null
  /** One start or stop at a time. */
  private val ops = Mutex()
  private val startRequests = AtomicLong(0)

  fun tokens(context: Context): ApiTokenStore =
    tokenStore
      ?: synchronized(this) {
        tokenStore ?: ApiTokenStore(context.applicationContext).also { tokenStore = it }
      }

  /**
   * Turns the API on (from a screen in front: Android lets only that start the service). Returns the
   * request's number: [LocalApiState.handledStart] reaches it once the service has handled this
   * start (after any stop that came before it).
   */
  fun start(context: Context): Long {
    val number = startRequests.incrementAndGet()
    _state.update { it.copy(error = null) }
    context.startForegroundService(
      Intent(context, ApiServerService::class.java)
        .setAction(ApiServerService.ACTION_START)
        .putExtra(ApiServerService.EXTRA_START_NUMBER, number)
    )
    return number
  }

  /** Turns the API off. */
  fun stop(context: Context) {
    context.startService(
      Intent(context, ApiServerService::class.java).setAction(ApiServerService.ACTION_STOP)
    )
  }

  /** Called by the service: binds the port. Returns null, or why it failed. */
  internal suspend fun startServer(context: Context, startNumber: Long): String? =
    ops.withLock {
      val why = if (server != null) null else bind(context)
      _state.update { it.copy(handledStart = maxOf(it.handledStart, startNumber)) }
      why
    }

  private suspend fun bind(context: Context): String? {
    val s = ApiServer(context.applicationContext, tokens(context), ::refresh)
    return try {
      s.start()
      server = s
      _state.update { it.copy(running = true, stopping = false, error = null) }
      null
    } catch (e: Exception) {
      val why =
        if (e is BindException || e.cause is BindException) {
          "Port $PORT is in use by another app."
        } else {
          "The local API could not start: ${e.message ?: e.javaClass.simpleName}"
        }
      Log.e(TAG, why, e)
      s.stop() // ends the engine host's thread
      _state.update { it.copy(running = false, error = why) }
      why
    }
  }

  /** Called by the service: returns when the model is unloaded and the port closed. */
  internal suspend fun stopServer() {
    ops.withLock {
      val s = server ?: return@withLock
      _state.update { it.copy(running = false, stopping = true) }
      ApiServerService.updateNotification()
      try {
        s.stop()
      } finally {
        server = null
        _state.update { LocalApiState(requests = it.requests, handledStart = it.handledStart) }
      }
    }
  }

  /** The server's catalog when it runs (the screens list models with it). */
  internal fun runningServer(): ApiServer? = server

  private fun refresh() {
    val s = server
    val loaded = s?.host?.loadedInfo()
    val audio = listOfNotNull(s?.asr?.loadedInfo(), s?.tts?.loadedInfo())
    _state.update {
      it.copy(
        loadedModel = loaded?.first?.name,
        loadedBackend = loaded?.third,
        loadedAudio = audio.map { m -> "${m.name} on ${m.backend.uppercase()}" },
        requests = s?.requestCount ?: it.requests,
      )
    }
    ApiServerService.updateNotification()
  }
}
