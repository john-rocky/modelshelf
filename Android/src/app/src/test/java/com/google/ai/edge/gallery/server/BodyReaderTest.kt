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

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The body limit is counted while reading (review fix 6). */
class BodyReaderTest {
  private suspend fun expect413(block: suspend () -> Unit): ApiError {
    try {
      block()
    } catch (e: ApiError) {
      assertEquals(413, e.status)
      return e
    }
    fail("expected HTTP 413")
    throw AssertionError()
  }

  @Test
  fun aBodyWithinTheLimitIsRead() = runBlocking<Unit> {
    val bytes = ByteArray(200_000) { it.toByte() }
    assertArrayEquals(bytes, BodyReader().read(ByteReadChannel(bytes), null, 200_000))
    assertArrayEquals(bytes, BodyReader().read(ByteReadChannel(bytes), 200_000L, 200_000))
  }

  @Test
  fun aChunkedBodyOverTheLimitIsRefusedWhileItComes() = runBlocking<Unit> {
    // No Content-Length, and a sender that would send 160 MB.
    val channel = ByteChannel()
    val sent = AtomicLong()
    val sender =
      launch(Dispatchers.Default) {
        val piece = ByteArray(16 * 1024)
        try {
          repeat(10_000) {
            channel.writeFully(piece)
            channel.flush()
            sent.addAndGet(piece.size.toLong())
          }
          channel.flushAndClose()
        } catch (_: Exception) {}
      }
    val e = expect413 { BodyReader().read(channel, null, 4L * 1024 * 1024) }
    assertEquals("The request body is larger than 4 MB.", e.message)
    channel.cancel() // the server answers and closes the connection
    sender.cancelAndJoin()
    // The reader stopped near the limit instead of taking in the whole body.
    assertTrue("sent ${sent.get()}", sent.get() < 8L * 1024 * 1024)
  }

  @Test
  fun aDeclaredLengthOverTheLimitIsRefusedBeforeReading() = runBlocking<Unit> {
    val channel = ByteReadChannel(ByteArray(10))
    expect413 { BodyReader().read(channel, 5L * 1024 * 1024, 4L * 1024 * 1024) }
    assertFalse(channel.isClosedForRead)
  }

  @Test
  fun aBodyOfExactlyTheLimitIsReadAndOneMoreByteIsNot() = runBlocking<Unit> {
    val limit = 70_000L
    assertEquals(limit.toInt(), BodyReader().read(ByteReadChannel(ByteArray(70_000)), null, limit).size)
    expect413 { BodyReader().read(ByteReadChannel(ByteArray(70_001)), null, limit) }
  }

  @Test
  fun aBodyShorterThanItsDeclaredLengthIsWhatCame() = runBlocking<Unit> {
    // The limit check trusts the bytes, not the header.
    assertEquals(5, BodyReader().read(ByteReadChannel(ByteArray(5)), 100L, 1_000).size)
  }


  @Test
  fun aWholeBodyWithItsLengthIsOneArrayOfExactlyThatLength() = runBlocking<Unit> {
    val bytes = ByteArray(300_000) { (it * 7).toByte() }
    val body = BodyReader().readWhole(ByteReadChannel(bytes), bytes.size.toLong(), 1_000_000)
    assertEquals(bytes.size, body.array.size)
    assertEquals(bytes.size, body.size)
    assertArrayEquals(bytes, body.array)
  }

  @Test
  fun aWholeBodyThatEndsBeforeItsLengthIs400() = runBlocking<Unit> {
    try {
      BodyReader().readWhole(ByteReadChannel(ByteArray(5)), 100L, 1_000)
      fail("expected HTTP 400")
    } catch (e: ApiError) {
      assertEquals(400, e.status)
      assertEquals("The request body ended after 5 of the 100 bytes its Content-Length names.", e.message)
    }
  }

  @Test
  fun aWholeBodyOverItsLimitReadsNoByteWhenTheLengthSaysSo() = runBlocking<Unit> {
    val channel = ByteReadChannel(ByteArray(10))
    val e = expect413 { BodyReader().readWhole(channel, 30_000_000L, 26_214_400L) }
    assertEquals(0L, (e as BodyTooLarge).readBytes)
    // The body's bytes are all still in the channel.
    val left = ByteArray(16)
    assertEquals(10, channel.readAvailable(left, 0, left.size))
  }

  @Test
  fun atMostFourBodiesAreReadAtOnce() = runBlocking<Unit> {
    // The reads start in order on this test's one thread: the first four take the places, the
    // fifth waits for one.
    val reader = BodyReader()
    val channels = List(5) { ByteChannel() }
    val reads = channels.map { c -> launch { reader.read(c, null, 1_000) } }
    delay(200)
    // The fifth body ends, but its read has no place yet, so it does not finish.
    channels[4].flushAndClose()
    delay(200)
    assertFalse(reads[4].isCompleted)
    // The first body ends: its place goes to the fifth read, which then finishes too.
    channels[0].flushAndClose()
    delay(200)
    assertTrue(reads[0].isCompleted)
    assertTrue(reads[4].isCompleted)
    assertEquals(0, (1..3).count { reads[it].isCompleted })
    for (i in 1..3) channels[i].flushAndClose()
    reads.forEach { it.join() }
  }
}
