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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "MSLocalApi"

/** What the screens and the notification show about the local API. */
data class LocalApiState(
  val running: Boolean = false,
  /** Why the last start failed, or null. */
  val error: String? = null,
  /** The loaded model's API name, or null. */
  val loadedModel: String? = null,
  val loadedBackend: String? = null,
  val requests: Long = 0,
)

/**
 * Model Shelf's local API (DESIGN.md §g): HTTP on 127.0.0.1:11434 inside a foreground service, for
 * other apps on this phone that the user lets in. [start] and [stop] go through the service so the
 * process stays alive while the API is on.
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

  fun tokens(context: Context): ApiTokenStore =
    tokenStore
      ?: synchronized(this) {
        tokenStore ?: ApiTokenStore(context.applicationContext).also { tokenStore = it }
      }

  /** Turns the API on (from a screen in front: Android lets only that start the service). */
  fun start(context: Context) {
    _state.update { it.copy(error = null) }
    context.startForegroundService(
      Intent(context, ApiServerService::class.java).setAction(ApiServerService.ACTION_START)
    )
  }

  /** Turns the API off. */
  fun stop(context: Context) {
    context.startService(
      Intent(context, ApiServerService::class.java).setAction(ApiServerService.ACTION_STOP)
    )
  }

  /** Called by the service: binds the port. Returns null, or why it failed. */
  @Synchronized
  internal fun startServer(context: Context): String? {
    if (server != null) return null
    val s = ApiServer(context.applicationContext, tokens(context), ::refresh)
    return try {
      s.start()
      server = s
      _state.update { it.copy(running = true, error = null) }
      null
    } catch (e: Exception) {
      val why =
        if (e is BindException || e.cause is BindException) {
          "Port $PORT is in use by another app."
        } else {
          "The local API could not start: ${e.message ?: e.javaClass.simpleName}"
        }
      Log.e(TAG, why, e)
      _state.update { it.copy(running = false, error = why) }
      why
    }
  }

  /** Called by the service. */
  internal suspend fun stopServer() {
    val s = synchronized(this) { server.also { server = null } } ?: return
    s.stop()
    _state.update { LocalApiState(running = false, requests = it.requests) }
  }

  /** The server's catalog when it runs (the screens list models with it). */
  internal fun runningServer(): ApiServer? = server

  private fun refresh() {
    val s = server
    val loaded = s?.host?.loadedInfo()
    _state.update {
      it.copy(
        loadedModel = loaded?.first?.name,
        loadedBackend = loaded?.third,
        requests = s?.requestCount ?: it.requests,
      )
    }
    ApiServerService.updateNotification()
  }
}
