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

import android.os.SystemClock
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * When to cut a speech answer while it is sent, so a caller that does not read holds neither a
 * turn (none is held while sending) nor its audio for long. The answer is cut:
 * - when a write waits and the caller has taken no byte for [stallMs], counted from the start of
 *   the current stretch of writing (the end of the synthesis for a WAV file, each sentence for PCM)
 *   or from the last write the caller's reading let through, whichever is later (bytes the
 *   connection's buffers take do not count: a caller that reads nothing is cut [stallMs] after
 *   the stretch began);
 * - at [deadline] (the end of the synthesis + the audio's length + [SPEECH_WRITE_GRACE_MS]), which a
 *   caller that reads as it plays meets;
 * - when the connection closed (the caller left), seen within [TICK_MS].
 */
class WriteWatch(
  /** When the sending began (the end of the synthesis for a WAV file). */
  val start: Long,
  @Volatile var deadline: Long,
  private val stallMs: Long = SPEECH_WRITE_STALL_MS,
  private val time: () -> Long = { SystemClock.elapsedRealtime() },
) {
  /** Where the stall counts from. */
  @Volatile var clock: Long = start
  /** A write is under way (it may be waiting for the caller). */
  @Volatile var writing = false
  /** Why the answer was cut; null while it was not. */
  @Volatile var cutWhy: String? = null
    private set
  /** The answer did not go out whole (cut, or a later sentence failed). */
  @Volatile var stopped = false

  /** The watch's clock (elapsed real time; tests give their own). */
  fun now(): Long = time()

  /** Watches while [writer] runs, and cuts it (cancels it) when a rule above says so. */
  suspend fun guard(channel: ByteWriteChannel, writer: Job) {
    while (writer.isActive) {
      val t = now()
      val why =
        when {
          channel.isClosedForWrite -> "the caller left"
          t >= deadline ->
            "the audio's length plus ${SPEECH_WRITE_GRACE_MS / 1000} s passed since the synthesis ended"
          writing && t - clock >= stallMs -> "the caller took no byte for ${stallMs / 1000} s"
          else -> null
        }
      if (why != null) {
        cutWhy = why
        writer.cancel()
        return
      }
      val untilStall = if (writing) clock + stallMs - t else Long.MAX_VALUE
      delay(minOf(deadline - t, untilStall, TICK_MS).coerceAtLeast(1L))
    }
  }

  companion object {
    const val TICK_MS = 250L
  }
}

/** Writes a speech answer for [watch], in slices, and notes the writes the caller's reading let through. */
class WatchedWriter(private val channel: ByteWriteChannel, private val watch: WriteWatch) {
  /** A new stretch of writing (the next sentence of a PCM answer): the stall counts from now. */
  fun newStretch() {
    watch.clock = maxOf(watch.clock, watch.now())
  }

  suspend fun write(bytes: ByteArray) {
    var at = 0
    while (at < bytes.size) {
      val end = minOf(bytes.size, at + SLICE_BYTES)
      watch.writing = true
      val t0 = System.nanoTime()
      try {
        channel.writeFully(bytes, at, end)
        channel.flush()
      } finally {
        watch.writing = false
      }
      // A write that had to wait went through because the caller read: the stall starts again.
      if (System.nanoTime() - t0 >= WAITED_NS) watch.clock = maxOf(watch.clock, watch.now())
      at = end
    }
  }

  companion object {
    const val SLICE_BYTES = 16 * 1024
    /** A write that took this long waited for the caller (the buffers were full). */
    const val WAITED_NS = 1_000_000L
  }
}
