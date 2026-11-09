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
 * One engine per model file (DESIGN.md §g-2): the app's chat and the local API never load the same
 * file at once. Whoever loads it first holds it; the other says who holds it.
 */
object EngineOwnership {
  /** The file the local API has loaded, or null. */
  @Volatile private var apiPath: String? = null

  /** Files the chat screens have loaded, by model name. */
  private val chatPaths = mutableMapOf<String, String>()

  fun apiHolds(path: String): Boolean = apiPath == path

  @Synchronized fun chatHolds(path: String): Boolean = path in chatPaths.values

  fun apiLoaded(path: String?) {
    apiPath = path
  }

  @Synchronized
  fun chatLoaded(modelName: String, path: String) {
    chatPaths[modelName] = path
  }

  @Synchronized
  fun chatReleased(modelName: String) {
    chatPaths.remove(modelName)
  }
}
