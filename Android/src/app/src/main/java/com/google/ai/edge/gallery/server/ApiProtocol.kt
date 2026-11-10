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

import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/*
 * The request side of Model Shelf's local API: the Ollama and OpenAI
 * request bodies read into one form, and the rules that need no Android class (the sampler order,
 * whether a request continues the conversation the engine holds, keep_alive). Kept free of Android
 * classes so the unit tests run on the computer.
 */

/** How long a model stays loaded after its last request when the request names no keep_alive. */
const val DEFAULT_KEEP_ALIVE_MS = 5 * 60 * 1000L

/** The longest a request may run when the model's card names no request_timeout_s. */
const val DEFAULT_REQUEST_TIMEOUT_S = 300

/** The output budget when neither the request nor the card names one. */
const val DEFAULT_MAX_OUTPUT_TOKENS = 2048

/** Request bodies above this size are refused (text only in this version). */
const val MAX_BODY_BYTES = 4L * 1024 * 1024

const val REQUEST_SOURCE = "the request"
const val APP_DEFAULT_SOURCE = "the app's default"
private const val GREEDY_SOURCE = "greedy, as the request's temperature is 0"

private const val TAGS_HINT = "GET /api/tags lists the models on this phone"
private const val MODELS_HINT = "GET /v1/models lists the models on this phone"

enum class TurnRole(val wire: String) {
  SYSTEM("system"),
  USER("user"),
  ASSISTANT("assistant"),
}

/** One message of a conversation, text only. */
data class ChatTurn(val role: TurnRole, val text: String)

enum class Dialect(val path: String) {
  OLLAMA_CHAT("/api/chat"),
  OLLAMA_GENERATE("/api/generate"),
  OPENAI_CHAT("/v1/chat/completions"),
}

/** The sampler values a request names; null = not named. */
data class SamplerRequest(
  val topK: Int? = null,
  val topP: Double? = null,
  val temperature: Double? = null,
  val seed: Int? = null,
)

/** A chat or generate request in one form, whichever dialect it came in. */
data class ApiRequest(
  val dialect: Dialect,
  val model: String,
  val turns: List<ChatTurn>,
  val stream: Boolean,
  val sampler: SamplerRequest = SamplerRequest(),
  val maxOutputTokens: Int? = null,
  /** Ollama's options.num_ctx: the engine's token budget (prompt and output together). */
  val numCtx: Int? = null,
  /** null = the default; 0 = unload right after this request; negative = keep loaded. */
  val keepAliveMs: Long? = null,
  /** Fields the request set that this version reads but does not apply (logged, and sent back). */
  val ignored: List<String> = emptyList(),
  /** OpenAI stream_options.include_usage. */
  val includeUsage: Boolean = false,
)

/**
 * A request the API refuses: the HTTP status and one sentence for the caller. [retryAfterS] goes out
 * as Retry-After (every 503 that asks the caller to try again carries one).
 */
open class ApiError(
  val status: Int,
  override val message: String,
  val code: String? = null,
  val retryAfterS: Int? = null,
) : Exception(message)

/** A model file on this phone that the API can serve. Built by [ApiModelCatalog]. */
data class ApiModelEntry(
  /** The API name: `<Hugging Face repo>:<file name without .litertlm>`. */
  val name: String,
  val repoId: String,
  val file: String,
  val path: String,
  val sizeBytes: Long,
  /** The file's sha256 when the list gives it, else the commit it was downloaded from. */
  val digest: String,
  val commit: String,
  val modifiedMs: Long,
  /** Backends in the order the API tries them ("gpu", "cpu", "npu", "tpu"). */
  val accelerators: List<String>,
  /** The engine's token budget (prompt and output together). */
  val maxNumTokens: Int,
  val contextLength: Int?,
  val sampler: SamplerDefaults,
  /** The model keeps state between conversations on one engine (LiteRT-LM #3165). */
  val stateful: Boolean,
  val statefulWhy: String?,
  val requestTimeoutS: Int?,
  val maxOutputTokensMin: Int?,
  /** Which list named the file: Google's model list or the shelf. */
  val listedBy: String,
  /** The phone check that put the file on the main shelf, or null. */
  val checked: String?,
) {
  /** The first backend the API tries is the NPU: the file's own sampler applies. */
  val firstIsNpu: Boolean
    get() = accelerators.firstOrNull().let { it == "npu" || it == "tpu" }
}

/**
 * A speech model the API holds, for /api/ps, the notification and the Local API screen. [kind] is
 * the word GET /v1/models uses: "transcription" or "speech".
 */
data class LoadedAudioModel(
  val name: String,
  val kind: String,
  /** npu, gpu or cpu. */
  val backend: String,
  val sizeBytes: Long,
  val commit: String,
  /** Wall-clock time it unloads at; Long.MAX_VALUE = not set. */
  val expiresAtMs: Long,
)

/** A model's own sampler values and where they come from; null values fall back to the app's. */
data class SamplerDefaults(
  val topK: Int? = null,
  val topP: Double? = null,
  val temperature: Double? = null,
  val source: String = APP_DEFAULT_SOURCE,
)

/** One sampler value and where it came from. */
data class Setting<T>(val value: T, val source: String)

/** The sampler a request runs with, each value with its source. */
data class ResolvedSampler(
  val topK: Setting<Int>,
  val topP: Setting<Double>,
  val temperature: Setting<Double>,
  val seed: Int?,
  /** False for a file that runs on the NPU: no sampler is passed and the file's own applies. */
  val applied: Boolean,
) {
  fun sameValues(other: ResolvedSampler?): Boolean =
    other != null &&
      topK.value == other.topK.value &&
      topP.value == other.topP.value &&
      temperature.value == other.temperature.value &&
      seed == other.seed &&
      applied == other.applied

  /** The log line's text. */
  fun describe(): String =
    if (!applied) {
      "none passed: the NPU file uses its own sampler (request values are not applied)"
    } else {
      "top-k ${topK.value} (${topK.source}), top-p ${topP.value} (${topP.source}), " +
        "temperature ${temperature.value} (${temperature.source})" +
        (seed?.let { ", seed $it ($REQUEST_SOURCE)" } ?: "")
    }

  /** The response header's value (ASCII). */
  fun header(): String =
    if (!applied) "npu-file-own"
    else "top_k=${topK.value};top_p=${topP.value};temperature=${temperature.value}"
}

/**
 * The sampler order: the request, then the model's list entry (card, manifest or
 * Google's list), then the app's defaults. A temperature of 0 asks for greedy decoding (top-k 1).
 */
fun resolveSampler(request: SamplerRequest, defaults: SamplerDefaults, npu: Boolean): ResolvedSampler {
  val greedy = request.temperature != null && request.temperature <= 0.0
  val topK =
    when {
      greedy -> Setting(1, GREEDY_SOURCE)
      request.topK != null -> Setting(request.topK, REQUEST_SOURCE)
      defaults.topK != null -> Setting(defaults.topK, defaults.source)
      else -> Setting(DEFAULT_TOPK, APP_DEFAULT_SOURCE)
    }
  val topP =
    when {
      greedy -> Setting(1.0, GREEDY_SOURCE)
      request.topP != null -> Setting(request.topP, REQUEST_SOURCE)
      defaults.topP != null -> Setting(defaults.topP, defaults.source)
      else -> Setting(DEFAULT_TOPP.toCleanDouble(), APP_DEFAULT_SOURCE)
    }
  val temperature =
    when {
      request.temperature != null -> Setting(request.temperature, REQUEST_SOURCE)
      defaults.temperature != null -> Setting(defaults.temperature, defaults.source)
      else -> Setting(DEFAULT_TEMPERATURE.toCleanDouble(), APP_DEFAULT_SOURCE)
    }
  return ResolvedSampler(topK, topP, temperature, request.seed, applied = !npu)
}

/** 0.95f as 0.95, not 0.949999988079071. */
fun Float.toCleanDouble(): Double = toString().toDouble()

/** What the engine does with a request's messages. */
sealed class TurnPlan {
  /** The request repeats the conversation the engine holds and adds one user message. */
  data class Continue(val newTurn: ChatTurn) : TurnPlan()

  /** A new conversation: the system text, the earlier turns to load, and the turn to answer. */
  data class Fresh(val system: String?, val earlier: List<ChatTurn>, val last: ChatTurn) : TurnPlan()
}

/**
 * Whether [turns] continue the conversation the engine holds ([held] = its messages and its own
 * replies, or null when it holds none). Only an exact repeat plus one user message continues;
 * anything else starts a new conversation.
 */
fun planTurn(held: List<ChatTurn>?, turns: List<ChatTurn>): TurnPlan {
  val last = turns.lastOrNull() ?: throw ApiError(400, "Send at least one message.")
  if (last.role != TurnRole.USER) throw ApiError(400, "The last message must come from the user.")
  if (held != null && turns.size == held.size + 1 && turns.subList(0, held.size) == held) {
    return TurnPlan.Continue(last)
  }
  val system =
    turns
      .filter { it.role == TurnRole.SYSTEM }
      .joinToString("\n\n") { it.text }
      .takeIf { it.isNotBlank() }
  val rest = turns.filter { it.role != TurnRole.SYSTEM }
  return TurnPlan.Fresh(system = system, earlier = rest.dropLast(1), last = rest.last())
}

/** The output budget: the request's, else the larger of the card's floor and the app's default. */
fun resolveMaxOutputTokens(requested: Int?, cardMin: Int?, engineMax: Int): Int {
  val wanted = requested ?: maxOf(cardMin ?: 0, DEFAULT_MAX_OUTPUT_TOKENS)
  return wanted.coerceIn(1, maxOf(1, engineMax))
}

/**
 * The engine size (prompt and output tokens together) for Ollama's options.num_ctx: at most the
 * model's max tokens (the app's setting for the file, and the model's own context length when the
 * list gives one), so a request cannot make an engine bigger than the app would.
 */
fun resolveEngineTokens(numCtx: Int?, maxNumTokens: Int, contextLength: Int?): Int {
  val cap = maxOf(1, minOf(maxNumTokens, contextLength ?: maxNumTokens))
  return (numCtx ?: cap).coerceIn(1, cap)
}

/** An engine made for [loadedTokens] serves a request that needs [wantedTokens] without a reload. */
fun engineServes(loadedTokens: Int, wantedTokens: Int): Boolean = loadedTokens >= wantedTokens

/** A client's text made safe for a response header: letters, digits and ._- only, at most 64 each. */
fun headerSafe(values: List<String>): String =
  values.joinToString(",") { v -> v.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }.take(64) }

/**
 * A name a caller sent (a model or a voice), for logcat: only letters, digits and "._-/:" (a model
 * name such as litert-community/Kokoro-82M stays whole), at most 64, with the sent length when
 * anything was left out, so a caller's text never reaches the log as it was sent (a caller may put
 * the user's text in the wrong field; spaces are always left out).
 */
fun logName(value: String): String {
  val kept = value.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-/:" }.take(64)
  return if (kept == value) kept else "$kept…(${value.length} characters)"
}

/** An error message for logcat: each quoted part (a name a caller sent) as [logName] keeps it, at most 300 characters. */
fun logText(message: String): String =
  Regex("\"([^\"]*)\"").replace(message) { "\"" + logName(it.groupValues[1]) + "\"" }.take(300)

/**
 * Finds a model by the name a request gives: the exact API name, the same ignoring case, without
 * `hf.co/` or `:latest`, or the repo alone when one file of that repo is on the phone.
 */
fun <T> matchModel(requested: String, entries: List<T>, nameOf: (T) -> String, repoOf: (T) -> String): T? {
  var r = requested.trim()
  for (prefix in listOf("hf.co/", "huggingface.co/", "https://huggingface.co/")) {
    if (r.startsWith(prefix, ignoreCase = true)) r = r.substring(prefix.length)
  }
  r = r.removeSuffix(":latest")
  entries.firstOrNull { nameOf(it) == r }?.let { return it }
  entries.firstOrNull { nameOf(it).equals(r, ignoreCase = true) }?.let { return it }
  return entries.filter { repoOf(it).equals(r, ignoreCase = true) }.singleOrNull()
}

/**
 * Ollama's keep_alive: a number of seconds or a duration ("5m", "30s", "1h", "500ms"). Returns
 * milliseconds, 0 to unload at once, -1 to keep the model loaded, null when not given.
 */
fun parseKeepAlive(element: JsonElement?): Long? {
  if (element == null || element.isJsonNull) return null
  val bad = ApiError(400, "\"keep_alive\" must be a duration such as \"5m\" or a number of seconds.")
  if (!element.isJsonPrimitive) throw bad
  val p = element.asJsonPrimitive
  if (p.isNumber) {
    val seconds = p.asDouble
    return if (seconds < 0) -1L else (seconds * 1000).toLong()
  }
  if (!p.isString) throw bad
  val text = p.asString.trim().lowercase()
  if (text.isEmpty()) return null
  val m = Regex("^(-?\\d+(?:\\.\\d+)?)(ms|s|m|h)?$").matchEntire(text) ?: throw bad
  val value = m.groupValues[1].toDouble()
  if (value < 0) return -1L
  val unitMs =
    when (m.groupValues[2]) {
      "ms" -> 1.0
      "", "s" -> 1000.0
      "m" -> 60_000.0
      "h" -> 3_600_000.0
      else -> throw bad
    }
  return (value * unitMs).toLong()
}

// ---------------------------------------------------------------------------------------------
// Ollama: POST /api/chat and POST /api/generate (https://github.com/ollama/ollama/blob/main/docs/api.md)

fun parseOllamaChat(body: String): ApiRequest {
  val o = parseBody(body)
  val model = o.modelName(TAGS_HINT)
  rejectOllamaExtras(o)
  val ignored = mutableListOf<String>()
  val options = o.opt("options")?.objectOrThrow("options")
  val parsed = readOllamaOptions(options, ignored)
  val messages = o.opt("messages")?.arrayOrThrow("messages")
  return ApiRequest(
    dialect = Dialect.OLLAMA_CHAT,
    model = model,
    turns = readOllamaMessages(messages),
    stream = o.optBoolean("stream") ?: true,
    sampler = checkSampler(parsed.sampler),
    maxOutputTokens = parsed.maxOutputTokens,
    numCtx = parsed.numCtx,
    keepAliveMs = parseKeepAlive(o.opt("keep_alive")),
    ignored = ignored,
  )
}

fun parseOllamaGenerate(body: String): ApiRequest {
  val o = parseBody(body)
  val model = o.modelName(TAGS_HINT)
  rejectOllamaExtras(o)
  if (o.nonEmpty("images")) throw ApiError(400, "Images are not supported by this API yet.")
  if (o.optBoolean("raw") == true) {
    throw ApiError(400, "\"raw\" prompts are not supported by this API yet.")
  }
  if (o.nonEmpty("suffix")) throw ApiError(400, "\"suffix\" is not supported by this API yet.")
  if (o.nonEmpty("template")) throw ApiError(400, "\"template\" is not supported by this API yet.")
  if (o.nonEmpty("context")) {
    throw ApiError(
      400,
      "\"context\" is not supported by this API; send the whole conversation to /api/chat instead.",
    )
  }
  val ignored = mutableListOf<String>()
  val options = o.opt("options")?.objectOrThrow("options")
  val parsed = readOllamaOptions(options, ignored)
  val prompt = o.optString("prompt") ?: ""
  val system = o.optString("system")
  val turns = buildList {
    if (!system.isNullOrEmpty()) add(ChatTurn(TurnRole.SYSTEM, system))
    if (prompt.isNotEmpty()) add(ChatTurn(TurnRole.USER, prompt))
  }
  return ApiRequest(
    dialect = Dialect.OLLAMA_GENERATE,
    model = model,
    // A system text alone is not a request to answer: with no prompt the call loads or unloads.
    turns = if (prompt.isEmpty()) emptyList() else turns,
    stream = o.optBoolean("stream") ?: true,
    sampler = checkSampler(parsed.sampler),
    maxOutputTokens = parsed.maxOutputTokens,
    numCtx = parsed.numCtx,
    keepAliveMs = parseKeepAlive(o.opt("keep_alive")),
    ignored = ignored,
  )
}

/** /api/show and the other calls that name a model: `{"model": "..."}` (older clients: "name"). */
fun parseModelOnly(body: String): String = parseBody(body).modelName(TAGS_HINT)

private data class OllamaOptions(
  val sampler: SamplerRequest,
  val maxOutputTokens: Int?,
  val numCtx: Int?,
)

private val OLLAMA_OPTIONS_USED =
  setOf("temperature", "top_k", "top_p", "num_predict", "num_ctx", "seed", "stop")

private fun readOllamaOptions(options: JsonObject?, ignored: MutableList<String>): OllamaOptions {
  if (options == null) return OllamaOptions(SamplerRequest(), null, null)
  if (options.nonEmpty("stop")) {
    throw ApiError(400, "\"stop\" sequences are not supported by this API yet.")
  }
  for (key in options.keySet()) {
    if (key !in OLLAMA_OPTIONS_USED && !options.get(key).isJsonNull) ignored.add("options.$key")
  }
  val numPredict = options.optInt("num_predict", "options.num_predict")
  val maxOutput =
    when {
      numPredict == null -> null
      numPredict == -1 || numPredict == -2 -> null
      numPredict >= 1 -> numPredict
      else -> throw ApiError(400, "\"options.num_predict\" must be 1 or more (or -1).")
    }
  val numCtx = options.optInt("num_ctx", "options.num_ctx")
  if (numCtx != null && numCtx < 1) throw ApiError(400, "\"options.num_ctx\" must be 1 or more.")
  return OllamaOptions(
    sampler =
      SamplerRequest(
        topK = options.optInt("top_k", "options.top_k"),
        topP = options.optDouble("top_p", "options.top_p"),
        temperature = options.optDouble("temperature", "options.temperature"),
        seed = options.optInt("seed", "options.seed"),
      ),
    maxOutputTokens = maxOutput,
    numCtx = numCtx,
  )
}

private fun rejectOllamaExtras(o: JsonObject) {
  val format = o.opt("format")
  if (format != null && !(format.isJsonPrimitive && format.asString.isEmpty())) {
    throw ApiError(400, "\"format\" (structured output) is not supported by this API yet.")
  }
  if (o.nonEmpty("tools")) throw ApiError(400, "Tools are not supported by this API yet.")
  val think = o.opt("think")
  if (think != null && !(think.isJsonPrimitive && think.asJsonPrimitive.isBoolean && !think.asBoolean)) {
    throw ApiError(400, "\"think\" is not supported by this API yet; leave it out or set it to false.")
  }
}

private fun readOllamaMessages(messages: JsonArray?): List<ChatTurn> {
  if (messages == null) return emptyList()
  return messages.mapIndexed { i, element ->
    if (!element.isJsonObject) throw ApiError(400, "messages[$i] must be an object.")
    val m = element.asJsonObject
    if (m.nonEmpty("images")) {
      throw ApiError(400, "Images are not supported by this API yet (messages[$i].images).")
    }
    if (m.nonEmpty("tool_calls")) {
      throw ApiError(400, "Tool calls are not supported by this API yet (messages[$i].tool_calls).")
    }
    val content = m.opt("content")
    val text =
      when {
        content == null -> ""
        content.isJsonPrimitive && content.asJsonPrimitive.isString -> content.asString
        else -> throw ApiError(400, "messages[$i].content must be a string.")
      }
    ChatTurn(roleOf(m, i), text)
  }
}

// ---------------------------------------------------------------------------------------------
// OpenAI: POST /v1/chat/completions (https://platform.openai.com/docs/api-reference/chat/create)

private val OPENAI_PENALTIES = listOf("frequency_penalty", "presence_penalty")

fun parseOpenAiChat(body: String): ApiRequest {
  val o = parseBody(body)
  val model = o.modelName(MODELS_HINT)
  val messages =
    o.opt("messages")?.arrayOrThrow("messages") ?: throw ApiError(400, "\"messages\" is required.")
  if (messages.size() == 0) throw ApiError(400, "\"messages\" must not be empty.")
  val turns = messages.mapIndexed { i, element -> readOpenAiMessage(element, i) }
  o.optInt("n")?.let { if (it != 1) throw ApiError(400, "Only \"n\": 1 is supported.") }
  if (o.nonEmpty("tools") || o.nonEmpty("functions")) {
    throw ApiError(400, "Tools are not supported by this API yet.")
  }
  o.opt("response_format")?.let { rf ->
    val type = if (rf.isJsonObject) rf.asJsonObject.optString("type") else null
    if (type != null && type != "text") {
      throw ApiError(400, "\"response_format\" \"$type\" is not supported by this API yet.")
    }
  }
  if (o.nonEmpty("stop")) throw ApiError(400, "\"stop\" sequences are not supported by this API yet.")
  if (o.optBoolean("logprobs") == true || o.opt("top_logprobs") != null) {
    throw ApiError(400, "\"logprobs\" are not supported by this API yet.")
  }
  if (o.nonEmpty("audio")) throw ApiError(400, "Audio output is not supported by this API yet.")
  o.opt("modalities")?.arrayOrThrow("modalities")?.forEach {
    if (!(it.isJsonPrimitive && it.asString == "text")) {
      throw ApiError(400, "Only the \"text\" modality is supported by this API yet.")
    }
  }
  val ignored = mutableListOf<String>()
  for (key in OPENAI_PENALTIES) {
    val v = o.optDouble(key)
    if (v != null && v != 0.0) ignored.add(key)
  }
  if (o.nonEmpty("logit_bias")) ignored.add("logit_bias")
  val maxTokens = o.optInt("max_completion_tokens") ?: o.optInt("max_tokens")
  if (maxTokens != null && maxTokens < 1) throw ApiError(400, "\"max_tokens\" must be 1 or more.")
  val temperature = o.optDouble("temperature")
  if (temperature != null && temperature > 2.0) {
    throw ApiError(400, "\"temperature\" must be between 0 and 2.")
  }
  val streamOptions = o.opt("stream_options")?.objectOrThrow("stream_options")
  return ApiRequest(
    dialect = Dialect.OPENAI_CHAT,
    model = model,
    turns = turns,
    stream = o.optBoolean("stream") ?: false,
    sampler =
      checkSampler(
        SamplerRequest(
          topK = o.optInt("top_k"),
          topP = o.optDouble("top_p"),
          temperature = temperature,
          seed = o.optInt("seed"),
        )
      ),
    maxOutputTokens = maxTokens,
    keepAliveMs = parseKeepAlive(o.opt("keep_alive")),
    ignored = ignored,
    includeUsage = streamOptions?.optBoolean("include_usage") == true,
  )
}

private fun readOpenAiMessage(element: JsonElement, i: Int): ChatTurn {
  if (!element.isJsonObject) throw ApiError(400, "messages[$i] must be an object.")
  val m = element.asJsonObject
  if (m.nonEmpty("tool_calls") || m.nonEmpty("function_call")) {
    throw ApiError(400, "Tool calls are not supported by this API yet (messages[$i]).")
  }
  val role = roleOf(m, i)
  val content = m.opt("content")
  val text =
    when {
      content == null -> ""
      content.isJsonPrimitive && content.asJsonPrimitive.isString -> content.asString
      content.isJsonArray ->
        content.asJsonArray
          .mapIndexed { j, part ->
            val p =
              if (part.isJsonObject) part.asJsonObject
              else throw ApiError(400, "messages[$i].content[$j] must be an object.")
            val type = p.optString("type") ?: "text"
            if (type != "text") {
              throw ApiError(
                400,
                "Only text content is supported by this API yet (messages[$i].content[$j] is $type).",
              )
            }
            p.optString("text") ?: ""
          }
          .joinToString("\n")
      else -> throw ApiError(400, "messages[$i].content must be a string or a list of parts.")
    }
  return ChatTurn(role, text)
}

// ---------------------------------------------------------------------------------------------
// Shared readers.

private fun parseBody(body: String): JsonObject {
  val root =
    try {
      JsonParser.parseString(body)
    } catch (e: Exception) {
      throw ApiError(400, "The body is not valid JSON.")
    }
  if (root == null || !root.isJsonObject) throw ApiError(400, "The body must be a JSON object.")
  return root.asJsonObject
}

private fun roleOf(m: JsonObject, i: Int): TurnRole {
  val role = (m.optString("role") ?: "").lowercase()
  return when (role) {
    "system", "developer" -> TurnRole.SYSTEM
    "user" -> TurnRole.USER
    "assistant" -> TurnRole.ASSISTANT
    "tool", "function" ->
      throw ApiError(400, "Tool messages are not supported by this API yet (messages[$i]).")
    "" -> throw ApiError(400, "messages[$i] needs a \"role\".")
    else -> throw ApiError(400, "messages[$i] has an unknown role \"$role\".")
  }
}

private fun checkSampler(s: SamplerRequest): SamplerRequest {
  s.topK?.let { if (it < 1) throw ApiError(400, "\"top_k\" must be 1 or more.") }
  s.topP?.let { if (it <= 0.0 || it > 1.0) throw ApiError(400, "\"top_p\" must be above 0 and at most 1.") }
  s.temperature?.let { if (it < 0.0) throw ApiError(400, "\"temperature\" must be 0 or more.") }
  return s
}

private fun JsonObject.modelName(hint: String): String {
  val name = optString("model") ?: optString("name")
  if (name.isNullOrBlank()) throw ApiError(400, "Name a model in \"model\" ($hint).")
  return name.trim()
}

private fun JsonObject.opt(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }

/** True when the key holds something other than null, false, "", [] or {}. */
private fun JsonObject.nonEmpty(key: String): Boolean {
  val e = opt(key) ?: return false
  return when {
    e.isJsonArray -> e.asJsonArray.size() > 0
    e.isJsonObject -> e.asJsonObject.size() > 0
    e.isJsonPrimitive && e.asJsonPrimitive.isString -> e.asString.isNotEmpty()
    e.isJsonPrimitive && e.asJsonPrimitive.isBoolean -> e.asBoolean
    else -> true
  }
}

private fun JsonElement.objectOrThrow(key: String): JsonObject =
  if (isJsonObject) asJsonObject else throw ApiError(400, "\"$key\" must be an object.")

private fun JsonElement.arrayOrThrow(key: String): JsonArray =
  if (isJsonArray) asJsonArray else throw ApiError(400, "\"$key\" must be a list.")

private fun JsonObject.optString(key: String): String? {
  val e = opt(key) ?: return null
  if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) {
    throw ApiError(400, "\"$key\" must be a string.")
  }
  return e.asString
}

private fun JsonObject.optBoolean(key: String): Boolean? {
  val e = opt(key) ?: return null
  if (!e.isJsonPrimitive || !e.asJsonPrimitive.isBoolean) {
    throw ApiError(400, "\"$key\" must be true or false.")
  }
  return e.asBoolean
}

private fun JsonObject.optDouble(key: String, label: String = key): Double? {
  val e = opt(key) ?: return null
  if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) {
    throw ApiError(400, "\"$label\" must be a number.")
  }
  return e.asDouble
}

private fun JsonObject.optInt(key: String, label: String = key): Int? {
  val d = optDouble(key, label) ?: return null
  if (d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) {
    throw ApiError(400, "\"$label\" must be a whole number.")
  }
  return d.toInt()
}
