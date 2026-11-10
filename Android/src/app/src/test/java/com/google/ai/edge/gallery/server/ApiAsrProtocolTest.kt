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

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The multipart reader and the transcription request and response forms. */
class ApiAsrProtocolTest {
  private fun assertRefused(status: Int, block: () -> Unit): ApiError {
    try {
      block()
    } catch (e: ApiError) {
      assertEquals(e.message, status, e.status)
      return e
    }
    fail("expected HTTP $status")
    throw AssertionError()
  }

  private val audio = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 13, 10, 45, 45, 0x7F)

  /** A multipart body as curl and openai-python send it. */
  private fun body(boundary: String, lineEnd: String, vararg fields: Pair<String, Any>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for ((name, value) in fields) {
      out.write("--$boundary$lineEnd".toByteArray())
      if (value is ByteArray) {
        out.write("Content-Disposition: form-data; name=\"$name\"; filename=\"a b.wav\"$lineEnd".toByteArray())
        out.write("Content-Type: audio/wav$lineEnd$lineEnd".toByteArray())
        out.write(value)
      } else {
        out.write("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd$value".toByteArray())
      }
      out.write(lineEnd.toByteArray())
    }
    out.write("--$boundary--$lineEnd".toByteArray())
    return out.toByteArray()
  }

  @Test
  fun multipartReadsFieldsAndBinaryFileExactly() {
    for (lineEnd in listOf("\r\n", "\n")) {
      val b = body("XyZ123", lineEnd, "model" to "litert-community/Phonon-2.5-English", "file" to audio)
      val parts = MultipartForm.parse("multipart/form-data; boundary=XyZ123", b)
      assertEquals(2, parts.size)
      assertEquals("litert-community/Phonon-2.5-English", parts[0].text())
      assertNull(parts[0].filename)
      assertEquals("a b.wav", parts[1].filename)
      assertEquals("audio/wav", parts[1].contentType)
      assertArrayEquals(audio, parts[1].copyBytes())
    }
  }

  @Test
  fun multipartTakesAQuotedBoundaryAndAnExtendedFileName() {
    val b =
      ("--a b\r\nContent-Disposition: form-data; name=\"file\"; filename*=UTF-8''r%C3%A9union.wav\r\n\r\n" +
          "abc\r\n--a b--\r\n")
        .toByteArray()
    // A boundary with a space comes quoted.
    val parts = MultipartForm.parse("multipart/form-data; boundary=\"a b\"", b)
    assertEquals("réunion.wav", parts.single().filename)
    assertEquals("abc", parts.single().text())
  }

  @Test
  fun notMultipartOrBrokenBodiesAre400() {
    assertRefused(400) { MultipartForm.parse("application/json", "{}".toByteArray()) }
    assertRefused(400) { MultipartForm.parse("multipart/form-data", ByteArray(3)) }
    assertRefused(400) { MultipartForm.parse("multipart/form-data; boundary=zz", "no parts".toByteArray()) }
    assertRefused(400) {
      MultipartForm.parse("multipart/form-data; boundary=zz", "--zz\r\nContent-Disposition: form-data\r\n\r\nx".toByteArray())
    }
  }

  private fun form(vararg fields: Pair<String, Any>): List<FormPart> =
    MultipartForm.parse("multipart/form-data; boundary=B", body("B", "\r\n", *fields))

  @Test
  fun transcriptionFormDefaultsAndFields() {
    val r = parseTranscriptionForm(form("file" to audio, "model" to "m"))
    assertEquals("m", r.model)
    assertEquals(TranscriptFormat.JSON, r.format)
    assertEquals(false, r.stream)
    assertNull(r.language)
    assertTrue(r.ignored.isEmpty())
    val s =
      parseTranscriptionForm(
        form(
          "model" to "m",
          "file" to audio,
          "language" to "en",
          "response_format" to "text",
          "stream" to "true",
          "temperature" to "0",
          "prompt" to "hi",
        )
      )
    assertEquals(TranscriptFormat.TEXT, s.format)
    assertTrue(s.stream)
    assertEquals("en", s.language)
    assertEquals(listOf("temperature", "prompt"), s.ignored)
  }

  @Test
  fun transcriptionFormRefusals() {
    assertRefused(400) { parseTranscriptionForm(form("model" to "m")) }
    assertRefused(400) { parseTranscriptionForm(form("file" to audio)) }
    assertRefused(400) { parseTranscriptionForm(form("file" to ByteArray(0), "model" to "m")) }
    assertRefused(400) { parseTranscriptionForm(form("file" to audio, "file" to audio, "model" to "m")) }
    assertRefused(400) { parseTranscriptionForm(form("file" to audio, "model" to "m", "response_format" to "srt")) }
    assertRefused(400) { parseTranscriptionForm(form("file" to audio, "model" to "m", "stream" to "yes")) }
    assertRefused(400) {
      parseTranscriptionForm(
        form("file" to audio, "model" to "m", "stream" to "true", "response_format" to "verbose_json")
      )
    }
  }

  private val phonon =
    AsrEntry(
      name = "litert-community/Phonon-2.5-English",
      kind = AsrKind.PHONON,
      path = "/m/Phonon-2.5-English/c",
      sizeBytes = 1,
      commit = "c",
      languages = listOf("en-US"),
      modifiedMs = 0,
      listedBy = "test",
    )

  @Test
  fun languageIsCheckedByItsPrimarySubtag() {
    checkTranscriptionLanguage(null, phonon)
    checkTranscriptionLanguage("en", phonon)
    checkTranscriptionLanguage("en-GB", phonon)
    checkTranscriptionLanguage("EN", phonon)
    val e = assertRefused(400) { checkTranscriptionLanguage("ja", phonon) }
    assertEquals("litert-community/Phonon-2.5-English does not transcribe \"ja\"; it takes en.", e.message)
    // A model whose list names no languages is not checked.
    checkTranscriptionLanguage("ja", phonon.copy(languages = emptyList()))
  }

  @Test
  fun verboseJsonNamesTheRequestsLanguageOrTheModelsOnlyOne() {
    // Parakeet TDT v3's list starts with bg-BG: a list's first language is not the audio's.
    val parakeet =
      phonon.copy(
        name = "litert-community/parakeet-tdt-0.6b-v3",
        kind = AsrKind.MEDIAPIPE,
        languages = listOf("bg-BG", "cs-CZ", "en-US", "de-DE"),
      )
    val plain = parseTranscriptionForm(form("file" to audio, "model" to "m"))
    val english = parseTranscriptionForm(form("file" to audio, "model" to "m", "language" to "en"))
    assertNull(transcriptLanguage(plain, parakeet))
    assertEquals("english", transcriptLanguage(english, parakeet))
    assertEquals("english", transcriptLanguage(plain, phonon))
    val v = JsonParser.parseString(AsrJson.verbose("hi", null, 1.0, emptyList())).asJsonObject
    assertFalse(v.has("language"))
    assertEquals("hi", v.get("text").asString)
  }

  @Test
  fun responseShapes() {
    assertEquals("hello world", JsonParser.parseString(AsrJson.json("hello world")).asJsonObject.get("text").asString)
    val pieces =
      listOf(TranscriptPiece(" first", 0.0, 30.0, listOf(1, 2)), TranscriptPiece("", 30.0, 31.0, emptyList()),
        TranscriptPiece("second ", 30.0, 41.5, listOf(3)))
    val text = joinPieces(pieces)
    assertEquals("first second", text)
    val v = JsonParser.parseString(AsrJson.verbose(text, languageName("en-US"), 41.5, pieces)).asJsonObject
    assertEquals("transcribe", v.get("task").asString)
    assertEquals("english", v.get("language").asString)
    assertEquals(41.5, v.get("duration").asDouble, 0.0)
    assertEquals(3, v.getAsJsonArray("segments").size())
    assertEquals(30.0, v.getAsJsonArray("segments")[2].asJsonObject.get("start").asDouble, 0.0)
    val d = JsonParser.parseString(AsrJson.delta(" second")).asJsonObject
    assertEquals("transcript.text.delta", d.get("type").asString)
    assertEquals(" second", d.get("delta").asString)
    val done = JsonParser.parseString(AsrJson.done(text)).asJsonObject
    assertEquals("transcript.text.done", done.get("type").asString)
    assertEquals(text, done.get("text").asString)
  }

  @Test
  fun modelsListShowsSpeechModelsWithTheirKind() {
    val o = JsonParser.parseString(ApiJson.openAiModels(emptyList(), listOf(phonon))).asJsonObject
    val m = o.getAsJsonArray("data")[0].asJsonObject
    assertEquals("litert-community/Phonon-2.5-English", m.get("id").asString)
    assertEquals("litert-community", m.get("owned_by").asString)
    assertEquals("transcription", m.getAsJsonObject("modelshelf").get("kind").asString)
  }


  @Test
  fun aFormOfMoreThan64FieldsIsRefusedAnd64AreRead() {
    val fields: List<Pair<String, Any>> = List(64) { "f$it" to "v$it" }
    assertEquals(64, MultipartForm.parse("multipart/form-data; boundary=B", body("B", "\r\n", *fields.toTypedArray())).size)
    val more = fields + ("f64" to "v")
    try {
      MultipartForm.parse("multipart/form-data; boundary=B", body("B", "\r\n", *more.toTypedArray()))
      fail("expected a 400")
    } catch (e: ApiError) {
      assertEquals(400, e.status)
      assertEquals("The form has more than 64 fields.", e.message)
    }
  }

  @Test
  fun aPartsHeadersAreLookedForOnlyInTheirFirst16Kilobytes() {
    val b =
      ("--B\r\nContent-Disposition: form-data; name=\"x\"; filename=\"" + "a".repeat(20_000) + "\"\r\n\r\n" +
          "abc\r\n--B--\r\n")
        .toByteArray()
    try {
      MultipartForm.parse("multipart/form-data; boundary=B", b)
      fail("expected a 400")
    } catch (e: ApiError) {
      assertEquals(400, e.status)
    }
  }

  @Test
  fun aPreambleIsSkippedAndBytesPastTheSizeAreNotRead() {
    val form = body("XyZ123", "\r\n", "model" to "m", "file" to audio)
    val withPreamble = "This is a preamble.\r\n".toByteArray() + form
    val array = withPreamble + ByteArray(1_000) { 0x2D } // "-----..." after the body
    val parts = MultipartForm.parse("multipart/form-data; boundary=XyZ123", array, withPreamble.size)
    assertEquals(2, parts.size)
    assertArrayEquals(audio, parts[1].copyBytes())
    // The file part points into the body: nothing is copied.
    assertTrue(parts[1].data === array)
  }

  @Test
  fun aRequestWithoutItsAudioKeepsItsFields() {
    val form = body("XyZ123", "\r\n", "model" to "m", "language" to "en", "file" to audio)
    val r = parseTranscriptionForm(MultipartForm.parse("multipart/form-data; boundary=XyZ123", form))
    val kept = r.withoutAudio()
    assertEquals("m", kept.model)
    assertEquals("en", kept.language)
    assertEquals(r.format, kept.format)
    assertEquals("a b.wav", kept.audio.filename)
    assertEquals(0, kept.audio.size)
    assertEquals(0, kept.audio.data.size)
  }
}
