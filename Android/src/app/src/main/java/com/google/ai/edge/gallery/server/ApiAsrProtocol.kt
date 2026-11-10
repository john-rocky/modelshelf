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
import com.google.gson.JsonObject

// The request and response side of OpenAI's POST /v1/audio/transcriptions in Model Shelf's local
// API (https://platform.openai.com/docs/api-reference/audio/createTranscription). No Android here,
// so it is tested on the JVM (ApiAsrProtocolTest).

/** The most audio bytes a transcription request may send (OpenAI's own limit). */
const val MAX_AUDIO_BODY_BYTES = 25L * 1024 * 1024

/** The most audio one request may hold, after it is turned into 16 kHz mono. */
const val MAX_AUDIO_SECONDS = 600

/** A speech recognition model the local API can serve. */
data class AsrEntry(
  /** The API name: the Hugging Face repo. */
  val name: String,
  val kind: AsrKind,
  /** [AsrKind.PHONON]: its folder; [AsrKind.MEDIAPIPE]: the .litertlm file. */
  val path: String,
  val sizeBytes: Long,
  val commit: String,
  /**
   * The languages the model takes, as BCP-47 tags (`en-US`), from the model list; empty when the
   * list names none (the request's language is then not checked).
   */
  val languages: List<String>,
  val modifiedMs: Long,
  val listedBy: String,
)

enum class AsrKind(
  /**
   * Whether the engine is told the request's language. Neither is: Phonon-2.5-English is English
   * only, and MediaPipe's AudioTranscriber (Parakeet TDT v3) finds the language itself. The language
   * is still checked against the model's list.
   */
  val takesLanguage: Boolean
) {
  /** Phonon-2.5-English: Model Shelf's own LiteRT pipeline (NPU, GPU or CPU). */
  PHONON(takesLanguage = false),
  /** A .litertlm file for MediaPipe's AudioTranscriber (Parakeet TDT). */
  MEDIAPIPE(takesLanguage = false),
}

enum class TranscriptFormat(val wire: String) {
  JSON("json"),
  TEXT("text"),
  VERBOSE_JSON("verbose_json"),
}

/** A POST /v1/audio/transcriptions, read from its form fields. */
class TranscriptionRequest(
  val model: String,
  val audio: FormPart,
  /** ISO-639-1 (`en`) or BCP-47 (`en-US`), as sent; null when not sent. */
  val language: String?,
  val format: TranscriptFormat,
  val stream: Boolean,
  /** Fields sent that the API does not apply, for the log and X-ModelShelf-Not-Applied. */
  val ignored: List<String>,
)

private const val MODELS_HINT = "GET /v1/models lists the models on this phone"

/** Reads the form of a transcription request. Throws [ApiError] 400 for what it cannot take. */
fun parseTranscriptionForm(parts: List<FormPart>): TranscriptionRequest {
  fun text(name: String): String? =
    parts.firstOrNull { it.name == name && it.filename == null }?.text()?.trim()?.ifEmpty { null }
  val files = parts.filter { it.name == "file" }
  if (files.isEmpty()) {
    throw ApiError(400, "Send the audio as the form field \"file\" (a WAV file).")
  }
  if (files.size > 1) throw ApiError(400, "Send one audio file per request.")
  val audio = files.single()
  if (audio.bytes.isEmpty()) throw ApiError(400, "The audio file is empty.")
  val model = text("model") ?: throw ApiError(400, "Name a speech model in the form field \"model\"; $MODELS_HINT.")
  val format =
    when (val f = text("response_format") ?: "json") {
      "json" -> TranscriptFormat.JSON
      "text" -> TranscriptFormat.TEXT
      "verbose_json" -> TranscriptFormat.VERBOSE_JSON
      else ->
        throw ApiError(400, "\"response_format\" \"$f\" is not supported; use json, text or verbose_json.")
    }
  val stream =
    when (text("stream")?.lowercase()) {
      null,
      "false" -> false
      "true" -> true
      else -> throw ApiError(400, "\"stream\" must be true or false.")
    }
  if (stream && format == TranscriptFormat.VERBOSE_JSON) {
    throw ApiError(400, "A streamed transcription takes response_format json or text.")
  }
  // Other fields (prompt, temperature, timestamp_granularities[], ...) are read and not applied.
  val known = setOf("file", "model", "language", "response_format", "stream")
  val ignored = parts.map { it.name }.filter { it !in known }.distinct().map { it.take(64) }
  return TranscriptionRequest(
    model = model,
    audio = audio,
    language = text("language"),
    format = format,
    stream = stream,
    ignored = ignored,
  )
}

/**
 * Checks the request's language against the model's list (the primary subtag: `en` matches
 * `en-US`). Throws [ApiError] 400 with the languages the model takes.
 */
fun checkTranscriptionLanguage(language: String?, entry: AsrEntry) {
  if (language == null || entry.languages.isEmpty()) return
  val wanted = language.substringBefore('-').substringBefore('_').lowercase()
  val primaries = entry.languages.map { it.substringBefore('-').lowercase() }.distinct()
  if (wanted !in primaries) {
    throw ApiError(
      400,
      "${entry.name} does not transcribe \"$language\"; it takes ${primaries.joinToString(", ")}.",
    )
  }
}

/**
 * The fields of [request] the API reads but does not apply with [entry], for the log and
 * X-ModelShelf-Not-Applied: the unknown fields, and `language` when the engine is not told it.
 */
fun transcriptionNotApplied(request: TranscriptionRequest, entry: AsrEntry): List<String> =
  request.ignored + listOfNotNull("language".takeIf { request.language != null && !entry.kind.takesLanguage })

/**
 * The language verbose_json names: the request's language, else the model's language when it
 * takes only one; else null and the field is left out (neither engine says which language it
 * heard, and a list's first language is not the audio's).
 */
fun transcriptLanguage(request: TranscriptionRequest, entry: AsrEntry): String? =
  (request.language ?: entry.languages.singleOrNull())?.let(::languageName)

/** One piece of a transcript: a Phonon window, or a final segment of MediaPipe's transcriber. */
class TranscriptPiece(val text: String, val startS: Double, val endS: Double, val tokens: List<Int>)

object AsrJson {
  fun json(text: String): String = JsonObject().apply { addProperty("text", text) }.toString()

  /**
   * OpenAI's verbose_json: the fields Model Shelf knows (no log probabilities). A null [language]
   * leaves the field out ([transcriptLanguage]).
   */
  fun verbose(text: String, language: String?, durationS: Double, pieces: List<TranscriptPiece>): String =
    JsonObject()
      .apply {
        addProperty("task", "transcribe")
        if (language != null) addProperty("language", language)
        addProperty("duration", round3(durationS))
        addProperty("text", text)
        add(
          "segments",
          JsonArray().apply {
            pieces.forEachIndexed { i, p ->
              add(
                JsonObject().apply {
                  addProperty("id", i)
                  addProperty("seek", (p.startS * 100).toInt())
                  addProperty("start", round3(p.startS))
                  addProperty("end", round3(p.endS))
                  addProperty("text", p.text)
                  add("tokens", JsonArray().apply { p.tokens.forEach { add(it) } })
                }
              )
            }
          },
        )
      }
      .toString()

  /** One SSE event of a streamed transcription (OpenAI's transcript.text.delta). */
  fun delta(text: String): String =
    JsonObject()
      .apply {
        addProperty("type", "transcript.text.delta")
        addProperty("delta", text)
      }
      .toString()

  /** The last SSE event of a streamed transcription (OpenAI's transcript.text.done). */
  fun done(text: String): String =
    JsonObject()
      .apply {
        addProperty("type", "transcript.text.done")
        addProperty("text", text)
      }
      .toString()

  private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}

/** The English name OpenAI's verbose_json gives a language, for the tags the models here take. */
fun languageName(tag: String?): String {
  val primary = tag?.substringBefore('-')?.lowercase() ?: return "unknown"
  return when (primary) {
    "en" -> "english"
    "de" -> "german"
    "fr" -> "french"
    "es" -> "spanish"
    "it" -> "italian"
    "nl" -> "dutch"
    "pt" -> "portuguese"
    "pl" -> "polish"
    "ru" -> "russian"
    else -> primary
  }
}

/** Joins pieces as the card's runner joins windows: the non-empty texts with one space. */
fun joinPieces(pieces: List<TranscriptPiece>): String =
  pieces.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
