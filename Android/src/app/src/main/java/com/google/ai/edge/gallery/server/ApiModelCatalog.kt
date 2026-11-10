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
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.data.AllowedModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import com.google.ai.edge.gallery.huggingface.LiveListClient
import com.google.ai.edge.gallery.huggingface.ShelfApiMeta
import com.google.ai.edge.gallery.services.tts.MediaPipeTtsModels
import com.google.ai.edge.gallery.services.tts.TtsModelRegistry
import com.google.ai.edge.gallery.speech.PhononFiles
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "MSApiCatalog"

/** The allowlist copy the app saves after each fetch (ModelManagerViewModel). */
private const val ALLOWLIST_FILE = "model_allowlist.json"

/** The test allowlist the app reads first when it exists (ModelManagerViewModel). */
private const val ALLOWLIST_TEST_FILE = "/data/local/tmp/model_allowlist_test.json"

/** A list built less than this long ago is reused. */
private const val FRESH_FOR_MS = 15_000L

/**
 * The models the local API can serve: the LLM and speech files on this phone that Google's model
 * list or the shelf names, read from the copies the app saved (the API never fetches a list or a
 * file; downloads stay in the app), and Phonon-2.5-English when its files are in the app's model
 * folder.
 */
class ApiModelCatalog(private val context: Context) {
  private val lock = Mutex()
  private var lists = Lists(emptyList(), emptyList(), emptyList())
  private var builtAtMs = 0L

  private class Lists(val llm: List<ApiModelEntry>, val asr: List<AsrEntry>, val tts: List<TtsEntry>)

  /** The chat models on this phone, rebuilt when [refresh] or when the last list is old. */
  suspend fun entries(refresh: Boolean = false): List<ApiModelEntry> = lists(refresh).llm

  /** The speech recognition models on this phone, as [entries]. */
  suspend fun asrEntries(refresh: Boolean = false): List<AsrEntry> = lists(refresh).asr

  /** The text-to-speech models on this phone, as [entries]. */
  suspend fun ttsEntries(refresh: Boolean = false): List<TtsEntry> = lists(refresh).tts

  private suspend fun lists(refresh: Boolean): Lists =
    lock.withLock {
      val now = SystemClock.elapsedRealtime()
      if (refresh || builtAtMs == 0L || now - builtAtMs > FRESH_FOR_MS) {
        try {
          lists = withContext(Dispatchers.IO) { build() }
        } catch (e: Exception) {
          Log.e(TAG, "Could not read the model lists", e)
        }
        builtAtMs = now
      }
      lists
    }

  /** The text-to-speech model a request names, or null; a miss reads the lists again once. */
  suspend fun findTts(requested: String): TtsEntry? {
    val hit = matchModel(requested, ttsEntries(), { it.name }, { it.name })
    if (hit != null) return hit
    return matchModel(requested, ttsEntries(refresh = true), { it.name }, { it.name })
  }

  /** The speech model a request names, or null; a miss reads the lists again once. */
  suspend fun findAsr(requested: String): AsrEntry? {
    val hit = matchModel(requested, asrEntries(), { it.name }, { it.name })
    if (hit != null) return hit
    return matchModel(requested, asrEntries(refresh = true), { it.name }, { it.name })
  }

  /** The model a request names, or null; a miss reads the lists again once. */
  suspend fun find(requested: String): ApiModelEntry? {
    val hit = matchModel(requested, entries(), { it.name }, { it.repoId })
    if (hit != null) return hit
    return matchModel(requested, entries(refresh = true), { it.name }, { it.repoId })
  }

  private suspend fun build(): Lists {
    val modelsDir = getModelStorageDir(context)
    val out = mutableListOf<ApiModelEntry>()
    val asr = mutableListOf<AsrEntry>()
    val tts = mutableListOf<TtsEntry>()
    val ttsModels = mutableListOf<Model>()
    val seenFiles = mutableSetOf<Pair<String, String>>()

    // 1. Google's model list, as the app last saved it (the test file first, as the app does).
    val allowlistFile =
      listOf(File(ALLOWLIST_TEST_FILE), File(modelsDir, ALLOWLIST_FILE)).firstOrNull {
        try {
          it.isFile && it.canRead()
        } catch (e: SecurityException) {
          false
        }
      }
    val allowlist =
      allowlistFile?.let {
        try {
          Gson().fromJson(it.readText(), ModelAllowlist::class.java)
        } catch (e: Exception) {
          Log.w(TAG, "Unreadable model list ${it.absolutePath}: $e")
          null
        }
      }
    val listName = "Google's model list (model_allowlists/${BuildConfig.VERSION_NAME.replace('.', '_')}.json)"
    for (allowed in allowlist?.models.orEmpty()) {
      if (allowed.disabled == true) continue
      val speech = toAsrEntry(allowed, listName)
      if (speech != null) {
        asr.add(speech)
        continue
      }
      val voice = toTtsEntry(allowed, listName)
      if (voice != null) {
        tts.add(voice.first)
        ttsModels.add(voice.second)
        continue
      }
      val entry =
        toEntry(
          allowed = allowed,
          listedBy = listName,
          defaults =
            SamplerDefaults(
              topK = allowed.defaultConfig?.topK,
              topP = allowed.defaultConfig?.topP?.toCleanDouble(),
              temperature = allowed.defaultConfig?.temperature?.toCleanDouble(),
              source = listName,
            ),
          meta = null,
        ) ?: continue
      seenFiles.add(allowed.modelId to allowed.modelFile)
      out.add(entry)
    }

    // 2. The shelf (litert-community on Hugging Face), from the copy saved on the device.
    val shelf = LiveListClient(File(context.filesDir, "live_list")).load(networkAllowed = false)
    for (allowed in shelf.models) {
      if ((allowed.modelId to allowed.modelFile) in seenFiles) continue
      val meta = shelf.api[allowed.name]
      val entry =
        toEntry(
          allowed = allowed,
          listedBy = "the shelf (litert-community on Hugging Face)",
          defaults =
            SamplerDefaults(
              topK = meta?.topK,
              topP = meta?.topP?.toCleanDouble(),
              temperature = meta?.temperature?.toCleanDouble(),
              source = meta?.samplerSource ?: APP_DEFAULT_SOURCE,
            ),
          meta = meta,
        ) ?: continue
      seenFiles.add(allowed.modelId to allowed.modelFile)
      out.add(entry)
    }

    // 3. Phonon-2.5-English: its files in the app's model folder (no model list names it yet).
    PhononFiles.present(context)
      ?.takeIf { it.usable().isNotEmpty() }
      ?.let { p ->
        asr.add(
          AsrEntry(
            name = PhononFiles.REPO,
            kind = AsrKind.PHONON,
            path = p.dir.absolutePath,
            sizeBytes = p.sizeBytes,
            commit = PhononFiles.COMMIT,
            languages = listOf("en-US"),
            modifiedMs = File(p.dir, PhononFiles.TOKENIZER).lastModified(),
            listedBy = "its files in the app's model folder",
          )
        )
      }

    // Two files may give the same API name (same repo and stem from both lists): keep the first.
    val unique = out.distinctBy { it.name }
    val uniqueAsr = asr.distinctBy { it.name }
    val uniqueTts = tts.distinctBy { it.name }
    registerVoiceModels(ttsModels)
    Log.i(
      TAG,
      "Models for the local API: ${unique.size} chat models on this phone " +
        "(${unique.joinToString { it.name }}), ${uniqueAsr.size} speech models " +
        "(${uniqueAsr.joinToString { it.name }}), ${uniqueTts.size} text-to-speech models " +
        "(${uniqueTts.joinToString { it.name }}); lists read: " +
        "${allowlistFile?.name ?: "no Google model list saved"}, shelf ${shelf.source}",
    )
    return Lists(unique, uniqueAsr, uniqueTts)
  }

  /**
   * A text-to-speech model of Google's list that the app's MediaPipe TTS engine runs, when its file
   * is on the phone; with the list's model, which the engine finds the file by.
   */
  private fun toTtsEntry(allowed: AllowedModel, listedBy: String): Pair<TtsEntry, Model>? {
    val model = allowed.toModel() ?: return null
    if (model.modelType != ModelType.TTS || model.backendSpec.runtimeType != RuntimeType.LITERT_LM) {
      return null
    }
    val profile = model.ttsProfile ?: return null
    val spec = MediaPipeTtsModels.MODEL_SPECS.firstOrNull { it.engineModelId == profile.engineModelId } ?: return null
    val path = model.getPath(context)
    val file = File(path)
    if (!file.isFile || File("$path.$TMP_FILE_EXT").exists()) return null
    val expected = model.downloadInfo.sizeInBytes
    if (expected > 0 && file.length() != expected) {
      Log.w(TAG, "Skipping ${file.name}: ${file.length()} B on the phone, the list says $expected B")
      return null
    }
    val voices = profile.voices.map { it.id }.ifEmpty { spec.defaultVoiceIds }
    val default = profile.defaultVoiceId?.takeIf { it in voices } ?: voices.first()
    return TtsEntry(
      name = allowed.modelId,
      engineModelId = profile.engineModelId,
      path = path,
      sizeBytes = file.length(),
      commit = model.downloadInfo.version,
      voices = listOf(default) + voices.filter { it != default },
      defaultVoice = default,
      languages = profile.languages.ifEmpty { spec.defaultLanguages },
      modifiedMs = file.lastModified(),
      listedBy = listedBy,
    ) to model
  }

  /**
   * The app's TTS engine finds a model's file through [TtsModelRegistry], which the app fills when
   * its model list loads. When the local API runs before that (the process started for the connect
   * screen), the voice models on the phone are added here.
   */
  private fun registerVoiceModels(models: List<Model>) {
    if (models.isEmpty()) return
    val registered = TtsModelRegistry.registeredAllowlistModels
    val missing =
      models.filter { m ->
        registered.none { it.ttsProfile?.engineModelId == m.ttsProfile?.engineModelId }
      }
    if (missing.isEmpty()) return
    TtsModelRegistry.registerAllowlistModels(registered + missing)
    Log.i(TAG, "Registered for the voice engine: ${missing.joinToString { it.name }}")
  }

  /** A speech model of Google's list (MediaPipe's AudioTranscriber) whose file is on the phone. */
  private fun toAsrEntry(allowed: AllowedModel, listedBy: String): AsrEntry? {
    val model = allowed.toModel() ?: return null
    if (model.modelType != ModelType.ASR || model.backendSpec.runtimeType != RuntimeType.LITERT_LM) {
      return null
    }
    val path = model.getPath(context)
    val file = File(path)
    if (!file.isFile || File("$path.$TMP_FILE_EXT").exists()) return null
    val expected = model.downloadInfo.sizeInBytes
    if (expected > 0 && file.length() != expected) {
      Log.w(TAG, "Skipping ${file.name}: ${file.length()} B on the phone, the list says $expected B")
      return null
    }
    return AsrEntry(
      name = allowed.modelId,
      kind = AsrKind.MEDIAPIPE,
      path = path,
      sizeBytes = file.length(),
      commit = model.downloadInfo.version,
      languages = model.asrProfile?.languages.orEmpty(),
      modifiedMs = file.lastModified(),
      listedBy = listedBy,
    )
  }

  private fun toEntry(
    allowed: AllowedModel,
    listedBy: String,
    defaults: SamplerDefaults,
    meta: ShelfApiMeta?,
  ): ApiModelEntry? {
    val model = allowed.toModel() ?: return null
    if (!model.isLlm || model.backendSpec.runtimeType != RuntimeType.LITERT_LM) return null
    val path = model.getPath(context)
    val file = File(path)
    if (!file.isFile) return null
    if (File("$path.$TMP_FILE_EXT").exists()) return null
    val expected = model.downloadInfo.sizeInBytes
    if (expected > 0 && file.length() != expected) {
      Log.w(TAG, "Skipping ${file.name}: ${file.length()} B on the phone, the list says $expected B")
      return null
    }
    val stem = file.name.removeSuffix(".litertlm")
    val accelerators = model.backendSpec.accelerators.map { it.label.lowercase() }
    return ApiModelEntry(
      name = "${allowed.modelId}:$stem",
      repoId = allowed.modelId,
      file = file.name,
      path = path,
      sizeBytes = file.length(),
      digest = meta?.sha256 ?: model.downloadInfo.version,
      commit = model.downloadInfo.version,
      modifiedMs = file.lastModified(),
      accelerators = accelerators.ifEmpty { listOf("gpu", "cpu") },
      maxNumTokens = model.llmProfile?.maxTokens ?: allowed.defaultConfig?.maxTokens ?: 1024,
      contextLength = meta?.contextLength ?: allowed.defaultConfig?.maxContextLength,
      sampler = defaults,
      stateful = meta?.stateful == true,
      statefulWhy = meta?.statefulWhy,
      requestTimeoutS = meta?.requestTimeoutS,
      maxOutputTokensMin = meta?.maxOutputTokensMin,
      listedBy = listedBy,
      checked = meta?.checked,
    )
  }
}
