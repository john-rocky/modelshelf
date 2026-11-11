/*
 * Copyright 2026 Google LLC
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

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.SessionConfig
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.tools.ToolDispatcher
import com.google.ai.edge.gallery.tools.ToolExecutionContext
import com.google.ai.edge.gallery.tools.ToolsProvider
import com.google.ai.edge.litertlm.Contents
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "AGDefaultAgentRuntimeExecutor"

/**
 * Default implementation of [AgentRuntimeExecutor] orchestrating context assembly, execution
 * context injection, LiteRT-LM inference turns, and event streaming.
 *
 * This implementation is thread-safe and lock-free. Internal session state such as the active model
 * and tool execution context is encapsulated within an immutable structure managed by an atomic
 * reference. This allows lifecycle operations ([initialize], [executeStream], [interrupt], and
 * [cleanUp]) to be invoked safely across concurrent threads or coroutines without contention, race
 * conditions, or deadlocks.
 *
 * @property skillsProvider Provider for retrieving active skills.
 * @property toolsProvider Provider for retrieving available tools.
 * @property toolDispatcher Dispatcher for executing tool calls.
 * @property llmSessionManager Manager for orchestrating LLM session lifecycle and persistence.
 */
open class DefaultAgentRuntimeExecutor(
  val skillsProvider: SkillsProvider,
  val toolsProvider: ToolsProvider,
  val toolDispatcher: ToolDispatcher,
  val llmSessionManager: LlmSessionManager,
) : AgentRuntimeExecutor {

  /** Active session state for the current conversation. */
  private data class ActiveSession(
    val sessionConfig: SessionConfig,
    val toolExecutionContext: ToolExecutionContext,
  )

  /** Atomic reference to the active session. */
  private val activeSession = AtomicReference<ActiveSession?>(null)

  override val activeSessionId: String?
    get() = llmSessionManager.activeSessionId

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (String) -> Unit,
  ) {
    val effectiveSessionId =
      config.sessionId.ifEmpty { llmSessionManager.activeSessionId ?: generateSessionId() }
    val effectiveSystemInstruction =
      if (!config.systemInstruction.isNullOrEmpty()) Contents.of(config.systemInstruction) else null
    val executionContext =
      ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel)

    val sessionConfig =
      SessionConfig(
        model = config.model,
        taskId = config.taskId,
        supportImage = config.supportImage,
        supportAudio = config.supportAudio,
        systemInstruction = effectiveSystemInstruction,
        tools = toolsProvider.getLiteRtToolProviders(),
        enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
      )

    // Model Shelf: the session is stored only when the model loaded (D-614 round 10d). A refused or
    // failed load (another chat answers, the local API holds the file) leaves the session of the
    // chat that has a model; before, it replaced it, and that chat's clean-up then took the
    // refused model's session.
    val newSession =
      ActiveSession(sessionConfig = sessionConfig, toolExecutionContext = executionContext)

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = executionContext,
    )

    val wasModelInitialized =
      config.model.initStatusFlow.value is Model.InitializationStatus.Initialized

    config.model.runtimeHelper.initialize(
      context = context,
      model = config.model,
      taskId = config.taskId,
      supportImage = config.supportImage,
      supportAudio = config.supportAudio,
      onDone = { error ->
        if (
          error.isEmpty() &&
            config.model.initStatusFlow.value is Model.InitializationStatus.Initialized
        ) {
          activeSession.set(newSession)
          if (!wasModelInitialized) {
            llmSessionManager.setSessionConfig(
              sessionId = effectiveSessionId,
              config = sessionConfig,
            )
          }
        }
        onDone(error)
      },
      systemInstruction = effectiveSystemInstruction,
      tools = toolsProvider.getLiteRtToolProviders(),
      enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
      initialMessages = config.initialMessages,
    )

    if (
      wasModelInitialized &&
        config.model.initStatusFlow.value is Model.InitializationStatus.Initialized
    ) {
      activeSession.set(newSession)
      llmSessionManager.resetSession(
        sessionId = effectiveSessionId,
        config = sessionConfig,
        initialMessages = config.initialMessages,
        enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
      )
    }
  }

  private fun ProducerScope<AgentEvent>.emitEvent(event: AgentEvent) {
    trySend(event).onFailure { Log.w(TAG, "Failed to emit event: ${event::class.simpleName}") }
  }

  override fun executeStream(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): Flow<AgentEvent> = callbackFlow {
    emitEvent(AgentEvent.LoopInitiated(request = request))

    // Model Shelf: with no session (its load was refused, or another screen's clean-up took it) the
    // question ends in an error, which the chat shows and answers by loading its model again; it
    // threw before, and nothing caught it, so the process ended with every app's local API (D-614
    // round 10d).
    val session = activeSession.get()
    if (session == null) {
      Log.w(TAG, "A question with no session: $NO_SESSION")
      emitEvent(AgentEvent.Error(errorMessage = NO_SESSION))
      close()
      awaitClose {}
      return@callbackFlow
    }
    val curModel = session.sessionConfig.model

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = session.toolExecutionContext,
    )

    val images = request.attachments.filterIsInstance<Attachment.ImageBitmap>().map { it.bitmap }
    val audioClips =
      request.attachments.filterIsInstance<Attachment.AudioBytes>().map { it.audioBytes }

    val finalResponse = StringBuilder()
    val extraContext =
      (request.metadata[AgentRequest.LITERTLM_EXTRA_CONTEXT] as? Map<*, *>)
        ?.entries
        ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
        ?.toMap() ?: emptyMap()
    val sessionId =
      (request.metadata[AgentRequest.SESSION_ID] as? String) ?: llmSessionManager.activeSessionId
    if (sessionId == null) {
      Log.w(TAG, "A question with no session id: $NO_SESSION")
      emitEvent(AgentEvent.Error(errorMessage = NO_SESSION))
      close()
      awaitClose {}
      return@callbackFlow
    }

    val resultListener = { partialResult: String, done: Boolean, partialThinking: String? ->
      if (!partialResult.startsWith("<ctrl")) {
        if (partialResult.isNotEmpty() || !partialThinking.isNullOrEmpty() || done) {
          emitEvent(
            AgentEvent.StreamToken(token = partialResult, thinking = partialThinking, done = done)
          )
        }
        if (partialResult.isNotEmpty()) {
          finalResponse.append(partialResult)
        }
      }
      if (done) {
        emitEvent(AgentEvent.LoopTerminated(finalResponse = finalResponse.toString()))
        close()
      }
    }

    val cleanUpListener = {
      emitEvent(AgentEvent.LoopCancelled)
      close()
      Unit
    }

    val onError = { errorMsg: String ->
      Log.e(TAG, "Error in AgentLoop inference: $errorMsg")
      emitEvent(AgentEvent.Error(errorMessage = errorMsg))
      close()
      Unit
    }

    llmSessionManager.generateResponse(
      sessionId = sessionId,
      model = curModel,
      input = request.query,
      resultListener = resultListener,
      cleanUpListener = cleanUpListener,
      onError = onError,
      images = images,
      audioClips = audioClips,
      extraContext = extraContext,
    )

    awaitClose {
      // Clean up when flow collection is cancelled
    }
  }

  override suspend fun execute(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): AgentResponse {
    var finalOutput = ""
    var isSuccess = true

    executeStream(context, request).collect { event ->
      when (event) {
        is AgentEvent.LoopTerminated -> {
          finalOutput = event.finalResponse
        }
        is AgentEvent.Error -> {
          isSuccess = false
          finalOutput = event.errorMessage
        }
        else -> {}
      }
    }
    return AgentResponse(output = finalOutput, isSuccessful = isSuccess)
  }

  override fun interrupt() {
    val session = activeSession.get() ?: return
    val curModel = session.sessionConfig.model
    val sessionId = llmSessionManager.activeSessionId ?: return
    Log.d(TAG, "Interrupting session $sessionId for model: ${curModel.name}")
    llmSessionManager.stopResponse(sessionId = sessionId, model = curModel)
  }

  override suspend fun resetSession(config: AgentRuntimeConfig) {
    val effectiveSessionId =
      config.sessionId.ifEmpty { llmSessionManager.activeSessionId ?: generateSessionId() }
    val executionContext =
      ToolExecutionContext(taskId = config.taskId, actionChannel = config.actionChannel)

    val effectiveSystemInstruction =
      if (!config.systemInstruction.isNullOrEmpty()) Contents.of(config.systemInstruction) else null
    val sessionConfig =
      SessionConfig(
        model = config.model,
        taskId = config.taskId,
        supportImage = config.supportImage,
        supportAudio = config.supportAudio,
        systemInstruction = effectiveSystemInstruction,
        tools = toolsProvider.getLiteRtToolProviders(),
        enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
      )

    activeSession.set(
      ActiveSession(sessionConfig = sessionConfig, toolExecutionContext = executionContext)
    )

    toolDispatcher.setupExecutionContext(
      tools = toolsProvider.getAvailableTools(),
      context = executionContext,
    )

    llmSessionManager.resetSession(
      sessionId = effectiveSessionId,
      config = sessionConfig,
      initialMessages = config.initialMessages,
      enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
    )
  }

  override fun cleanUp(onDone: () -> Unit) = cleanUp(model = null, onDone = onDone)

  // Model Shelf: the session is dropped only when it is [model]'s (D-614 round 10d). The clean-up of
  // one screen's model (late, after the chat below loaded another model) took the executor's one
  // session before, whatever model it was asked about, and that chat's next question crashed.
  override fun cleanUp(model: Model?, onDone: () -> Unit) {
    val session = activeSession.get()
    val sessionModel = session?.sessionConfig?.model
    if (session != null && (model == null || sessionModel === model)) {
      activeSession.compareAndSet(session, null)
    }
    val target = model ?: sessionModel
    if (target == null) {
      onDone()
      return
    }
    target.runtimeHelper.cleanUp(model = target, onDone = onDone)
  }
}

/** Model Shelf: what a question gets when the executor holds no session. */
internal const val NO_SESSION = "The model of this chat is not loaded."
