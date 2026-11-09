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

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Request bodies read at the same time; more wait (memory stays bounded however many connect). */
private const val MAX_BODIES_READING = 4

/**
 * Reads request bodies with a byte limit that holds however the body comes: the limit is counted
 * while reading, so a body sent in chunks (no Content-Length) stops at the limit like one that
 * declares its length. At most [MAX_BODIES_READING] bodies are read at once.
 */
class BodyReader(private val permits: Int = MAX_BODIES_READING) {
  private val reading = Semaphore(permits)

  /**
   * The body's bytes. Throws a 413 [ApiError] when [declaredLength] or the bytes read pass [limit];
   * then the rest is not read.
   */
  suspend fun read(channel: ByteReadChannel, declaredLength: Long?, limit: Long): ByteArray {
    if (declaredLength != null && declaredLength > limit) throw tooLarge(limit)
    return reading.withPermit {
      val out = ByteArrayOutputStream(declaredLength?.toInt()?.coerceIn(0, 1 shl 20) ?: 8192)
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val n = channel.readAvailable(buffer, 0, buffer.size)
        if (n < 0) break
        if (out.size().toLong() + n > limit) throw tooLarge(limit)
        out.write(buffer, 0, n)
      }
      out.toByteArray()
    }
  }

  companion object {
    fun tooLarge(limit: Long) =
      ApiError(413, "The request body is larger than ${sizeText(limit)}.", "request_too_large")

    fun sizeText(bytes: Long): String =
      if (bytes % (1024 * 1024) == 0L) "${bytes / (1024 * 1024)} MB" else "$bytes bytes"
  }
}
