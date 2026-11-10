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

import com.google.ai.edge.gallery.services.tts.TtsTextNormalizer
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The request and response side of OpenAI's POST /v1/audio/speech in Model Shelf's local API
// (https://platform.openai.com/docs/api-reference/audio/createSpeech). No Android here, so it is
// tested on the JVM (ApiTtsProtocolTest).

/** The most characters one speech request may send (OpenAI's own limit). */
const val MAX_SPEECH_INPUT_CHARS = 4096

/**
 * The largest speech request body: 4,096 characters written as JSON escapes (12 bytes for a
 * character outside the BMP) are 49,152 bytes, and the other fields are short. The body is read
 * before the request takes its place in the queue, so a waiting request holds at most this much.
 */
const val MAX_SPEECH_BODY_BYTES = 64L * 1024

/** The voice models of Model Shelf give 24 kHz mono 16-bit PCM. */
const val SPEECH_OUTPUT_RATE_HZ = 24_000

/**
 * The most audio one request may make: 10 minutes, over twice the length 4,096 characters take at
 * 15 characters a second (273 s). A voice model that goes past it is stopped (a 500).
 *
 * Memory: a WAV answer keeps its 16-bit audio until it is sent, at most 28.8 MB; a pcm answer is
 * sent sentence by sentence and keeps about one sentence. While one sentence is spoken, the voice
 * engine also holds that sentence's float samples twice (its chunks, joined for word timestamps):
 * 8 bytes per sample, about 0.2 MB per second of the sentence, at most 20 s for a sentence of
 * [MAX_SPEECH_PIECE_CHARS] characters at the usual pace.
 */
const val MAX_SPEECH_AUDIO_S = 600

/**
 * The longest piece of text the API gives the voice engine in one call. The input is split the way
 * the app's read-aloud splits a chat answer, one sentence per call, so each sentence keeps its own
 * end (a question stays a question); a longer sentence is split at its last comma, else its last
 * space, before this many characters.
 */
const val MAX_SPEECH_PIECE_CHARS = 300

/**
 * A speech answer stops being sent when a write waits and the caller has taken no byte for this
 * long, counted from the end of the synthesis (a WAV file) or the start of each sentence (PCM), or
 * from the last write the caller's reading let through ([WriteWatch]): a caller that stopped reading
 * is cut within a minute of the synthesis end, and one that plays as it reads never waits that long.
 */
const val SPEECH_WRITE_STALL_MS = 55_000L

/**
 * A speech answer also stops when the audio's own length plus this much has passed since the
 * synthesis ended: a caller that reads as it plays (real time) finishes well before.
 */
const val SPEECH_WRITE_GRACE_MS = 60_000L

/** The Retry-After of a speech request refused while the app reads aloud to the user. */
const val READ_ALOUD_RETRY_S = 10

/** [MAX_SPEECH_AUDIO_S] in bytes of 24 kHz mono 16-bit PCM. */
const val MAX_SPEECH_AUDIO_BYTES = MAX_SPEECH_AUDIO_S * SPEECH_OUTPUT_RATE_HZ * 2

/**
 * The pieces the voice engine speaks for [input], in order: the sentences of
 * [TtsTextNormalizer.segmentTextIntoChunks] (what the read-aloud speaks: Markdown and emoji left
 * out), each split again when it is longer than [MAX_SPEECH_PIECE_CHARS]. A piece with no letter or
 * digit says nothing and is left out, so the list is empty when nothing can be spoken.
 */
fun speechPieces(input: String): List<String> {
  val out = mutableListOf<String>()
  for (chunk in TtsTextNormalizer.segmentTextIntoChunks(input)) {
    var rest = chunk.cleanSpokenText.trim()
    while (rest.codePointCount(0, rest.length) > MAX_SPEECH_PIECE_CHARS) {
      val limit = rest.offsetByCodePoints(0, MAX_SPEECH_PIECE_CHARS)
      val comma = rest.lastIndexOf(", ", limit - 1)
      val space = rest.lastIndexOf(' ', limit - 1)
      val cut =
        when {
          comma > 0 -> comma + 1
          space > 0 -> space
          else -> limit
        }
      addSpoken(out, rest.substring(0, cut))
      rest = rest.substring(cut).trim()
    }
    addSpoken(out, rest)
  }
  return out
}

private fun addSpoken(out: MutableList<String>, piece: String) {
  val p = piece.trim()
  if (p.any { it.isLetterOrDigit() }) out.add(p)
}

private const val SPEECH_MODELS_HINT = "GET /v1/models lists the models on this phone"

/** How many voices an error sentence names before it points to GET /v1/models. */
private const val VOICES_IN_A_SENTENCE = 8

/** A text-to-speech model the local API can serve (run by the app's MediaPipe TTS engine). */
data class TtsEntry(
  /** The API name: the Hugging Face repo. */
  val name: String,
  /** The engine's id for the model (the model list's ttsConfig.engineModelId). */
  val engineModelId: String,
  /** The .litertlm file. */
  val path: String,
  val sizeBytes: Long,
  val commit: String,
  /** The voices the model has, the default first. */
  val voices: List<String>,
  val defaultVoice: String,
  /** BCP-47 tags of the languages the model speaks, from the model list. */
  val languages: List<String>,
  val modifiedMs: Long,
  val listedBy: String,
)

enum class SpeechFormat(val wire: String, val contentType: String) {
  /** A WAV file: RIFF header, then 24 kHz mono 16-bit little-endian PCM. */
  WAV("wav", "audio/wav"),
  /** The same samples without a header (OpenAI's pcm). */
  PCM("pcm", "audio/pcm"),
}

/** A POST /v1/audio/speech, read from its JSON body. */
class SpeechRequest(
  val model: String,
  val input: String,
  /** As sent; null = the model's default voice. */
  val voice: String?,
  val format: SpeechFormat,
  /** Fields sent that the API does not apply, for the log and X-ModelShelf-Not-Applied. */
  val ignored: List<String>,
) {
  /** The input's length in characters (Unicode code points), the unit of the 4096 limit. */
  val inputChars: Int
    get() = input.codePointCount(0, input.length)
}

/** Reads the JSON body of a speech request. Throws [ApiError] 400 for what it cannot take. */
fun parseSpeechRequest(body: String): SpeechRequest {
  val root =
    try {
      JsonParser.parseString(body)
    } catch (e: Exception) {
      throw ApiError(400, "The body is not valid JSON.")
    }
  if (root == null || !root.isJsonObject) throw ApiError(400, "The body must be a JSON object.")
  val o = root.asJsonObject
  val model =
    o.stringField("model")?.trim()?.ifEmpty { null }
      ?: throw ApiError(400, "Name a text-to-speech model in \"model\" ($SPEECH_MODELS_HINT).")
  val input = o.stringField("input") ?: throw ApiError(400, "\"input\" is required: the text to speak.")
  if (input.isBlank()) throw ApiError(400, "\"input\" is empty: send the text to speak.")
  val chars = input.codePointCount(0, input.length)
  if (chars > MAX_SPEECH_INPUT_CHARS) {
    throw ApiError(
      400,
      "\"input\" has $chars characters; Model Shelf speaks at most $MAX_SPEECH_INPUT_CHARS per " +
        "request, so send the text in parts.",
    )
  }
  val voice = o.voiceField()
  val format =
    when (val f = o.stringField("response_format")?.trim()?.lowercase()?.ifEmpty { null } ?: "wav") {
      "wav" -> SpeechFormat.WAV
      "pcm" -> SpeechFormat.PCM
      else -> throw ApiError(400, "\"response_format\" \"$f\" is not supported; use wav or pcm.")
    }
  val ignored = mutableListOf<String>()
  o.numberField("speed")?.let { speed ->
    if (speed < 0.25 || speed > 4.0) throw ApiError(400, "\"speed\" must be between 0.25 and 4.0.")
    // The voice engine sets its speaking rate when it loads a model, not per request.
    if (speed != 1.0) ignored.add("speed")
  }
  if (!o.stringField("instructions").isNullOrBlank()) ignored.add("instructions")
  o.stringField("stream_format")?.let { sf ->
    if (sf.trim().lowercase() != "audio") {
      throw ApiError(400, "\"stream_format\" \"$sf\" is not supported; the audio comes in the response body.")
    }
  }
  val known = setOf("model", "input", "voice", "response_format", "speed", "instructions", "stream_format")
  ignored.addAll(o.keySet().filter { it !in known }.map { it.take(64) })
  return SpeechRequest(model = model, input = input, voice = voice, format = format, ignored = ignored)
}

/**
 * The voice a request names, as the model spells it: the exact id, or the same ignoring case; the
 * model's default when the request names none. Throws [ApiError] 400 with the model's voices.
 */
fun resolveSpeechVoice(requested: String?, entry: TtsEntry, voices: List<String> = entry.voices): String {
  val wanted = requested?.trim()?.ifEmpty { null } ?: return entry.defaultVoice
  voices.firstOrNull { it == wanted }?.let { return it }
  voices.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
  val named = voices.take(VOICES_IN_A_SENTENCE).joinToString(", ")
  val more = if (voices.size > VOICES_IN_A_SENTENCE) " and ${voices.size - VOICES_IN_A_SENTENCE} more" else ""
  throw ApiError(
    400,
    "Voice \"$wanted\" is not in ${entry.name}, which has $named$more (GET /v1/models lists them all).",
    "invalid_voice",
  )
}

/** Writes WAV files: the 44-byte RIFF header of 16-bit PCM, then the samples. */
object WavWriter {
  const val HEADER_BYTES = 44

  fun header(dataBytes: Int, rateHz: Int = SPEECH_OUTPUT_RATE_HZ, channels: Int = 1, bits: Int = 16): ByteArray {
    require(dataBytes >= 0 && rateHz > 0 && channels > 0 && bits % 8 == 0)
    val blockAlign = channels * bits / 8
    return ByteBuffer.allocate(HEADER_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN)
      .put("RIFF".toByteArray(Charsets.US_ASCII))
      .putInt(36 + dataBytes)
      .put("WAVE".toByteArray(Charsets.US_ASCII))
      .put("fmt ".toByteArray(Charsets.US_ASCII))
      .putInt(16)
      .putShort(1) // PCM
      .putShort(channels.toShort())
      .putInt(rateHz)
      .putInt(rateHz * blockAlign)
      .putShort(blockAlign.toShort())
      .putShort(bits.toShort())
      .put("data".toByteArray(Charsets.US_ASCII))
      .putInt(dataBytes)
      .array()
  }

  /** A whole WAV file of 24 kHz mono 16-bit [pcm]. */
  fun wav(pcm: ByteArray, rateHz: Int = SPEECH_OUTPUT_RATE_HZ): ByteArray =
    header(pcm.size, rateHz) + pcm
}

/** Milliseconds of audio in [pcmBytes] bytes of 24 kHz mono 16-bit PCM. */
fun speechAudioMs(pcmBytes: Int): Long = pcmBytes.toLong() * 1000 / (SPEECH_OUTPUT_RATE_HZ * 2)

object TtsJson {
  /** The /v1/models entry of a text-to-speech model. */
  fun model(e: TtsEntry): JsonObject =
    JsonObject().apply {
      addProperty("id", e.name)
      addProperty("object", "model")
      addProperty("created", e.modifiedMs / 1000)
      addProperty("owned_by", e.name.substringBefore('/'))
      add(
        "modelshelf",
        JsonObject().apply {
          addProperty("kind", "speech")
          addProperty("endpoint", "/v1/audio/speech")
          addProperty("commit", e.commit)
          addProperty("size", e.sizeBytes)
          addProperty("default_voice", e.defaultVoice)
          add("voices", JsonArray().apply { e.voices.forEach { add(it) } })
          add("languages", JsonArray().apply { e.languages.forEach { add(it) } })
          addProperty("sample_rate", SPEECH_OUTPUT_RATE_HZ)
          add("response_formats", JsonArray().apply { SpeechFormat.entries.forEach { add(it.wire) } })
          addProperty("max_input_characters", MAX_SPEECH_INPUT_CHARS)
        },
      )
    }
}

private fun JsonObject.field(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }

private fun JsonObject.stringField(key: String): String? {
  val e = field(key) ?: return null
  if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) throw ApiError(400, "\"$key\" must be a string.")
  return e.asString
}

private fun JsonObject.numberField(key: String): Double? {
  val e = field(key) ?: return null
  if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) throw ApiError(400, "\"$key\" must be a number.")
  return e.asDouble
}

/** OpenAI's voice: a voice id, or an object {"id": ...} (its custom voices). */
private fun JsonObject.voiceField(): String? {
  val e = field("voice") ?: return null
  return when {
    e.isJsonPrimitive && e.asJsonPrimitive.isString -> e.asString
    e.isJsonObject -> e.asJsonObject.stringField("id")
    else -> throw ApiError(400, "\"voice\" must be a voice id such as \"af_heart\".")
  }
}
