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
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "MSApiEngine"

/** Requests that may wait behind the one that runs (one answer at a time). */
private const val MAX_WAITING = 4

/** While the stop waits for the turn that runs, one log line this often. */
private const val STOP_LOG_EVERY_MS = 30_000L

/** The answer to requests that arrive while the local API stops. */
const val STOPPING_MESSAGE = "Model Shelf's local API is stopping."

/**
 * The local API's engine: one LLM loaded at a time, one answer at a time, up to [MAX_WAITING]
 * requests waiting in arrival order. Speech requests ([ApiAsrHost]) take their turns here too.
 *
 * State (LiteRT-LM #3165): the host keeps the conversation it holds and the messages it has seen.
 * A request that repeats them and adds one user message continues that conversation. Any other
 * request starts a new conversation; for a model that keeps state between conversations on one
 * engine (`stateful`), the engine is made again first, so no request sees another's state.
 *
 * Stop: [beginShutdown] (no new turn; the answer that runs is asked to stop), then the server closes
 * the port, then [shutdown] waits for the turn that runs, unloads the model and ends the host's
 * thread and jobs. An engine that was being made when its request was cancelled is finished and
 * closed, never dropped.
 */
class ApiEngineHost(private val context: Context, private val onStateChanged: () -> Unit) {
  private val engineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "ms-api-engine") }.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val turnLock = Mutex()
  private val inFlight = AtomicInteger(0)
  private var unloadJob: Job? = null
  @Volatile private var loaded: Loaded? = null
  @Volatile private var activeRun: AnswerRun? = null
  @Volatile private var averageTurnMs = 15_000L
  /** Set when the server stops: no turn starts, and an engine made meanwhile is closed. */
  @Volatile private var closing = false
  private val wakeLock: PowerManager.WakeLock =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ModelShelf:LocalApi")
      .apply { setReferenceCounted(false) }

  /** The loaded model. */
  class Loaded(
    val entry: ApiModelEntry,
    var engine: Engine,
    val backend: String,
    /** The engine's size in tokens (prompt and output together). */
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

  class Running(val prepared: Prepared, val run: AnswerRun, val tokensBefore: Int?)

  /** The loaded model, its expiry and backend, for /api/ps and the notification. */
  fun loadedInfo(): Triple<ApiModelEntry, Long, String>? =
    loaded?.let { Triple(it.entry, it.expiresAtMs, it.backend) }

  /**
   * Runs [block] when the requests before this one are done. Throws [Busy] when [MAX_WAITING]
   * requests already wait, and a 503 when the API is stopping. [block] gets the milliseconds this
   * request waited.
   */
  suspend fun <T> withTurn(block: suspend (queueMs: Long) -> T): T {
    if (closing) throw stoppingError()
    val n = inFlight.incrementAndGet()
    if (n > 1 + MAX_WAITING) {
      inFlight.decrementAndGet()
      throw Busy(((averageTurnMs * (n - 1)) / 1000).toInt().coerceIn(1, 600))
    }
    val arrived = SystemClock.elapsedRealtime()
    try {
      return turnLock.withLock {
        if (closing) throw stoppingError()
        block(SystemClock.elapsedRealtime() - arrived)
      }
    } finally {
      inFlight.decrementAndGet()
    }
  }

  /**
   * Runs [block] under the turn lock without a place in the queue, for housekeeping such as the
   * speech model's keep-alive.
   */
  suspend fun <T> underTurnLock(block: suspend () -> T): T = turnLock.withLock { block() }

  /** Holds the API's wake lock while [block] runs (a speech request, inside [withTurn]). */
  suspend fun <T> keepingAwake(timeoutMs: Long, block: suspend () -> T): T {
    wakeLock.acquire(timeoutMs)
    try {
      return block()
    } finally {
      if (wakeLock.isHeld) wakeLock.release()
    }
  }

  /** Loads [entry] (inside [withTurn]) without answering: Ollama's empty /api/chat. */
  suspend fun loadOnly(entry: ApiModelEntry, numCtx: Int?): Long {
    unloadJob?.cancel()
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
    unloadJob?.cancel()
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
    val config =
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
    val engine = l.engine
    val conversation =
      makeOrClose(engineDispatcher, make = { engine.createConversation(config) }, close = { it.close() })
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

  /** Starts the answer. Its events arrive in [Running.run]. */
  fun start(prepared: Prepared, maxOutputTokens: Int, timeoutMs: Long): Running {
    val events = Channel<AnswerEvent>(Channel.UNLIMITED)
    val conversation = prepared.conversation
    val before = tokenCount(conversation)
    prepared.loaded.engineUsed = true
    val run =
      AnswerRun(
        events = events,
        cancel = { conversation.cancelProcess() },
        startMs = SystemClock.elapsedRealtime(),
        clock = SystemClock::elapsedRealtime,
        log = { Log.w(TAG, it) },
      )
    activeRun = run
    wakeLock.acquire(timeoutMs + 60_000L)
    try {
      conversation.sendMessageAsync(
        Contents.of(prepared.text),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val text = message.toString()
            if (text.isNotEmpty()) events.trySend(AnswerEvent.Text(text))
          }

          override fun onDone() {
            events.trySend(AnswerEvent.Done)
            events.close()
          }

          override fun onError(throwable: Throwable) {
            events.trySend(AnswerEvent.Failed(throwable))
            events.close()
          }
        },
        mapOf("enable_thinking" to false),
        maxOutputToken = maxOutputTokens,
      )
    } catch (e: Exception) {
      events.trySend(AnswerEvent.Failed(e))
      events.close()
    }
    // The stop began while this answer was being set up: it did not see this run.
    if (closing) run.requestStop()
    return Running(prepared, run, before)
  }

  /** See [AnswerRun.pump]. */
  suspend fun pump(
    running: Running,
    timeoutMs: Long,
    idleEveryMs: Long?,
    onStart: (suspend () -> Unit)? = null,
    onText: suspend (String) -> Unit,
    onIdle: (suspend () -> Unit)?,
  ): AnswerResult = running.run.pump(timeoutMs, idleEveryMs, onStart, onText, onIdle)

  /**
   * Ends the turn. The generation is stopped first if nothing read it to its end (the caller went
   * away before or while it streamed). Keeps the conversation for the next request when the answer
   * ended normally, else drops it (its state after a stopped answer is unknown). Returns the
   * prompt's token count when the engine gives one.
   */
  suspend fun finish(running: Running, result: AnswerResult?, requestTurns: List<ChatTurn>): Int? =
    withContext(NonCancellable) {
      running.run.stop()
      if (activeRun === running.run) activeRun = null
      if (wakeLock.isHeld) wakeLock.release()
      val p = running.prepared
      val after = tokenCount(p.conversation)
      if (result != null && result.end == AnswerEnd.DONE) {
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

  /** The server's stop, first step (before the port closes): no new turn, the answer is stopped. */
  fun beginShutdown() {
    closing = true
    unloadJob?.cancel()
    activeRun?.requestStop()
  }

  /**
   * The server's stop, last step (after the port closed): waits for the turn that runs to end, then
   * unloads the model and ends the host's thread and jobs. Never gives up and is not cancellable: a
   * model must not stay loaded, nor its file stay marked as held, after the API is off.
   */
  suspend fun shutdown() =
    withContext(NonCancellable) {
      beginShutdown()
      val t0 = SystemClock.elapsedRealtime()
      val ticker =
        scope.launch {
          while (true) {
            delay(STOP_LOG_EVERY_MS)
            Log.w(
              TAG,
              "The stop still waits for the turn that runs " +
                "(${(SystemClock.elapsedRealtime() - t0) / 1000} s)",
            )
          }
        }
      try {
        turnLock.withLock { unloadLocked("the local API stopped") }
      } finally {
        ticker.cancel()
      }
      scope.cancel()
      engineDispatcher.close()
      Log.i(TAG, "The engine host ended ${SystemClock.elapsedRealtime() - t0} ms after the stop")
    }

  private suspend fun ensureLoaded(entry: ApiModelEntry, numCtx: Int?): Loaded {
    val tokens = resolveEngineTokens(numCtx, entry.maxNumTokens, entry.contextLength)
    val current = loaded
    if (
      current != null &&
        current.entry.path == entry.path &&
        engineServes(current.maxNumTokens, tokens)
    ) {
      return current
    }
    // Refuse before anything is unloaded when the chat holds the file.
    if (EngineOwnership.chatHolds(entry.path)) throw modelInUse()
    if (current != null) {
      unloadLocked(
        if (current.entry.path == entry.path) "num_ctx $tokens is more than its ${current.maxNumTokens}"
        else "${entry.name} was asked for"
      )
    }
    if (!EngineOwnership.reserveForApi(entry.path, this)) throw modelInUse()
    val errors = mutableListOf<String>()
    try {
      for (accelerator in entry.accelerators.distinct()) {
        val t0 = SystemClock.elapsedRealtime()
        val engine =
          try {
            newEngine(entry.path, accelerator, tokens)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Could not load ${entry.name} on ${accelerator.uppercase()}: ${e.message}")
            errors.add("${accelerator.uppercase()}: ${e.message}")
            continue
          }
        if (closing) {
          closeEngine(engine)
          Log.i(TAG, "Closed ${entry.name}: the local API stopped while it loaded")
          throw stoppingError()
        }
        val l = Loaded(entry = entry, engine = engine, backend = accelerator, maxNumTokens = tokens)
        loaded = l
        Log.i(
          TAG,
          "Loaded ${entry.name} on ${accelerator.uppercase()} with max tokens $tokens in " +
            "${SystemClock.elapsedRealtime() - t0} ms",
        )
        onStateChanged()
        return l
      }
    } catch (t: Throwable) {
      EngineOwnership.releaseForApi(entry.path, this)
      throw t
    }
    EngineOwnership.releaseForApi(entry.path, this)
    throw ApiError(
      500,
      "Model Shelf could not load ${entry.name} (${errors.joinToString("; ").take(400)}).",
      code = "load_failed",
    )
  }

  /** Makes and initializes an engine; it is closed, not dropped, if the request is cancelled. */
  private suspend fun newEngine(path: String, accelerator: String, maxNumTokens: Int): Engine {
    val backend =
      when (accelerator) {
        "cpu" -> Backend.CPU()
        "gpu" -> Backend.GPU()
        "npu",
        "tpu" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> throw IllegalArgumentException("unknown accelerator $accelerator")
      }
    val cacheDir = engineCacheDir(path)
    return makeOrClose(
      engineDispatcher,
      make = {
        val engine =
          Engine(
            EngineConfig(
              modelPath = path,
              backend = backend,
              maxNumTokens = maxNumTokens,
              cacheDir = cacheDir,
            )
          )
        try {
          engine.initialize()
        } catch (e: Exception) {
          try {
            engine.close()
          } catch (_: Exception) {}
          throw e
        }
        engine
      },
      close = {
        it.close()
        Log.i(TAG, "Closed the engine of $path: its request was cancelled while it loaded")
      },
    )
  }

  /**
   * Where the engine keeps its caches (the GPU's weight cache): next to the model, as the chat does,
   * when that folder is writable; else the app's own cache folder. A model folder the app cannot
   * write makes the GPU fail with "Permission denied" and the load fall back to the CPU.
   */
  private fun engineCacheDir(path: String): String? {
    val folder = File(path).parentFile
    val writable =
      try {
        folder != null && folder.canWrite()
      } catch (e: SecurityException) {
        false
      }
    if (writable) return null
    val dir = File(context.cacheDir, "litertlm")
    dir.mkdirs()
    Log.i(TAG, "The folder of $path is not writable; the engine's caches go to ${dir.absolutePath}")
    return dir.absolutePath
  }

  private suspend fun restartEngine(l: Loaded) {
    val t0 = SystemClock.elapsedRealtime()
    closeEngine(l.engine)
    try {
      l.engine = newEngine(l.entry.path, l.backend, l.maxNumTokens)
    } catch (e: Exception) {
      loaded = null
      EngineOwnership.releaseForApi(l.entry.path, this)
      onStateChanged()
      if (e is CancellationException) throw e
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

  private suspend fun closeEngine(engine: Engine) {
    withContext(engineDispatcher + NonCancellable) {
      try {
        engine.close()
      } catch (e: Exception) {
        Log.w(TAG, "Closing the engine failed: ${e.message}")
      }
    }
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
    closeEngine(l.engine)
    EngineOwnership.releaseForApi(l.entry.path, this)
    Log.i(TAG, "Unloaded ${l.entry.name} ($why)")
    onStateChanged()
  }

  private fun modelInUse() =
    ApiError(
      409,
      "This model is open in Model Shelf's chat. Close that chat, or ask for another model.",
      code = "model_in_use",
    )

  private fun stoppingError() = ApiError(503, STOPPING_MESSAGE, "stopping")

  private fun tokenCount(conversation: Conversation): Int? =
    try {
      conversation.getTokenCount()
    } catch (e: Exception) {
      null
    }
}
