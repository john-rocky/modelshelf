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

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MSApiEngine"

/** Requests that may wait behind the one that runs (DESIGN §g-3, "その他の決まり"). */
private const val MAX_WAITING = 4

/** How long a stopped answer may take to end before the API gives up waiting for it. */
private const val STOP_WAIT_MS = 15_000L

/**
 * The local API's engine: one LLM loaded at a time, one answer at a time, up to [MAX_WAITING]
 * requests waiting in arrival order (DESIGN §g-3).
 *
 * State (constraint 1, LiteRT-LM #3165): the host keeps the conversation it holds and the messages
 * it has seen. A request that repeats them and adds one user message continues that conversation.
 * Any other request starts a new conversation; for a model that keeps state between conversations
 * on one engine (`stateful`), the engine is made again first, so no request sees another's state.
 */
class ApiEngineHost(private val context: Context, private val onStateChanged: () -> Unit) {
  private val engineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "ms-api-engine") }.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val turnLock = Mutex()
  private val inFlight = AtomicInteger(0)
  private var unloadJob: Job? = null
  @Volatile private var loaded: Loaded? = null
  @Volatile private var active: Conversation? = null
  @Volatile private var averageTurnMs = 15_000L
  private val wakeLock: PowerManager.WakeLock =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ModelShelf:LocalApi")
      .apply { setReferenceCounted(false) }

  /** The loaded model. */
  class Loaded(
    val entry: ApiModelEntry,
    var engine: Engine,
    val backend: String,
    val maxNumTokens: Int,
  ) {
    var conversation: Conversation? = null
    /** The messages of [conversation] and its own replies; null = nothing to continue. */
    var held: List<ChatTurn>? = null
    var sampler: ResolvedSampler? = null
    /** The engine has answered since it was made. */
    var engineUsed = false
    /** Wall-clock time the model unloads at; Long.MAX_VALUE = never. */
    var expiresAtMs = Long.MAX_VALUE
  }

  /** The queue is full. */
  class Busy(val retryAfterS: Int) : Exception("busy")

  /** A request ready to answer. */
  class Prepared(
    val loaded: Loaded,
    val conversation: Conversation,
    val text: String,
    /** What happened to the conversation, for the log and the response header. */
    val how: String,
    val engineRestart: Boolean,
    /** Milliseconds spent loading or restarting the engine for this request. */
    val loadMs: Long,
    val sampler: ResolvedSampler,
  )

  sealed class Event {
    data class Text(val text: String) : Event()

    data object Done : Event()

    data class Failed(val error: Throwable) : Event()
  }

  class Running(
    val prepared: Prepared,
    val events: Channel<Event>,
    val startMs: Long,
    val tokensBefore: Int?,
  )

  enum class End {
    DONE,
    TIMEOUT,
    FAILED,
    CLIENT_GONE,
  }

  class Result(
    val text: String,
    val firstTextMs: Long,
    val inferenceMs: Long,
    val chunks: Int,
    val end: End,
    val error: String?,
  )

  /** The loaded model, its expiry and backend, for /api/ps and the notification. */
  fun loadedInfo(): Triple<ApiModelEntry, Long, String>? =
    loaded?.let { Triple(it.entry, it.expiresAtMs, it.backend) }

  /**
   * Runs [block] when the requests before this one are done. Throws [Busy] when [MAX_WAITING]
   * requests already wait. [block] gets the milliseconds this request waited.
   */
  suspend fun <T> withTurn(block: suspend (queueMs: Long) -> T): T {
    val n = inFlight.incrementAndGet()
    if (n > 1 + MAX_WAITING) {
      inFlight.decrementAndGet()
      throw Busy(((averageTurnMs * (n - 1)) / 1000).toInt().coerceIn(1, 600))
    }
    val arrived = SystemClock.elapsedRealtime()
    try {
      return turnLock.withLock {
        unloadJob?.cancel()
        block(SystemClock.elapsedRealtime() - arrived)
      }
    } finally {
      inFlight.decrementAndGet()
    }
  }

  /** Loads [entry] (inside [withTurn]) without answering: Ollama's empty /api/chat. */
  suspend fun loadOnly(entry: ApiModelEntry, numCtx: Int?): Long {
    val t0 = SystemClock.elapsedRealtime()
    ensureLoaded(entry, numCtx)
    return SystemClock.elapsedRealtime() - t0
  }

  /**
   * Gets the engine and conversation for [request] (inside [withTurn]). [reuseEngineForTest] keeps
   * the engine of a stateful model (debug builds only), to show what the restart prevents.
   */
  suspend fun prepare(
    entry: ApiModelEntry,
    request: ApiRequest,
    reuseEngineForTest: Boolean,
  ): Prepared {
    val t0 = SystemClock.elapsedRealtime()
    val l = ensureLoaded(entry, request.numCtx)
    val npu = l.backend == "npu" || l.backend == "tpu"
    val sampler = resolveSampler(request.sampler, entry.sampler, npu)
    val held = if (l.conversation != null && sampler.sameValues(l.sampler)) l.held else null
    val plan = planTurn(held, request.turns)
    if (plan is TurnPlan.Continue) {
      return Prepared(
        loaded = l,
        conversation = l.conversation!!,
        text = plan.newTurn.text,
        how = "continued the conversation it holds",
        engineRestart = false,
        loadMs = SystemClock.elapsedRealtime() - t0,
        sampler = sampler,
      )
    }
    val fresh = plan as TurnPlan.Fresh
    closeConversation(l)
    var restart = false
    if (entry.stateful && l.engineUsed) {
      if (reuseEngineForTest) {
        Log.w(TAG, "TEST: keeping the used engine of stateful ${entry.name} (debug header)")
      } else {
        restartEngine(l)
        restart = true
      }
    }
    val conversation =
      withContext(engineDispatcher) {
        l.engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (sampler.applied) {
                SamplerConfig(
                  topK = sampler.topK.value,
                  topP = sampler.topP.value,
                  temperature = sampler.temperature.value,
                  seed = sampler.seed ?: 0,
                )
              } else {
                null
              },
            systemInstruction = fresh.system?.let { Contents.of(it) },
            initialMessages =
              fresh.earlier.map {
                if (it.role == TurnRole.ASSISTANT) Message.model(it.text) else Message.user(it.text)
              },
          )
        )
      }
    l.conversation = conversation
    l.held = null
    l.sampler = sampler
    return Prepared(
      loaded = l,
      conversation = conversation,
      text = fresh.last.text,
      how =
        if (restart) "new engine and conversation (the model keeps state between conversations)"
        else "new conversation",
      engineRestart = restart,
      loadMs = SystemClock.elapsedRealtime() - t0,
      sampler = sampler,
    )
  }

  /** Starts the answer. The events arrive in [Running.events]. */
  fun start(prepared: Prepared, maxOutputTokens: Int, timeoutMs: Long): Running {
    val events = Channel<Event>(Channel.UNLIMITED)
    val before = tokenCount(prepared.conversation)
    prepared.loaded.engineUsed = true
    active = prepared.conversation
    wakeLock.acquire(timeoutMs + 60_000L)
    val startMs = SystemClock.elapsedRealtime()
    try {
      prepared.conversation.sendMessageAsync(
        Contents.of(prepared.text),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val text = message.toString()
            if (text.isNotEmpty()) events.trySend(Event.Text(text))
          }

          override fun onDone() {
            events.trySend(Event.Done)
            events.close()
          }

          override fun onError(throwable: Throwable) {
            events.trySend(Event.Failed(throwable))
            events.close()
          }
        },
        mapOf("enable_thinking" to false),
        maxOutputToken = maxOutputTokens,
      )
    } catch (e: Exception) {
      events.trySend(Event.Failed(e))
      events.close()
    }
    return Running(prepared, events, startMs, before)
  }

  /**
   * Passes the answer's text to [onText] as it comes, until the end or [timeoutMs] from the start.
   * With [idleEveryMs], calls [onIdle] after that long without text (SSE keep-alive). A failure in
   * [onText] (the caller went away) stops the answer.
   */
  suspend fun pump(
    running: Running,
    timeoutMs: Long,
    idleEveryMs: Long?,
    onText: suspend (String) -> Unit,
    onIdle: (suspend () -> Unit)?,
  ): Result {
    val text = StringBuilder()
    var first = -1L
    var chunks = 0
    val deadline = running.startMs + timeoutMs
    fun elapsed() = SystemClock.elapsedRealtime() - running.startMs
    fun result(end: End, error: String?) = Result(text.toString(), first, elapsed(), chunks, end, error)
    try {
      while (true) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
          stop(running)
          return result(
            End.TIMEOUT,
            "The answer took longer than ${timeoutMs / 1000} s and was stopped.",
          )
        }
        val wait = if (idleEveryMs != null) minOf(remaining, idleEveryMs) else remaining
        val received = withTimeoutOrNull(wait) { running.events.receiveCatching() }
        if (received == null) {
          if (onIdle != null && SystemClock.elapsedRealtime() < deadline) onIdle()
          continue
        }
        when (val event = received.getOrNull()) {
          null -> return result(End.DONE, null)
          is Event.Text -> {
            if (first < 0) first = elapsed()
            text.append(event.text)
            chunks++
            onText(event.text)
          }
          is Event.Done -> return result(End.DONE, null)
          is Event.Failed -> {
            Log.e(TAG, "The engine failed while answering", event.error)
            return result(End.FAILED, event.error.message ?: event.error.toString())
          }
        }
      }
    } catch (e: CancellationException) {
      stop(running)
      throw e
    } catch (e: Exception) {
      stop(running)
      return result(End.CLIENT_GONE, e.toString())
    }
  }

  /**
   * Ends the turn: keeps the conversation for the next request when the answer ended normally,
   * else drops it (its state after a stopped answer is unknown). Returns the prompt's token count
   * when the engine gives one.
   */
  suspend fun finish(running: Running, result: Result?, requestTurns: List<ChatTurn>): Int? =
    withContext(NonCancellable) {
      active = null
      if (wakeLock.isHeld) wakeLock.release()
      val p = running.prepared
      val after = tokenCount(p.conversation)
      if (result != null && result.end == End.DONE) {
        p.loaded.held = requestTurns + ChatTurn(TurnRole.ASSISTANT, result.text)
        p.loaded.sampler = p.sampler
        averageTurnMs = (averageTurnMs * 3 + result.inferenceMs) / 4
      } else {
        p.loaded.held = null
        closeConversation(p.loaded)
      }
      val before = running.tokensBefore
      val chunks = result?.chunks ?: 0
      if (before != null && after != null) (after - before - chunks).takeIf { it >= 0 } else null
    }

  /** Sets when the model unloads after this request (inside [withTurn]). */
  suspend fun keepAlive(keepAliveMs: Long?) {
    val l = loaded ?: return
    val keep = keepAliveMs ?: DEFAULT_KEEP_ALIVE_MS
    when {
      keep == 0L -> unloadLocked("keep_alive 0")
      keep < 0 -> l.expiresAtMs = Long.MAX_VALUE
      else -> {
        l.expiresAtMs = System.currentTimeMillis() + keep
        unloadJob =
          scope.launch {
            delay(keep)
            turnLock.withLock {
              if (loaded === l && System.currentTimeMillis() >= l.expiresAtMs) {
                unloadLocked("keep_alive of ${keep / 1000} s ended")
              }
            }
          }
      }
    }
    onStateChanged()
  }

  /** Unloads the model at [path] if it is the loaded one (inside [withTurn]). */
  suspend fun unloadIf(path: String, why: String): Boolean {
    if (loaded?.entry?.path != path) return false
    unloadLocked(why)
    return true
  }

  /** Stops the answer that runs and unloads the model; for the server's stop. */
  suspend fun shutdown() {
    active?.let {
      try {
        it.cancelProcess()
      } catch (e: Exception) {
        Log.w(TAG, "Could not stop the answer: ${e.message}")
      }
    }
    unloadJob?.cancel()
    val done = withTimeoutOrNull(30_000L) { turnLock.withLock { unloadLocked("the local API stopped") } }
    if (done == null) Log.w(TAG, "An answer did not end within 30 s of the stop")
  }

  private suspend fun ensureLoaded(entry: ApiModelEntry, numCtx: Int?): Loaded {
    val wanted = numCtx ?: entry.maxNumTokens
    val tokens = (entry.contextLength?.let { minOf(wanted, it) } ?: wanted).coerceAtLeast(1)
    val current = loaded
    if (current != null && current.entry.path == entry.path && current.maxNumTokens == tokens) {
      return current
    }
    if (current != null) unloadLocked("${entry.name} was asked for")
    if (EngineOwnership.chatHolds(entry.path)) {
      throw ApiError(
        409,
        "This model is open in Model Shelf's chat. Close that chat, or ask for another model.",
        code = "model_in_use",
      )
    }
    val errors = mutableListOf<String>()
    for (accelerator in entry.accelerators.distinct()) {
      val t0 = SystemClock.elapsedRealtime()
      try {
        val engine = newEngine(entry.path, accelerator, tokens)
        val l = Loaded(entry = entry, engine = engine, backend = accelerator, maxNumTokens = tokens)
        loaded = l
        EngineOwnership.apiLoaded(entry.path)
        Log.i(
          TAG,
          "Loaded ${entry.name} on ${accelerator.uppercase()} with max tokens $tokens in " +
            "${SystemClock.elapsedRealtime() - t0} ms",
        )
        onStateChanged()
        return l
      } catch (e: Exception) {
        Log.w(TAG, "Could not load ${entry.name} on ${accelerator.uppercase()}: ${e.message}")
        errors.add("${accelerator.uppercase()}: ${e.message}")
      }
    }
    throw ApiError(
      500,
      "Model Shelf could not load ${entry.name} (${errors.joinToString("; ").take(400)}).",
      code = "load_failed",
    )
  }

  private suspend fun newEngine(path: String, accelerator: String, maxNumTokens: Int): Engine =
    withContext(engineDispatcher) {
      val backend =
        when (accelerator) {
          "cpu" -> Backend.CPU()
          "gpu" -> Backend.GPU()
          "npu",
          "tpu" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
          else -> throw IllegalArgumentException("unknown accelerator $accelerator")
        }
      val engine = Engine(EngineConfig(modelPath = path, backend = backend, maxNumTokens = maxNumTokens))
      try {
        engine.initialize()
      } catch (e: Exception) {
        try {
          engine.close()
        } catch (_: Exception) {}
        throw e
      }
      engine
    }

  private suspend fun restartEngine(l: Loaded) {
    val t0 = SystemClock.elapsedRealtime()
    withContext(engineDispatcher) {
      try {
        l.engine.close()
      } catch (e: Exception) {
        Log.w(TAG, "Closing the used engine failed: ${e.message}")
      }
    }
    try {
      l.engine = newEngine(l.entry.path, l.backend, l.maxNumTokens)
    } catch (e: Exception) {
      loaded = null
      EngineOwnership.apiLoaded(null)
      onStateChanged()
      throw ApiError(
        500,
        "Model Shelf could not make a new engine for ${l.entry.name}: ${e.message}",
        code = "load_failed",
      )
    }
    l.engineUsed = false
    Log.i(
      TAG,
      "New engine for ${l.entry.name} (${l.entry.statefulWhy}) in " +
        "${SystemClock.elapsedRealtime() - t0} ms",
    )
  }

  private suspend fun closeConversation(l: Loaded) {
    val conversation = l.conversation ?: return
    l.conversation = null
    withContext(engineDispatcher + NonCancellable) {
      try {
        conversation.close()
      } catch (e: Exception) {
        Log.w(TAG, "Closing a conversation failed: ${e.message}")
      }
    }
  }

  private suspend fun unloadLocked(why: String) {
    val l = loaded ?: return
    loaded = null
    closeConversation(l)
    withContext(engineDispatcher + NonCancellable) {
      try {
        l.engine.close()
      } catch (e: Exception) {
        Log.w(TAG, "Closing the engine failed: ${e.message}")
      }
    }
    EngineOwnership.apiLoaded(null)
    Log.i(TAG, "Unloaded ${l.entry.name} ($why)")
    onStateChanged()
  }

  private suspend fun stop(running: Running) {
    withContext(NonCancellable) {
      try {
        running.prepared.conversation.cancelProcess()
      } catch (e: Exception) {
        Log.w(TAG, "Could not stop the answer: ${e.message}")
      }
      withTimeoutOrNull(STOP_WAIT_MS) {
        while (true) {
          val event = running.events.receiveCatching().getOrNull() ?: break
          if (event !is Event.Text) break
        }
      }
    }
  }

  private fun tokenCount(conversation: Conversation): Int? =
    try {
      conversation.getTokenCount()
    } catch (e: Exception) {
      null
    }
}
