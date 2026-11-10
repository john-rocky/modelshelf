/*
 * Copyright 2025 Google LLC
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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.common.metrics.InferenceStatus
import com.google.ai.edge.gallery.common.metrics.MetricsTracker
import com.google.ai.edge.gallery.common.metrics.asSession
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.LITERT_LM_RUNTIME_VERSION
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.THOUGHT_CHANNEL
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.data.supportModelBenchmark
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.EXTRA_CONTEXT_MAX_OUTPUT_TOKENS
import com.google.ai.edge.gallery.runtime.LlmConversationInstance
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.server.ApiHoldsModelFile
import com.google.ai.edge.gallery.server.ChatEngineSlot
import com.google.ai.edge.gallery.server.EngineOwnership
import com.google.ai.edge.gallery.server.makeForChat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatModelHelper"
private const val EXTRA_CONTEXT_EPHEMERAL_INFERENCE = "aicore_ephemeral_inference"

// Model Shelf: shown when the local API holds the model file.
private const val API_HOLDS_MODEL =
  "The local API is using this model. Stop the local API in Settings > Local API to chat with " +
    "the model here."

// Model Shelf: shown when another chat's engine is answering while this chat opens its model (one
// chat engine per process: ChatEngineSlot closes nothing that answers).
private const val ANOTHER_CHAT_ANSWERS =
  "Another chat is still answering; open this model again when that answer ends."

// Model Shelf: how long the answer of a screen that is gone gets to stop before its engine closes.
private const val ANSWER_STOP_WAIT_MS = 10_000L

// Model Shelf: a load whose screen went away while the engine was made (D-614 round 10d).
private const val SCREEN_GONE = "The screen of this chat closed while its model loaded."

// Model Shelf: a question on an engine that was closed, or sent while a link replaces the screen
// (D-614 round 10d); the chat shows it and loads its model again.
private const val CHAT_ENGINE_CLOSED = "The model of this chat was closed."

/**
 * Holds the runtime state for an initialized LiteRT-LM model.
 *
 * @param engine The underlying LiteRT-LM [Engine] managing model weights and hardware acceleration.
 * @param conversation The active [Conversation] session handling prompt history and token
 *   generation.
 * @param metricsTracker Optional [MetricsTracker] collecting per-turn latency, throughput, memory,
 *   and battery telemetry. Null when the instance is handed in from an external cache (e.g. a
 *   shared agent conversation manager), because the engine initialization for that path runs
 *   outside [LlmChatModelHelper.initialize] and so is not measured. Tracks nothing when the model
 *   does not support benchmark telemetry.
 * @param turnsInFlight Model Shelf: the answers of the main conversation that run now (0 or 1); the
 *   process's one chat engine is not closed for another chat while it answers.
 */
data class LlmModelInstance(
  val engine: Engine,
  var conversation: Conversation,
  val metricsTracker: MetricsTracker? = null,
  val turnsInFlight: AtomicInteger = AtomicInteger(0),
) : LlmConversationInstance {
  val activeEphemeralConversations: MutableSet<Conversation> =
    Collections.synchronizedSet(mutableSetOf())

  override fun getTokenCount(): Int = conversation.getTokenCount()

  override fun sendMessage(prompt: String, maxOutputTokens: Int?): Message =
    conversation.sendMessage(prompt, maxOutputToken = maxOutputTokens)
}

object LlmChatModelHelper : LlmModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  // Model Shelf: the process's one chat engine (D-614 round 10c). Every chat engine is made here,
  // in initialize, through this slot, which first closes any chat engine still alive (another
  // screen's, or one a screen that is gone left behind) and closes each engine once.
  private val chatEngines =
    ChatEngineSlot<Model, LlmModelInstance>(
      isAnswering = { it.turnsInFlight.get() > 0 },
      closeEngine = { model, instance -> closeInstance(model, instance) },
    )

  @Suppress("GlobalCoroutineDispatchers", "AndroidLintDispatcherUsage")
  internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun initialize(
    context: Context,
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
      Log.d(TAG, "Model '${model.name}' already initialized in LlmChatModelHelper. Skipping.")
      model.markInitialized()
      onDone("")
      return
    }
    model.markInitializationStarted()
    // Model Shelf: one engine per model file; the local API may hold this one. The file is
    // reserved right before the engine is made (below), and given back if the engine cannot be
    // made, and in cleanUp.
    if (EngineOwnership.apiHolds(model.getPath(context = context))) {
      Log.i(TAG, "'${model.name}': $API_HOLDS_MODEL")
      model.markInitializationFailed(API_HOLDS_MODEL)
      onDone(API_HOLDS_MODEL)
      return
    }
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val visionBackend =
      when (model.currentVisionAccelerator) {
        Accelerator.CPU -> Backend.CPU()
        Accelerator.GPU -> Backend.GPU()
        Accelerator.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
      }
    val shouldEnableImage = supportImage
    val shouldEnableAudio = supportAudio
    val preferredBackend =
      when (model.currentAccelerator ?: Accelerator.GPU) {
        Accelerator.CPU -> Backend.CPU()
        Accelerator.GPU -> Backend.GPU()
        Accelerator.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
      }
    Log.d(TAG, "Preferred backend: $preferredBackend")

    val modelPath = model.getPath(context = context)
    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = preferredBackend,
        visionBackend = if (shouldEnableImage) visionBackend else null, // must be GPU for Gemma 3n
        audioBackend = if (shouldEnableAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
        maxNumTokens = maxTokens,
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath
          else null,
      )

    var supportsSpeculativeDecoding = false
    // Check if the model file supports speculative decoding and visual token budget selection.
    try {
      ModelInfo.from(model.getPath(context)).use { modelInfo ->
        if (modelInfo is ModelInfo.Llm) {
          supportsSpeculativeDecoding = modelInfo.hasSpeculativeDecodingSupport()
        }
      }
    } catch (e: Exception) {
      // Ignore exceptions and assume not supported.
    }
    var speculativeDecoding = false
    // Check if the model supports speculative decoding for the given task type and if the
    // speculative decoding is enabled in the settings.
    if (
      supportsSpeculativeDecoding &&
        model.allowCapability(capability = ModelCapability.SPECULATIVE_DECODING, taskId = taskId)
    ) {
      speculativeDecoding =
        model.getBooleanConfigValue(
          key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
          defaultValue = false,
        )
    }
    val metricsTracker =
      MetricsTracker.create(
        context = context,
        model = model,
        taskId = taskId,
        ioDispatcher = ioDispatcher,
      )
    // Create an instance of LiteRT LM engine and conversation.
    // Model Shelf: through the process's one chat-engine slot. Every chat engine still alive is
    // closed before this one is made, and none is closed while it answers (then this chat gets
    // one sentence instead of an engine).
    val made =
      try {
        chatEngines.makeForChat(
          owner = model,
          name = model.name,
          path = modelPath,
          create = {
            Log.i(
              TAG,
              "LiteRT-LM $LITERT_LM_RUNTIME_VERSION: creating the engine for '${model.name}' on " +
                "${model.currentAccelerator ?: Accelerator.GPU}, max tokens $maxTokens",
            )
            val enableBenchmark = false
            ExperimentalFlags.enableBenchmark = enableBenchmark
            ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
            Log.d(TAG, "Speculative decoding enabled: $speculativeDecoding")
            val engine = Engine(engineConfig)
            try {
              engine.initialize()
            } finally {
              ExperimentalFlags.enableSpeculativeDecoding = false
              ExperimentalFlags.enableBenchmark = false
            }

            val conversation =
              try {
                createConversation(
                  engine = engine,
                  model = model,
                  systemInstruction = systemInstruction,
                  tools = tools,
                  enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
                  initialMessages = initialMessages,
                  enableResponseFormat = enableResponseFormat,
                )
              } catch (e: Exception) {
                try {
                  engine.close()
                } catch (_: Exception) {}
                throw e
              }
            LlmModelInstance(
              engine = engine,
              conversation = conversation,
              metricsTracker = metricsTracker,
            )
          },
          publish = { instance -> model.markInitialized(instance) },
          closing = { other ->
            Log.i(
              TAG,
              "Clean up model '${other.name}' done (closed before '${model.name}' opens: one chat " +
                "engine per process)",
            )
          },
        )
      } catch (e: ApiHoldsModelFile) {
        // The local API took the file after the check at the top (its reservation is given back).
        Log.i(TAG, "'${model.name}': $API_HOLDS_MODEL")
        model.markInitializationFailed(API_HOLDS_MODEL)
        metricsTracker.onModelInitialized()
        onDone(API_HOLDS_MODEL)
        return
      } catch (e: Exception) {
        // makeForChat gave back the chat's reservation of the file.
        val errorMsg = cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error")
        model.markInitializationFailed(errorMsg)
        metricsTracker.onModelInitialized()
        onDone(errorMsg)
        return
      }
    if (made is ChatEngineSlot.Result.Answering<*>) {
      val answering = (made.owner as? Model)?.name ?: "another chat"
      Log.i(TAG, "'${model.name}': $ANOTHER_CHAT_ANSWERS ('$answering' answers)")
      model.markInitializationFailed(ANOTHER_CHAT_ANSWERS)
      metricsTracker.onModelInitialized()
      onDone(ANOTHER_CHAT_ANSWERS)
      return
    }
    if (made is ChatEngineSlot.Result.Gone<*>) {
      // Model Shelf: closed once made, its screen being gone (D-614 round 10d, review finding 10).
      Log.i(TAG, "'${model.name}': $SCREEN_GONE (its engine is closed)")
      model.markInitializationFailed(SCREEN_GONE)
      metricsTracker.onModelInitialized()
      logLiveChatEngines("after closing the engine of '${model.name}' (its screen is gone)")
      onDone(SCREEN_GONE)
      return
    }
    logLiveChatEngines("after creating the engine for '${model.name}'")
    metricsTracker.onModelInitialized()
    onDone("")
  }

  /**
   * Model Shelf: closes the chat engine that one of [models] holds: the models of a screen that is
   * gone for good, so no chat engine outlives every screen. Runs on the IO dispatcher; an answer
   * the engine runs is stopped first and gets [ANSWER_STOP_WAIT_MS] to end (an engine that still
   * answers then stays until the answer ends and another chat opens).
   */
  fun closeEnginesOf(models: Collection<Model>, why: String) {
    val owners = Collections.newSetFromMap(IdentityHashMap<Model, Boolean>())
    owners.addAll(models)
    // An engine still being made for one of them is closed as soon as it is made (D-614 round 10d).
    val engines = chatEngines.screensGone { it in owners }
    if (engines.isEmpty()) return
    CoroutineScope(ioDispatcher).launch {
      for (instance in engines) {
        val model = chatEngines.ownerOf(instance) ?: continue
        if (!answerEnded(model, instance, why)) continue
        if (chatEngines.close(instance)) {
          Log.i(TAG, "Clean up model '${model.name}' done ($why)")
          logLiveChatEngines("after closing the engine of '${model.name}'")
        }
      }
    }
  }

  /**
   * Model Shelf: the sentence that a link to a screen of [model] shows over the current screen
   * instead of opening that screen, or null when the screen opens. A screen opened on top of a chat
   * gets no engine while a chat engine answers (the slot closes nothing that answers), nor while
   * the local API holds the model file; and going back from such a screen cleans up the AI Chat
   * executor's one session, which the chat below would need for its next question. So the link
   * opens nothing then, and the chat below stays as it is.
   */
  fun sentenceInsteadOfLinkedScreen(context: Context, model: Model): String? =
    when {
      chatEngines.anyAnswering() -> ANOTHER_CHAT_ANSWERS
      chatEngines.liveCount > 0 && EngineOwnership.apiHolds(model.getPath(context = context)) ->
        API_HOLDS_MODEL
      else -> null
    }

  /**
   * Model Shelf: [sentenceInsteadOfLinkedScreen] and, when it is null, a hold on the chat-engine
   * slot until [releaseLinkedScreenHold], both under the slot's lock: a question sent between the
   * gate and the switch of screens is not sent (it gets an error), and a question sent before the
   * gate makes the gate say that a chat answers (D-614 round 10d, review finding 7).
   */
  fun holdForLinkedScreen(context: Context, model: Model): String? {
    val path = model.getPath(context = context)
    return chatEngines.hold { answering, liveCount ->
      when {
        answering -> ANOTHER_CHAT_ANSWERS
        liveCount > 0 && EngineOwnership.apiHolds(path) -> API_HOLDS_MODEL
        else -> null
      }
    }
  }

  /** Model Shelf: ends [holdForLinkedScreen]. */
  fun releaseLinkedScreenHold() = chatEngines.release()

  // Model Shelf: the number of chat engines alive in this process (0 or 1), read in the device
  // checks (D-614 round 10c, tools/r10c_engines.py).
  private fun logLiveChatEngines(after: String) {
    Log.i(TAG, "Live chat engines in this process: ${chatEngines.liveCount} ($after)")
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
  ) {
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      try {
        instance.conversation.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close previous conversation: ${e.message}", e)
      }
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")
      Log.d(TAG, "Enable response format: $enableResponseFormat")

      val newConversation =
        createConversation(
          engine = instance.engine,
          model = model,
          systemInstruction = systemInstruction,
          tools = tools,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          initialMessages = initialMessages,
          enableResponseFormat = enableResponseFormat,
        )
      instance.conversation = newConversation
      // The replacement conversation starts on an empty KV cache, so per-session token accounting
      // and sensor histories are rewound to match. This also clears a turn left active by closing
      // the old conversation mid-flight.
      instance.metricsTracker?.resetSession()

      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset conversation", e)
    }
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  private fun createConversation(
    engine: Engine,
    model: Model,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
    enableResponseFormat: Boolean,
  ): Conversation {
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator = model.currentAccelerator ?: Accelerator.GPU
    val samplerConfig =
      if (accelerator == Accelerator.NPU || accelerator == Accelerator.TPU) {
        null
      } else {
        SamplerConfig(
          topK = topK,
          topP = topP.toDouble(),
          temperature = temperature.toDouble(),
        )
      }
    // Model Shelf: which sampler the conversation gets, to check a card's session_defaults on a
    // phone (D-614 round 4).
    Log.i(
      TAG,
      if (samplerConfig != null) {
        "Sampler for '${model.name}' on $accelerator: top-k $topK, top-p $topP, " +
          "temperature $temperature"
      } else {
        "Sampler for '${model.name}' on $accelerator: none set (the model file's own)"
      },
    )
    ExperimentalFlags.enableConversationConstrainedDecoding = enableConversationConstrainedDecoding
    return try {
      engine.createConversation(
        ConversationConfig(
          samplerConfig = samplerConfig,
          systemInstruction = systemInstruction,
          tools = tools,
          initialMessages = initialMessages,
        )
      )
    } finally {
      ExperimentalFlags.enableConversationConstrainedDecoding = false
    }
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun cleanUp(model: Model, onDone: () -> Unit) {
    // Model Shelf: the engine is read once (a close on another thread between two reads made the
    // cast throw), and a model with no engine (a refused load, an engine another chat's load
    // closed) is done at once: onDone was not called then, and the callers that go on in it (the
    // chat's error path that loads the model again) stopped there (D-614 round 10d).
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onDone()
      return
    }

    // Model Shelf: an answer still running (Stop was pressed, its turn is still winding down) is
    // stopped and given time to end before its engine is closed; an engine that still answers then
    // is left to the next chat's load (as closeEnginesOf does), never closed while it answers.
    if (!answerEnded(model, instance, why = "its screen is left")) {
      onDone()
      return
    }

    // Model Shelf: closed through the chat-engine slot, which closes each engine once: the slot may
    // have closed this one already (another chat opened, or the screen is gone).
    if (!chatEngines.close(instance)) {
      Log.d(TAG, "The engine of '${model.name}' was closed already.")
      if (model.instance === instance) {
        model.resetInitialization()
      }
      onDone()
      return
    }
    onDone()
    logLiveChatEngines("after closing the engine of '${model.name}'")
  }

  /**
   * Model Shelf: closes [instance] of [model]: what cleanUp did before the chat-engine slot. The
   * slot calls this once per engine, with its lock held.
   */
  @OptIn(ExperimentalApi::class) // as cleanUp before
  private fun closeInstance(model: Model, instance: LlmModelInstance) {
    val snapshotEphemerals = instance.activeEphemeralConversations.toList()
    instance.activeEphemeralConversations.clear()
    for (ephemeral in snapshotEphemerals) {
      try {
        ephemeral.cancelProcess()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to cancel ephemeral conversation during cleanup: ${e.message}")
      }
      try {
        ephemeral.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close ephemeral conversation during cleanup: ${e.message}")
      }
    }

    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }

    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }
    EngineOwnership.releaseForChat(model.name)

    instance.metricsTracker?.resetSession()
    instance.metricsTracker?.close()

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    // Model Shelf: the model's state goes back to idle only if this is still its engine (an older
    // engine of a model that loads again now leaves the new load alone).
    if (model.instance === instance) {
      model.resetInitialization()
    }

    Log.d(TAG, "Clean up done.")
  }

  /**
   * Model Shelf: true when [instance] answers nothing (any more): an answer it runs is stopped and
   * gets [ANSWER_STOP_WAIT_MS] to end. False when it still answers then (it is not closed). Blocks;
   * runs off the main thread (the leave clean-up, closeEnginesOf).
   */
  private fun answerEnded(model: Model, instance: LlmModelInstance, why: String): Boolean {
    if (instance.turnsInFlight.get() <= 0) return true
    Log.i(TAG, "Stopping the answer of '${model.name}' ($why)")
    stopInstance(instance)
    var waitedMs = 0L
    while (instance.turnsInFlight.get() > 0 && waitedMs < ANSWER_STOP_WAIT_MS) {
      Thread.sleep(50)
      waitedMs += 50
    }
    if (instance.turnsInFlight.get() > 0) {
      Log.w(TAG, "'${model.name}' still answers $ANSWER_STOP_WAIT_MS ms after the stop")
      return false
    }
    return true
  }

  // Model Shelf: stops what [instance] answers now (the main conversation and any ephemeral one).
  private fun stopInstance(instance: LlmModelInstance) {
    try {
      instance.conversation.cancelProcess()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to cancel conversation process", e)
    }
    for (ephemeral in instance.activeEphemeralConversations.toList()) {
      try {
        ephemeral.cancelProcess()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to cancel ephemeral conversation process", e)
      }
    }
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlmModelInstance ?: return
    try {
      // Signal the native engine to cancel first. The engine's worker thread will finalize
      // `TimeDecodeTurnEnd()` in `BenchmarkInfo` and then invoke
      // `MessageCallback.onError(CancellationException)`, which calls `metricsTracker.cancelTurn()`
      // at the exact point when engine benchmark and KV-cache token counts are ready.
      instance.conversation.cancelProcess()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to cancel conversation process", e)
      val unused = instance.metricsTracker?.cancelTurn()
    }
    for (ephemeral in instance.activeEphemeralConversations.toList()) {
      try {
        ephemeral.cancelProcess()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to cancel ephemeral conversation process", e)
      }
    }
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
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
  ) {
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onError("LlmModelInstance is not initialized.")
      return
    }

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val isEphemeral = extraContext[EXTRA_CONTEXT_EPHEMERAL_INFERENCE] == "true"
    val conversation =
      if (isEphemeral) {
        val accelerator = model.currentAccelerator ?: Accelerator.GPU
        val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
        val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
        val temperature =
          model.getFloatConfigValue(
            key = ConfigKeys.TEMPERATURE,
            defaultValue = DEFAULT_TEMPERATURE,
          )
        instance.engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (accelerator == Accelerator.NPU || accelerator == Accelerator.TPU) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
          )
        )
      } else {
        instance.conversation
      }

    // Model Shelf: an answer of the main conversation counts from the send to the engine's last
    // callback (or a failure before the send), so the chat-engine slot does not close the engine
    // under it.
    // Its start and end are logged for the device checks (D-614 round 10c).
    val turnCounted = AtomicBoolean(false)
    fun endCountedTurn(how: String) {
      if (turnCounted.compareAndSet(true, false)) {
        instance.turnsInFlight.decrementAndGet()
        Log.i(TAG, "Answer of '${model.name}' ended ($how)")
      }
    }

    val ephemeralClosed = AtomicBoolean(false)
    var scopeCompletionHandle: DisposableHandle? = null
    fun closeEphemeralConversationOnce() {
      if (!isEphemeral || !ephemeralClosed.compareAndSet(false, true)) return
      scopeCompletionHandle?.dispose()
      scopeCompletionHandle = null
      instance.activeEphemeralConversations.remove(conversation)
      // Offload native Conversation.close() onto ioDispatcher so MessageCallback threads never
      // self-join their own native LiteRT-LM worker thread.
      CoroutineScope(ioDispatcher).launch {
        try {
          conversation.close()
        } catch (e: Exception) {
          Log.w(TAG, "Failed to close ephemeral conversation: ${e.message}", e)
        }
      }
    }

    if (isEphemeral) {
      instance.activeEphemeralConversations.add(conversation)
      scopeCompletionHandle =
        coroutineScope?.coroutineContext?.get(Job)?.invokeOnCompletion { cause ->
          if (cause != null) {
            try {
              conversation.cancelProcess()
            } catch (e: Exception) {
              Log.w(TAG, "Failed to cancel ephemeral conversation on scope cancellation", e)
            }
          }
        }
    }

    // Model Shelf: an answer of the main conversation counts as answering from here, before its
    // input is encoded, and the mark goes up under the chat-engine slot's lock: the link gate and
    // the slot see it before anything is sent, and an engine the slot closed, or the slot while a
    // link holds it, gets no turn (D-614 round 10d, review finding 7: the mark went up right before
    // the send, outside the lock).
    if (!isEphemeral) {
      val started =
        chatEngines.startTurn(instance) {
          turnCounted.set(true)
          instance.turnsInFlight.incrementAndGet()
        }
      if (started == null) {
        Log.i(TAG, "Answer of '${model.name}' not started: $CHAT_ENGINE_CLOSED")
        onError(CHAT_ENGINE_CLOSED)
        return
      }
      Log.i(TAG, "Answer of '${model.name}' started")
    }
    try {
      // Step 1: Start the turn before processing the input, so its latency includes the input
      // processing the user waits for.
      if (!isEphemeral) {
        instance.metricsTracker?.startTurn(conversation.asSession())
      }
      // Step 2: Assemble multimodal prompt attachments (images, audio clips, and text).
      val contents = mutableListOf<Content>()
      for (image in images) {
        contents.add(Content.ImageBytes(image.toPngByteArray()))
      }
      for (audioClip in audioClips) {
        contents.add(Content.AudioBytes(audioClip))
      }
      // Add text after images/audio to ensure proper autoregressive token sequencing.
      if (input.trim().isNotEmpty()) {
        contents.add(Content.Text(input))
      }

      // Step 3: Configure extra runtime parameters (such as thinking reasoning mode).
      val enableThinking = extraContext["enable_thinking"] == "true"
      val finalExtraContext: Map<String, Any> = extraContext + ("enable_thinking" to enableThinking)
      // Caps the reply length when the caller sets EXTRA_CONTEXT_MAX_OUTPUT_TOKENS. Without the
      // cap, a constrained reply whose schema allows unlimited whitespace can decode until the KV
      // cache is full.
      val maxOutputTokens =
        extraContext[EXTRA_CONTEXT_MAX_OUTPUT_TOKENS]?.toIntOrNull()?.takeIf { it > 0 }

      // Step 4: Dispatch asynchronous streaming inference to the native LiteRT-LM engine.
      conversation.sendMessageAsync(
        Contents.of(contents),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val text = message.toString()
            val thinking = message.channels[THOUGHT_CHANNEL]
            // Record streaming token to lock TTFT on first token and update live metrics.
            if (!isEphemeral) {
              instance.metricsTracker?.onNewToken(tokenText = text, thinkingText = thinking)
            }
            resultListener(text, false, thinking)
          }

          override fun onDone() {
            if (isEphemeral) {
              closeEphemeralConversationOnce()
            } else {
              // Finalize turn metrics with SUCCESS status.
              val unused =
                instance.metricsTracker?.endTurn(
                  statusCode = InferenceStatus.Code.SUCCESS,
                  errorMessage = null,
                )
            }
            resultListener("", true, null)
            endCountedTurn("done")
          }

          override fun onError(throwable: Throwable) {
            closeEphemeralConversationOnce()
            if (throwable is CancellationException) {
              // Native cancellation via cancelProcess() completed on the worker thread; finalize
              // cancelled-turn metrics now that engine benchmark and token counts are ready.
              Log.i(TAG, "The inference is cancelled.")
              if (!isEphemeral) {
                val unused = instance.metricsTracker?.cancelTurn()
              }
              resultListener("", true, null)
            } else {
              // Engine error or crash: record ERROR status with error message.
              Log.e(TAG, "onError", throwable)
              if (!isEphemeral) {
                val unused =
                  instance.metricsTracker?.endTurn(
                    statusCode = InferenceStatus.Code.ERROR,
                    errorMessage = throwable.message ?: "Unknown error",
                  )
              }
              onError("Error: ${throwable.message}")
            }
            endCountedTurn(if (throwable is CancellationException) "stopped" else "error")
          }
        },
        finalExtraContext,
        maxOutputToken = maxOutputTokens,
      )
    } catch (e: Exception) {
      if (isEphemeral) {
        closeEphemeralConversationOnce()
      } else {
        // No engine callback follows a failure before the message is sent, so end the turn here.
        val unused =
          instance.metricsTracker?.endTurn(
            statusCode = InferenceStatus.Code.ERROR,
            errorMessage = e.message ?: "Unknown error",
          )
      }
      endCountedTurn("not sent: ${e.message}")
      throw e
    }
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
