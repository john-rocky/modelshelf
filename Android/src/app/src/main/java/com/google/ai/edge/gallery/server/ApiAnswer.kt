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

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// The parts of an answer's life that need neither Android nor the engine, so the ways an answer
// ends can be tested on the JVM (ApiAnswerTest).

/** What the engine sends while it answers. */
sealed class AnswerEvent {
  data class Text(val text: String) : AnswerEvent()

  data object Done : AnswerEvent()

  data class Failed(val error: Throwable) : AnswerEvent()
}

enum class AnswerEnd {
  DONE,
  TIMEOUT,
  FAILED,
  CLIENT_GONE,
}

class AnswerResult(
  val text: String,
  val firstTextMs: Long,
  val inferenceMs: Long,
  val chunks: Int,
  val end: AnswerEnd,
  val error: String?,
)

/**
 * One answer the engine is making: its events, and how to stop it.
 *
 * Whatever ends the turn, the generation is ended first: [pump] stops it when the caller goes away
 * or the time is up, and [stop] (called by the host before the conversation is closed) stops it if
 * nothing read it to the end, e.g. when the first write to a client that already left fails.
 *
 * @param cancel asks the engine to stop (Conversation.cancelProcess); it may be called from any
 *   thread.
 * @param clock milliseconds of a monotonic clock.
 */
class AnswerRun(
  val events: Channel<AnswerEvent>,
  private val cancel: () -> Unit,
  val startMs: Long,
  private val clock: () -> Long,
  private val log: (String) -> Unit,
  private val stopWaitMs: Long = STOP_WAIT_MS,
) {
  /** The engine said it is done or failed, or the answer was stopped. */
  @Volatile
  var ended = false
    private set

  /**
   * Passes the answer's text to [onText] as it comes, until the end or [timeoutMs] from the start.
   * [onStart] runs first (the first bytes of a stream). With [idleEveryMs], calls [onIdle] after that
   * long without text (SSE keep-alive). A failure in [onStart], [onText] or [onIdle] (the caller went
   * away) stops the answer.
   */
  suspend fun pump(
    timeoutMs: Long,
    idleEveryMs: Long?,
    onStart: (suspend () -> Unit)?,
    onText: suspend (String) -> Unit,
    onIdle: (suspend () -> Unit)?,
  ): AnswerResult {
    val text = StringBuilder()
    var first = -1L
    var chunks = 0
    val deadline = startMs + timeoutMs
    fun elapsed() = clock() - startMs
    fun result(end: AnswerEnd, error: String?) =
      AnswerResult(text.toString(), first, elapsed(), chunks, end, error)
    try {
      onStart?.invoke()
      while (true) {
        val remaining = deadline - clock()
        if (remaining <= 0) {
          stop()
          return result(
            AnswerEnd.TIMEOUT,
            "The answer took longer than ${timeoutMs / 1000} s and was stopped.",
          )
        }
        val wait = if (idleEveryMs != null) minOf(remaining, idleEveryMs) else remaining
        val received = withTimeoutOrNull(wait) { events.receiveCatching() }
        if (received == null) {
          if (onIdle != null && clock() < deadline) onIdle()
          continue
        }
        when (val event = received.getOrNull()) {
          null,
          is AnswerEvent.Done -> {
            ended = true
            return result(AnswerEnd.DONE, null)
          }
          is AnswerEvent.Text -> {
            if (first < 0) first = elapsed()
            text.append(event.text)
            chunks++
            onText(event.text)
          }
          is AnswerEvent.Failed -> {
            ended = true
            log("The engine failed while answering: ${event.error}")
            return result(AnswerEnd.FAILED, event.error.message ?: event.error.toString())
          }
        }
      }
    } catch (e: CancellationException) {
      stop()
      throw e
    } catch (e: Exception) {
      stop()
      return result(AnswerEnd.CLIENT_GONE, e.toString())
    }
  }

  /**
   * Stops the answer if it has not ended: asks the engine to stop, then reads its events until it
   * says it ended (at most [stopWaitMs]). Not cancellable.
   */
  suspend fun stop() {
    if (ended) return
    withContext(NonCancellable) {
      try {
        cancel()
      } catch (e: Exception) {
        log("Could not stop the answer: ${e.message}")
      }
      val drained =
        withTimeoutOrNull(stopWaitMs) {
          while (true) {
            val event = events.receiveCatching().getOrNull() ?: break
            if (event !is AnswerEvent.Text) break
          }
          true
        }
      if (drained == null) log("The stopped answer did not end within ${stopWaitMs / 1000} s")
      ended = true
    }
  }

  /** Asks the engine to stop without waiting (the server's stop; the turn's owner drains). */
  fun requestStop() {
    if (ended) return
    try {
      cancel()
    } catch (e: Exception) {
      log("Could not stop the answer: ${e.message}")
    }
  }

  companion object {
    /** How long a stopped answer may take to end. */
    const val STOP_WAIT_MS = 15_000L
  }
}

/**
 * Makes something that must not be dropped half made (an engine: gigabytes of memory, and the
 * model file marked as held). [make] runs to its end on [context] even when the caller is cancelled
 * meanwhile; if the caller was cancelled, the made thing is given to [close] and the cancellation
 * goes on, so it is never lost.
 *
 * (A plain withContext(dispatcher) { make() } drops the result when the caller is cancelled before
 * it resumes: the result is delivered with a prompt cancellation guarantee.)
 */
internal suspend fun <T : Any> makeOrClose(
  context: CoroutineContext,
  make: () -> T,
  close: (T) -> Unit,
): T {
  var made: T? = null
  fun closeMade() {
    val m = made ?: return
    made = null
    try {
      close(m)
    } catch (_: Exception) {}
  }
  try {
    withContext(NonCancellable + context) { made = make() }
  } catch (e: CancellationException) {
    withContext(NonCancellable + context) { closeMade() }
    throw e
  }
  if (!currentCoroutineContext().isActive) {
    withContext(NonCancellable + context) { closeMade() }
    throw CancellationException("Cancelled while it was being made; it was closed.")
  }
  return made!!
}
