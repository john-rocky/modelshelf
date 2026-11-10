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

import java.io.File

/**
 * Whether the local API makes room before it loads a model. It reads /proc/meminfo; when
 * MemAvailable minus what the model needs would fall under the floor, it first unloads its other
 * models: a voice model unloads the API's chat and speech recognition models, and a chat or speech
 * recognition model unloads the API's voice model (chat and speech recognition never unload each
 * other). What a model needs = its file size × a factor of its kind; the floor = max(450 MiB,
 * MemTotal / 10). On a 12 GB phone the three fit side by side and stay loaded (the voice round trip
 * stays fast); on an 8 GB phone the voice model loads alone.
 */
object MemoryRule {
  const val FLOOR_MIN_KB = 450L * 1024
  const val TTS_FACTOR = 2.0
  const val MEDIAPIPE_ASR_FACTOR = 1.5
  const val PHONON_FACTOR = 1.0
  const val CHAT_FACTOR = 1.0

  class Meminfo(val totalKb: Long, val availableKb: Long)

  fun floorKb(totalKb: Long): Long = maxOf(FLOOR_MIN_KB, totalKb / 10)

  fun needKb(sizeBytes: Long, factor: Double): Long = (sizeBytes * factor / 1024).toLong()

  fun mustMakeRoom(m: Meminfo, needKb: Long): Boolean = m.availableKb - needKb < floorKb(m.totalKb)

  /** MemTotal and MemAvailable of a /proc/meminfo text; null when either is missing. */
  fun parse(text: String): Meminfo? {
    fun kb(name: String): Long? =
      Regex("(?m)^$name:\\s+(\\d+) kB").find(text)?.groupValues?.get(1)?.toLongOrNull()
    val total = kb("MemTotal") ?: return null
    val available = kb("MemAvailable") ?: return null
    return Meminfo(total, available)
  }

  fun read(): Meminfo? = runCatching { parse(File("/proc/meminfo").readText()) }.getOrNull()
}
