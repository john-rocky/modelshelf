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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiProtocolTest {

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

  // ---- Ollama /api/chat ---------------------------------------------------------------------

  @Test
  fun ollamaChatReadsMessagesOptionsAndDefaults() {
    val r =
      parseOllamaChat(
        """{"model":"m","messages":[{"role":"system","content":"Be brief."},
          {"role":"user","content":"Hi"}],
          "options":{"temperature":0.2,"top_k":5,"top_p":0.9,"num_predict":64,"num_ctx":2048,
          "seed":7,"repeat_penalty":1.1},"keep_alive":"10m"}"""
      )
    assertEquals(Dialect.OLLAMA_CHAT, r.dialect)
    assertEquals("m", r.model)
    assertEquals(
      listOf(ChatTurn(TurnRole.SYSTEM, "Be brief."), ChatTurn(TurnRole.USER, "Hi")),
      r.turns,
    )
    assertTrue("Ollama streams by default", r.stream)
    assertEquals(SamplerRequest(topK = 5, topP = 0.9, temperature = 0.2, seed = 7), r.sampler)
    assertEquals(64, r.maxOutputTokens)
    assertEquals(2048, r.numCtx)
    assertEquals(600_000L, r.keepAliveMs)
    assertEquals(listOf("options.repeat_penalty"), r.ignored)
  }

  @Test
  fun ollamaChatStreamFalseAndUnlimitedPredict() {
    val r =
      parseOllamaChat(
        """{"model":"m","stream":false,"messages":[{"role":"user","content":"x"}],
          "options":{"num_predict":-1}}"""
      )
    assertFalse(r.stream)
    assertNull(r.maxOutputTokens)
  }

  @Test
  fun ollamaChatRefusesWhatV0DoesNotDo() {
    val user = """[{"role":"user","content":"x"}]"""
    assertRefused(400) { parseOllamaChat("""{"model":"m","messages":$user,"format":"json"}""") }
    assertRefused(400) {
      parseOllamaChat("""{"model":"m","messages":$user,"tools":[{"type":"function"}]}""")
    }
    assertRefused(400) { parseOllamaChat("""{"model":"m","messages":$user,"think":true}""") }
    assertRefused(400) {
      parseOllamaChat("""{"model":"m","messages":$user,"options":{"stop":["\n"]}}""")
    }
    assertRefused(400) {
      parseOllamaChat("""{"model":"m","messages":[{"role":"user","content":"x","images":["aGk="]}]}""")
    }
    assertRefused(400) {
      parseOllamaChat("""{"model":"m","messages":[{"role":"tool","content":"x"}]}""")
    }
    assertRefused(400) { parseOllamaChat("""{"messages":$user}""") }
    assertRefused(400) { parseOllamaChat("not json") }
    assertRefused(400) { parseOllamaChat("[1,2]") }
    assertRefused(400) {
      parseOllamaChat("""{"model":"m","messages":$user,"options":{"top_k":0}}""")
    }
  }

  @Test
  fun ollamaChatAcceptsThinkFalseAndEmptyFormat() {
    val r =
      parseOllamaChat(
        """{"model":"m","messages":[{"role":"user","content":"x"}],"think":false,"format":""}"""
      )
    assertEquals(1, r.turns.size)
  }

  @Test
  fun ollamaChatWithoutMessagesIsALoadRequest() {
    val r = parseOllamaChat("""{"model":"m","keep_alive":0}""")
    assertTrue(r.turns.isEmpty())
    assertEquals(0L, r.keepAliveMs)
  }

  // ---- Ollama /api/generate -----------------------------------------------------------------

  @Test
  fun ollamaGenerateBuildsSystemAndPrompt() {
    val r = parseOllamaGenerate("""{"model":"m","system":"S","prompt":"P","stream":false}""")
    assertEquals(
      listOf(ChatTurn(TurnRole.SYSTEM, "S"), ChatTurn(TurnRole.USER, "P")),
      r.turns,
    )
    assertFalse(r.stream)
    assertTrue(parseOllamaGenerate("""{"model":"m","system":"S"}""").turns.isEmpty())
    assertRefused(400) { parseOllamaGenerate("""{"model":"m","prompt":"P","context":[1,2,3]}""") }
    assertRefused(400) { parseOllamaGenerate("""{"model":"m","prompt":"P","raw":true}""") }
  }

  // ---- OpenAI /v1/chat/completions ----------------------------------------------------------

  @Test
  fun openAiChatReadsFieldsAndParts() {
    val r =
      parseOpenAiChat(
        """{"model":"m","messages":[{"role":"developer","content":"D"},
          {"role":"user","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}],
          "temperature":0,"top_p":0.5,"max_tokens":10,"max_completion_tokens":20,"seed":3,
          "stream":true,"stream_options":{"include_usage":true},"presence_penalty":0.5,
          "frequency_penalty":0,"user":"u"}"""
      )
    assertEquals(Dialect.OPENAI_CHAT, r.dialect)
    assertEquals(
      listOf(ChatTurn(TurnRole.SYSTEM, "D"), ChatTurn(TurnRole.USER, "a\nb")),
      r.turns,
    )
    assertEquals(0.0, r.sampler.temperature!!, 0.0)
    assertEquals(0.5, r.sampler.topP!!, 0.0)
    assertEquals(3, r.sampler.seed)
    assertEquals("max_completion_tokens wins", 20, r.maxOutputTokens)
    assertTrue(r.stream)
    assertTrue(r.includeUsage)
    assertEquals(listOf("presence_penalty"), r.ignored)
  }

  @Test
  fun openAiChatDefaultsToNoStream() {
    assertFalse(parseOpenAiChat("""{"model":"m","messages":[{"role":"user","content":"x"}]}""").stream)
  }

  @Test
  fun openAiChatRefusesWhatV0DoesNotDo() {
    val user = """[{"role":"user","content":"x"}]"""
    assertRefused(400) { parseOpenAiChat("""{"model":"m","messages":$user,"n":2}""") }
    assertRefused(400) { parseOpenAiChat("""{"model":"m","messages":$user,"stop":"END"}""") }
    assertRefused(400) {
      parseOpenAiChat("""{"model":"m","messages":$user,"tools":[{"type":"function"}]}""")
    }
    assertRefused(400) {
      parseOpenAiChat("""{"model":"m","messages":$user,"response_format":{"type":"json_object"}}""")
    }
    assertRefused(400) {
      parseOpenAiChat(
        """{"model":"m","messages":[{"role":"user","content":[{"type":"image_url",
          "image_url":{"url":"data:,"}}]}]}"""
      )
    }
    assertRefused(400) { parseOpenAiChat("""{"model":"m","messages":[]}""") }
    assertRefused(400) { parseOpenAiChat("""{"model":"m"}""") }
    assertRefused(400) { parseOpenAiChat("""{"model":"m","messages":$user,"temperature":3}""") }
    // An empty stop list and a text response format are fine.
    parseOpenAiChat("""{"model":"m","messages":$user,"stop":[],"response_format":{"type":"text"}}""")
  }

  // ---- keep_alive ---------------------------------------------------------------------------

  @Test
  fun keepAliveForms() {
    fun ka(json: String) = parseKeepAlive(JsonParser.parseString(json))
    assertEquals(300_000L, ka("\"5m\""))
    assertEquals(30_000L, ka("\"30s\""))
    assertEquals(30_000L, ka("\"30\""))
    assertEquals(500L, ka("\"500ms\""))
    assertEquals(3_600_000L, ka("\"1h\""))
    assertEquals(0L, ka("0"))
    assertEquals(0L, ka("\"0\""))
    assertEquals(120_000L, ka("120"))
    assertEquals(-1L, ka("-1"))
    assertEquals(-1L, ka("\"-1m\""))
    assertNull(parseKeepAlive(null))
    assertRefused(400) { ka("\"soon\"") }
  }

  // ---- Sampler order ------------------------------------------------------------------------

  @Test
  fun samplerTakesTheRequestThenTheCardThenTheApp() {
    val card = SamplerDefaults(topK = 20, topP = 0.8, temperature = null, source = "the model card")
    val s = resolveSampler(SamplerRequest(topK = 3), card, npu = false)
    assertEquals(Setting(3, REQUEST_SOURCE), s.topK)
    assertEquals(Setting(0.8, "the model card"), s.topP)
    assertEquals(Setting(1.0, APP_DEFAULT_SOURCE), s.temperature)
    assertTrue(s.applied)
    val app = resolveSampler(SamplerRequest(), SamplerDefaults(), npu = false)
    assertEquals(64, app.topK.value)
    assertEquals(0.95, app.topP.value, 0.0)
    assertTrue(app.describe().contains("top-k 64 (the app's default)"))
  }

  @Test
  fun temperatureZeroIsGreedy() {
    val s = resolveSampler(SamplerRequest(temperature = 0.0, topK = 40), SamplerDefaults(), npu = false)
    assertEquals(1, s.topK.value)
    assertEquals(1.0, s.topP.value, 0.0)
    assertEquals(0.0, s.temperature.value, 0.0)
  }

  @Test
  fun npuFilesTakeNoSampler() {
    val s = resolveSampler(SamplerRequest(topK = 3), SamplerDefaults(), npu = true)
    assertFalse(s.applied)
    assertEquals("npu-file-own", s.header())
  }

  // ---- Conversation plan --------------------------------------------------------------------

  @Test
  fun aRepeatPlusOneUserMessageContinues() {
    val held =
      listOf(
        ChatTurn(TurnRole.SYSTEM, "S"),
        ChatTurn(TurnRole.USER, "Q1"),
        ChatTurn(TurnRole.ASSISTANT, "A1"),
      )
    val next = held + ChatTurn(TurnRole.USER, "Q2")
    assertEquals(TurnPlan.Continue(ChatTurn(TurnRole.USER, "Q2")), planTurn(held, next))
  }

  @Test
  fun anythingElseStartsANewConversation() {
    val held = listOf(ChatTurn(TurnRole.USER, "Q1"), ChatTurn(TurnRole.ASSISTANT, "A1"))
    val edited =
      listOf(
        ChatTurn(TurnRole.SYSTEM, "S1"),
        ChatTurn(TurnRole.USER, "Q1"),
        ChatTurn(TurnRole.ASSISTANT, "A1 changed"),
        ChatTurn(TurnRole.SYSTEM, "S2"),
        ChatTurn(TurnRole.USER, "Q2"),
      )
    val plan = planTurn(held, edited)
    assertEquals(
      TurnPlan.Fresh(
        system = "S1\n\nS2",
        earlier = listOf(ChatTurn(TurnRole.USER, "Q1"), ChatTurn(TurnRole.ASSISTANT, "A1 changed")),
        last = ChatTurn(TurnRole.USER, "Q2"),
      ),
      plan,
    )
    assertTrue(planTurn(null, listOf(ChatTurn(TurnRole.USER, "x"))) is TurnPlan.Fresh)
    assertRefused(400) { planTurn(null, listOf(ChatTurn(TurnRole.ASSISTANT, "x"))) }
    assertRefused(400) { planTurn(null, emptyList()) }
  }

  // ---- Limits and names ---------------------------------------------------------------------

  @Test
  fun outputBudget() {
    assertEquals(100, resolveMaxOutputTokens(100, null, 4000))
    assertEquals(DEFAULT_MAX_OUTPUT_TOKENS, resolveMaxOutputTokens(null, null, 4000))
    assertEquals(3000, resolveMaxOutputTokens(null, 3000, 4000))
    assertEquals(1024, resolveMaxOutputTokens(9999, null, 1024))
  }

  @Test
  fun modelNames() {
    data class E(val name: String, val repo: String)
    val list =
      listOf(
        E("litert-community/gemma-4-E2B-it-litert-lm:gemma-4-E2B-it", "litert-community/gemma-4-E2B-it-litert-lm"),
        E("litert-community/LFM2.5-230M:LFM2.5-230M_int4", "litert-community/LFM2.5-230M"),
        E("litert-community/LFM2.5-230M:LFM2.5-230M_int8", "litert-community/LFM2.5-230M"),
      )
    fun find(n: String) = matchModel(n, list, { it.name }, { it.repo })?.name
    assertEquals(list[0].name, find(list[0].name))
    assertEquals(list[0].name, find(list[0].name.uppercase()))
    assertEquals(list[0].name, find("hf.co/" + list[0].name))
    assertEquals(list[0].name, find(list[0].name + ":latest"))
    assertEquals("one file of the repo", list[0].name, find("litert-community/gemma-4-E2B-it-litert-lm"))
    assertNull("two files of the repo", find("litert-community/LFM2.5-230M"))
    assertNull(find("nope"))
  }

  // ---- Response shapes the official clients read --------------------------------------------

  private val entry =
    ApiModelEntry(
      name = "litert-community/x:x_int4",
      repoId = "litert-community/x",
      file = "x_int4.litertlm",
      path = "/tmp/x_int4.litertlm",
      sizeBytes = 123,
      digest = "abc",
      commit = "c0ffee",
      modifiedMs = 1_700_000_000_000,
      accelerators = listOf("gpu", "cpu"),
      maxNumTokens = 4096,
      contextLength = 4096,
      sampler = SamplerDefaults(topK = 20, source = "the model card (session_defaults)"),
      stateful = true,
      statefulWhy = "litertlm_manifest.json known_issues",
      requestTimeoutS = null,
      maxOutputTokensMin = null,
      listedBy = "the shelf",
      checked = null,
    )

  @Test
  fun showHasTheFieldsOllamaPythonNeeds() {
    val o = JsonParser.parseString(ApiJson.ollamaShow(entry)).asJsonObject
    // ollama-python 0.6.3 ShowResponse: model_info has no default, so it must be present.
    assertTrue(o.has("model_info"))
    assertEquals("top_k 20\ntop_p 0.95\ntemperature 1.0\nnum_ctx 4096", o.get("parameters").asString)
    val ms = o.getAsJsonObject("modelshelf")
    assertTrue(ms.get("stateful").asBoolean)
    assertEquals(
      "the model card (session_defaults)",
      ms.getAsJsonObject("sampler").getAsJsonObject("top_k").get("source").asString,
    )
    assertEquals(300, ms.get("request_timeout_s").asInt)
  }

  @Test
  fun showGivesTheEngineLimitNotTheModelsContextLength() {
    // A model that could take 32,000 tokens, in an engine the app makes for 4,096.
    val big = entry.copy(maxNumTokens = 4096, contextLength = 32_000)
    val o = JsonParser.parseString(ApiJson.ollamaShow(big)).asJsonObject
    assertEquals(4096, o.getAsJsonObject("model_info").get("litertlm.context_length").asInt)
    assertTrue(o.get("parameters").asString.endsWith("num_ctx 4096"))
    assertEquals(32_000, o.getAsJsonObject("modelshelf").get("context_length").asInt)
  }

  // ---- options.num_ctx ----------------------------------------------------------------------

  @Test
  fun numCtxIsCappedAtTheModelsMaxTokens() {
    assertEquals(4096, resolveEngineTokens(null, 4096, null))
    assertEquals(2048, resolveEngineTokens(2048, 4096, null))
    // A model list without a context length (most of them) no longer lets num_ctx grow the engine.
    assertEquals(4096, resolveEngineTokens(1_000_000, 4096, null))
    assertEquals(4096, resolveEngineTokens(1_000_000, 4096, 32_000))
    assertEquals(1024, resolveEngineTokens(8192, 4096, 1024))
    assertEquals(1, resolveEngineTokens(1, 4096, null))
  }

  @Test
  fun aBiggerEngineServesASmallerNumCtxWithoutAReload() {
    assertTrue(engineServes(loadedTokens = 4096, wantedTokens = 2048))
    assertTrue(engineServes(loadedTokens = 4096, wantedTokens = 4096))
    assertFalse(engineServes(loadedTokens = 2048, wantedTokens = 4096))
  }

  @Test
  fun notAppliedHeaderKeepsOnlySafeCharacters() {
    assertEquals(
      "options.mirostat,options.badnameX-Injected1",
      headerSafe(listOf("options.mirostat", "options.bad name\r\nX-Injected: 1")),
    )
    assertEquals(64, headerSafe(listOf("x".repeat(500))).length)
  }

  @Test
  fun tagsAndModelsList() {
    val tags = JsonParser.parseString(ApiJson.ollamaTags(listOf(entry))).asJsonObject
    val m = tags.getAsJsonArray("models")[0].asJsonObject
    assertEquals(entry.name, m.get("model").asString)
    assertEquals("int4", m.getAsJsonObject("details").get("quantization_level").asString)
    assertEquals("not checked on a phone", m.getAsJsonObject("details").get("checked").asString)
    val models = JsonParser.parseString(ApiJson.openAiModels(listOf(entry))).asJsonObject
    assertEquals("list", models.get("object").asString)
    val d = models.getAsJsonArray("data")[0].asJsonObject
    assertEquals(entry.name, d.get("id").asString)
    assertEquals("litert-community", d.get("owned_by").asString)
  }

  @Test
  fun chunkShapes() {
    val stats = TurnStats(1, 2, 3, 10, 20, 5, 7)
    val done = JsonParser.parseString(ApiJson.ollamaDone("m", false, "", "stop", stats)).asJsonObject
    assertTrue(done.get("done").asBoolean)
    assertEquals("stop", done.get("done_reason").asString)
    assertEquals(5, done.get("eval_count").asInt)
    assertEquals(7, done.get("prompt_eval_count").asInt)
    val chunk =
      JsonParser.parseString(ApiJson.openAiChunk("id", "m", 1L, ApiJson.textDelta("hi"), null))
        .asJsonObject
    assertEquals("chat.completion.chunk", chunk.get("object").asString)
    val choice = chunk.getAsJsonArray("choices")[0].asJsonObject
    assertEquals("hi", choice.getAsJsonObject("delta").get("content").asString)
    assertTrue(choice.get("finish_reason").isJsonNull)
    val error = JsonParser.parseString(ApiJson.openAiError("no", 401, "invalid_api_key")).asJsonObject
    assertEquals("invalid_api_key", error.getAsJsonObject("error").get("code").asString)
  }
}
