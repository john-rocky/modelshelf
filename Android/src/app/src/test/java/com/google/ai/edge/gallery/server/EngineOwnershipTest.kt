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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One engine per model file (review fixes 1 and 4). EngineOwnership is global: unique paths. */
class EngineOwnershipTest {
  private fun path(name: String) = "/models/$name-${System.nanoTime()}.litertlm"

  @Test
  fun chatAndApiNeverHoldOneFile() {
    val p = path("a")
    val host = Any()
    assertTrue(EngineOwnership.reserveForApi(p, host))
    assertFalse(EngineOwnership.reserveForChat("A", p))
    EngineOwnership.releaseForApi(p, host)
    assertTrue(EngineOwnership.reserveForChat("A", p))
    assertFalse(EngineOwnership.reserveForApi(p, host))
    EngineOwnership.releaseForChat("A")
    assertFalse(EngineOwnership.chatHolds(p))
    assertTrue(EngineOwnership.reserveForApi(p, host))
    EngineOwnership.releaseForApi(p, host)
  }

  @Test
  fun aHostThatStoppedCannotClearTheNextHostsMark() {
    val p = path("b")
    val old = Any()
    val next = Any()
    assertTrue(EngineOwnership.reserveForApi(p, old))
    assertFalse(EngineOwnership.reserveForApi(p, next))
    EngineOwnership.releaseForApi(p, old)
    assertTrue(EngineOwnership.reserveForApi(p, next))
    // A late release from the old host (its keep-alive ran after the stop) changes nothing.
    EngineOwnership.releaseForApi(p, old)
    assertTrue(EngineOwnership.apiHolds(p))
    assertFalse(EngineOwnership.reserveForChat("B", p))
    EngineOwnership.releaseForApi(p, next)
    assertFalse(EngineOwnership.apiHolds(p))
  }

  @Test
  fun aChatAndTheApiLoadingAtOnceNeverBothWin() {
    repeat(300) { round ->
      val p = path("race$round")
      val host = Any()
      val go = CountDownLatch(1)
      val winners = ConcurrentLinkedQueue<String>()
      val threads =
        (0 until 6).map { i ->
          thread {
            go.await()
            if (i % 2 == 0) {
              if (EngineOwnership.reserveForApi(p, host)) winners.add("api")
            } else {
              if (EngineOwnership.reserveForChat("chat$i-$round", p)) winners.add("chat")
            }
          }
        }
      go.countDown()
      threads.forEach { it.join() }
      assertEquals("round $round: $winners", 1, winners.toSet().size)
      EngineOwnership.releaseForApi(p, host)
      for (i in 0 until 6) EngineOwnership.releaseForChat("chat$i-$round")
    }
  }
}
