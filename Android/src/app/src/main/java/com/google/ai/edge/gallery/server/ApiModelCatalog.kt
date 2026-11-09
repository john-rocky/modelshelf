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
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import com.google.ai.edge.gallery.huggingface.LiveListClient
import com.google.ai.edge.gallery.huggingface.ShelfApiMeta
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MSApiCatalog"

/** The allowlist copy the app saves after each fetch (ModelManagerViewModel). */
private const val ALLOWLIST_FILE = "model_allowlist.json"

/** The test allowlist the app reads first when it exists (ModelManagerViewModel). */
private const val ALLOWLIST_TEST_FILE = "/data/local/tmp/model_allowlist_test.json"

/** A list built less than this long ago is reused. */
private const val FRESH_FOR_MS = 15_000L

/**
 * The models the local API can serve: the LLM files on this phone that Google's model list or the
 * shelf names, read from the copies the app saved (the API never fetches a list or a file;
 * downloads stay in the app, DESIGN §g-3 row 4).
 */
class ApiModelCatalog(private val context: Context) {
  private val lock = Mutex()
  private var cached: List<ApiModelEntry> = emptyList()
  private var builtAtMs = 0L

  /** The models on this phone, rebuilt when [refresh] or when the last list is old. */
  suspend fun entries(refresh: Boolean = false): List<ApiModelEntry> =
    lock.withLock {
      val now = SystemClock.elapsedRealtime()
      if (refresh || builtAtMs == 0L || now - builtAtMs > FRESH_FOR_MS) {
        cached =
          try {
            build()
          } catch (e: Exception) {
            Log.e(TAG, "Could not read the model lists", e)
            cached
          }
        builtAtMs = now
      }
      cached
    }

  /** The model a request names, or null; a miss reads the lists again once. */
  suspend fun find(requested: String): ApiModelEntry? {
    val hit = matchModel(requested, entries(), { it.name }, { it.repoId })
    if (hit != null) return hit
    return matchModel(requested, entries(refresh = true), { it.name }, { it.repoId })
  }

  private suspend fun build(): List<ApiModelEntry> {
    val modelsDir = getModelStorageDir(context)
    val out = mutableListOf<ApiModelEntry>()
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

    // Two files may give the same API name (same repo and stem from both lists): keep the first.
    val unique = out.distinctBy { it.name }
    Log.i(
      TAG,
      "Models for the local API: ${unique.size} on this phone " +
        "(${unique.joinToString { it.name }}); lists read: " +
        "${allowlistFile?.name ?: "no Google model list saved"}, shelf ${shelf.source}",
    )
    return unique
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
