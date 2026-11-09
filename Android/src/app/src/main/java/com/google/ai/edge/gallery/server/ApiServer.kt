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
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MSApiServer"

/** SSE comment lines while an OpenAI stream waits for text (DESIGN §g-3, row 2). */
private const val SSE_KEEP_ALIVE_MS = 10_000L

/** Debug builds only: keep a stateful model's used engine, to show what the restart prevents. */
private const val DEBUG_REUSE_HEADER = "X-ModelShelf-Debug-Reuse-Engine"

/**
 * The HTTP server of the local API (DESIGN.md §g-2): Ollama's /api/ and OpenAI's /v1/ on
 * 127.0.0.1:11434, every call with an app's token. One line per request goes to logcat
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
  private val requestIds = AtomicLong(0)
  private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? =
    null

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

  /** Stops answering, unloads the model and closes the port. */
  suspend fun stop() {
    host.shutdown()
    server?.stop(500, 2_000)
    server = null
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
        call.respondJson(HttpStatusCode.OK, ApiJson.ollamaPs(listOfNotNull(host.loadedInfo())))
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
        log("GET /v1/models from ${grant.packageName}: ${entries.size} models")
        call.respondJson(HttpStatusCode.OK, ApiJson.openAiModels(entries))
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
      for (path in listOf("/v1/audio/transcriptions", "/v1/audio/speech")) {
        post(path) {
          notHere(call, 501, "Speech is not in this version of Model Shelf yet.", openAi = true)
        }
      }
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
      host.withTurn { queueMs ->
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
          call.response.header("X-ModelShelf-Not-Applied", request.ignored.joinToString(","))
        }
        val running = host.start(prepared, maxOutput, timeoutS * 1000L)
        val r: ApiEngineHost.Result
        try {
          r = answer(call, request, entry, running, timeoutS * 1000L, arrived, queueMs, prepared)
        } catch (t: Throwable) {
          // The caller went away or the call was cancelled: end the turn, drop the conversation.
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
        host.keepAlive(request.keepAliveMs)
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
  ): ApiEngineHost.Result {
    val model = entry.name
    val generate = request.dialect == Dialect.OLLAMA_GENERATE
    fun stats(r: ApiEngineHost.Result, promptTokens: Int?) =
      TurnStats(
        loadMs = prepared.loadMs,
        queueMs = queueMs,
        firstTextMs = r.firstTextMs,
        inferenceMs = r.inferenceMs,
        totalMs = SystemClock.elapsedRealtime() - arrived,
        evalChunks = r.chunks,
        promptTokens = promptTokens,
      )
    fun finishReason(r: ApiEngineHost.Result, maxOut: Int) = if (r.chunks >= maxOut) "length" else "stop"
    val maxOut =
      resolveMaxOutputTokens(request.maxOutputTokens, entry.maxOutputTokensMin, prepared.loaded.maxNumTokens)

    if (!request.stream) {
      val r = host.pump(running, timeoutMs, null, onText = {}, onIdle = null)
      when (r.end) {
        ApiEngineHost.End.DONE -> {
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
        ApiEngineHost.End.TIMEOUT ->
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
      var result: ApiEngineHost.Result? = null
      call.respondBytesWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
        sse(ApiJson.openAiChunk(id, model, created, ApiJson.roleDelta(), null))
        val r =
          host.pump(
            running,
            timeoutMs,
            SSE_KEEP_ALIVE_MS,
            onText = { sse(ApiJson.openAiChunk(id, model, created, ApiJson.textDelta(it), null)) },
            onIdle = {
              writeStringUtf8(": keep-alive\n\n")
              flush()
            },
          )
        result = r
        if (r.end == ApiEngineHost.End.DONE) {
          sse(ApiJson.openAiChunk(id, model, created, com.google.gson.JsonObject(), finishReason(r, maxOut)))
          if (request.includeUsage) sse(ApiJson.openAiUsageChunk(id, model, created, stats(r, null)))
          writeStringUtf8("data: [DONE]\n\n")
          flush()
        } else if (r.end != ApiEngineHost.End.CLIENT_GONE) {
          sse(ApiJson.openAiError(r.error ?: "The answer stopped.", if (r.end == ApiEngineHost.End.TIMEOUT) 504 else 500, null))
        }
      }
      return result ?: ApiEngineHost.Result("", -1, 0, 0, ApiEngineHost.End.CLIENT_GONE, "no stream")
    }

    var result: ApiEngineHost.Result? = null
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
      if (r.end == ApiEngineHost.End.DONE) {
        writeStringUtf8(ApiJson.ollamaDone(model, generate, "", finishReason(r, maxOut), stats(r, null)) + "\n")
        flush()
      } else if (r.end != ApiEngineHost.End.CLIENT_GONE) {
        writeStringUtf8(ApiJson.ollamaError(r.error ?: "The answer stopped.") + "\n")
        flush()
      }
    }
    return result ?: ApiEngineHost.Result("", -1, 0, 0, ApiEngineHost.End.CLIENT_GONE, "no stream")
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
      host.withTurn { queueMs ->
        val reason: String
        var loadMs = 0L
        if (request.keepAliveMs == 0L) {
          host.unloadIf(entry.path, "keep_alive 0 with no message")
          reason = "unload"
        } else {
          loadMs = host.loadOnly(entry, request.numCtx)
          host.keepAlive(request.keepAliveMs)
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

  /** The app a request comes from, checked before the body is read; null after a 401. */
  private suspend fun caller(call: ApplicationCall, openAi: Boolean): ApiTokenStore.Grant? {
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

  private suspend fun readBody(call: ApplicationCall, openAi: Boolean): String? {
    val length = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (length != null && length > MAX_BODY_BYTES) {
      respondError(call, ApiError(413, "The request body is larger than 4 MB."), openAi)
      return null
    }
    return try {
      call.receiveText()
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
