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

package com.google.ai.edge.gallery.agent

import android.content.ContextWrapper
import android.graphics.Bitmap
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.SessionConfig
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.runtime.testingModelHelper
import com.google.ai.edge.gallery.skills.NoOpSkillsProvider
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.gallery.tools.RuntimeToolsProvider
import com.google.ai.edge.gallery.ui.llmchat.LlmChatTask
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The executor's one session (D-614 round 10d, review findings 1 and 3, and the round 10c race),
 * on the real [DefaultAgentRuntimeExecutor] and [LlmChatTask] with a helper that loads nothing.
 */
class AgentSessionTest {
  /** A helper whose loads succeed at once, unless the model is in [refuse]. */
  private class FakeHelper : LlmModelHelper {
    val refuse: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())
    val cleanedUp: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun initialize(
      context: android.content.Context,
      model: Model,
      taskId: String,
      supportImage: Boolean,
      supportAudio: Boolean,
      onDone: (String) -> Unit,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      initialMessages: List<Message>,
      coroutineScope: CoroutineScope?,
      enableResponseFormat: Boolean,
    ) {
      if (model.instance != null) {
        model.markInitialized()
        onDone("")
        return
      }
      model.markInitializationStarted()
      val refusal = refuse[model.name]
      if (refusal != null) {
        model.markInitializationFailed(refusal)
        onDone(refusal)
        return
      }
      model.markInitialized(Any())
      onDone("")
    }

    override fun resetConversation(
      model: Model,
      supportImage: Boolean,
      supportAudio: Boolean,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      initialMessages: List<Message>,
      enableResponseFormat: Boolean,
    ) {}

    override fun cleanUp(model: Model, onDone: () -> Unit) {
      cleanedUp.add(model.name)
      model.resetInitialization()
      onDone()
    }

    override fun runInference(
      model: Model,
      input: String,
      resultListener: ResultListener,
      cleanUpListener: CleanUpListener,
      onError: (message: String) -> Unit,
      images: List<Bitmap>,
      audioClips: List<ByteArray>,
      coroutineScope: CoroutineScope?,
      extraContext: Map<String, String>,
      responseJsonSchema: String?,
    ) {}

    override fun stopResponse(model: Model) {}
  }

  /** A session manager that answers every question at once and notes whose model it used. */
  private class FakeSessions : LlmSessionManager {
    override var activeSessionId: String? = null
    val answeredWith: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun createSession(config: SessionConfig): String = "s"

    override suspend fun loadSession(
      sessionId: String,
      config: SessionConfig,
    ): List<ChatMessageProto> = emptyList()

    override suspend fun resetSession(
      sessionId: String,
      config: SessionConfig,
      initialMessages: List<Message>,
      enableConversationConstrainedDecoding: Boolean,
    ) {
      activeSessionId = sessionId
    }

    override fun setSessionConfig(sessionId: String, config: SessionConfig) {
      activeSessionId = sessionId
    }

    override suspend fun listSessions(taskId: String?): List<ChatSessionProto> = emptyList()

    override suspend fun deleteSession(sessionId: String) {}

    override suspend fun clearAllSessions() {}

    override suspend fun saveSessionHistory(
      sessionId: String,
      messages: List<ChatMessageProto>,
      originalModel: String?,
      taskId: String?,
    ) {}

    override suspend fun generateResponse(
      sessionId: String,
      model: Model,
      input: String,
      resultListener: ResultListener,
      cleanUpListener: CleanUpListener,
      onError: (message: String) -> Unit,
      images: List<Bitmap>,
      audioClips: List<ByteArray>,
      extraContext: Map<String, String>,
    ) {
      answeredWith.add(model.name)
      resultListener("answer of ${model.name}", true, null)
    }

    override fun stopResponse(sessionId: String, model: Model) {}

    override suspend fun linkFeedbackToSession(
      sessionId: String,
      feedbackId: String,
      messageIndex: Int?,
    ) {}
  }

  private val context = ContextWrapper(null)
  private val helper = FakeHelper()
  private val sessions = FakeSessions()
  private val executor =
    DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = RuntimeToolsProvider(),
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = sessions,
    )

  @Before
  fun setUp() {
    testingModelHelper = helper
  }

  @After
  fun tearDown() {
    testingModelHelper = null
  }

  private fun load(model: Model, taskId: String = "llm_chat"): String {
    var error: String? = null
    runBlocking {
      executor.initialize(
        context = context,
        config = AgentRuntimeConfig(model = model, taskId = taskId),
        onDone = { error = it },
      )
    }
    return error ?: "(onDone not called)"
  }

  /** Every event of one question, or the exception the stream ended with. */
  private fun ask(): Pair<List<AgentEvent>, Throwable?> {
    val events = mutableListOf<AgentEvent>()
    val thrown =
      try {
        runBlocking {
          withTimeout(5_000) {
            events.addAll(
              executor
                .executeStream(AgentExecutionContext(), AgentRequest(query = "question"))
                .toList()
            )
          }
        }
        null
      } catch (e: Throwable) {
        e
      }
    return events to thrown
  }

  // Finding 1 (and 2, 3): a chat screen whose executor holds no session asks a question. At 35e5905
  // executeStream throws "Model not initialized in DefaultAgentRuntimeExecutor"; nothing catches it,
  // so the process dies with every app's local API. After: the question ends in an error event,
  // which the chat shows and answers by loading the model again.
  @Test
  fun aQuestionWithoutASessionEndsInAnErrorEventInsteadOfAnException() {
    val (events, thrown) = ask()
    assertEquals("the stream threw", null, thrown?.toString())
    assertTrue("no error event: $events", events.any { it is AgentEvent.Error })
    assertTrue("no question reached a model", sessions.answeredWith.isEmpty())
  }

  // Finding 3: chat A is loaded; the manager opens B under the same executor and B's load is refused
  // (another chat answers, or the local API holds B). At 35e5905 the executor stored B's session
  // before the load, so A's next question went to B (and A's clean-up took B's session). After: a
  // refused load leaves the executor's session as it was.
  @Test
  fun aRefusedLoadLeavesTheSessionOfTheLoadedChat() {
    val a = Model(name = "session-a")
    val b = Model(name = "session-b")
    assertEquals("", load(a))
    helper.refuse[b.name] = "Another chat is still answering."
    assertEquals("Another chat is still answering.", load(b))

    val (events, thrown) = ask()
    assertEquals("the stream threw", null, thrown?.toString())
    assertEquals("A's question went to", listOf(a.name), sessions.answeredWith.toList())
    assertTrue("A's question did not end: $events", events.any { it is AgentEvent.LoopTerminated })
  }

  // The round 10c race: Back on the top screen (model T) and the chat below loads model L under the
  // same executor; the top screen's clean-up runs after L's load stored its session. At 35e5905 the
  // clean-up took the executor's one session whatever model it was asked about, so L's chat had no
  // session and its next question crashed. After: the clean-up of T leaves L's session and engine.
  @Test
  fun theCleanUpOfTheScreenAboveLeavesTheSessionTheChatBelowJustLoaded() {
    val top = Model(name = "race-top")
    val below = Model(name = "race-below")
    val task = LlmChatTask(context, executor)
    assertEquals("", load(top))
    // The top screen's engine is closed; Back starts its clean-up, which is late.
    helper.cleanUp(top) {}
    assertEquals("", load(below))

    var done = false
    task.cleanUpModelFn(
      context = context,
      coroutineScope = CoroutineScope(Dispatchers.Unconfined),
      model = top,
      onDone = { done = true },
    )
    assertTrue("the clean-up of the top screen's model did not end", done)
    assertTrue(
      "the clean-up closed the chat below's model: ${helper.cleanedUp}",
      below.name !in helper.cleanedUp,
    )
    val (events, thrown) = ask()
    assertEquals("the stream threw", null, thrown?.toString())
    assertEquals("the question below went to", listOf(below.name), sessions.answeredWith.toList())
    assertTrue("the question did not end: $events", events.any { it is AgentEvent.LoopTerminated })
  }
}
