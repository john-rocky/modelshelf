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
import io.ktor.utils.io.readAvailable
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A speech answer is cut when its caller stops reading, and only then (line A). */
class SpeechWriteWatchTest {
  private fun now() = System.nanoTime() / 1_000_000

  /** Writes [bytes] to [channel] under [watch] and returns the milliseconds it took. */
  private suspend fun send(channel: ByteChannel, watch: WriteWatch, bytes: Int): Long {
    val t0 = now()
    kotlinx.coroutines.coroutineScope {
      val writer =
        launch(Dispatchers.Default) { runCatching { WatchedWriter(channel, watch).write(ByteArray(bytes)) } }
      launch(Dispatchers.Default) { watch.guard(channel, writer) }
      writer.join()
    }
    return now() - t0
  }

  @Test
  fun aCallerThatReadsNothingIsCutWhenTheStallEnds() = runBlocking<Unit> {
    // Nobody reads: the channel takes 1 MiB, then a write waits.
    val channel = ByteChannel()
    val watch = WriteWatch(start = now(), deadline = Long.MAX_VALUE, stallMs = 400, time = ::now)
    withTimeout(10_000) { send(channel, watch, 3 * 1024 * 1024) }
    // From the watch's own start, the clock the stall is counted on.
    val took = now() - watch.start
    assertTrue(watch.cutWhy!!, watch.cutWhy!!.startsWith("the caller took no byte"))
    // Counted from the start of the writing: the bytes the buffer took do not reset it.
    assertTrue("took $took ms", took in 400L..6_000L)
  }

  @Test
  fun aCallerThatReadsAsItGoesIsNotCut() = runBlocking<Unit> {
    val channel = ByteChannel()
    val total = 3 * 1024 * 1024
    val watch = WriteWatch(start = now(), deadline = Long.MAX_VALUE, stallMs = 5_000, time = ::now)
    val reader =
      launch(Dispatchers.Default) {
        val buf = ByteArray(64 * 1024)
        var got = 0
        while (got < total) {
          val n = channel.readAvailable(buf, 0, buf.size)
          if (n < 0) break
          got += n
          delay(20)
        }
      }
    withTimeout(20_000) { send(channel, watch, total) }
    reader.join()
    assertNull(watch.cutWhy)
  }

  @Test
  fun theDeadlineCutsAReaderThatIsTooSlow() = runBlocking<Unit> {
    val channel = ByteChannel()
    val watch = WriteWatch(start = now(), deadline = now() + 500, stallMs = 10_000, time = ::now)
    val reader =
      launch(Dispatchers.Default) {
        runCatching {
          val buf = ByteArray(16 * 1024)
          while (true) {
            if (channel.readAvailable(buf, 0, buf.size) < 0) break
            delay(50)
          }
        }
      }
    val took = withTimeout(10_000) { send(channel, watch, 4 * 1024 * 1024) }
    channel.cancel(IOException("the test ends"))
    reader.join()
    assertTrue(watch.cutWhy!!, watch.cutWhy!!.startsWith("the audio's length plus"))
    assertTrue("took $took ms", took in 450L..6_000L)
  }

  @Test
  fun aCallerThatLeftEndsTheWritingWithinATick() = runBlocking<Unit> {
    val channel = ByteChannel()
    val watch = WriteWatch(start = now(), deadline = Long.MAX_VALUE, stallMs = 10_000, time = ::now)
    launch(Dispatchers.Default) {
      delay(200)
      channel.cancel(IOException("the caller left"))
    }
    val took = withTimeout(10_000) { send(channel, watch, 3 * 1024 * 1024) }
    assertTrue("took $took ms", took < 200 + WriteWatch.TICK_MS + 5_000)
  }
}
