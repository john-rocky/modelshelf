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
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
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
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/*
 * The shelf: litert-community's LiteRT-LM repos on Hugging Face, listed live.
 *
 * One list call returns every repo with its card data and file names. Each file's defaults come
 * from, in this order:
 * 1. the card's `litert_lm:` block (YAML front matter, top-level key),
 * 2. the repo's `litertlm_manifest.json` at the listed commit, while the card has no block. The
 *    block copies the manifest's names and nesting (`model.*` at the top, `variants[]` as is), so
 *    one reader serves both,
 * 3. the app's defaults (Gallery's sampler, CPU then GPU).
 * Rules decided by the D-614 window (2026-11-08):
 * - the first accelerator is the backend of the variant's `recommended[]` row for this phone
 *   (android, `soc` = Build.SOC_MODEL), else its `default_backend`, then the rest of `backends`,
 * - a file whose `min_runtime_version` is newer than the app's LiteRT-LM stays listed and says
 *   that it needs an app update,
 * - sampler values are taken as written; `session_defaults.source` names where they came from,
 * - the main shelf lists a file only after a phone check with its sampler (a `checked[]` row for
 *   android, at least 3 runs, the sampler the app will use); other files are folded below it with
 *   the reason, and gated repos are folded with a link to their page (no download).
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
/** A `checked[]` row puts a file on the main shelf with at least this many runs. */
private const val MIN_CHECK_RUNS = 3
private const val SAMPLER_TOLERANCE = 1e-4f

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

/** Why a shelf file is folded below the main shelf instead of listed in it. */
enum class FoldReason(val badge: String) {
  /** No android `checked[]` row with [MIN_CHECK_RUNS] runs and the sampler the app will use. */
  NOT_CHECKED("Not checked on a phone"),
  /** The model keeps state between conversations on one engine (`stateful`). */
  STATE_MODEL("State model"),
}

/** A gated repo: listed with a link to its page; this app cannot download it (no sign-in). */
data class GatedRepo(val id: String, val url: String)

/**
 * What the local API needs about a shelf file besides its list entry: the
 * sampler values as written with their source, whether the model keeps state, and the limits.
 */
data class ShelfApiMeta(
  val topK: Int?,
  val topP: Float?,
  val temperature: Float?,
  /** Where the sampler values come from, e.g. "the model card (session_defaults, from readme)". */
  val samplerSource: String,
  val stateful: Boolean,
  /** Why the file counts as stateful; null when it does not. */
  val statefulWhy: String?,
  val requestTimeoutS: Int?,
  val maxOutputTokensMin: Int?,
  val contextLength: Int?,
  val sha256: String?,
  /** The phone check that put the file on the main shelf, or null. */
  val checked: String?,
)

/** The result of one shelf load. */
data class LiveListResult(
  val models: List<AllowedModel> = emptyList(),
  /** Per model name: what the local API reads about the file. */
  val api: Map<String, ShelfApiMeta> = emptyMap(),
  /** Per model name: the line shown under that model in the list. */
  val notes: Map<String, String> = emptyMap(),
  /** Per model name of a folded file: why it is folded. Files not in it are on the main shelf. */
  val folded: Map<String, FoldReason> = emptyMap(),
  /** Gated repos, shown folded with a link; no download. */
  val gatedRepos: List<GatedRepo> = emptyList(),
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
  /** Shelf files folded below the main shelf (not checked on a phone, or a state model). */
  val foldedNames: Set<String> = emptySet(),
  val gatedRepos: List<GatedRepo> = emptyList(),
  val status: String = "",
)

/** One line for the top of the shelf section. */
fun LiveListResult.statusLine(): String {
  val time =
    if (savedAtMs > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(savedAtMs))
    else ""
  val counts = "${models.size} files from $repoCount repos"
  val foldedCount = models.count { it.name in folded }
  val extras = buildList {
    add("${models.size - foldedCount} checked on a phone")
    if (foldedCount > 0) add("$foldedCount folded below")
    if (gatedRepos.isNotEmpty()) add("${gatedRepos.size} gated repos folded below")
    if (needsUpdateCount > 0) add("$needsUpdateCount need a newer app")
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

  /**
   * Loads the shelf. With [networkAllowed] false (the local API) only the copy saved on the device
   * is read and nothing is fetched or saved; the cache folder is still created when it is missing.
   */
  suspend fun load(networkAllowed: Boolean = true): LiveListResult =
    withContext(Dispatchers.IO) {
      cacheDir.mkdirs()
      val listUrl =
        "$apiBaseUrl/api/models?author=$LIVE_LIST_AUTHOR&filter=litert-lm&limit=1000" +
          "&expand=cardData&expand=sha&expand=siblings&expand=lastModified&expand=downloads" +
          "&expand=gated"
      if (networkAllowed) Log.d(TAG, "Loading the shelf: $listUrl")
      val fetched =
        if (networkAllowed) httpGet(listUrl)
        else HttpResult(body = null, error = "saved copy only")
      var networkError: String? = fetched.error
      var repos: List<ListRepo>? = fetched.body?.let { parseList(it) }
      if (fetched.body != null && repos == null) {
        networkError = "unreadable list"
      }
      val online = repos != null
      var savedAtMs = System.currentTimeMillis()
      if (!online) {
        if (networkAllowed) Log.w(TAG, "Shelf list not fetched ($networkError); reading the saved copy")
        repos = readText(File(cacheDir, LIST_FILE))?.let { parseList(it) }
        savedAtMs = readSavedAt()
        if (repos == null) {
          return@withContext LiveListResult(
            source = LiveListSource.NONE,
            networkError = networkError,
          )
        }
      }

      // The recommended[] rows are matched by this value (decision 1 of 15:4x).
      Log.i(
        TAG,
        "Shelf: this phone's Build.SOC_MODEL is '${Build.SOC_MODEL}' (matched as '$SOC'), " +
          "Build.MODEL '${Build.MODEL}'",
      )
      val shelfRepos = repos!!.filter { isShelfRepo(it) }
      val gatedRepos =
        shelfRepos.filter { it.gated }.map { GatedRepo(id = it.id, url = "$HF_BASE_URL/${it.id}") }
      val usable = shelfRepos.filter { !it.gated }
      val repoFiles = loadRepoFiles(usable, online)

      val models = mutableListOf<AllowedModel>()
      val api = mutableMapOf<String, ShelfApiMeta>()
      val notes = mutableMapOf<String, String>()
      val folded = mutableMapOf<String, FoldReason>()
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
        for (entry in converted) {
          models.add(entry.model)
          api[entry.model.name] = entry.api
          notes[entry.model.name] = entry.note
          entry.fold?.let { folded[entry.model.name] = it }
          if (entry.newer) needsUpdate++
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
          ", main ${models.size - folded.size}, folded ${folded.size}, gated ${gatedRepos.size}" +
          ", skipped $skipped, needs a newer app $needsUpdate",
      )
      LiveListResult(
        models = models,
        api = api,
        notes = notes,
        folded = folded,
        gatedRepos = gatedRepos,
        source = if (online) LiveListSource.NETWORK else LiveListSource.CACHE,
        savedAtMs = savedAtMs,
        networkError = if (online) null else networkError,
        repoCount = shown,
        gatedRepoCount = gatedRepos.size,
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

  /** One shelf file: the model, the line under it, needs a newer app, why it is folded (or null). */
  private data class ShelfEntry(
    val model: AllowedModel,
    val api: ShelfApiMeta,
    val note: String,
    val newer: Boolean,
    val fold: FoldReason?,
  )

  /** One shelf entry per file the device can use. */
  private fun convertRepo(repo: ListRepo, files: RepoFiles): List<ShelfEntry> {
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

    val out = mutableListOf<ShelfEntry>()
    for (file in shelfFiles) {
      val size = files.sizes[file] ?: continue
      val v = variantByFile[file]
      val (accelerators, acceleratorWhy) = acceleratorOrder(v)
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
      val statefulWhy =
        when {
          modelLevel.bool("stateful") == true -> "${defaultsSource.label} says stateful: true"
          (v?.arr("known_issues")?.strings() ?: emptyList()).any {
            it.contains("new Engine for each conversation", ignoreCase = true)
          } ->
            "${defaultsSource.label} known_issues: \"Create a new Engine for each conversation\""
          else -> null
        }
      val stateful = statefulWhy != null
      val check = qualifyingCheck(v, topK, topP, temperature)
      val fold =
        when {
          stateful -> FoldReason.STATE_MODEL
          check == null -> FoldReason.NOT_CHECKED
          else -> null
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
        if (check != null) {
          append(
            "\n\nChecked on a phone with this sampler: ${checkText(check)}, LiteRT-LM " +
              "${check.str("runtime") ?: "?"}, ${check.str("date") ?: "no date"}."
          )
        } else {
          append(
            "\n\nNot checked on a phone with this sampler yet, so it is folded below the main " +
              "shelf."
          )
        }
        if (stateful) {
          append(
            "\n\nKnown issue: this model keeps state between conversations on one engine; " +
              "reload the model for a clean conversation."
          )
        }
      }
      val note =
        listOfNotNull(
            if (newer) {
              "Needs a newer app: LiteRT-LM $minRuntime (this app has $LITERT_LM_RUNTIME_VERSION)"
            } else {
              null
            },
            fold?.badge ?: check?.let { "Checked on ${checkText(it)}" },
            "Defaults from ${defaultsSource.label}",
          )
          .joinToString(" · ")

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
      Log.d(
        TAG,
        "Shelf file '$name' (${defaultsSource.label}): accelerators $accelerators " +
          "($acceleratorWhy), sampler top-k ${topK ?: "app"} top-p ${topP ?: "app"} " +
          "temperature ${temperature ?: "app"}, " +
          (fold?.let { "folded: ${it.badge}" } ?: "main shelf: checked on ${checkText(check!!)}"),
      )
      val api =
        ShelfApiMeta(
          topK = topK,
          topP = topP,
          temperature = temperature,
          samplerSource =
            "${defaultsSource.label} (session_defaults" +
              (if (samplerSource != null) ", from $samplerSource" else "") +
              ")",
          stateful = stateful,
          statefulWhy = statefulWhy,
          requestTimeoutS = sessionDefaults?.int("request_timeout_s"),
          maxOutputTokensMin = sessionDefaults?.int("max_output_tokens_min"),
          contextLength = context,
          sha256 = files.sha256s[file],
          checked = check?.let { checkText(it) },
        )
      out.add(ShelfEntry(model = model, api = api, note = note, newer = newer, fold = fold))
    }
    return out
  }

  /**
   * The first `checked[]` row that puts a file on the main shelf: android, at
   * least [MIN_CHECK_RUNS] runs, and the sampler the app will use for this file (the card's or
   * manifest's session_defaults, the app's defaults for a key they do not name). A row without a
   * sampler says nothing about one and does not count.
   */
  private fun qualifyingCheck(
    variant: JsonObject?,
    topK: Int?,
    topP: Float?,
    temperature: Float?,
  ): JsonObject? {
    val useK = topK ?: DEFAULT_TOPK
    val useP = topP ?: DEFAULT_TOPP
    val useT = temperature ?: DEFAULT_TEMPERATURE
    return variant?.arr("checked")?.objects()?.firstOrNull { row ->
      row.str("os").equals("android", ignoreCase = true) &&
        (row.int("runs") ?: 0) >= MIN_CHECK_RUNS &&
        row.int("top_k") == useK &&
        row.float("top_p")?.let { abs(it - useP) < SAMPLER_TOLERANCE } == true &&
        row.float("temperature")?.let { abs(it - useT) < SAMPLER_TOLERANCE } == true
    }
  }

  private fun checkText(row: JsonObject): String =
    "${row.str("device") ?: "a phone"} (${(row.str("backend") ?: "?").uppercase()}, " +
      "${row.int("runs") ?: 0} runs)"

  /**
   * The accelerator order of one file and why (decision 3 of 13:4x, decision 1 of 15:4x): the
   * backend of the file's `recommended[]` row for this phone first, else `default_backend`, then
   * the rest of `backends`. The row for this phone is the android row whose `soc` equals
   * Build.SOC_MODEL (case ignored). A file with one android row that names no `soc` takes that
   * row. Several android rows and none for this SoC (or one row for another SoC) = the rows are
   * about other phones: `default_backend`, not the first row. `device_class` is text for people
   * and is not read.
   */
  private fun acceleratorOrder(variant: JsonObject?): Pair<List<String>, String> {
    val known = setOf("cpu", "gpu", "npu")
    val androidRows =
      variant?.arr("recommended")?.objects()?.filter {
        it.str("platform").equals("android", ignoreCase = true)
      } ?: emptyList()
    val socRow =
      if (SOC.isEmpty()) null
      else androidRows.firstOrNull { it.str("soc")?.trim()?.lowercase() == SOC }
    val defaultBackend = variant?.str("default_backend")
    val (first, why) =
      when {
        socRow != null ->
          socRow.str("backend") to "recommended row for soc ${socRow.str("soc")}"
        androidRows.size == 1 && androidRows[0].str("soc").isNullOrBlank() ->
          androidRows[0].str("backend") to "the one android recommended row"
        androidRows.isNotEmpty() ->
          defaultBackend to
            "default_backend $defaultBackend: ${androidRows.size} android rows, none for soc '$SOC'"
        else -> defaultBackend to "default_backend $defaultBackend: no android row"
      }
    val order = mutableListOf<String>()
    val firstLower = first?.lowercase()
    if (firstLower != null && firstLower in known) order.add(firstLower)
    variant?.arr("backends")?.strings()?.forEach { b ->
      val lower = b.lowercase()
      if (lower in known && lower !in order) order.add(lower)
    }
    if (order.isEmpty()) return listOf("cpu", "gpu") to "no backends named: the app's CPU, GPU"
    return order to why
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
