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
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val TAG = "MSApiServer"

/** SSE comment lines while an OpenAI stream waits for text. */
private const val SSE_KEEP_ALIVE_MS = 10_000L

/** Debug builds only: keep a stateful model's used engine, to show what the restart prevents. */
private const val DEBUG_REUSE_HEADER = "X-ModelShelf-Debug-Reuse-Engine"

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
  @Volatile
  private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? =
    null
  /** Set when the stop begins: requests that arrive get a 503. */
  @Volatile private var stopping = false

  val requestCount: Long
    get() = requestIds.get()

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
      host.beginShutdown()
      asr.beginShutdown()
      tts.beginShutdown()
      val s = server
      server = null
      withContext(Dispatchers.IO) { s?.stop(500, 2_000) }
      host.shutdown()
      asr.shutdown()
      tts.shutdown()
      Log.i(TAG, "Stopped")
    }

  private fun Application.routes() {
    routing {
      get("/") { call.respondText("Model Shelf local API is running", ContentType.Text.Plain) }

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
        log("#$id ${dialect.path} from ${grant.packageName}: ${e.status} ${e.message}")
        respondError(call, e, openAi)
        return
      }
    log(
      "#$id ${dialect.path} from ${grant.packageName}: model ${request.model}, " +
        "${request.turns.size} messages, stream ${request.stream}" +
        (if (request.ignored.isEmpty()) "" else ", not applied: ${request.ignored.joinToString()}")
    )
    val entry = catalog.find(request.model)
    if (entry == null) {
      val e = notOnPhone(request.model, openAi)
      log("#$id ${e.status} ${e.message}")
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
      log("#$id 503 busy, retry after ${e.retryAfterS} s")
      call.response.header(HttpHeaders.RetryAfter, e.retryAfterS)
      respondError(
        call,
        ApiError(503, "Model Shelf is answering other requests. Try again in ${e.retryAfterS} s.", "busy"),
        openAi,
      )
    } catch (e: ApiError) {
      log("#$id ${e.status} ${e.message}")
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

  /** OpenAI's POST /v1/audio/transcriptions: a WAV file in, its text out (or streamed as SSE). */
  private suspend fun transcribe(call: ApplicationCall) {
    val arrived = SystemClock.elapsedRealtime()
    val grant = caller(call, openAi = true) ?: return
    val id = requestIds.incrementAndGet()
    val body = readBodyBytes(call, MAX_AUDIO_BODY_BYTES, openAi = true) ?: return
    try {
      val request =
        parseTranscriptionForm(MultipartForm.parse(call.request.header(HttpHeaders.ContentType), body))
      log(
        "#$id /v1/audio/transcriptions from ${grant.packageName}: model ${request.model}, file " +
          "${request.audio.filename ?: "(no name)"} ${request.audio.bytes.size} B, response_format " +
          "${request.format.wire}, stream ${request.stream}" +
          (request.language?.let { ", language $it" } ?: "") +
          (if (request.ignored.isEmpty()) "" else ", not applied: ${request.ignored.joinToString()}")
      )
      val entry =
        catalog.findAsr(request.model)
          ?: if (catalog.find(request.model) != null) {
            throw ApiError(
              400,
              "\"${request.model}\" is a chat model. Name a speech model; GET /v1/models lists them.",
            )
          } else {
            throw ApiError(
              404,
              "Speech model \"${request.model}\" is not on this phone; GET /v1/models lists the " +
                "models here.",
              "model_not_found",
            )
          }
      checkTranscriptionLanguage(request.language, entry)
      val notApplied = transcriptionNotApplied(request, entry)
      val wav =
        try {
          WavReader.read(request.audio.bytes, MAX_AUDIO_SECONDS)
        } catch (e: WavFormatException) {
          throw ApiError(400, e.message ?: "The file is not a WAV file Model Shelf can read.")
        }
      val audioMs = (wav.seconds * 1000).toLong()
      asr.withModelTurn { queueMs ->
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
        log(
          "#$id ${entry.name} on ${loaded.backend.uppercase()} (${loaded.backendNote}): " +
            "${"%.2f".format(audioMs / 1000.0)} s of audio (${wav.describeSource()}), load " +
            "${loaded.loadMs} ms, waited $queueMs ms"
        )
        val result =
          host.keepingAwake(audioMs + 120_000L) {
            if (request.stream) streamTranscript(call, entry, wav.samples) else wholeTranscript(call, request, entry, wav.samples, audioMs)
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
      log("#$id 503 busy, retry after ${e.retryAfterS} s")
      call.response.header(HttpHeaders.RetryAfter, e.retryAfterS)
      respondError(
        call,
        ApiError(503, "Model Shelf is answering other requests. Try again in ${e.retryAfterS} s.", "busy"),
        openAi = true,
      )
    } catch (e: ApiError) {
      log("#$id ${e.status} ${e.message}")
      respondError(call, e, openAi = true)
    }
  }

  /** The whole transcript in one response; null after an error response. */
  private suspend fun wholeTranscript(
    call: ApplicationCall,
    request: TranscriptionRequest,
    entry: AsrEntry,
    audio: FloatArray,
    audioMs: Long,
  ): ApiAsrHost.Result? {
    val r =
      try {
        asr.transcribe(entry, audio) {}
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
  ): ApiAsrHost.Result? {
    var result: ApiAsrHost.Result? = null
    call.respondBytesWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
      coroutineScope {
        val pieces = Channel<TranscriptPiece>(Channel.UNLIMITED)
        val work = async {
          try {
            runCatching { asr.transcribe(entry, audio) { pieces.trySend(it) } }
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
            Log.e(TAG, "The transcription failed", it)
            sse(ApiJson.openAiError("The transcription failed: ${it.message ?: it.javaClass.simpleName}", 500, null))
          },
        )
      }
    }
    return result
  }

  // ---- Text to speech ------------------------------------------------------------------------

  /**
   * OpenAI's POST /v1/audio/speech: text in, 24 kHz mono 16-bit speech out, as a WAV file (the
   * default) or as headerless PCM. The whole audio comes in one response.
   */
  private suspend fun speak(call: ApplicationCall) {
    val arrived = SystemClock.elapsedRealtime()
    val grant = caller(call, openAi = true) ?: return
    // A small body (the input is checked as soon as it is parsed): a waiting request holds little.
    val body = readBodyBytes(call, MAX_SPEECH_BODY_BYTES, openAi = true)?.toString(Charsets.UTF_8) ?: return
    val id = requestIds.incrementAndGet()
    try {
      val request = parseSpeechRequest(body)
      // The log carries the input's length, not the text.
      log(
        "#$id /v1/audio/speech from ${grant.packageName}: model ${request.model}, input " +
          "${request.inputChars} characters, voice ${request.voice ?: "(the model's default)"}, " +
          "response_format ${request.format.wire}" +
          (if (request.ignored.isEmpty()) "" else ", not applied: ${request.ignored.joinToString()}")
      )
      val entry =
        catalog.findTts(request.model)
          ?: throw when {
            catalog.findAsr(request.model) != null ->
              ApiError(
                400,
                "\"${request.model}\" is a speech recognition model, not a text-to-speech model " +
                  "(GET /v1/models lists them).",
              )
            catalog.find(request.model) != null ->
              ApiError(
                400,
                "\"${request.model}\" is a chat model, not a text-to-speech model (GET /v1/models lists them).",
              )
            else ->
              ApiError(
                404,
                "Text-to-speech model \"${request.model}\" is not on this phone; GET /v1/models lists " +
                  "the models here.",
                "model_not_found",
              )
          }
      val voice = resolveSpeechVoice(request.voice, entry)
      tts.withModelTurn { queueMs ->
        val loaded = tts.load(entry, voice)
        // The model lists its own voices once loaded: check the voice against that list too.
        resolveSpeechVoice(voice, entry, loaded.voices)
        val timeoutS = DEFAULT_REQUEST_TIMEOUT_S
        log(
          "#$id ${entry.name} on ${loaded.backend.uppercase()} (${loaded.backendNote}): voice $voice, " +
            "load ${loaded.loadMs} ms, waited $queueMs ms, time limit $timeoutS s ($APP_DEFAULT_SOURCE)"
        )
        val r = host.keepingAwake(timeoutS * 1000L + 60_000L) { tts.synthesize(entry, voice, request.input, timeoutS * 1000L) }
        // The WAV header, then the engine's pieces as they are: the audio is not copied into one array.
        val header = if (request.format == SpeechFormat.WAV) WavWriter.header(r.pcmBytes) else ByteArray(0)
        val length = header.size.toLong() + r.pcmBytes
        call.response.header("X-ModelShelf-Backend", loaded.backend)
        call.response.header("X-ModelShelf-Backend-Detail", loaded.backendNote)
        call.response.header("X-ModelShelf-Load-Ms", loaded.loadMs)
        call.response.header("X-ModelShelf-Queue-Ms", queueMs)
        call.response.header("X-ModelShelf-Voice", voice)
        call.response.header("X-ModelShelf-Audio-Ms", r.audioMs)
        call.response.header("X-ModelShelf-Inference-Ms", r.synthMs)
        call.response.header("X-ModelShelf-First-Audio-Ms", r.firstAudioMs)
        call.response.header("X-ModelShelf-Sample-Rate", SPEECH_OUTPUT_RATE_HZ)
        if (request.ignored.isNotEmpty()) {
          call.response.header("X-ModelShelf-Not-Applied", headerSafe(request.ignored))
        }
        call.respondBytesWriter(ContentType.parse(request.format.contentType), HttpStatusCode.OK, length) {
          writeFully(header)
          for (piece in r.pieces) writeFully(piece)
        }
        log(
          "#$id ended: ${"%.2f".format(r.audioMs / 1000.0)} s of audio in ${r.synthMs} ms " +
            "(${if (r.synthMs > 0) "%.1f".format(r.audioMs.toDouble() / r.synthMs) else "-"} times real time), " +
            "first audio ${r.firstAudioMs} ms, ${r.pieces.size} pieces, $length bytes " +
            "${request.format.wire}, total ${SystemClock.elapsedRealtime() - arrived} ms"
        )
      }
    } catch (e: ApiEngineHost.Busy) {
      log("#$id 503 busy, retry after ${e.retryAfterS} s")
      call.response.header(HttpHeaders.RetryAfter, e.retryAfterS)
      respondError(
        call,
        ApiError(503, "Model Shelf is answering other requests. Try again in ${e.retryAfterS} s.", "busy"),
        openAi = true,
      )
    } catch (e: ApiError) {
      log("#$id ${e.status} ${e.message}")
      respondError(call, e, openAi = true)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (call.response.isCommitted) {
        // The audio was being sent: the caller went away, and no error can follow it.
        log("#$id the audio stopped while it was sent: ${e.message ?: e.javaClass.simpleName}")
      } else {
        Log.e(TAG, "#$id The synthesis failed", e)
        respondError(call, ApiError(500, "The synthesis failed: ${e.message ?: e.javaClass.simpleName}"), openAi = true)
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
      call.response.header(HttpHeaders.RetryAfter, e.retryAfterS)
      respondError(call, ApiError(503, "Model Shelf is answering other requests.", "busy"), false)
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
    log("${call.request.local.method.value} ${call.request.local.uri}: 401 (${if (token == null) "no token" else "unknown token"})")
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
      log("${call.request.local.method.value} ${call.request.local.uri}: ${e.status} ${e.message}")
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
    log("${call.request.local.method.value} ${call.request.local.uri}: $status $message")
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

  private suspend fun respondError(call: ApplicationCall, e: ApiError, openAi: Boolean) {
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
  }
}
