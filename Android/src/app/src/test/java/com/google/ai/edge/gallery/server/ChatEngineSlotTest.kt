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

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * One chat engine per process (D-614 round 10c): the slot the app's chat makes its engines through.
 * EngineOwnership is global: every test uses its own names and paths.
 */
class ChatEngineSlotTest {
  /** A chat screen's model object: two screens of one model are two owners with one name. */
  private class Owner(val name: String)

  private class FakeEngine(val owner: Owner) {
    var closed = false
    var answering = false
  }

  private val events = Collections.synchronizedList(mutableListOf<String>())

  private fun slot() =
    ChatEngineSlot<Owner, FakeEngine>(
      isAnswering = { it.answering },
      closeEngine = { owner, engine ->
        assertFalse("closed twice", engine.closed)
        engine.closed = true
        EngineOwnership.releaseForChat(owner.name)
        events.add("close ${owner.name}")
      },
    )

  private fun unique(name: String) = "$name-${System.nanoTime()}"

  private fun path(name: String) = "/models/$name.litertlm"

  private fun ChatEngineSlot<Owner, FakeEngine>.open(owner: Owner, path: String): FakeEngine {
    val made =
      makeForChat(
        owner = owner,
        name = owner.name,
        path = path,
        create = {
          events.add("create ${owner.name}")
          FakeEngine(owner)
        },
      )
    return (made as ChatEngineSlot.Result.Made<Owner, FakeEngine>).engine
  }

  @Test
  fun aSecondChatOfTheSameFileClosesTheFirstBeforeItsEngineIsMade() {
    val slot = slot()
    val name = unique("same")
    val p = path(name)
    // Two screens of one model: two model objects with one name and one file.
    val first = Owner(name)
    val second = Owner(name)
    val a = slot.open(first, p)
    val b = slot.open(second, p)
    assertEquals(listOf("create $name", "close $name", "create $name"), events)
    assertTrue(a.closed)
    assertFalse(b.closed)
    assertEquals(1, slot.liveCount)
    assertSame(second, slot.ownerOf(b))
    // The chat still holds the file (the second reservation came after the first was given back).
    assertTrue(EngineOwnership.chatHolds(p))
    assertFalse(EngineOwnership.reserveForApi(p, Any()))
    assertTrue(slot.close(b))
    assertFalse(EngineOwnership.chatHolds(p))
  }

  @Test
  fun aChatOfAnotherFileClosesTheFirstBeforeItsEngineIsMade() {
    val slot = slot()
    val one = Owner(unique("one"))
    val other = Owner(unique("other"))
    val a = slot.open(one, path(one.name))
    val b = slot.open(other, path(other.name))
    assertEquals(listOf("create ${one.name}", "close ${one.name}", "create ${other.name}"), events)
    assertTrue(a.closed)
    assertEquals(1, slot.liveCount)
    assertFalse(EngineOwnership.chatHolds(path(one.name)))
    assertTrue(EngineOwnership.chatHolds(path(other.name)))
    slot.close(b)
  }

  @Test
  fun anEngineIsClosedOnceAndAfterTheCloseNothingIsClosedAgain() {
    val slot = slot()
    val one = Owner(unique("once"))
    val a = slot.open(one, path(one.name))
    assertTrue(slot.close(a))
    assertEquals(0, slot.liveCount)
    assertNull(slot.ownerOf(a))
    // The screen's own cleanup after the slot closed the engine (or twice) closes nothing.
    assertFalse(slot.close(a))
    assertEquals(listOf("create ${one.name}", "close ${one.name}"), events)
    // The next chat finds nothing to close.
    val next = Owner(unique("next"))
    val b = slot.open(next, path(next.name))
    assertEquals(listOf("create ${one.name}", "close ${one.name}", "create ${next.name}"), events)
    assertEquals(1, slot.liveCount)
    slot.close(b)
  }

  @Test
  fun whileTheApiHoldsTheFileTheChatGetsNoEngineAndTheOpenChatKeepsItsOwn() {
    val slot = slot()
    val open = Owner(unique("open"))
    val a = slot.open(open, path(open.name))
    val wanted = Owner(unique("api"))
    val p = path(wanted.name)
    val host = Any()
    assertTrue(EngineOwnership.reserveForApi(p, host))
    try {
      slot.open(wanted, p)
      fail("the chat got an engine for a file the local API holds")
    } catch (e: ApiHoldsModelFile) {
      // The chat's answer: the local API is using this model.
    }
    assertFalse(a.closed)
    assertEquals(1, slot.liveCount)
    assertEquals(listOf("create ${open.name}"), events)
    assertFalse(EngineOwnership.chatHolds(p))
    // Once the API gives the file back, the chat gets it (and the open chat's engine closes first).
    EngineOwnership.releaseForApi(p, host)
    val b = slot.open(wanted, p)
    assertTrue(a.closed)
    assertTrue(EngineOwnership.chatHolds(p))
    slot.close(b)
  }

  @Test
  fun whileAChatEngineAnswersNothingIsClosedOrMade() {
    val slot = slot()
    val busy = Owner(unique("busy"))
    val a = slot.open(busy, path(busy.name))
    assertFalse(slot.anyAnswering())
    a.answering = true
    assertTrue(slot.anyAnswering())
    val wanted = Owner(unique("wanted"))
    val made =
      slot.makeForChat(
        owner = wanted,
        name = wanted.name,
        path = path(wanted.name),
        create = { FakeEngine(wanted).also { events.add("create ${wanted.name}") } },
      )
    assertTrue(made is ChatEngineSlot.Result.Answering<*>)
    assertSame(busy, (made as ChatEngineSlot.Result.Answering<*>).owner)
    assertFalse(a.closed)
    assertEquals(1, slot.liveCount)
    assertFalse(EngineOwnership.chatHolds(path(wanted.name)))
    // The answer ended: now the chat opens and the old engine closes first.
    a.answering = false
    assertFalse(slot.anyAnswering())
    val b = slot.open(wanted, path(wanted.name))
    assertTrue(a.closed)
    assertEquals(1, slot.liveCount)
    slot.close(b)
  }

  @Test
  fun aFailedEngineLeavesNoEngineAndNoReservation() {
    val slot = slot()
    val one = Owner(unique("fails"))
    try {
      slot.makeForChat(
        owner = one,
        name = one.name,
        path = path(one.name),
        create = { throw IllegalStateException("no memory") },
      )
      fail("the failure did not reach the caller")
    } catch (e: IllegalStateException) {
      assertEquals("no memory", e.message)
    }
    assertEquals(0, slot.liveCount)
    assertFalse(EngineOwnership.chatHolds(path(one.name)))
  }

  // Model Shelf round 10d (review finding 18): the engines created and not closed are counted (one
  // at most at any time, one at the end), and a failure in a screen's thread fails the test.
  @Test
  fun screensOpeningChatsAtOnceNeverHaveTwoEngines() {
    repeat(50) { round ->
      val slot = slot()
      val making = AtomicInteger(0)
      val mostMaking = AtomicInteger(0)
      val created = Collections.synchronizedList(mutableListOf<FakeEngine>())
      val mostNotClosed = AtomicInteger(0)
      val failures = Collections.synchronizedList(mutableListOf<Throwable>())
      val go = CountDownLatch(1)
      val threads =
        (0 until 6).map { i ->
          thread {
            try {
              go.await()
              val owner = Owner(unique("race$round-$i"))
              slot.makeForChat(
                owner = owner,
                name = owner.name,
                path = path(owner.name),
                create = {
                  mostMaking.accumulateAndGet(making.incrementAndGet()) { x, y -> maxOf(x, y) }
                  // The engines created and not closed, this one included.
                  val notClosed = synchronized(created) { created.count { !it.closed } } + 1
                  mostNotClosed.accumulateAndGet(notClosed) { x, y -> maxOf(x, y) }
                  Thread.sleep(1)
                  making.decrementAndGet()
                  FakeEngine(owner).also { created.add(it) }
                },
              )
            } catch (t: Throwable) {
              failures.add(t)
            }
          }
        }
      go.countDown()
      threads.forEach { it.join() }
      assertEquals("failures in the screens' threads", emptyList<Throwable>(), failures.toList())
      assertEquals("two engines made at once", 1, mostMaking.get())
      assertEquals("two engines not closed at once", 1, mostNotClosed.get())
      assertEquals(1, slot.liveCount)
      assertEquals("engines made and never closed", 1, created.count { !it.closed })
      slot.enginesOf { true }.forEach { slot.close(it) }
    }
  }

  // Model Shelf round 10d (review finding 7): a turn's "answering" mark goes up under the slot's
  // lock. A turn started first makes a link's gate refuse; a gate that holds the slot first keeps
  // the turn from starting until it is released; a closed engine gets no turn.
  @Test
  fun aTurnAndALinksHoldExcludeEachOther() {
    val slot = slot()
    val owner = Owner(unique("turn"))
    val engine = slot.open(owner, path(owner.name))
    val gate = { answering: Boolean, _: Int -> if (answering) "answers" else null }

    assertEquals(1, slot.startTurn(engine) { engine.answering = true; 1 })
    assertEquals("answers", slot.hold(gate))
    engine.answering = false

    assertNull(slot.hold(gate))
    assertNull(slot.startTurn(engine) { engine.answering = true; 1 })
    assertFalse(engine.answering)
    slot.release()
    assertEquals(1, slot.startTurn(engine) { 1 })

    slot.close(engine)
    assertNull(slot.startTurn(engine) { 1 })
  }

  // Model Shelf round 10d (review finding 10): the screen of an engine still being made goes away;
  // the engine is closed as soon as it is made and its file is given back.
  @Test
  fun anEngineMadeAfterItsScreenIsGoneIsClosedAtOnce() {
    val slot = slot()
    val owner = Owner(unique("gone"))
    val creating = CountDownLatch(1)
    val finish = CountDownLatch(1)
    var result: ChatEngineSlot.Result<Owner, FakeEngine>? = null
    val maker = thread {
      result =
        slot.makeForChat(
          owner = owner,
          name = owner.name,
          path = path(owner.name),
          create = {
            creating.countDown()
            finish.await()
            FakeEngine(owner)
          },
        )
    }
    assertTrue(creating.await(5, java.util.concurrent.TimeUnit.SECONDS))
    assertEquals(emptyList<FakeEngine>(), slot.screensGone { it === owner })
    finish.countDown()
    maker.join(5_000)
    assertTrue("made: $result", result is ChatEngineSlot.Result.Gone<*>)
    assertEquals(0, slot.liveCount)
    assertFalse(EngineOwnership.chatHolds(path(owner.name)))
  }
}
