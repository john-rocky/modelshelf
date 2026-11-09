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

package com.google.ai.edge.gallery.speech

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin TDT decode and tokenizer against the Phonon-2.5-English card's Python code, run on a
 * Mac for 20 LibriSpeech test-clean utterances (CC BY 4.0; fixtures in resources/phonon:
 * `ref_windows.jsonl` = each window's decode with the arg-max token and duration of every joint
 * call and the reference text; `tokenizer_subset.json` = the pieces of the model's tokenizer.json
 * those texts use).
 */
class PhononReferenceTest {
  private fun resource(name: String): String =
    javaClass.getResourceAsStream("/phonon/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

  private val utterances: List<JsonObject> =
    resource("ref_windows.jsonl").lines().filter { it.isNotBlank() }.map {
      JsonParser.parseString(it).asJsonObject
    }

  private fun ints(o: JsonObject, key: String): List<Int> = o.getAsJsonArray(key).map { it.asInt }

  /** Replays recorded joint decisions: logits with 1.0 at the recorded token and duration. */
  private class Replay(private val steps: List<Pair<Int, Int>>) : TdtSteps {
    var joints = 0
    val framesAsked = mutableListOf<Int>()
    val predicted = mutableListOf<Int>()
    var resets = 0

    override fun reset() {
      resets++
    }

    override fun joint(frame: Int): FloatArray {
      val (token, d) = steps[joints++]
      framesAsked.add(frame)
      return FloatArray(8193 + 5).also {
        it[token] = 1f
        it[8193 + d] = 1f
      }
    }

    override fun predict(token: Int) {
      predicted.add(token)
    }
  }

  @Test
  fun greedyDecodeMatchesTheCardsPythonLoopOnEveryWindow() {
    var windows = 0
    var steps = 0
    for (u in utterances) {
      for (w in u.getAsJsonArray("windows").map { it.asJsonObject }) {
        val recorded = w.getAsJsonArray("steps").map { it.asJsonArray[0].asInt to it.asJsonArray[1].asInt }
        val replay = Replay(recorded)
        val decoder = TdtGreedyDecoder()
        val tokens = decoder.decode(replay, w.get("valid_frames").asInt)
        val id = u.get("id").asString
        assertEquals(id, ints(w, "ids"), tokens.map { it.id })
        assertEquals(id, ints(w, "frames"), tokens.map { it.frame })
        assertEquals(id, ints(w, "durs"), tokens.map { it.duration })
        assertEquals(id, recorded.size, replay.joints)
        assertEquals(id, recorded.size, decoder.lastSteps)
        assertEquals(id, 1, replay.resets)
        // Every emitted token but the end token goes to the prediction network.
        assertEquals(id, tokens.map { it.id }.filter { it != 3 }, replay.predicted)
        windows++
        steps += recorded.size
      }
    }
    assertEquals(20, windows)
    assertEquals(1309, steps)
  }

  @Test
  fun argmaxTakesTheFirstMaximumLikeNumpy() {
    assertEquals(1, TdtGreedyDecoder.argmax(floatArrayOf(0f, 2f, 2f, 1f), 0, 4))
    assertEquals(3, TdtGreedyDecoder.argmax(floatArrayOf(9f, 0f, 0f, 1f, 1f), 1, 5))
    assertEquals(1, TdtGreedyDecoder.argmax(floatArrayOf(Float.NaN, 0.5f, 0.2f), 0, 3))
  }

  @Test
  fun aBlankWithDurationZeroMovesOneFrameAndTenTokensOnAFrameMoveOn() {
    // Frame 0: blank with duration 0 (moves 1); frame 1: ten tokens with duration 0 (the tenth
    // moves 1); frame 2: blank with duration 4 (past the last valid frame).
    val steps = listOf(8192 to 0) + List(10) { 100 to 0 } + listOf(8192 to 4)
    val replay = Replay(steps)
    val tokens = TdtGreedyDecoder().decode(replay, validFrames = 3)
    assertEquals(listOf(0) + List(10) { 1 } + listOf(2), replay.framesAsked)
    assertEquals(10, tokens.count { it.frame == 1 })
  }

  @Test
  fun tokenizerDecodesTheReferenceTextsAsTheHuggingFaceTokenizerDid() {
    val tokenizer = TdtTokenizer.parse(resource("tokenizer_subset.json").reader())
    for (u in utterances) {
      val texts =
        u.getAsJsonArray("windows").map { tokenizer.decode(ints(it.asJsonObject, "ids"), dropIds = setOf(3)) }
      val joined = texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
      assertEquals(u.get("id").asString, u.get("text").asString, joined)
    }
  }

  @Test
  fun tokenizerSkipsSpecialTokensAndDropsTheEndToken() {
    val tokenizer = TdtTokenizer.parse(resource("tokenizer_subset.json").reader())
    val ids = ints(utterances[0].getAsJsonArray("windows")[0].asJsonObject, "ids")
    val plain = tokenizer.decode(ids)
    // <unk> (0) and <pad> (2) are special; <|endoftext|> (3) is not, so it is dropped by id.
    assertEquals(plain, tokenizer.decode(listOf(0) + ids + listOf(2)))
    assertEquals(plain, tokenizer.decode(ids + listOf(3), dropIds = setOf(3)))
    assertTrue(tokenizer.decode(ids + listOf(3)).endsWith("<|endoftext|>"))
    assertTrue(!plain.startsWith(" "))
  }
}
