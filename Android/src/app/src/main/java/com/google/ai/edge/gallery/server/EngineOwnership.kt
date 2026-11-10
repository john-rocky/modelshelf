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

/**
 * One engine per model file: the app's chat and the local API never load the same file at once.
 *
 * Each side reserves the file before it makes an engine and gives it back when the engine is closed
 * or could not be made, so the check and the mark are one step: two loads of one file cannot both
 * pass the check. The local API reserves with its engine host as the owner; a host only gives back
 * its own reservation, so a host that is shutting down cannot clear the mark of the next one.
 *
 * Text to speech goes the other way, to the same end: the app's read-aloud and the local API use one
 * shared engine, so there is one engine per voice model file without a reservation (see the last
 * section).
 */
object EngineOwnership {
  /** Files the local API has reserved, with the engine host that holds each. */
  private val apiFiles = mutableMapOf<String, Any>()

  /** Files the chat screens have reserved, by model name. */
  private val chatFiles = mutableMapOf<String, String>()

  @Synchronized fun apiHolds(path: String): Boolean = path in apiFiles

  @Synchronized fun chatHolds(path: String): Boolean = path in chatFiles.values

  /**
   * Reserves [path] for the local API's [host]. False when a chat holds it, or another host does.
   */
  @Synchronized
  fun reserveForApi(path: String, host: Any): Boolean {
    if (path in chatFiles.values) return false
    val holder = apiFiles[path]
    if (holder != null && holder !== host) return false
    apiFiles[path] = host
    return true
  }

  /** Gives back [path] if [host] holds it. */
  @Synchronized
  fun releaseForApi(path: String, host: Any) {
    if (apiFiles[path] === host) apiFiles.remove(path)
  }

  /** Reserves [path] for the chat of [modelName]. False when the local API holds it. */
  @Synchronized
  fun reserveForChat(modelName: String, path: String): Boolean {
    if (path in apiFiles) return false
    chatFiles[modelName] = path
    return true
  }

  @Synchronized
  fun releaseForChat(modelName: String) {
    chatFiles.remove(modelName)
  }

  // ---- Text to speech ----------------------------------------------------------------------------
  // The app's read-aloud and the local API share one MediaPipe TTS engine (the app's singleton
  // MediaPipeTtsEngine): it holds one voice model at a time and runs one synthesis at a time, so a
  // voice model file is never loaded twice and nothing has to be reserved. What is decided here is
  // when the local API may unload that shared model: only when nobody used the engine after the
  // API's own last call (the engine counts every prepare and synthesis of every user). When the app
  // stops or releases the engine during an API request, the request ends with a 503 and the app
  // keeps the engine.

  /** The engine's use count right after the local API's last call, or null when the API holds nothing. */
  private var apiTtsMark: Long? = null

  /** The local API finished a call on the shared TTS engine; [useCount] is the engine's count after it. */
  @Synchronized
  fun noteApiTtsUse(useCount: Long) {
    apiTtsMark = useCount
  }

  /**
   * Whether the local API may unload the shared TTS model now: the API used it last ([useCount] is
   * still the count the API noted). Clears the API's mark either way: after this, the model is the
   * app's to keep or unload.
   */
  @Synchronized
  fun apiMayUnloadTts(useCount: Long): Boolean {
    val mark = apiTtsMark
    apiTtsMark = null
    return mark != null && mark == useCount
  }
}
