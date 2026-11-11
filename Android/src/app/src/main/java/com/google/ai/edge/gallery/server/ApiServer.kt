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
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.speech.WavAudio
import com.google.ai.edge.gallery.speech.WavFormatException
import com.google.ai.edge.gallery.speech.WavReader
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MSApiServer"

/** SSE comment lines while an OpenAI stream waits for text. */
private const val SSE_KEEP_ALIVE_MS = 10_000L

/** Debug builds only: keep a stateful model's used engine, to show what the restart prevents. */
private const val DEBUG_REUSE_HEADER = "X-ModelShelf-Debug-Reuse-Engine"

/**
 * Debug builds only: a speech request's time limit and audio limit in seconds, lower than the
 * app's (300 s and 600 s cannot be reached with 4,096 characters), to test what happens at them.
 */
private const val DEBUG_TIME_LIMIT_HEADER = "X-ModelShelf-Debug-Time-Limit-S"
private const val DEBUG_AUDIO_LIMIT_HEADER = "X-ModelShelf-Debug-Audio-Limit-S"

/**
 * The HTTP server of the local API: Ollama's /api/ and OpenAI's /v1/ on 127.0.0.1:11434, every
 * call with an app's token. One line per request goes to logcat
 * (tag MSApiServer): who asked, which model, the sampler and where each value came from, what
 * happened to the engine, and the times.
 */
class ApiServer(
  private val context: Context,
  private val tokens: ApiTokenStore,
  onStateChanged: () -> Unit,
) {
  val catalog = ApiModelCatalog(context)
  val host = ApiEngineHost(context, onStateChanged)
  val asr = ApiAsrHost(context, host, onStateChanged)
  val tts = ApiTtsHost(context, host, onStateChanged)
  private val requestIds = AtomicLong(0)
  private val bodies = BodyReader()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var memoryWatch: Job? = null
  @Volatile
  private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? =
    null
  /** Set when the stop begins: requests that arrive get a 503. */
  @Volatile private var stopping = false

  val requestCount: Long
    get() = requestIds.get()

  private enum class Kind {
    CHAT,
    SPEECH_RECOGNITION,
    VOICE,
  }

  init {
    host.beforeLoad = { name, needKb -> makeRoom(Kind.CHAT, name, needKb) }
    asr.beforeLoad = { name, needKb -> makeRoom(Kind.SPEECH_RECOGNITION, name, needKb) }
    tts.beforeLoad = { name, needKb -> makeRoom(Kind.VOICE, name, needKb) }
  }

  /**
   * Before the API loads a model (inside that request's turn): when MemAvailable minus what the
   * model needs would fall under the floor ([MemoryRule]), a voice model first unloads the API's
   * chat and speech recognition models, and a chat or speech recognition model first unloads the
   * API's voice model.
   */
  private suspend fun makeRoom(kind: Kind, name: String, needKb: Long) {
    val m = MemoryRule.read() ?: return
    val floor = MemoryRule.floorKb(m.totalKb)
    if (!MemoryRule.mustMakeRoom(m, needKb)) {
      log("Memory: $name needs about $needKb kB; MemAvailable ${m.availableKb} kB leaves the floor of $floor kB: nothing unloaded")
      return
    }
    val why = "MemAvailable ${m.availableKb} kB minus $needKb kB for $name is under the floor of $floor kB"
    val unloaded =
      when (kind) {
        Kind.VOICE -> {
          val chat = host.unloadForMemory(why)
          val speech = asr.unloadForMemory(why)
          chat || speech
        }
        Kind.CHAT,
        Kind.SPEECH_RECOGNITION -> tts.unloadForMemory(why)
      }
    if (!unloaded) {
      log("Memory: $why: nothing to unload")
      return
    }
    log("Memory: $why: unloaded the API's other models first")
    MemoryRule.read()?.let { log("Memory: MemAvailable ${it.availableKb} kB after the unload") }
  }

  /**
   * A memory signal (onTrimMemory, onLowMemory, or MemAvailable under the floor while the API holds
   * a model): once no request runs, unloads the API's idle models, the voice model first.
   */
  fun onMemorySignal(why: String) {
    if (stopping) return
    scope.launch {
      host.underTurnLock {
        if (stopping) return@underTurnLock
        val unloaded =
          listOf(
            "voice" to tts.unloadForMemory(why),
            "speech recognition" to asr.unloadForMemory(why),
            "chat" to host.unloadForMemory(why),
          ).filter { it.second }.map { it.first }
        log("Memory signal ($why): unloaded ${if (unloaded.isEmpty()) "nothing (no model loaded)" else unloaded.joinToString()}")
      }
    }
  }

  /** While the API holds a model, MemAvailable is read every 30 s; under the floor is a memory signal. */
  private fun watchMemory() {
    memoryWatch?.cancel()
    memoryWatch =
      scope.launch {
        while (true) {
          delay(MEMORY_WATCH_MS)
          if (host.loadedInfo() == null && asr.loadedInfo() == null && !tts.holdsModel()) continue
          val m = MemoryRule.read() ?: continue
          val floor = MemoryRule.floorKb(m.totalKb)
          if (m.availableKb < floor) onMemorySignal("MemAvailable ${m.availableKb} kB under the floor of $floor kB")
        }
      }
  }

  /** Binds 127.0.0.1:11434; throws when the port is taken. */
  fun start() {
    val s =
      embeddedServer(
        CIO,
        applicationEnvironment {},
        configure = {
          connector {
            host = LocalApi.HOST
            port = LocalApi.PORT
          }
          connectionIdleTimeoutSeconds = 600
        },
        module = { routes() },
      )
    s.start(wait = false)
    server = s
    watchMemory()
    Log.i(TAG, "Listening on ${LocalApi.BASE_URL} (loopback only)")
  }

  /**
   * Stops, in this order: requests that arrive get a 503 and the answer that runs is stopped; the
   * port closes (requests still open are cancelled); then, once the turn that runs has ended, the
   * model is unloaded and the engine's thread ends. Not cancellable.
   */
  suspend fun stop() =
    withContext(NonCancellable) {
      stopping = true
      memoryWatch?.cancel()
      host.beginShutdown()
      asr.beginShutdown()
      tts.beginShutdown()
      val s = server
      server = null
      withContext(Dispatchers.IO) { s?.stop(500, 2_000) }
      host.shutdown()
      asr.shutdown()
      tts.shutdown()
      scope.cancel()
      Log.i(TAG, "Stopped")
    }

  private fun Application.routes() {
    routing {
      get("/") { call.respondText("Model Shelf local API is running", ContentType.Text.Plain) }

      // The check a calling app runs before it sends its token (ApiVerify): no token here.
      get(ApiVerify.PATH) { verify(call) }

      // Ollama.
      get("/api/version") {
        if (caller(call, openAi = false) == null) return@get
        call.respondJson(HttpStatusCode.OK, ApiJson.ollamaVersion(BuildConfig.VERSION_NAME))
      }
      get("/api/tags") {
        val grant = caller(call, openAi = false) ?: return@get
        val entries = catalog.entries(refresh = true)
        log("GET /api/tags from ${grant.packageName}: ${entries.size} models")
        call.respondJson(HttpStatusCode.OK, ApiJson.ollamaTags(entries))
      }
      post("/api/show") {
        val grant = caller(call, openAi = false) ?: return@post
        val body = readBody(call, openAi = false) ?: return@post
        try {
          val name = parseModelOnly(body)
          val entry = catalog.find(name) ?: throw notOnPhone(name, openAi = false)
          log("POST /api/show from ${grant.packageName}: ${entry.name}")
          call.respondJson(HttpStatusCode.OK, ApiJson.ollamaShow(entry))
        } catch (e: ApiError) {
          respondError(call, e, openAi = false)
        }
      }
      get("/api/ps") {
        if (caller(call, openAi = false) == null) return@get
        call.respondJson(
          HttpStatusCode.OK,
          ApiJson.ollamaPs(listOfNotNull(host.loadedInfo()), listOfNotNull(asr.loadedInfo(), tts.loadedInfo())),
        )
      }
      post("/api/chat") { chat(call, Dialect.OLLAMA_CHAT) }
      post("/api/generate") { chat(call, Dialect.OLLAMA_GENERATE) }
      for (path in listOf("/api/pull", "/api/push", "/api/create", "/api/copy")) {
        post(path) { notHere(call, 501, "Download models in the Model Shelf app.", openAi = false) }
      }
      delete("/api/delete") {
        notHere(call, 501, "Delete models in the Model Shelf app.", openAi = false)
      }
      for (path in listOf("/api/embed", "/api/embeddings")) {
        post(path) { notHere(call, 400, "Embeddings are not supported by this API yet.", false) }
      }

      // OpenAI.
      get("/v1/models") {
        val grant = caller(call, openAi = true) ?: return@get
        val entries = catalog.entries(refresh = true)
        val speech = catalog.asrEntries()
        val voices = catalog.ttsEntries()
        log(
          "GET /v1/models from ${grant.packageName}: ${entries.size} chat, ${speech.size} speech " +
            "recognition and ${voices.size} text-to-speech models"
        )
        call.respondJson(HttpStatusCode.OK, ApiJson.openAiModels(entries, speech, voices))
      }
      post("/v1/chat/completions") { chat(call, Dialect.OPENAI_CHAT) }
      post("/v1/completions") {
        notHere(call, 400, "Use /v1/chat/completions; /v1/completions is not supported.", true)
      }
      post("/v1/responses") {
        notHere(call, 400, "/v1/responses is not supported by this API yet.", openAi = true)
      }
      post("/v1/embeddings") {
        notHere(call, 400, "Embeddings are not supported by this API yet.", openAi = true)
      }
      post("/v1/audio/transcriptions") { transcribe(call) }
      post("/v1/audio/speech") { speak(call) }
    }
  }

  // ---- The check ---------------------------------------------------------------------------

  /**
   * GET /modelshelf/v1/verify?app=<package name>&nonce=<16 bytes, base64url>: the proof that this
   * server holds the app's secret ([ApiVerify]). No token, and the same form of answer for every
   * package name. The log names the connection's port: a request that follows on the same
   * connection logs the same port.
   */
  private suspend fun verify(call: ApplicationCall) {
    val port = call.request.local.remotePort
    if (stopping) {
      respondError(call, ApiError(503, STOPPING_MESSAGE, "stopping"), openAi = true)
      return
    }
    val app = call.request.queryParameters["app"]
    val nonce = ApiVerify.nonce(call.request.queryParameters["nonce"])
    if (app == null || !ApiVerify.validApp(app) || nonce == null) {
      log("GET ${ApiVerify.PATH} (port $port): 400")
      respondError(
        call,
        ApiError(400, "Send ?app=<your package name>&nonce=<16 random bytes as base64url>."),
        openAi = true,
      )
      return
    }
    val key = tokens.verifyKey(app)
    log("GET ${ApiVerify.PATH} from $app (port $port): ${if (key != null) "a connected app" else "not a connected app"}")
    call.respondText(
      ApiVerify.answer(app, ApiVerify.proof(key ?: tokens.unknownKey(), app, nonce)),
      ContentType.Application.Json,
      HttpStatusCode.OK,
    )
  }

  // ---- Chat and generate --------------------------------------------------------------------

  private suspend fun chat(call: ApplicationCall, dialect: Dialect) {
    val openAi = dialect == Dialect.OPENAI_CHAT
    val arrived = SystemClock.elapsedRealtime()
    val grant = caller(call, openAi) ?: return
    val body = readBody(call, openAi) ?: return
    val id = requestIds.incrementAndGet()
    val request =
      try {
        when (dialect) {
          Dialect.OLLAMA_CHAT -> parseOllamaChat(body)
          Dialect.OLLAMA_GENERATE -> parseOllamaGenerate(body)
          Dialect.OPENAI_CHAT -> parseOpenAiChat(body)
        }
      } catch (e: ApiError) {
        log("#$id ${dialect.path} from ${from(call, grant)}: ${e.status} ${logText(e.message)}")
        respondError(call, e, openAi)
        return
      }
    log(
      "#$id ${dialect.path} from ${from(call, grant)}: model ${logName(request.model)}, " +
        "${request.turns.size} messages, stream ${request.stream}" + notApplied(request.ignored)
    )
    val entry = catalog.find(request.model)
    if (entry == null) {
      val e = noChatModel(request.model, openAi)
      log("#$id ${e.status} ${logText(e.message)}")
      respondError(call, e, openAi)
      return
    }
    if (request.turns.isEmpty()) {
      loadOrUnload(call, id, entry, request, arrived)
      return
    }
    if (request.turns.last().role != TurnRole.USER) {
      respondError(call, ApiError(400, "The last message must come from the user."), openAi)
      return
    }
    val reuseForTest = BuildConfig.DEBUG && call.request.header(DEBUG_REUSE_HEADER) == "1"
    try {
      host.withModelTurn(entry, request.keepAliveMs) { queueMs ->
        val prepared = host.prepare(entry, request, reuseForTest)
        val maxOutput =
          resolveMaxOutputTokens(request.maxOutputTokens, entry.maxOutputTokensMin, prepared.loaded.maxNumTokens)
        val timeoutS = entry.requestTimeoutS ?: DEFAULT_REQUEST_TIMEOUT_S
        log(
          "#$id ${entry.name} on ${prepared.loaded.backend.uppercase()}: ${prepared.how}" +
            (if (prepared.loadMs > 0) " (${prepared.loadMs} ms)" else "") +
            "; waited $queueMs ms; sampler: ${prepared.sampler.describe()}; max output " +
            "$maxOutput tokens; time limit $timeoutS s (" +
            (if (entry.requestTimeoutS != null) "the model card" else APP_DEFAULT_SOURCE) +
            ")"
        )
        call.response.header("X-ModelShelf-Engine-Restart", if (prepared.engineRestart) "1" else "0")
        call.response.header("X-ModelShelf-Queue-Ms", queueMs)
        call.response.header("X-ModelShelf-Load-Ms", prepared.loadMs)
        call.response.header("X-ModelShelf-Sampler", prepared.sampler.header())
        call.response.header("X-ModelShelf-Backend", prepared.loaded.backend)
        if (request.ignored.isNotEmpty()) {
          call.response.header("X-ModelShelf-Not-Applied", headerSafe(request.ignored))
        }
        val running = host.start(prepared, maxOutput, timeoutS * 1000L)
        val r: AnswerResult
        try {
          r = answer(call, request, entry, running, timeoutS * 1000L, arrived, queueMs, prepared)
        } catch (t: Throwable) {
          // The caller went away or the call was cancelled: finish() stops the generation if it
          // still runs, then drops the conversation.
          host.finish(running, null, request.turns)
          throw t
        }
        val promptTokens = host.finish(running, r, request.turns)
        log(
          "#$id ended ${r.end.name.lowercase()}: first text ${r.firstTextMs} ms, answer " +
            "${r.inferenceMs} ms, total ${SystemClock.elapsedRealtime() - arrived} ms, " +
            "${r.chunks} text pieces, prompt tokens ${promptTokens ?: "?"}" +
            (r.error?.let { "; $it" } ?: "")
        )
      }
    } catch (e: ApiEngineHost.Busy) {
      busy(call, id, e, openAi)
    } catch (e: ApiError) {
      log("#$id ${e.status} ${logText(e.message)}")
      respondError(call, e, openAi)
    }
  }

  /**
   * Streams or returns the answer. Returns how it ended; never throws for an engine failure or a
   * time-out (those are answered in the dialect's error form).
   */
  private suspend fun answer(
    call: ApplicationCall,
    request: ApiRequest,
    entry: ApiModelEntry,
    running: ApiEngineHost.Running,
    timeoutMs: Long,
    arrived: Long,
    queueMs: Long,
    prepared: ApiEngineHost.Prepared,
  ): AnswerResult {
    val model = entry.name
    val generate = request.dialect == Dialect.OLLAMA_GENERATE
    fun stats(r: AnswerResult, promptTokens: Int?) =
      TurnStats(
        loadMs = prepared.loadMs,
        queueMs = queueMs,
        firstTextMs = r.firstTextMs,
        inferenceMs = r.inferenceMs,
        totalMs = SystemClock.elapsedRealtime() - arrived,
        evalChunks = r.chunks,
        promptTokens = promptTokens,
      )
    fun finishReason(r: AnswerResult, maxOut: Int) = if (r.chunks >= maxOut) "length" else "stop"
    val maxOut =
      resolveMaxOutputTokens(request.maxOutputTokens, entry.maxOutputTokensMin, prepared.loaded.maxNumTokens)

    if (!request.stream) {
      val r = host.pump(running, timeoutMs, null, onText = {}, onIdle = null)
      when (r.end) {
        AnswerEnd.DONE -> {
          val body =
            if (request.dialect == Dialect.OPENAI_CHAT) {
              ApiJson.openAiCompletion(
                "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""),
                model,
                r.text,
                finishReason(r, maxOut),
                stats(r, null),
              )
            } else {
              ApiJson.ollamaDone(model, generate, r.text, finishReason(r, maxOut), stats(r, null))
            }
          call.respondJson(HttpStatusCode.OK, body)
        }
        AnswerEnd.TIMEOUT ->
          respondError(call, ApiError(504, r.error ?: "Time limit reached."), request.dialect == Dialect.OPENAI_CHAT)
        else ->
          respondError(
            call,
            ApiError(500, "The model stopped with an error: ${r.error}"),
            request.dialect == Dialect.OPENAI_CHAT,
          )
      }
      return r
    }

    if (request.dialect == Dialect.OPENAI_CHAT) {
      val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
      val created = System.currentTimeMillis() / 1000
      var result: AnswerResult? = null
      call.respondBytesWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
        val r =
          host.pump(
            running,
            timeoutMs,
            SSE_KEEP_ALIVE_MS,
            // The first write meets a client that left while it waited: inside pump, so the
            // generation is stopped.
            onStart = { sse(ApiJson.openAiChunk(id, model, created, ApiJson.roleDelta(), null)) },
            onText = { sse(ApiJson.openAiChunk(id, model, created, ApiJson.textDelta(it), null)) },
            onIdle = {
              writeStringUtf8(": keep-alive\n\n")
              flush()
            },
          )
        result = r
        if (r.end == AnswerEnd.DONE) {
          sse(ApiJson.openAiChunk(id, model, created, com.google.gson.JsonObject(), finishReason(r, maxOut)))
          if (request.includeUsage) sse(ApiJson.openAiUsageChunk(id, model, created, stats(r, null)))
          writeStringUtf8("data: [DONE]\n\n")
          flush()
        } else if (r.end != AnswerEnd.CLIENT_GONE) {
          sse(ApiJson.openAiError(r.error ?: "The answer stopped.", if (r.end == AnswerEnd.TIMEOUT) 504 else 500, null))
        }
      }
      return result ?: AnswerResult("", -1, 0, 0, AnswerEnd.CLIENT_GONE, "no stream")
    }

    var result: AnswerResult? = null
    call.respondBytesWriter(contentType = NDJSON, status = HttpStatusCode.OK) {
      val r =
        host.pump(
          running,
          timeoutMs,
          null,
          onText = {
            writeStringUtf8(ApiJson.ollamaChunk(model, it, generate) + "\n")
            flush()
          },
          onIdle = null,
        )
      result = r
      if (r.end == AnswerEnd.DONE) {
        writeStringUtf8(ApiJson.ollamaDone(model, generate, "", finishReason(r, maxOut), stats(r, null)) + "\n")
        flush()
      } else if (r.end != AnswerEnd.CLIENT_GONE) {
        writeStringUtf8(ApiJson.ollamaError(r.error ?: "The answer stopped.") + "\n")
        flush()
      }
    }
    return result ?: AnswerResult("", -1, 0, 0, AnswerEnd.CLIENT_GONE, "no stream")
  }

  // ---- Speech recognition --------------------------------------------------------------------

  /** A transcription's audio, read and decoded; the request is kept without the body's bytes. */
  private class ReceivedAudio(val request: TranscriptionRequest, val wav: WavAudio)

  /**
   * OpenAI's POST /v1/audio/transcriptions: a WAV file in, its text out (or streamed as SSE). The
   * body is read inside the request's turn, so a request that waits holds none of its audio (only
   * the connection's buffer); a body that declares more than [MAX_AUDIO_BODY_BYTES] is refused
   * before it queues, with no byte of it read.
   */
  private suspend fun transcribe(call: ApplicationCall) {
    val arrived = SystemClock.elapsedRealtime()
    val grant = caller(call, openAi = true) ?: return
    val id = requestIds.incrementAndGet()
    val from = from(call, grant)
    val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > MAX_AUDIO_BODY_BYTES) {
      log("#$id /v1/audio/transcriptions from $from: 413, Content-Length $declared B, read 0 B")
      respondError(call, BodyTooLarge(MAX_AUDIO_BODY_BYTES, 0), openAi = true)
      return
    }
    try {
      asr.withModelTurn { queueMs ->
        val received = receiveAudio(call, id, from, declared, queueMs)
        val request = received.request
        val wav = received.wav
        val entry = asrModel(request.model)
        checkTranscriptionLanguage(request.language, entry)
        val notApplied = transcriptionNotApplied(request, entry)
        val audioMs = (wav.seconds * 1000).toLong()
        val loaded = asr.load(entry)
        call.response.header("X-ModelShelf-Backend", loaded.backend)
        call.response.header("X-ModelShelf-Backend-Detail", loaded.backendNote)
        call.response.header("X-ModelShelf-Load-Ms", loaded.loadMs)
        call.response.header("X-ModelShelf-Queue-Ms", queueMs)
        call.response.header("X-ModelShelf-Audio-Ms", audioMs)
        call.response.header("X-ModelShelf-Audio-Source", wav.describeSource())
        if (notApplied.isNotEmpty()) {
          call.response.header("X-ModelShelf-Not-Applied", headerSafe(notApplied))
        }
        val limitMs = TRANSCRIPTION_LIMIT_BASE_MS + audioMs
        log(
          "#$id ${entry.name} on ${loaded.backend.uppercase()} (${loaded.backendNote}): " +
            "${"%.2f".format(audioMs / 1000.0)} s of audio (${wav.describeSource()}), load " +
            "${loaded.loadMs} ms, waited $queueMs ms, time limit ${limitMs / 1000} s"
        )
        val result =
          host.keepingAwake(limitMs + 60_000L) {
            if (request.stream) {
              streamTranscript(call, entry, wav.samples, limitMs)
            } else {
              wholeTranscript(call, request, entry, wav.samples, audioMs, limitMs)
            }
          }
        if (result != null) {
          log(
            "#$id ended: inference ${result.inferenceMs} ms for ${"%.2f".format(audioMs / 1000.0)} s " +
              "(${if (result.inferenceMs > 0) "%.1f".format(audioMs.toDouble() / result.inferenceMs) else "-"} " +
              "times real time), ${result.pieces.size} pieces, ${result.text.length} characters, total " +
              "${SystemClock.elapsedRealtime() - arrived} ms"
          )
        }
      }
    } catch (e: ApiEngineHost.Busy) {
      busy(call, id, e)
    } catch (e: BodyTooLarge) {
      log("#$id 413, read ${e.readBytes} B of the body")
      respondError(call, e, openAi = true)
    } catch (e: ApiError) {
      log("#$id ${e.status} ${logText(e.message)}")
      respondError(call, e, openAi = true)
    }
  }

  /**
   * Reads, parses and decodes a transcription's body, inside its turn: the body's bytes are
   * reachable only in here, so once this returns only the 16 kHz samples are kept. The body has
   * [AUDIO_UPLOAD_LIMIT_MS] to arrive (then 408).
   */
  private suspend fun receiveAudio(
    call: ApplicationCall,
    id: Long,
    from: String,
    declared: Long?,
    queueMs: Long,
  ): ReceivedAudio {
    val t0 = SystemClock.elapsedRealtime()
    val body =
      try {
        withTimeoutOrNull(AUDIO_UPLOAD_LIMIT_MS) {
          bodies.readWhole(call.receiveChannel(), declared, MAX_AUDIO_BODY_BYTES)
        } ?: throw ApiError(408, "The audio did not arrive within ${AUDIO_UPLOAD_LIMIT_MS / 1000} s.", "timeout")
      } catch (e: CancellationException) {
        throw e
      } catch (e: ApiError) {
        throw e
      } catch (e: Exception) {
        throw ApiError(400, "Could not read the request body.")
      }
    val readMs = SystemClock.elapsedRealtime() - t0
    val request =
      parseTranscriptionForm(MultipartForm.parse(call.request.header(HttpHeaders.ContentType), body.array, body.size))
    log(
      "#$id /v1/audio/transcriptions from $from: model ${logName(request.model)}, file " +
        "${request.audio.size} B (body ${body.size} B read in $readMs ms after a wait of $queueMs ms), " +
        "response_format ${request.format.wire}, stream ${request.stream}" +
        (request.language?.let { ", language ${logName(it)}" } ?: "") +
        notApplied(request.ignored)
    )
    val job = currentCoroutineContext()[Job]
    val wav =
      try {
        withContext(Dispatchers.Default) {
          WavReader.read(request.audio.data, MAX_AUDIO_SECONDS, request.audio.offset, request.audio.size) {
            job?.ensureActive()
          }
        }
      } catch (e: WavFormatException) {
        throw ApiError(400, e.message ?: "The file is not a WAV file Model Shelf can read.")
      }
    return ReceivedAudio(request.withoutAudio(), wav)
  }

  /** The speech recognition model a transcription names; 400 for a chat or voice model, else 404. */
  private suspend fun asrModel(name: String): AsrEntry =
    catalog.findAsr(name)
      ?: throw when {
        catalog.findTts(name) != null ->
          ApiError(
            400,
            "\"$name\" is a text-to-speech model, not a speech recognition model (GET /v1/models lists them).",
          )
        catalog.find(name) != null ->
          ApiError(400, "\"$name\" is a chat model. Name a speech model; GET /v1/models lists them.")
        else ->
          ApiError(
            404,
            "Speech model \"$name\" is not on this phone; GET /v1/models lists the models here.",
            "model_not_found",
          )
      }

  /** The whole transcript in one response; null after an error response. */
  private suspend fun wholeTranscript(
    call: ApplicationCall,
    request: TranscriptionRequest,
    entry: AsrEntry,
    audio: FloatArray,
    audioMs: Long,
    limitMs: Long,
  ): ApiAsrHost.Result? {
    val r =
      try {
        asr.transcribe(entry, audio, limitMs) {}
      } catch (e: CancellationException) {
        throw e
      } catch (e: ApiError) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "The transcription failed", e)
        throw ApiError(500, "The transcription failed: ${e.message ?: e.javaClass.simpleName}")
      }
    call.response.header("X-ModelShelf-Inference-Ms", r.inferenceMs)
    when (request.format) {
      TranscriptFormat.JSON -> call.respondJson(HttpStatusCode.OK, AsrJson.json(r.text))
      TranscriptFormat.TEXT -> call.respondText(r.text, ContentType.Text.Plain, HttpStatusCode.OK)
      TranscriptFormat.VERBOSE_JSON ->
        call.respondJson(
          HttpStatusCode.OK,
          AsrJson.verbose(r.text, transcriptLanguage(request, entry), audioMs / 1000.0, r.pieces),
        )
    }
    return r
  }

  /**
   * The transcript as OpenAI's SSE events: one transcript.text.delta per piece as it ends (a
   * Phonon window is 30 s of audio), then transcript.text.done. An error after the stream began
   * ends it with an error event.
   */
  private suspend fun streamTranscript(
    call: ApplicationCall,
    entry: AsrEntry,
    audio: FloatArray,
    limitMs: Long,
  ): ApiAsrHost.Result? {
    var result: ApiAsrHost.Result? = null
    call.respondBytesWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
      coroutineScope {
        val pieces = Channel<TranscriptPiece>(Channel.UNLIMITED)
        val work = async {
          try {
            runCatching { asr.transcribe(entry, audio, limitMs) { pieces.trySend(it) } }
          } finally {
            pieces.close()
          }
        }
        var first = true
        for (p in pieces) {
          val text = p.text.trim()
          if (text.isEmpty()) continue
          sse(AsrJson.delta(if (first) text else " $text"))
          first = false
        }
        work.await().fold(
          onSuccess = {
            result = it
            sse(AsrJson.done(it.text))
          },
          onFailure = {
            if (it is CancellationException) throw it
            if (it is ApiError) {
              sse(ApiJson.openAiError(it.message, it.status, it.code))
            } else {
              Log.e(TAG, "The transcription failed", it)
              sse(ApiJson.openAiError("The transcription failed: ${it.message ?: it.javaClass.simpleName}", 500, null))
            }
          },
        )
      }
    }
    return result
  }

  // ---- Text to speech ------------------------------------------------------------------------

  /**
   * One speech request across its sentences ([speechPieces]): each sentence is spoken in a turn of
   * its own, so the queue moves between sentences.
   */
  private inner class SpeechRun(
    val id: Long,
    val entry: TtsEntry,
    var voice: String,
    val pieces: List<String>,
    /** The synthesis time limit ([DEFAULT_REQUEST_TIMEOUT_S], lower only with a debug header). */
    val limitS: Int = DEFAULT_REQUEST_TIMEOUT_S,
    /** Where [limitS] comes from, for the log. */
    val limitSource: String = APP_DEFAULT_SOURCE,
    /** The most audio, in bytes ([MAX_SPEECH_AUDIO_BYTES], lower only with a debug header). */
    val audioLimitBytes: Int = MAX_SPEECH_AUDIO_BYTES,
  ) {
    private val limitMs = limitS * 1000L
    var queueMs = 0L
    var loadMs = 0L
    var synthMs = 0L
    var pcmBytes = 0
    /** Milliseconds of synthesis before the first audio; -1 while there is none. */
    var firstAudioMs = -1L
    var backend = ""
    var backendNote = ""

    /** Speaks sentence [i] (from 0, in order) in a turn of its own and returns its audio. */
    suspend fun speak(i: Int): ApiTtsHost.Piece =
      tts.withModelTurn(continuing = i > 0) { waited ->
        if (i == 0) queueMs = waited
        var loaded = tts.load(entry, voice)
        loadMs += loaded.loadMs
        if (i == 0) {
          // The model lists its own voices once loaded: speak with the voice as that list spells it.
          val spelled = resolveSpeechVoice(voice, entry, loaded.voices)
          if (spelled != voice) {
            voice = spelled
            loaded = tts.load(entry, voice)
            loadMs += loaded.loadMs
          }
          backend = loaded.backend
          backendNote = loaded.backendNote
          log(
            "#$id ${entry.name} on ${backend.uppercase()} ($backendNote): voice ${logName(voice)}, " +
              "${pieces.size} sentences, load $loadMs ms, waited $waited ms, time limit " +
              "$limitS s ($limitSource)" +
              (if (audioLimitBytes != MAX_SPEECH_AUDIO_BYTES) ", audio limit ${speechAudioMs(audioLimitBytes) / 1000} s (debug header)" else "")
          )
        }
        val left = limitMs - synthMs
        val p =
          host.keepingAwake(left.coerceAtLeast(0L) + 60_000L) {
            tts.speakPiece(
              entry,
              voice,
              pieces[i],
              left,
              limitS.toLong(),
              audioLimitBytes - pcmBytes,
              (speechAudioMs(audioLimitBytes) / 1000).toInt(),
            )
          }
        if (firstAudioMs < 0 && p.firstAudioMs >= 0) firstAudioMs = synthMs + p.firstAudioMs
        synthMs += p.synthMs
        pcmBytes += p.pcmBytes
        p
      }

    /** The response headers both formats send. */
    fun headers(call: ApplicationCall, request: SpeechRequest) {
      call.response.header("X-ModelShelf-Backend", backend)
      call.response.header("X-ModelShelf-Backend-Detail", backendNote)
      call.response.header("X-ModelShelf-Load-Ms", loadMs)
      call.response.header("X-ModelShelf-Queue-Ms", queueMs)
      call.response.header("X-ModelShelf-Voice", voice)
      call.response.header("X-ModelShelf-Sentences", pieces.size)
      call.response.header("X-ModelShelf-First-Audio-Ms", firstAudioMs)
      call.response.header("X-ModelShelf-Sample-Rate", SPEECH_OUTPUT_RATE_HZ)
      if (request.ignored.isNotEmpty()) {
        call.response.header("X-ModelShelf-Not-Applied", headerSafe(request.ignored))
      }
    }
  }

  /**
   * OpenAI's POST /v1/audio/speech: text in, 24 kHz mono 16-bit speech out, as a WAV file (the
   * default) or as headerless PCM. The text is spoken one sentence at a time ([SpeechRun]), and no
   * turn is held while audio is sent. WAV: the file goes out after the last sentence (its header
   * names the size), so a caller needs a read timeout as long as the synthesis (up to
   * [DEFAULT_REQUEST_TIMEOUT_S] s). PCM: each sentence goes out as soon as it is spoken (chunked).
   * Sending is cut as [WriteWatch] says. While the app reads aloud to its user, a request gets 503
   * with Retry-After.
   */
  private suspend fun speak(call: ApplicationCall) {
    val arrived = SystemClock.elapsedRealtime()
    val grant = caller(call, openAi = true) ?: return
    // A small body (the input is checked as soon as it is parsed): a waiting request holds little.
    val body = readBodyBytes(call, MAX_SPEECH_BODY_BYTES, openAi = true)?.toString(Charsets.UTF_8) ?: return
    val id = requestIds.incrementAndGet()
    val from = from(call, grant)
    try {
      val request = parseSpeechRequest(body)
      // The log carries the input's length, never the text, and names the caller sent only as
      // [logName] keeps them.
      log(
        "#$id /v1/audio/speech from $from: model ${logName(request.model)}, input " +
          "${request.inputChars} characters, voice ${request.voice?.let(::logName) ?: "(the model's default)"}, " +
          "response_format ${request.format.wire}" + notApplied(request.ignored)
      )
      val entry = ttsModel(request.model)
      val pieces = speechPieces(request.input)
      if (pieces.isEmpty()) {
        throw ApiError(400, "The input has nothing to speak once marks and symbols are left out; send words.")
      }
      // The user's read-aloud comes first: refused before the request queues.
      tts.checkReadAloudIdle()
      val debugLimitS =
        if (BuildConfig.DEBUG) call.request.header(DEBUG_TIME_LIMIT_HEADER)?.toIntOrNull()?.coerceIn(1, DEFAULT_REQUEST_TIMEOUT_S) else null
      val debugAudioS =
        if (BuildConfig.DEBUG) call.request.header(DEBUG_AUDIO_LIMIT_HEADER)?.toIntOrNull()?.coerceIn(1, MAX_SPEECH_AUDIO_S) else null
      val run =
        SpeechRun(
          id,
          entry,
          resolveSpeechVoice(request.voice, entry),
          pieces,
          limitS = debugLimitS ?: DEFAULT_REQUEST_TIMEOUT_S,
          limitSource = if (debugLimitS != null) "debug header" else APP_DEFAULT_SOURCE,
          audioLimitBytes = debugAudioS?.let { it * SPEECH_OUTPUT_RATE_HZ * 2 } ?: MAX_SPEECH_AUDIO_BYTES,
        )
      if (request.format == SpeechFormat.WAV) speakWav(call, request, run, arrived) else speakPcm(call, request, run, arrived)
    } catch (e: ApiEngineHost.Busy) {
      busy(call, id, e)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Once the audio is being sent no error can follow it ([respondWatched] logged why it stopped).
      if (call.response.isCommitted) return
      if (e is ApiError) {
        log("#$id ${e.status} ${logText(e.message)}")
        respondError(call, e, openAi = true)
      } else {
        Log.e(TAG, "#$id The synthesis failed", e)
        respondError(call, ApiError(500, "The synthesis failed: ${e.message ?: e.javaClass.simpleName}"), openAi = true)
      }
    }
  }

  /** WAV: every sentence first, then the file, with no turn held while it is sent. */
  private suspend fun speakWav(call: ApplicationCall, request: SpeechRequest, run: SpeechRun, arrived: Long) {
    val chunks = ArrayList<ByteArray>()
    for (i in run.pieces.indices) chunks.addAll(run.speak(i).chunks)
    if (run.pcmBytes == 0) throw ApiError(400, "${run.entry.name} made no audio for this input; send words.")
    val synthEnd = SystemClock.elapsedRealtime()
    val audioMs = speechAudioMs(run.pcmBytes)
    val header = WavWriter.header(run.pcmBytes)
    val length = header.size.toLong() + run.pcmBytes
    run.headers(call, request)
    call.response.header("X-ModelShelf-Audio-Ms", audioMs)
    call.response.header("X-ModelShelf-Inference-Ms", run.synthMs)
    val watch = WriteWatch(start = synthEnd, deadline = synthEnd + audioMs + SPEECH_WRITE_GRACE_MS)
    respondWatched(call, request.format, length, watch, run.id) {
      write(header)
      for (c in chunks) write(c)
    }
    if (watch.stopped) return
    log(
      "#${run.id} ended: ${"%.2f".format(audioMs / 1000.0)} s of audio in ${run.synthMs} ms " +
        "(${if (run.synthMs > 0) "%.1f".format(audioMs.toDouble() / run.synthMs) else "-"} times real time), " +
        "first audio ${run.firstAudioMs} ms, ${run.pieces.size} sentences, $length bytes wav, sent " +
        "${SystemClock.elapsedRealtime() - synthEnd} ms after the synthesis, total " +
        "${SystemClock.elapsedRealtime() - arrived} ms"
    )
  }

  /**
   * PCM: the first sentence before any byte (so its errors still get their status), then each
   * sentence as soon as it is spoken. A caller that left is seen within [WriteWatch.TICK_MS] of
   * the next write, and the sentence being spoken stops then.
   */
  private suspend fun speakPcm(call: ApplicationCall, request: SpeechRequest, run: SpeechRun, arrived: Long) {
    var piece: ApiTtsHost.Piece? = run.speak(0)
    if (run.pieces.size == 1 && run.pcmBytes == 0) {
      throw ApiError(400, "${run.entry.name} made no audio for this input; send words.")
    }
    run.headers(call, request)
    val watch = WriteWatch(start = SystemClock.elapsedRealtime(), deadline = Long.MAX_VALUE)
    var synthEnd = 0L
    respondWatched(call, request.format, null, watch, run.id) {
      for (i in run.pieces.indices) {
        val p = piece ?: run.speak(i)
        piece = null
        if (i == run.pieces.lastIndex) {
          synthEnd = SystemClock.elapsedRealtime()
          watch.deadline = synthEnd + speechAudioMs(run.pcmBytes) + SPEECH_WRITE_GRACE_MS
        }
        newStretch()
        for (c in p.chunks) write(c)
      }
    }
    if (watch.stopped) return
    val audioMs = speechAudioMs(run.pcmBytes)
    log(
      "#${run.id} ended: ${"%.2f".format(audioMs / 1000.0)} s of audio in ${run.synthMs} ms " +
        "(${if (run.synthMs > 0) "%.1f".format(audioMs.toDouble() / run.synthMs) else "-"} times real time), " +
        "first audio ${run.firstAudioMs} ms, ${run.pieces.size} sentences, ${run.pcmBytes} bytes pcm, sent " +
        "${SystemClock.elapsedRealtime() - synthEnd} ms after the synthesis, total " +
        "${SystemClock.elapsedRealtime() - arrived} ms"
    )
  }

  /** The text-to-speech model a speech request names; 400 for a chat or recognition model, else 404. */
  private suspend fun ttsModel(name: String): TtsEntry =
    catalog.findTts(name)
      ?: throw when {
        catalog.findAsr(name) != null ->
          ApiError(
            400,
            "\"$name\" is a speech recognition model, not a text-to-speech model (GET /v1/models lists them).",
          )
        catalog.find(name) != null ->
          ApiError(400, "\"$name\" is a chat model, not a text-to-speech model (GET /v1/models lists them).")
        else ->
          ApiError(
            404,
            "Text-to-speech model \"$name\" is not on this phone; GET /v1/models lists the models here.",
            "model_not_found",
          )
      }

  /**
   * Sends a speech answer under [watch]: [block] writes through [WatchedWriter]. When the watch cuts
   * it, or [block] fails (a later sentence of a PCM answer), the writing stops and the connection
   * closes without the answer's end, so the caller cannot take a cut answer for a whole one.
   */
  private suspend fun respondWatched(
    call: ApplicationCall,
    format: SpeechFormat,
    length: Long?,
    watch: WriteWatch,
    id: Long,
    block: suspend WatchedWriter.() -> Unit,
  ) {
    call.respondBytesWriter(ContentType.parse(format.contentType), HttpStatusCode.OK, length) {
      val channel = this
      var failure: Throwable? = null
      try {
        coroutineScope {
          val writer = launch(Dispatchers.Default) { WatchedWriter(channel, watch).block() }
          val guard = launch(Dispatchers.Default) { watch.guard(channel, writer) }
          writer.join()
          guard.cancel()
        }
      } catch (e: Throwable) {
        failure = e
      }
      val cut = watch.cutWhy
      val f = failure
      if (cut != null || f != null) watch.stopped = true
      when {
        cut != null -> {
          log("#$id cut the answer ${watch.now() - watch.start} ms after the sending began: $cut")
          throw IOException("The answer was cut: $cut")
        }
        f is ApiError -> {
          log("#$id the audio stopped while it was sent: ${f.status} ${logText(f.message)}")
          throw f
        }
        f != null -> {
          if (f !is CancellationException) log("#$id the audio stopped while it was sent: ${f.message ?: f.javaClass.simpleName}")
          throw f
        }
      }
    }
  }

  /** Ollama's /api/chat or /api/generate with no message: load, or unload with keep_alive 0. */
  private suspend fun loadOrUnload(
    call: ApplicationCall,
    id: Long,
    entry: ApiModelEntry,
    request: ApiRequest,
    arrived: Long,
  ) {
    val generate = request.dialect == Dialect.OLLAMA_GENERATE
    try {
      host.withModelTurn(entry, request.keepAliveMs) { queueMs ->
        val reason: String
        var loadMs = 0L
        if (request.keepAliveMs == 0L) {
          host.unloadIf(entry.path, "keep_alive 0 with no message")
          reason = "unload"
        } else {
          loadMs = host.loadOnly(entry, request.numCtx)
          reason = "load"
        }
        log("#$id ${entry.name}: $reason (${loadMs} ms)")
        val stats = TurnStats(loadMs, queueMs, -1, 0, SystemClock.elapsedRealtime() - arrived, 0, null)
        call.respondJson(HttpStatusCode.OK, ApiJson.ollamaDone(entry.name, generate, "", reason, stats))
      }
    } catch (e: ApiEngineHost.Busy) {
      busy(call, id, e, openAi = false)
    } catch (e: ApiError) {
      respondError(call, e, openAi = false)
    }
  }

  // ---- Shared -------------------------------------------------------------------------------

  /** The app a request comes from, checked before the body is read; null after a 401 or 503. */
  private suspend fun caller(call: ApplicationCall, openAi: Boolean): ApiTokenStore.Grant? {
    if (stopping) {
      respondError(call, ApiError(503, STOPPING_MESSAGE, "stopping"), openAi)
      return null
    }
    val auth = call.request.header(HttpHeaders.Authorization)
    val token =
      auth?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.ifEmpty { null }
    val grant = token?.let { tokens.find(it) }
    if (grant != null) return grant
    val message =
      if (token == null) {
        "This API needs a token. Connect your app to Model Shelf, or copy a token in Model " +
          "Shelf > Settings > Local API, and send it as \"Authorization: Bearer <token>\"."
      } else {
        "Model Shelf does not know this token (it may have been removed). Connect again."
      }
    log("${where(call)}: 401 (${if (token == null) "no token" else "unknown token"}, port ${call.request.local.remotePort})")
    respondError(call, ApiError(401, message, "invalid_api_key"), openAi)
    return null
  }

  /** A JSON body (UTF-8, RFC 8259) of at most [MAX_BODY_BYTES]; null after a 413 or 400. */
  private suspend fun readBody(call: ApplicationCall, openAi: Boolean): String? =
    readBodyBytes(call, MAX_BODY_BYTES, openAi)?.toString(Charsets.UTF_8)

  /** The body's bytes, counted while they are read; null after a 413 or 400. */
  private suspend fun readBodyBytes(call: ApplicationCall, limit: Long, openAi: Boolean): ByteArray? {
    val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    return try {
      bodies.read(call.receiveChannel(), declared, limit)
    } catch (e: ApiError) {
      log("${where(call)}: ${e.status} ${logText(e.message)}" + ((e as? BodyTooLarge)?.let { ", read ${it.readBytes} B" } ?: ""))
      respondError(call, e, openAi)
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      respondError(call, ApiError(400, "Could not read the request body."), openAi)
      null
    }
  }

  private suspend fun notHere(call: ApplicationCall, status: Int, message: String, openAi: Boolean) {
    if (caller(call, openAi) == null) return
    log("${where(call)}: $status $message")
    respondError(call, ApiError(status, message), openAi)
  }

  private fun notOnPhone(name: String, openAi: Boolean): ApiError =
    ApiError(
      404,
      "Model \"$name\" is not on this phone. Download it in Model Shelf; " +
        (if (openAi) "GET /v1/models" else "GET /api/tags") +
        " lists the models here.",
      "model_not_found",
    )

  /** The answer to a chat request that names no chat model of this phone. */
  private suspend fun noChatModel(name: String, openAi: Boolean): ApiError =
    when {
      catalog.findTts(name) != null ->
        ApiError(400, "\"$name\" is a text-to-speech model, not a chat model; POST /v1/audio/speech takes it.")
      catalog.findAsr(name) != null ->
        ApiError(
          400,
          "\"$name\" is a speech recognition model, not a chat model; POST /v1/audio/transcriptions takes it.",
        )
      else -> notOnPhone(name, openAi)
    }

  /** A full queue: 503 with Retry-After. */
  private suspend fun busy(call: ApplicationCall, id: Long, e: ApiEngineHost.Busy, openAi: Boolean = true) {
    log("#$id 503 busy, retry after ${e.retryAfterS} s")
    respondError(
      call,
      ApiError(503, "Model Shelf is answering other requests; try again in ${e.retryAfterS} s.", "busy", e.retryAfterS),
      openAi,
    )
  }

  /** Who sent a request, for the log: the app and the port of its connection (one TCP connection, one port). */
  private fun from(call: ApplicationCall, grant: ApiTokenStore.Grant): String =
    "${grant.packageName} (port ${call.request.local.remotePort})"

  /** The method and path of a request, for the log (not the query string, which a caller writes). */
  private fun where(call: ApplicationCall): String =
    "${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')}"

  /** ", not applied: <fields>" for the log (the field names as [headerSafe] keeps them), or "". */
  private fun notApplied(ignored: List<String>): String =
    if (ignored.isEmpty()) {
      ""
    } else {
      ", not applied: ${headerSafe(ignored.take(16))}" + (if (ignored.size > 16) " and ${ignored.size - 16} more" else "")
    }

  private suspend fun respondError(call: ApplicationCall, e: ApiError, openAi: Boolean) {
    e.retryAfterS?.let { call.response.header(HttpHeaders.RetryAfter, it) }
    val body = if (openAi) ApiJson.openAiError(e.message, e.status, e.code) else ApiJson.ollamaError(e.message)
    call.respondJson(HttpStatusCode.fromValue(e.status), body)
  }

  private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, json: String) {
    respondText(json, ContentType.Application.Json, status)
  }

  private suspend fun ByteWriteChannel.sse(json: String) {
    writeStringUtf8("data: $json\n\n")
    flush()
  }

  private fun log(line: String) {
    Log.i(TAG, line)
  }

  private companion object {
    val NDJSON: ContentType = ContentType("application", "x-ndjson")
    const val MEMORY_WATCH_MS = 30_000L
  }
}
