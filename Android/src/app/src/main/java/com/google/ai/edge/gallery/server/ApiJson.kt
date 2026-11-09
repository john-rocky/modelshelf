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

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.time.Instant

/*
 * The response side of the local API: the JSON the Ollama and OpenAI clients read. Field names
 * follow Ollama's docs/api.md and OpenAI's chat completion objects; Model Shelf's own facts (where
 * the sampler came from, whether the model keeps state) go under a "modelshelf" key, which clients
 * ignore. No Android classes, so the unit tests run on the computer.
 */

object ApiJson {
  /** Far in the future: the expiry of a model loaded with keep_alive below 0. */
  private const val FOREVER = "9999-12-31T23:59:59Z"

  fun nowIso(): String = Instant.now().toString()

  fun isoOf(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

  fun ollamaError(message: String): String =
    JsonObject().apply { addProperty("error", message) }.toString()

  fun openAiError(message: String, status: Int, code: String?): String =
    JsonObject()
      .apply {
        add(
          "error",
          JsonObject().apply {
            addProperty("message", message)
            addProperty(
              "type",
              when (status) {
                401 -> "authentication_error"
                404 -> "not_found_error"
                429,
                503 -> "rate_limit_error"
                in 400..499 -> "invalid_request_error"
                else -> "server_error"
              },
            )
            add("param", JsonNull.INSTANCE)
            if (code != null) addProperty("code", code) else add("code", JsonNull.INSTANCE)
          },
        )
      }
      .toString()

  // ---- Ollama -------------------------------------------------------------------------------

  fun ollamaVersion(appVersion: String): String =
    JsonObject()
      .apply {
        addProperty("version", appVersion)
        addProperty("server", "Model Shelf")
      }
      .toString()

  private fun quantizationOf(file: String): String =
    Regex("(int2|int4|int8|q4|q8|f16|fp16|bf16|f32|fp32)", RegexOption.IGNORE_CASE)
      .find(file)
      ?.value
      ?.lowercase() ?: ""

  private fun details(e: ApiModelEntry): JsonObject =
    JsonObject().apply {
      addProperty("parent_model", "")
      addProperty("format", "litertlm")
      val family = e.repoId.substringAfter('/')
      addProperty("family", family)
      add("families", JsonArray().apply { add(family) })
      addProperty("parameter_size", "")
      addProperty("quantization_level", quantizationOf(e.file))
      // The shelf's line for this file: which phone check put it on the main shelf.
      addProperty("checked", e.checked ?: "not checked on a phone")
    }

  private fun shelfFacts(e: ApiModelEntry): JsonObject =
    JsonObject().apply {
      addProperty("repo", e.repoId)
      addProperty("file", e.file)
      addProperty("commit", e.commit)
      addProperty("listed_by", e.listedBy)
      addProperty("stateful", e.stateful)
      if (e.statefulWhy != null) addProperty("stateful_why", e.statefulWhy)
      add("accelerators", JsonArray().apply { e.accelerators.forEach { add(it) } })
      addProperty("max_num_tokens", e.maxNumTokens)
      if (e.contextLength != null) addProperty("context_length", e.contextLength)
      addProperty("request_timeout_s", e.requestTimeoutS ?: DEFAULT_REQUEST_TIMEOUT_S)
      addProperty(
        "request_timeout_source",
        if (e.requestTimeoutS != null) "the model card" else APP_DEFAULT_SOURCE,
      )
      if (e.maxOutputTokensMin != null) addProperty("max_output_tokens_min", e.maxOutputTokensMin)
      addProperty("checked", e.checked ?: "not checked on a phone")
    }

  fun ollamaTags(entries: List<ApiModelEntry>): String =
    JsonObject()
      .apply {
        add(
          "models",
          JsonArray().apply {
            for (e in entries) {
              add(
                JsonObject().apply {
                  addProperty("name", e.name)
                  addProperty("model", e.name)
                  addProperty("modified_at", isoOf(e.modifiedMs))
                  addProperty("size", e.sizeBytes)
                  addProperty("digest", e.digest)
                  add("details", details(e))
                  add("modelshelf", shelfFacts(e))
                }
              )
            }
          },
        )
      }
      .toString()

  /** The model's sampler as /api/show's "parameters" text and as values with their sources. */
  fun ollamaShow(e: ApiModelEntry): String {
    val sampler = resolveSampler(SamplerRequest(), e.sampler, npu = e.firstIsNpu)
    // The most tokens an engine of this API holds (options.num_ctx is capped there), not the
    // model's own context length (that one is modelshelf.context_length).
    val engineTokens = resolveEngineTokens(null, e.maxNumTokens, e.contextLength)
    val parameters =
      if (!sampler.applied) {
        "# the NPU file uses its own sampler; request values are not applied\nnum_ctx $engineTokens"
      } else {
        "top_k ${sampler.topK.value}\ntop_p ${sampler.topP.value}\ntemperature " +
          "${sampler.temperature.value}\nnum_ctx $engineTokens"
      }
    return JsonObject()
      .apply {
        addProperty("license", "")
        addProperty(
          "modelfile",
          "# Model Shelf: ${e.name}\nFROM ${e.repoId}/${e.file}\n" +
            parameters.lines().joinToString("\n") { if (it.startsWith("#")) it else "PARAMETER $it" },
        )
        addProperty("parameters", parameters)
        addProperty("template", "")
        add("details", details(e))
        add(
          "model_info",
          JsonObject().apply {
            addProperty("general.architecture", "litertlm")
            addProperty("general.basename", e.repoId.substringAfter('/'))
            addProperty("litertlm.context_length", engineTokens)
          },
        )
        add("capabilities", JsonArray().apply { add("completion") })
        addProperty("modified_at", isoOf(e.modifiedMs))
        add(
          "modelshelf",
          shelfFacts(e).apply {
            add(
              "sampler",
              JsonObject().apply {
                addProperty("applied", sampler.applied)
                add(
                  "top_k",
                  JsonObject().apply {
                    addProperty("value", sampler.topK.value)
                    addProperty("source", sampler.topK.source)
                  },
                )
                add(
                  "top_p",
                  JsonObject().apply {
                    addProperty("value", sampler.topP.value)
                    addProperty("source", sampler.topP.source)
                  },
                )
                add(
                  "temperature",
                  JsonObject().apply {
                    addProperty("value", sampler.temperature.value)
                    addProperty("source", sampler.temperature.source)
                  },
                )
              },
            )
          },
        )
      }
      .toString()
  }

  fun ollamaPs(loaded: List<Triple<ApiModelEntry, Long, String>>): String =
    JsonObject()
      .apply {
        add(
          "models",
          JsonArray().apply {
            for ((e, expiresAtMs, backend) in loaded) {
              add(
                JsonObject().apply {
                  addProperty("name", e.name)
                  addProperty("model", e.name)
                  addProperty("size", e.sizeBytes)
                  addProperty("digest", e.digest)
                  add("details", details(e))
                  addProperty(
                    "expires_at",
                    if (expiresAtMs == Long.MAX_VALUE) FOREVER else isoOf(expiresAtMs),
                  )
                  addProperty("size_vram", 0)
                  addProperty("context_length", e.maxNumTokens)
                  add("modelshelf", JsonObject().apply { addProperty("backend", backend) })
                }
              )
            }
          },
        )
      }
      .toString()

  /** One NDJSON line of a streamed /api/chat or /api/generate answer. */
  fun ollamaChunk(model: String, text: String, generate: Boolean): String =
    JsonObject()
      .apply {
        addProperty("model", model)
        addProperty("created_at", nowIso())
        if (generate) {
          addProperty("response", text)
        } else {
          add(
            "message",
            JsonObject().apply {
              addProperty("role", "assistant")
              addProperty("content", text)
            },
          )
        }
        addProperty("done", false)
      }
      .toString()

  /** The last line of a streamed answer, or the whole answer when [text] carries the full reply. */
  fun ollamaDone(
    model: String,
    generate: Boolean,
    text: String,
    doneReason: String,
    stats: TurnStats,
  ): String =
    JsonObject()
      .apply {
        addProperty("model", model)
        addProperty("created_at", nowIso())
        if (generate) {
          addProperty("response", text)
        } else {
          add(
            "message",
            JsonObject().apply {
              addProperty("role", "assistant")
              addProperty("content", text)
            },
          )
        }
        addProperty("done", true)
        addProperty("done_reason", doneReason)
        addProperty("total_duration", stats.totalMs * 1_000_000L)
        addProperty("load_duration", stats.loadMs * 1_000_000L)
        stats.promptTokens?.let { addProperty("prompt_eval_count", it) }
        addProperty("prompt_eval_duration", maxOf(0L, stats.firstTextMs) * 1_000_000L)
        addProperty("eval_count", stats.evalChunks)
        addProperty(
          "eval_duration",
          maxOf(0L, stats.inferenceMs - maxOf(0L, stats.firstTextMs)) * 1_000_000L,
        )
      }
      .toString()

  // ---- OpenAI -------------------------------------------------------------------------------

  /**
   * GET /v1/models: the chat models, then the speech models; each says which kind it is under
   * "modelshelf" (OpenAI's list has no field for it).
   */
  fun openAiModels(entries: List<ApiModelEntry>, asr: List<AsrEntry> = emptyList()): String =
    JsonObject()
      .apply {
        addProperty("object", "list")
        add(
          "data",
          JsonArray().apply {
            for (e in entries) {
              add(
                JsonObject().apply {
                  addProperty("id", e.name)
                  addProperty("object", "model")
                  addProperty("created", e.modifiedMs / 1000)
                  addProperty("owned_by", e.repoId.substringBefore('/'))
                  add("modelshelf", JsonObject().apply { addProperty("kind", "chat") })
                }
              )
            }
            for (e in asr) {
              add(
                JsonObject().apply {
                  addProperty("id", e.name)
                  addProperty("object", "model")
                  addProperty("created", e.modifiedMs / 1000)
                  addProperty("owned_by", e.name.substringBefore('/'))
                  add(
                    "modelshelf",
                    JsonObject().apply {
                      addProperty("kind", "transcription")
                      addProperty("endpoint", "/v1/audio/transcriptions")
                      addProperty("commit", e.commit)
                      addProperty("size", e.sizeBytes)
                      add("languages", JsonArray().apply { e.languages.forEach { add(it) } })
                    },
                  )
                }
              )
            }
          },
        )
      }
      .toString()

  private fun usage(stats: TurnStats): JsonObject =
    JsonObject().apply {
      val prompt = stats.promptTokens ?: 0
      addProperty("prompt_tokens", prompt)
      addProperty("completion_tokens", stats.evalChunks)
      addProperty("total_tokens", prompt + stats.evalChunks)
    }

  fun openAiCompletion(id: String, model: String, text: String, finish: String, stats: TurnStats): String =
    JsonObject()
      .apply {
        addProperty("id", id)
        addProperty("object", "chat.completion")
        addProperty("created", System.currentTimeMillis() / 1000)
        addProperty("model", model)
        add(
          "choices",
          JsonArray().apply {
            add(
              JsonObject().apply {
                addProperty("index", 0)
                add(
                  "message",
                  JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", text)
                    add("refusal", JsonNull.INSTANCE)
                  },
                )
                add("logprobs", JsonNull.INSTANCE)
                addProperty("finish_reason", finish)
              }
            )
          },
        )
        add("usage", usage(stats))
      }
      .toString()

  /** One SSE event of a streamed chat completion (without the "data: " prefix). */
  fun openAiChunk(id: String, model: String, created: Long, delta: JsonObject, finish: String?): String =
    JsonObject()
      .apply {
        addProperty("id", id)
        addProperty("object", "chat.completion.chunk")
        addProperty("created", created)
        addProperty("model", model)
        add(
          "choices",
          JsonArray().apply {
            add(
              JsonObject().apply {
                addProperty("index", 0)
                add("delta", delta)
                add("logprobs", JsonNull.INSTANCE)
                if (finish != null) addProperty("finish_reason", finish)
                else add("finish_reason", JsonNull.INSTANCE)
              }
            )
          },
        )
      }
      .toString()

  fun openAiUsageChunk(id: String, model: String, created: Long, stats: TurnStats): String =
    JsonObject()
      .apply {
        addProperty("id", id)
        addProperty("object", "chat.completion.chunk")
        addProperty("created", created)
        addProperty("model", model)
        add("choices", JsonArray())
        add("usage", usage(stats))
      }
      .toString()

  fun roleDelta(): JsonObject =
    JsonObject().apply {
      addProperty("role", "assistant")
      addProperty("content", "")
    }

  fun textDelta(text: String): JsonObject = JsonObject().apply { addProperty("content", text) }
}

/** The numbers of one answered request. */
data class TurnStats(
  val loadMs: Long,
  val queueMs: Long,
  /** From the start of the inference to the first text; -1 when no text came. */
  val firstTextMs: Long,
  /** From the start of the inference to its end. */
  val inferenceMs: Long,
  /** From the request's arrival to its end. */
  val totalMs: Long,
  /** Text pieces the engine sent; one piece is about one token. */
  val evalChunks: Int,
  /** Tokens the conversation grew by, less the pieces above; null when the engine did not say. */
  val promptTokens: Int?,
)
