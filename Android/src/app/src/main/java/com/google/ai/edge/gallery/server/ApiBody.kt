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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Request bodies read at the same time; more wait (memory stays bounded however many connect). */
private const val MAX_BODIES_READING = 4

/** The first buffer of a body that does not declare its length; it doubles as it fills. */
private const val FIRST_BUFFER_BYTES = 64 * 1024

/** A body's bytes: the first [size] bytes of [array]. The array may be longer; nothing is copied. */
class BodyBytes(val array: ByteArray, val size: Int) {
  fun text(): String = String(array, 0, size, Charsets.UTF_8)
}

/** A body over the limit, refused with a 413; [readBytes] is how much of it was read first. */
class BodyTooLarge(limit: Long, val readBytes: Long) :
  ApiError(413, "The request body is larger than ${BodyReader.sizeText(limit)}.", "request_too_large")

/**
 * Reads request bodies with a byte limit that holds however the body comes: the limit is counted
 * while reading, so a body sent in chunks (no Content-Length) stops at the limit like one that
 * declares its length, and one that declares more than the limit is refused before a byte is read.
 */
class BodyReader(private val permits: Int = MAX_BODIES_READING) {
  private val reading = Semaphore(permits)

  /**
   * A small body (JSON), read before the request queues; at most [permits] are read at once, and
   * each buffer grows only as bytes come (a declared length is not trusted for the allocation).
   * Throws [BodyTooLarge] past [limit]; then the rest is not read.
   */
  suspend fun read(channel: ByteReadChannel, declaredLength: Long?, limit: Long): ByteArray {
    if (declaredLength != null && declaredLength > limit) throw BodyTooLarge(limit, 0)
    return reading.withPermit {
      val body = readGrowing(channel, limit)
      if (body.size == body.array.size) body.array else body.array.copyOf(body.size)
    }
  }

  /**
   * The whole body in one array, with no copy: exactly Content-Length bytes when the length is
   * declared, else an array that doubles from 64 KB up to [limit] + 1 byte. Not counted against
   * [permits]: the audio endpoints call it inside their turn, so one such body is read at a time.
   * Throws [BodyTooLarge] past [limit], and a 400 when the body ends before its Content-Length.
   */
  suspend fun readWhole(channel: ByteReadChannel, declaredLength: Long?, limit: Long): BodyBytes {
    if (declaredLength == null) return readGrowing(channel, limit)
    if (declaredLength > limit) throw BodyTooLarge(limit, 0)
    val array = ByteArray(declaredLength.toInt())
    var at = 0
    while (at < array.size) {
      val n = channel.readAvailable(array, at, array.size - at)
      if (n < 0) {
        throw ApiError(400, "The request body ended after $at of the ${array.size} bytes its Content-Length names.")
      }
      at += n
    }
    return BodyBytes(array, at)
  }

  /** The body in an array that doubles from 64 KB up to [limit] + 1 byte as bytes come. */
  private suspend fun readGrowing(channel: ByteReadChannel, limit: Long): BodyBytes {
    var array = ByteArray(minOf(FIRST_BUFFER_BYTES.toLong(), limit + 1).toInt())
    var size = 0
    while (true) {
      if (size == array.size) {
        if (size > limit) throw BodyTooLarge(limit, size.toLong())
        array = array.copyOf(minOf(size.toLong() * 2, limit + 1).toInt())
      }
      val n = channel.readAvailable(array, size, array.size - size)
      if (n < 0) break
      size += n
      if (size > limit) throw BodyTooLarge(limit, size.toLong())
    }
    return BodyBytes(array, size)
  }

  companion object {
    fun sizeText(bytes: Long): String =
      if (bytes % (1024 * 1024) == 0L) "${bytes / (1024 * 1024)} MB" else "$bytes bytes"
  }
}
