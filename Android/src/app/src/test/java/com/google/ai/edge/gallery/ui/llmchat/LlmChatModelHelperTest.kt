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

package com.google.ai.edge.gallery.ui.llmchat

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.server.ChatEngineSlot
import com.google.ai.edge.gallery.server.EngineOwnership
import com.google.ai.edge.gallery.server.makeForChat
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ToolManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LlmChatModelHelper]'s clean-up and its one chat-engine slot (D-614 round 10d, review findings 3,
 * 10 and 19), with engines that were never started: their close fails at once and no native code is
 * loaded (the slot is reached through reflection; the helper is the app's real one).
 */
class LlmChatModelHelperTest {
  private fun unique(name: String) = "$name-${System.nanoTime()}"

  /** The helper's own slot. */
  @Suppress("UNCHECKED_CAST")
  private fun slot(): ChatEngineSlot<Model, LlmModelInstance> {
    val field = LlmChatModelHelper::class.java.getDeclaredField("chatEngines")
    field.isAccessible = true
    return field.get(LlmChatModelHelper) as ChatEngineSlot<Model, LlmModelInstance>
  }

  /**
   * An engine that was never started, with a conversation already marked closed: closing them
   * throws at once, which the helper catches, so no native call is made.
   */
  private fun unstartedInstance(): LlmModelInstance {
    val conversation = Conversation(0L, ToolManager(emptyList()), false, false)
    val alive = Conversation::class.java.getDeclaredField("_isAlive")
    alive.isAccessible = true
    (alive.get(conversation) as AtomicBoolean).set(false)
    return LlmModelInstance(engine = Engine(EngineConfig(modelPath = "/none")), conversation)
  }

  private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      if (condition()) return true
      Thread.sleep(20)
    }
    return condition()
  }

  // Findings 3 and 19: the clean-up of a model that has no engine (a refused load, a model closed by
  // another chat) returned at 35e5905 without calling onDone, so the error path's reload and the
  // other callers that go on in onDone stopped there. After: onDone is called.
  @Test
  fun theCleanUpOfAModelWithoutAnEngineStillCallsOnDone() {
    val done = AtomicInteger(0)
    LlmChatModelHelper.cleanUp(Model(name = unique("no-engine"))) { done.incrementAndGet() }
    assertEquals("onDone calls", 1, done.get())
  }

  // Finding 10: the screen goes away (the activity finishes; the local API's service keeps the
  // process) while its chat engine is still being made. At 35e5905 the close saw no engine yet, and
  // the engine made afterwards stayed alive with its file reserved and no screen. After: it is
  // closed right after it is made, and its file is given back.
  @Test
  fun anEngineStillBeingMadeWhenItsScreenGoesAwayIsClosedOnceMade() {
    val slot = slot()
    assertEquals("a chat engine is alive before the test", 0, slot.liveCount)
    val model = Model(name = unique("made-late"))
    val path = "/models/${model.name}.litertlm"
    val creating = CountDownLatch(1)
    val finish = CountDownLatch(1)
    var result: Any? = null
    val maker = thread {
      result =
        slot.makeForChat(
          owner = model,
          name = model.name,
          path = path,
          create = {
            creating.countDown()
            finish.await(5, TimeUnit.SECONDS)
            unstartedInstance()
          },
          publish = { instance -> model.markInitialized(instance) },
        )
    }
    assertTrue("the engine was not being made", creating.await(5, TimeUnit.SECONDS))
    LlmChatModelHelper.closeEnginesOf(listOf(model), why = "its screen is gone")
    finish.countDown()
    maker.join(5_000)
    try {
      assertTrue(
        "the engine made after its screen went away is still alive ($result)",
        waitFor(2_000) { slot.liveCount == 0 },
      )
      assertFalse("its file is still reserved for the chat", EngineOwnership.chatHolds(path))
    } finally {
      // Leave no engine to the next test.
      for (engine in slot.enginesOf { it === model }) slot.close(engine)
    }
  }
}
