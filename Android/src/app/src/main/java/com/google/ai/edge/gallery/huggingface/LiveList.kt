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

package com.google.ai.edge.gallery.huggingface

import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.AllowedModel
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.DefaultConfig
import com.google.ai.edge.gallery.data.LITERT_LM_RUNTIME_VERSION
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.needsNewerRuntime
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/*
 * The shelf: litert-community's LiteRT-LM repos on Hugging Face, listed live (DESIGN.md §b).
 *
 * One list call returns every repo with its card data and file names. Each file's defaults come
 * from, in this order:
 * 1. the card's `litert_lm:` block (YAML front matter, top-level key),
 * 2. the repo's `litertlm_manifest.json` at the listed commit, while the card has no block. The
 *    block copies the manifest's names and nesting (`model.*` at the top, `variants[]` as is), so
 *    one reader serves both,
 * 3. the app's defaults (Gallery's sampler, CPU then GPU).
 * Rules decided by the D-614 window (2026-11-08):
 * - the first accelerator is the variant's `recommended[]` backend for platform android, else its
 *   `default_backend`, then the rest of `backends` in order,
 * - a file whose `min_runtime_version` is newer than the app's LiteRT-LM stays listed and says
 *   that it needs an app update,
 * - sampler values are taken as written; `session_defaults.source` names where they came from.
 */

private const val TAG = "MSLiveList"

/** The Hugging Face author whose repos form the shelf. */
const val LIVE_LIST_AUTHOR = "litert-community"

private const val HF_BASE_URL = "https://huggingface.co"

/**
 * Debug builds only: a file whose first line is a base URL used instead of https://huggingface.co
 * for the list, manifest and file-size calls (downloads still go to huggingface.co). With
 * `adb reverse tcp:8765 tcp:8765` and a server on the computer, `http://127.0.0.1:8765` lets a test
 * answer the list call with an error. The file is read from /data/local/tmp or, when the device
 * does not let the app read that, from the app's files dir (`adb shell run-as <applicationId>`).
 * Delete the file to go back to Hugging Face.
 */
const val LIVE_LIST_BASE_URL_OVERRIDE_FILE = "/data/local/tmp/modelshelf_hf_base_url.txt"

private const val BASE_URL_OVERRIDE_NAME = "modelshelf_hf_base_url.txt"

private const val LIST_FILE = "list.json"
private const val META_FILE = "meta.json"
private const val REPO_DIR = "repo"
private const val MANIFEST_FILE_NAME = "litertlm_manifest.json"
private const val PARALLEL_REQUESTS = 12
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 30_000
private const val MAX_DEFAULT_MAX_TOKENS = 4096
private const val FALLBACK_MAX_TOKENS = 1024
/** The Max tokens slider starts at 2000 (Config.kt); a smaller context gets a fixed value. */
private const val MAX_TOKENS_SLIDER_MIN = 2000

/** Card pipeline tags of chat models; a repo without a tag is kept unless it is an embedder. */
private val CHAT_PIPELINE_TAGS =
  setOf("text-generation", "image-text-to-text", "any-to-any", "translation")

private val EKV_REGEX = Regex("ekv(\\d{3,6})", RegexOption.IGNORE_CASE)
private val SOC_TOKEN_REGEX = Regex("(?:^|[_\\-.])((?:sm|mt)\\d{4})(?:$|[_\\-.])")

/** Where the list on screen came from. */
enum class LiveListSource {
  /** Fetched from Hugging Face just now. */
  NETWORK,
  /** The list saved on the device the last time it was fetched. */
  CACHE,
  /** Neither: no network and nothing saved yet. */
  NONE,
}

/** Where a file's defaults came from. */
enum class DefaultsSource(val label: String) {
  CARD("the model card"),
  MANIFEST(MANIFEST_FILE_NAME),
  APP("the app's defaults"),
}

/** The result of one shelf load. */
data class LiveListResult(
  val models: List<AllowedModel> = emptyList(),
  /** Per model name: the line shown under that model in the list. */
  val notes: Map<String, String> = emptyMap(),
  val source: LiveListSource = LiveListSource.NONE,
  /** When the list on screen was fetched from Hugging Face (epoch ms), 0 if never. */
  val savedAtMs: Long = 0L,
  /** Why the network list was not used, e.g. "HTTP 503"; null when it was. */
  val networkError: String? = null,
  val repoCount: Int = 0,
  val gatedRepoCount: Int = 0,
  val skippedRepoCount: Int = 0,
  val needsUpdateCount: Int = 0,
)

/** What the model list screen shows about the shelf. */
data class LiveListUiState(
  val modelNames: Set<String> = emptySet(),
  val notes: Map<String, String> = emptyMap(),
  val status: String = "",
)

/** One line for the top of the shelf section. */
fun LiveListResult.statusLine(): String {
  val time =
    if (savedAtMs > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(savedAtMs))
    else ""
  val counts = "${models.size} files from $repoCount repos"
  val extras = buildList {
    if (needsUpdateCount > 0) add("$needsUpdateCount need a newer app")
    if (gatedRepoCount > 0) add("$gatedRepoCount gated repos need a sign-in and are not listed")
    if (skippedRepoCount > 0) add("$skippedRepoCount repos could not be read")
  }
  val extraText = if (extras.isEmpty()) "" else " (" + extras.joinToString("; ") + ")"
  return when (source) {
    LiveListSource.NETWORK -> "$counts, updated $time$extraText."
    LiveListSource.CACHE ->
      "Hugging Face did not answer (${networkError ?: "error"}). Showing the list saved $time: " +
        "$counts$extraText."
    LiveListSource.NONE ->
      "Could not load the list from Hugging Face (${networkError ?: "error"}) and no list is " +
        "saved on this device yet."
  }
}

private data class ListRepo(
  val id: String,
  val sha: String,
  val gated: Boolean,
  val pipelineTag: String?,
  /** The card's `litert_lm:` block, if the card has one. */
  val block: JsonObject?,
  val files: List<String>,
)

private data class HttpResult(val body: String?, val error: String?)

private data class RepoFiles(
  val manifest: JsonObject?,
  val sizes: Map<String, Long>,
  val sha256s: Map<String, String>,
)

/** Loads the shelf from Hugging Face, falling back to the copy saved under [cacheDir]. */
class LiveListClient(private val cacheDir: File) {

  private val userAgent = "ModelShelf/${BuildConfig.VERSION_NAME} (Android)"

  private val apiBaseUrl: String by lazy { readBaseUrlOverride() ?: HF_BASE_URL }

  suspend fun load(): LiveListResult =
    withContext(Dispatchers.IO) {
      cacheDir.mkdirs()
      val listUrl =
        "$apiBaseUrl/api/models?author=$LIVE_LIST_AUTHOR&filter=litert-lm&limit=1000" +
          "&expand=cardData&expand=sha&expand=siblings&expand=lastModified&expand=downloads" +
          "&expand=gated"
      Log.d(TAG, "Loading the shelf: $listUrl")
      val fetched = httpGet(listUrl)
      var networkError: String? = fetched.error
      var repos: List<ListRepo>? = fetched.body?.let { parseList(it) }
      if (fetched.body != null && repos == null) {
        networkError = "unreadable list"
      }
      val online = repos != null
      var savedAtMs = System.currentTimeMillis()
      if (!online) {
        Log.w(TAG, "Shelf list not fetched ($networkError); reading the saved copy")
        repos = readText(File(cacheDir, LIST_FILE))?.let { parseList(it) }
        savedAtMs = readSavedAt()
        if (repos == null) {
          return@withContext LiveListResult(
            source = LiveListSource.NONE,
            networkError = networkError,
          )
        }
      }

      val shelfRepos = repos!!.filter { isShelfRepo(it) }
      val gatedCount = shelfRepos.count { it.gated }
      val usable = shelfRepos.filter { !it.gated }
      val repoFiles = loadRepoFiles(usable, online)

      val models = mutableListOf<AllowedModel>()
      val notes = mutableMapOf<String, String>()
      var skipped = 0
      var shown = 0
      var needsUpdate = 0
      for (repo in usable) {
        val files = repoFiles[repo.id]
        if (files == null) {
          skipped++
          continue
        }
        val converted = convertRepo(repo, files)
        if (converted.isEmpty()) continue
        shown++
        for ((model, note, newer) in converted) {
          models.add(model)
          notes[model.name] = note
          if (newer) needsUpdate++
        }
      }

      if (online) {
        writeText(File(cacheDir, LIST_FILE), fetched.body!!)
        writeText(File(cacheDir, META_FILE), "{\"savedAtMs\": $savedAtMs}")
        pruneRepoFiles(usable)
      }
      Log.d(
        TAG,
        "Shelf: ${models.size} files from $shown repos, source ${if (online) "network" else "cache"}" +
          ", gated $gatedCount, skipped $skipped, needs a newer app $needsUpdate",
      )
      LiveListResult(
        models = models,
        notes = notes,
        source = if (online) LiveListSource.NETWORK else LiveListSource.CACHE,
        savedAtMs = savedAtMs,
        networkError = if (online) null else networkError,
        repoCount = shown,
        gatedRepoCount = gatedCount,
        skippedRepoCount = skipped,
        needsUpdateCount = needsUpdate,
      )
    }

  private fun isShelfRepo(repo: ListRepo): Boolean {
    if (repo.files.none { isLiteRtLmFileName(it) }) return false
    val tag = repo.pipelineTag
    return if (tag == null) !repo.id.contains("embedding", ignoreCase = true)
    else tag in CHAT_PIPELINE_TAGS
  }

  /** Reads (or fetches when [online]) the manifest and file sizes each repo needs. */
  private suspend fun loadRepoFiles(repos: List<ListRepo>, online: Boolean): Map<String, RepoFiles> {
    val out = mutableMapOf<String, RepoFiles>()
    coroutineScope {
      for (chunk in repos.chunked(PARALLEL_REQUESTS)) {
        val results =
          chunk.map { repo -> async(Dispatchers.IO) { repo.id to loadOneRepo(repo, online) } }.awaitAll()
        for ((id, files) in results) {
          if (files != null) out[id] = files
        }
      }
    }
    return out
  }

  private fun loadOneRepo(repo: ListRepo, online: Boolean): RepoFiles? {
    var manifest: JsonObject? = null
    if (repo.block == null && MANIFEST_FILE_NAME in repo.files) {
      val text =
        cachedOrFetched(
          file = repoCacheFile(repo, "manifest"),
          url = "$apiBaseUrl/${repo.id}/resolve/${repo.sha}/$MANIFEST_FILE_NAME",
          online = online,
        ) ?: return null
      manifest = parseObject(text) ?: return null
    }
    val sizes = mutableMapOf<String, Long>()
    val sha256s = mutableMapOf<String, String>()
    manifest?.arr("variants")?.objects()?.forEach { v ->
      val file = v.str("file") ?: return@forEach
      v.long("size_bytes")?.let { sizes[file] = it }
      v.str("sha256")?.let { sha256s[file] = it }
    }
    val needBlobs = repo.files.filter { isLiteRtLmFileName(it) }.any { it !in sizes }
    if (needBlobs) {
      val text =
        cachedOrFetched(
          file = repoCacheFile(repo, "blobs"),
          url = "$apiBaseUrl/api/models/${repo.id}/revision/${repo.sha}?blobs=true",
          online = online,
        ) ?: return null
      val obj = parseObject(text) ?: return null
      obj.arr("siblings")?.objects()?.forEach { s ->
        val name = s.str("rfilename") ?: return@forEach
        val lfs = s.obj("lfs")
        val size = lfs?.long("size") ?: s.long("size")
        if (size != null && name !in sizes) sizes[name] = size
        val sha = lfs?.str("sha256") ?: lfs?.str("oid")
        if (sha != null && name !in sha256s) sha256s[name] = sha
      }
    }
    return RepoFiles(manifest = manifest, sizes = sizes, sha256s = sha256s)
  }

  /** One shelf entry per file the device can use: the model, its note, needs a newer app. */
  private fun convertRepo(repo: ListRepo, files: RepoFiles): List<Triple<AllowedModel, String, Boolean>> {
    val defaultsSource =
      when {
        repo.block != null -> DefaultsSource.CARD
        files.manifest != null -> DefaultsSource.MANIFEST
        else -> DefaultsSource.APP
      }
    val modelLevel: JsonObject =
      repo.block ?: files.manifest?.obj("model") ?: JsonObject()
    val variantList: List<JsonObject> =
      (repo.block?.arr("variants") ?: files.manifest?.arr("variants"))?.objects() ?: emptyList()
    val variantByFile = variantList.mapNotNull { v -> v.str("file")?.let { it to v } }.toMap()

    // The declared order first (the first variant is the repo's default), then other files.
    val declared = variantList.mapNotNull { it.str("file") }.filter { it in repo.files }
    val rest = repo.files.filter { isLiteRtLmFileName(it) && it !in declared }.sorted()
    val shelfFiles =
      (declared + rest).filter { isLiteRtLmFileName(it) && isFileCompatibleWithDevice(it) }

    val sessionDefaults = modelLevel.obj("session_defaults")
    val topK = sessionDefaults?.int("top_k")
    val topP = sessionDefaults?.float("top_p")
    val temperature = sessionDefaults?.float("temperature")
    val samplerSource = sessionDefaults?.str("source")
    val capabilities = modelLevel.obj("capabilities")
    val vision = capabilities?.bool("vision") == true
    val audio = capabilities?.bool("audio") == true
    val thinking = capabilities?.obj("thinking")?.bool("declared") == true
    val repoShort = repo.id.substringAfter('/')

    val out = mutableListOf<Triple<AllowedModel, String, Boolean>>()
    for (file in shelfFiles) {
      val size = files.sizes[file] ?: continue
      val v = variantByFile[file]
      val accelerators = acceleratorOrder(v)
      // An NPU-only file built for one SoC (e.g. ..._sm8850...) only runs on that SoC.
      if (accelerators == listOf("npu")) {
        val socToken = SOC_TOKEN_REGEX.find(file.lowercase())?.groupValues?.get(1)
        if (socToken != null && !SOC.contains(socToken)) continue
      }
      val context =
        v?.int("context_length")
          ?: EKV_REGEX.find(file)?.groupValues?.get(1)?.toIntOrNull()
          ?: if (shelfFiles.size == 1) modelLevel.int("context_length") else null
      val sliderMax = context?.takeIf { it > MAX_TOKENS_SLIDER_MIN }
      var maxTokens =
        sessionDefaults?.int("max_num_tokens")?.let { if (context != null) minOf(it, context) else it }
          ?: context?.let { minOf(it, MAX_DEFAULT_MAX_TOKENS) }
          ?: FALLBACK_MAX_TOKENS
      if (sliderMax != null) maxTokens = maxTokens.coerceIn(MAX_TOKENS_SLIDER_MIN, sliderMax)
      val minRuntime = v?.str("min_runtime_version")
      val newer = needsNewerRuntime(minRuntime)
      val stateful =
        modelLevel.bool("stateful") == true ||
          (v?.arr("known_issues")?.strings() ?: emptyList()).any {
            it.contains("new Engine for each conversation", ignoreCase = true)
          }

      val stem = file.substringAfterLast('/').removeSuffix(".litertlm")
      val label =
        if (stem.startsWith(repoShort, ignoreCase = true) && stem.length > repoShort.length) {
          stem.substring(repoShort.length).trimStart('_', '-', '.')
        } else {
          stem
        }
      val displayName = if (shelfFiles.size > 1) "$repoShort · $label" else repoShort
      val name = "lc.${safeName(repoShort)}.${safeName(stem)}"
      val version = files.sha256s[file]?.take(16) ?: repo.sha.take(16)

      val samplerText =
        if (topK != null || topP != null || temperature != null) {
          val parts = buildList {
            topK?.let { add("top-k $it") }
            topP?.let { add("top-p $it") }
            temperature?.let { add("temperature $it") }
          }
          "Sampler: " + parts.joinToString(", ") +
            (if (samplerSource != null) " (from $samplerSource)" else "") +
            "; the rest is the app's default."
        } else if (defaultsSource != DefaultsSource.APP) {
          "Sampler: the app's default (${defaultsSource.label} names none)."
        } else {
          "Sampler: the app's default."
        }
      val info = buildString {
        if (newer) {
          append(
            "**Needs a newer Model Shelf:** this file asks for LiteRT-LM $minRuntime and the app " +
              "has $LITERT_LM_RUNTIME_VERSION. Update the app before you download it.\n\n"
          )
        }
        append("From [${repo.id}](https://huggingface.co/${repo.id}), file `$file`.\n\n")
        append("Defaults from ${defaultsSource.label}. ")
        append("Accelerators: ${accelerators.joinToString(", ") { it.uppercase() }}. ")
        append(samplerText)
        if (stateful) {
          append(
            "\n\nKnown issue: this model keeps state between conversations on one engine; " +
              "reload the model for a clean conversation."
          )
        }
      }
      val note =
        if (newer) "Needs a newer app: LiteRT-LM $minRuntime (this app has $LITERT_LM_RUNTIME_VERSION)"
        else "Defaults from ${defaultsSource.label}"

      val model =
        AllowedModel(
          name = name,
          modelId = repo.id,
          modelFile = file,
          commitHash = version,
          description = info,
          sizeInBytes = size,
          defaultConfig =
            DefaultConfig(
              topK = topK,
              topP = topP,
              temperature = temperature,
              accelerators = accelerators.joinToString(","),
              visionAccelerator = null,
              audioAccelerator = null,
              maxContextLength = sliderMax,
              maxTokens = maxTokens,
            ),
          taskTypes = listOf(BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_PROMPT_LAB),
          llmSupportImage = vision,
          llmSupportAudio = audio,
          capabilities = if (thinking) listOf(ModelCapability.LLM_THINKING) else null,
          url = "$HF_BASE_URL/${repo.id}/resolve/${repo.sha}/$file?download=true",
          learnMoreUrl = "https://huggingface.co/${repo.id}",
          displayName = displayName,
        )
      out.add(Triple(model, note, newer))
    }
    return out
  }

  /**
   * Decision 3: android's recommended backend first, else default_backend, then the rest. When a
   * file has several android rows, the row whose device_class names this device (its Build.MODEL,
   * e.g. "Pixel 11 Pro", or its SoC, e.g. "SM8850") wins; otherwise the first android row.
   */
  private fun acceleratorOrder(variant: JsonObject?): List<String> {
    val known = setOf("cpu", "gpu", "npu")
    val androidRows =
      variant?.arr("recommended")?.objects()?.filter {
        it.str("platform").equals("android", ignoreCase = true)
      } ?: emptyList()
    val deviceModel = (Build.MODEL ?: "").lowercase()
    val thisDevice =
      androidRows.firstOrNull { row ->
        val deviceClass = row.str("device_class")?.lowercase() ?: ""
        (deviceModel.isNotEmpty() && deviceClass.contains(deviceModel)) ||
          (SOC.isNotEmpty() && deviceClass.contains(SOC))
      }
    val recommended = (thisDevice ?: androidRows.firstOrNull())?.str("backend")
    val first = (recommended ?: variant?.str("default_backend"))?.lowercase()
    val order = mutableListOf<String>()
    if (first != null && first in known) order.add(first)
    variant?.arr("backends")?.strings()?.forEach { b ->
      val lower = b.lowercase()
      if (lower in known && lower !in order) order.add(lower)
    }
    return order.ifEmpty { listOf("cpu", "gpu") }
  }

  private fun parseList(text: String): List<ListRepo>? {
    val root =
      try {
        JsonParser.parseString(text)
      } catch (e: Exception) {
        Log.e(TAG, "Unreadable shelf list", e)
        return null
      }
    if (!root.isJsonArray) return null
    return root.asJsonArray.objects().mapNotNull { obj ->
      val id = obj.str("id") ?: return@mapNotNull null
      val sha = obj.str("sha") ?: return@mapNotNull null
      val gatedElement = obj.get("gated")
      val gated =
        when {
          gatedElement == null || gatedElement.isJsonNull -> false
          gatedElement.isJsonPrimitive && gatedElement.asJsonPrimitive.isBoolean ->
            gatedElement.asBoolean
          gatedElement.isJsonPrimitive -> gatedElement.asString != "false"
          else -> true
        }
      val cardData = obj.obj("cardData")
      ListRepo(
        id = id,
        sha = sha,
        gated = gated,
        pipelineTag = cardData?.str("pipeline_tag"),
        block = cardData?.obj("litert_lm"),
        files = obj.arr("siblings")?.objects()?.mapNotNull { it.str("rfilename") } ?: emptyList(),
      )
    }
  }

  private fun cachedOrFetched(file: File, url: String, online: Boolean): String? {
    readText(file)?.let {
      return it
    }
    if (!online) return null
    val result = httpGet(url)
    val body = result.body ?: return null
    writeText(file, body)
    return body
  }

  private fun repoCacheFile(repo: ListRepo, kind: String): File =
    File(File(cacheDir, REPO_DIR), "${safeName(repo.id)}@${repo.sha}.$kind.json")

  /** Removes saved per-repo files of commits that are no longer listed. */
  private fun pruneRepoFiles(repos: List<ListRepo>) {
    val keep = repos.map { "${safeName(it.id)}@${it.sha}." }
    File(cacheDir, REPO_DIR).listFiles()?.forEach { f ->
      if (keep.none { f.name.startsWith(it) }) f.delete()
    }
  }

  private fun readSavedAt(): Long =
    readText(File(cacheDir, META_FILE))?.let { parseObject(it)?.long("savedAtMs") } ?: 0L

  private fun readBaseUrlOverride(): String? {
    if (!BuildConfig.DEBUG) return null
    val candidates =
      listOfNotNull(
        File(LIVE_LIST_BASE_URL_OVERRIDE_FILE),
        cacheDir.parentFile?.let { File(it, BASE_URL_OVERRIDE_NAME) },
      )
    for (f in candidates) {
      val url =
        try {
          if (f.canRead()) f.readText().lineSequence().firstOrNull()?.trim()?.trimEnd('/') else null
        } catch (e: Exception) {
          null
        }
      if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
        Log.w(TAG, "Using the test base URL $url from ${f.absolutePath}")
        return url
      }
    }
    return null
  }

  private fun httpGet(url: String): HttpResult {
    return try {
      val connection = URL(url).openConnection() as HttpURLConnection
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      connection.requestMethod = "GET"
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("User-Agent", userAgent)
      try {
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_OK) {
          HttpResult(body = connection.inputStream.bufferedReader().use { it.readText() }, error = null)
        } else {
          Log.w(TAG, "HTTP $code for $url")
          HttpResult(body = null, error = "HTTP $code")
        }
      } finally {
        connection.disconnect()
      }
    } catch (e: IOException) {
      Log.w(TAG, "No answer for $url: $e")
      HttpResult(body = null, error = "offline or unreachable: ${e.javaClass.simpleName}")
    } catch (e: Exception) {
      Log.w(TAG, "Request failed for $url: $e")
      HttpResult(body = null, error = e.javaClass.simpleName)
    }
  }
}

private fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun readText(file: File): String? =
  try {
    if (file.isFile) file.readText() else null
  } catch (e: Exception) {
    Log.w(TAG, "Could not read ${file.absolutePath}: $e")
    null
  }

private fun writeText(file: File, text: String) {
  try {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
      file.delete()
      tmp.renameTo(file)
    }
  } catch (e: Exception) {
    Log.w(TAG, "Could not write ${file.absolutePath}: $e")
  }
}

private fun parseObject(text: String): JsonObject? =
  try {
    JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
  } catch (e: Exception) {
    null
  }

private fun JsonObject.member(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }

private fun JsonObject.str(key: String): String? =
  member(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.obj(key: String): JsonObject? =
  member(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String): JsonArray? =
  member(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.number(key: String): Number? {
  val p = member(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
  return if (p.isNumber) p.asNumber else p.asString.toDoubleOrNull()
}

private fun JsonObject.int(key: String): Int? = number(key)?.toInt()

private fun JsonObject.long(key: String): Long? = number(key)?.toLong()

private fun JsonObject.float(key: String): Float? = number(key)?.toFloat()

private fun JsonObject.bool(key: String): Boolean? {
  val p = member(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
  return if (p.isBoolean) p.asBoolean else p.asString.toBooleanStrictOrNull()
}

private fun JsonArray.objects(): List<JsonObject> = mapNotNull {
  it.takeIf { e -> e.isJsonObject }?.asJsonObject
}

private fun JsonArray.strings(): List<String> = mapNotNull {
  it.takeIf { e -> e.isJsonPrimitive }?.asString
}
