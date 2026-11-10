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

import com.google.ai.edge.gallery.speech.VoiceLevelMeter
import com.google.ai.edge.gallery.speech.WavReader
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiTtsProtocolTest {
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

  /** One sentence: a full stop at the end and none before a new sentence inside. */
  private fun assertOneSentence(message: String) {
    assertTrue(message, message.endsWith("."))
    assertFalse(message, Regex("""\. +[A-Z]""").containsMatchIn(message))
  }

  private val kokoroVoices = listOf("af_heart", "af_alloy", "af_aoede", "af_bella", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river", "bf_emma")

  private val kokoro =
    TtsEntry(
      name = "litert-community/Kokoro-82M",
      engineModelId = "mediapipe_kokoro_82m",
      path = "/x/kokoro_int8_gpu_fp16.litertlm",
      sizeBytes = 294_750_530,
      commit = "81e43a025bccabcbbd487f08bc3ebd65b5fc7295",
      voices = kokoroVoices,
      defaultVoice = "af_heart",
      languages = listOf("en-US", "en-GB"),
      modifiedMs = 1_700_000_000_000,
      listedBy = "Google's model list",
    )

  @Test
  fun readsTheFieldsOpenAiSends() {
    val r = parseSpeechRequest("""{"model":"litert-community/Kokoro-82M","input":"Hello there.","voice":"af_heart"}""")
    assertEquals("litert-community/Kokoro-82M", r.model)
    assertEquals("Hello there.", r.input)
    assertEquals("af_heart", r.voice)
    assertEquals(SpeechFormat.WAV, r.format)
    assertTrue(r.ignored.isEmpty())
    assertEquals(12, r.inputChars)
  }

  @Test
  fun wavIsTheDefaultAndPcmIsTaken() {
    assertEquals(SpeechFormat.WAV, parseSpeechRequest("""{"model":"m","input":"a","response_format":"wav"}""").format)
    assertEquals(SpeechFormat.PCM, parseSpeechRequest("""{"model":"m","input":"a","response_format":"PCM"}""").format)
    for (f in listOf("mp3", "opus", "aac", "flac", "x")) {
      val e = assertRefused(400) { parseSpeechRequest("""{"model":"m","input":"a","response_format":"$f"}""") }
      assertTrue(e.message, e.message.contains("use wav or pcm"))
    }
  }

  @Test
  fun refusesAMissingEmptyOrLongInput() {
    assertRefused(400) { parseSpeechRequest("""{"model":"m"}""") }
    val empty = assertRefused(400) { parseSpeechRequest("""{"model":"m","input":""}""") }
    assertTrue(empty.message, empty.message.contains("empty"))
    assertOneSentence(empty.message)
    assertRefused(400) { parseSpeechRequest("""{"model":"m","input":"   \n "}""") }
    assertRefused(400) { parseSpeechRequest("""{"model":"m","input":42}""") }
    val max = "a".repeat(MAX_SPEECH_INPUT_CHARS)
    assertEquals(MAX_SPEECH_INPUT_CHARS, parseSpeechRequest("""{"model":"m","input":"$max"}""").inputChars)
    val long = assertRefused(400) { parseSpeechRequest("""{"model":"m","input":"${max}b"}""") }
    assertTrue(long.message, long.message.contains("4097 characters") && long.message.contains("4096"))
    assertOneSentence(long.message)
  }

  @Test
  fun countsCharactersNotUtf16Units() {
    // 4096 characters outside the BMP are 8192 UTF-16 units: still within the limit.
    val face = String(Character.toChars(0x1F600))
    val r = parseSpeechRequest(JsonParser.parseString("{}").asJsonObject.apply {
      addProperty("model", "m")
      addProperty("input", face.repeat(MAX_SPEECH_INPUT_CHARS))
    }.toString())
    assertEquals(MAX_SPEECH_INPUT_CHARS, r.inputChars)
    assertEquals(2 * MAX_SPEECH_INPUT_CHARS, r.input.length)
  }

  @Test
  fun refusesAMissingModelAndABadBody() {
    assertRefused(400) { parseSpeechRequest("""{"input":"a"}""") }
    assertRefused(400) { parseSpeechRequest("""{"model":"  ","input":"a"}""") }
    assertRefused(400) { parseSpeechRequest("not json") }
    assertRefused(400) { parseSpeechRequest("[1,2]") }
    assertRefused(400) { parseSpeechRequest("null") }
  }

  @Test
  fun speedInstructionsAndUnknownFieldsAreNotApplied() {
    assertTrue(parseSpeechRequest("""{"model":"m","input":"a","speed":1.0}""").ignored.isEmpty())
    assertEquals(listOf("speed"), parseSpeechRequest("""{"model":"m","input":"a","speed":1.5}""").ignored)
    assertRefused(400) { parseSpeechRequest("""{"model":"m","input":"a","speed":5}""") }
    assertRefused(400) { parseSpeechRequest("""{"model":"m","input":"a","speed":"fast"}""") }
    assertEquals(
      listOf("instructions", "user"),
      parseSpeechRequest("""{"model":"m","input":"a","instructions":"Speak warmly.","user":"x"}""").ignored,
    )
    assertTrue(parseSpeechRequest("""{"model":"m","input":"a","instructions":""}""").ignored.isEmpty())
    assertTrue(parseSpeechRequest("""{"model":"m","input":"a","stream_format":"audio"}""").ignored.isEmpty())
    assertRefused(400) { parseSpeechRequest("""{"model":"m","input":"a","stream_format":"sse"}""") }
  }

  @Test
  fun readsTheVoiceAsAnIdOrAnObject() {
    assertNull(parseSpeechRequest("""{"model":"m","input":"a"}""").voice)
    assertNull(parseSpeechRequest("""{"model":"m","input":"a","voice":null}""").voice)
    assertEquals("bf_emma", parseSpeechRequest("""{"model":"m","input":"a","voice":{"id":"bf_emma"}}""").voice)
    assertRefused(400) { parseSpeechRequest("""{"model":"m","input":"a","voice":7}""") }
  }

  @Test
  fun resolvesTheVoiceOrNamesTheModelsVoices() {
    assertEquals("af_heart", resolveSpeechVoice(null, kokoro))
    assertEquals("af_heart", resolveSpeechVoice("  ", kokoro))
    assertEquals("bf_emma", resolveSpeechVoice("bf_emma", kokoro))
    assertEquals("bf_emma", resolveSpeechVoice("BF_Emma", kokoro))
    val e = assertRefused(400) { resolveSpeechVoice("alloy", kokoro) }
    assertEquals("invalid_voice", e.code)
    assertTrue(e.message, e.message.contains("\"alloy\" is not in litert-community/Kokoro-82M"))
    assertTrue(e.message, e.message.contains("af_heart, af_alloy") && e.message.contains("and 2 more"))
    assertOneSentence(e.message)
    // The list the loaded model gives wins over the model list's.
    assertRefused(400) { resolveSpeechVoice("bf_emma", kokoro, listOf("af_heart")) }
  }

  @Test
  fun writesACanonicalWavHeader() {
    val h = WavWriter.header(48_000)
    assertEquals(44, h.size)
    val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(at: Int) = String(h, at, 4, Charsets.US_ASCII)
    assertEquals("RIFF", tag(0))
    assertEquals(36 + 48_000, b.getInt(4))
    assertEquals("WAVE", tag(8))
    assertEquals("fmt ", tag(12))
    assertEquals(16, b.getInt(16))
    assertEquals(1, b.getShort(20).toInt())
    assertEquals(1, b.getShort(22).toInt())
    assertEquals(24_000, b.getInt(24))
    assertEquals(48_000, b.getInt(28))
    assertEquals(2, b.getShort(32).toInt())
    assertEquals(16, b.getShort(34).toInt())
    assertEquals("data", tag(36))
    assertEquals(48_000, b.getInt(40))
    assertEquals(1000L, speechAudioMs(48_000))
  }

  @Test
  fun theWavItWritesReadsBack() {
    val pcm = ByteBuffer.allocate(2 * 2400).order(ByteOrder.LITTLE_ENDIAN)
    repeat(2400) { pcm.putShort((sin(2 * PI * 440 * it / 24_000.0) * 10_000).toInt().toShort()) }
    val wav = WavWriter.wav(pcm.array())
    assertArrayEquals(pcm.array(), wav.copyOfRange(44, wav.size))
    val back = WavReader.read(wav)
    assertEquals(24_000, back.sourceRateHz)
    assertEquals(1, back.sourceChannels)
    assertEquals(16, back.sourceBits)
    assertEquals(0.1, back.seconds, 0.002)
  }

  @Test
  fun listsTheVoiceModelInV1Models() {
    val json = JsonParser.parseString(ApiJson.openAiModels(emptyList(), emptyList(), listOf(kokoro))).asJsonObject
    val m = json.getAsJsonArray("data").single().asJsonObject
    assertEquals("litert-community/Kokoro-82M", m.get("id").asString)
    val shelf = m.getAsJsonObject("modelshelf")
    assertEquals("speech", shelf.get("kind").asString)
    assertEquals("/v1/audio/speech", shelf.get("endpoint").asString)
    assertEquals("af_heart", shelf.get("default_voice").asString)
    assertEquals(kokoroVoices.size, shelf.getAsJsonArray("voices").size())
    assertEquals(24_000, shelf.get("sample_rate").asInt)
    assertEquals(4096, shelf.get("max_input_characters").asInt)
  }

  @Test
  fun aWaitingRequestAndOneRequestsAudioAreBounded() {
    // 4,096 characters outside the BMP, each written as two JSON escapes, fit in the body limit.
    val escaped = "\\uD83D\\uDE00".repeat(MAX_SPEECH_INPUT_CHARS)
    val body = "{\"model\":\"litert-community/Kokoro-82M\",\"voice\":\"af_heart\",\"input\":\"$escaped\"}"
    assertTrue(body.length < MAX_SPEECH_BODY_BYTES)
    assertEquals(MAX_SPEECH_INPUT_CHARS, parseSpeechRequest(body).inputChars)
    // 10 minutes of 24 kHz 16-bit mono, over twice 4,096 characters at 15 a second.
    assertEquals(28_800_000, MAX_SPEECH_AUDIO_BYTES)
    assertTrue(MAX_SPEECH_AUDIO_S > 2 * MAX_SPEECH_INPUT_CHARS / 15)
  }

  @Test
  fun psAndTheStatusLineShowTheSpeechModels() {
    val tts = LoadedAudioModel(kokoro.name, "speech", "gpu", kokoro.sizeBytes, kokoro.commit, Long.MAX_VALUE)
    val asr = LoadedAudioModel("litert-community/parakeet-tdt-0.6b-v3", "transcription", "gpu", 1, "c", 1_700_000_000_000)
    val models = JsonParser.parseString(ApiJson.ollamaPs(emptyList(), listOf(asr, tts))).asJsonObject.getAsJsonArray("models")
    assertEquals(2, models.size())
    val first = models[0].asJsonObject
    assertEquals("litert-community/parakeet-tdt-0.6b-v3", first.get("name").asString)
    assertEquals("transcription", first.getAsJsonObject("modelshelf").get("kind").asString)
    assertEquals("gpu", first.getAsJsonObject("modelshelf").get("backend").asString)
    assertEquals("speech", models[1].asJsonObject.getAsJsonObject("modelshelf").get("kind").asString)
    assertNull(LocalApiState().loadedLine())
    assertEquals(
      "Loaded: g on GPU, litert-community/Kokoro-82M on GPU",
      LocalApiState(loadedModel = "g", loadedBackend = "gpu", loadedAudio = listOf("${kokoro.name} on GPU")).loadedLine(),
    )
  }

  @Test
  fun transcriptionLanguageIsNotApplied() {
    val entry =
      AsrEntry("litert-community/Phonon-2.5-English", AsrKind.PHONON, "/x", 1, "c", listOf("en-US"), 0, "x")
    fun request(language: String?, ignored: List<String> = emptyList()) =
      TranscriptionRequest("m", FormPart("file", "a.wav", null, byteArrayOf(1)), language, TranscriptFormat.JSON, false, ignored)
    assertEquals(listOf("language"), transcriptionNotApplied(request("en"), entry))
    assertTrue(transcriptionNotApplied(request(null), entry).isEmpty())
    assertEquals(listOf("prompt", "language"), transcriptionNotApplied(request("en", listOf("prompt")), entry))
  }

  @Test
  fun theApiUnloadsTheSharedVoiceModelOnlyWhenItUsedItLast() {
    // Nothing noted: nothing to unload.
    assertFalse(EngineOwnership.apiMayUnloadTts(3))
    EngineOwnership.noteApiTtsUse(5)
    assertTrue(EngineOwnership.apiMayUnloadTts(5))
    // The mark is gone after the answer.
    assertFalse(EngineOwnership.apiMayUnloadTts(5))
    EngineOwnership.noteApiTtsUse(7)
    // The app called the engine after the API.
    assertFalse(EngineOwnership.apiMayUnloadTts(9))
  }

  @Test
  fun theLevelMeterGivesDbfsPerSecondAndASummary() {
    val lines = mutableListOf<String>()
    val meter = VoiceLevelMeter("test", 16_000) { lines.add(it) }
    val second = FloatArray(16_000) { (0.1 * sin(2 * PI * 400 * it / 16_000.0)).toFloat() }
    meter.add(second.copyOfRange(0, 1600))
    meter.add(second.copyOfRange(1600, 16_000))
    meter.add(FloatArray(8_000))
    meter.finish()
    meter.finish()
    assertEquals(lines.toString(), 3, lines.size)
    assertTrue(lines[0], lines[0].startsWith("Level test, second 1: rms -23.0 dBFS, peak -20.0 dBFS"))
    assertTrue(lines[1], lines[1].startsWith("Level test, second 2: rms -inf dBFS"))
    assertTrue(lines[2], lines[2].startsWith("Level test, summary: 1.50 s, rms -24.8 dBFS, peak -20.0 dBFS"))
    assertTrue(lines[2], lines[2].contains("loudest second -23.0 dBFS (second 1)"))
  }
}
