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

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** How an answer ends (review fix 3) and how an engine load ends when its request goes (fix 1). */
class ApiAnswerTest {
  private fun now() = System.nanoTime() / 1_000_000

  /**
   * An answer whose engine, once asked to stop, ends with an error as LiteRT-LM does
   * (onError(CancellationException)). [cancels] counts the stop requests.
   */
  private fun answer(
    events: Channel<AnswerEvent>,
    cancels: AtomicInteger,
    engineEndsOnCancel: Boolean = true,
    stopWaitMs: Long = 2_000,
  ) =
    AnswerRun(
      events = events,
      cancel = {
        cancels.incrementAndGet()
        if (engineEndsOnCancel) {
          events.trySend(AnswerEvent.Failed(CancellationException("cancelled")))
          events.close()
        }
      },
      startMs = now(),
      clock = ::now,
      log = {},
      stopWaitMs = stopWaitMs,
    )

  @Test
  fun aClientThatLeftWhileItWaitedStopsTheGenerationAtTheFirstWrite() = runBlocking<Unit> {
    val events = Channel<AnswerEvent>(Channel.UNLIMITED)
    val cancels = AtomicInteger()
    val run = answer(events, cancels)
    events.trySend(AnswerEvent.Text("Hello"))
    // The OpenAI stream's first write (the role delta) fails: the client is gone.
    val result =
      run.pump(
        timeoutMs = 60_000,
        idleEveryMs = 10_000,
        onStart = { throw IOException("Broken pipe") },
        onText = {},
        onIdle = null,
      )
    assertEquals(AnswerEnd.CLIENT_GONE, result.end)
    assertEquals(1, cancels.get())
    assertTrue(run.ended)
    // The host's finish() stops again: no second request to the engine.
    run.stop()
    assertEquals(1, cancels.get())
  }

  @Test
  fun aFailedTextWriteStopsTheGeneration() = runBlocking<Unit> {
    val events = Channel<AnswerEvent>(Channel.UNLIMITED)
    val cancels = AtomicInteger()
    val run = answer(events, cancels)
    events.trySend(AnswerEvent.Text("a"))
    events.trySend(AnswerEvent.Text("b"))
    var writes = 0
    val result =
      run.pump(
        timeoutMs = 60_000,
        idleEveryMs = null,
        onStart = null,
        onText = { if (++writes == 2) throw IOException("Connection reset") },
        onIdle = null,
      )
    assertEquals(AnswerEnd.CLIENT_GONE, result.end)
    assertEquals("ab", result.text)
    assertEquals(1, cancels.get())
  }

  @Test
  fun anAnswerNobodyReadIsStoppedWhenTheTurnEnds() = runBlocking<Unit> {
    // The response never started (the request was cancelled before pump ran): finish() stops it.
    val events = Channel<AnswerEvent>(Channel.UNLIMITED)
    val cancels = AtomicInteger()
    val run = answer(events, cancels)
    events.trySend(AnswerEvent.Text("x"))
    assertFalse(run.ended)
    run.stop()
    assertEquals(1, cancels.get())
    assertTrue(run.ended)
  }

  @Test
  fun anAnswerThatEndedIsNotCancelled() = runBlocking<Unit> {
    val events = Channel<AnswerEvent>(Channel.UNLIMITED)
    val cancels = AtomicInteger()
    val run = answer(events, cancels)
    events.trySend(AnswerEvent.Text("a"))
    events.trySend(AnswerEvent.Done)
    events.close()
    val result = run.pump(60_000, null, null, onText = {}, onIdle = null)
    assertEquals(AnswerEnd.DONE, result.end)
    assertEquals("a", result.text)
    run.stop()
    assertEquals(0, cancels.get())
  }

  @Test
  fun theTimeLimitStopsTheGeneration() = runBlocking<Unit> {
    val events = Channel<AnswerEvent>(Channel.UNLIMITED)
    val cancels = AtomicInteger()
    val run = answer(events, cancels)
    val result = run.pump(timeoutMs = 200, idleEveryMs = null, null, onText = {}, onIdle = null)
    assertEquals(AnswerEnd.TIMEOUT, result.end)
    assertEquals(1, cancels.get())
  }

  @Test
  fun aStopTheEngineIgnoresEndsAfterTheWait() = runBlocking<Unit> {
    val events = Channel<AnswerEvent>(Channel.UNLIMITED)
    val cancels = AtomicInteger()
    val run = answer(events, cancels, engineEndsOnCancel = false, stopWaitMs = 200)
    val t0 = now()
    run.stop()
    assertTrue(now() - t0 < 2_000)
    assertTrue(run.ended)
    assertEquals(1, cancels.get())
  }

  @Test
  fun requestStopAsksOnceAndLeavesTheDrainToTheTurn() = runBlocking<Unit> {
    val events = Channel<AnswerEvent>(Channel.UNLIMITED)
    val cancels = AtomicInteger()
    val run = answer(events, cancels)
    run.requestStop()
    assertEquals(1, cancels.get())
    // The turn reads the engine's end as a failure, then finish() needs no second stop.
    val result = run.pump(60_000, null, null, onText = {}, onIdle = null)
    assertEquals(AnswerEnd.FAILED, result.end)
    run.stop()
    assertEquals(1, cancels.get())
  }

  // ---- An engine made for a request that went away (fix 1) ----------------------------------

  @Test
  fun anEngineMadeForACancelledRequestIsClosedNotDropped() = runBlocking<Unit> {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val making = CountDownLatch(1)
    val finishMaking = CountDownLatch(1)
    val closed = AtomicReference<Any?>(null)
    val engine = Any()
    val request =
      launch(Dispatchers.Default) {
        makeOrClose(
          dispatcher,
          make = {
            making.countDown()
            finishMaking.await()
            engine
          },
          close = { closed.set(it) },
        )
        fail("the request was cancelled; makeOrClose must not return")
      }
    making.await()
    request.cancel() // the server stops, or the client leaves, while the engine loads
    finishMaking.countDown() // the load runs to its end anyway
    request.join()
    assertTrue(request.isCancelled)
    assertSame(engine, closed.get())
    dispatcher.close()
  }

  @Test
  fun anEngineMadeForALiveRequestIsReturned() = runBlocking<Unit> {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val closed = AtomicReference<Any?>(null)
    val engine = Any()
    val got = makeOrClose(dispatcher, make = { engine }, close = { closed.set(it) })
    assertSame(engine, got)
    assertNull(closed.get())
    dispatcher.close()
  }

  @Test
  fun aLoadThatFailsHasNothingToClose() = runBlocking<Unit> {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val closes = AtomicInteger()
    try {
      makeOrClose<Any>(dispatcher, make = { throw IllegalStateException("no GPU") }, close = {
        closes.incrementAndGet()
      })
      fail("the load failed")
    } catch (e: IllegalStateException) {
      assertEquals("no GPU", e.message)
    }
    assertEquals(0, closes.get())
    dispatcher.close()
  }
}
