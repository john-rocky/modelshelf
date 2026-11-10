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

import com.google.ai.edge.gallery.services.tts.coalesceClausesForStaticShapeImpl
import com.google.ai.edge.gallery.services.tts.sanitizeTextForMediaPipeImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The API speaks one sentence per engine call, as the read-aloud does (line B). */
class SpeechPiecesTest {
  /** The passes the voice engine speaks for [piece] (Kokoro joins clauses up to 110 characters). */
  private fun passes(piece: String) = coalesceClausesForStaticShapeImpl(sanitizeTextForMediaPipeImpl(piece))

  @Test
  fun eachSentenceIsACallOfItsOwnAndKeepsItsEnd() {
    val pieces = speechPieces("Is it ready? Yes. Ship it.")
    assertEquals(listOf("Is it ready?", "Yes.", "Ship it."), pieces)
    for (p in pieces) assertEquals(listOf(p), passes(p))
    // The whole text in one call (round 10) turned the sentence ends into commas.
    assertEquals(listOf("Is it ready, Yes, Ship it."), passes("Is it ready? Yes. Ship it."))
  }

  @Test
  fun linesAreSentencesToo() {
    assertEquals(listOf("First line", "Second line"), speechPieces("First line\nSecond line"))
  }

  @Test
  fun aLongTextWithoutPunctuationIsSplitAtSpacesUnder300Characters() {
    val words = mutableListOf<String>()
    while (words.joinToString(" ").length < 4_090) words.add("word${words.size}")
    val input = words.joinToString(" ").take(4_096).substringBeforeLast(' ')
    val pieces = speechPieces(input)
    assertTrue(pieces.size >= 14)
    for (p in pieces) assertTrue("${p.length} characters", p.codePointCount(0, p.length) <= MAX_SPEECH_PIECE_CHARS)
    // No word is cut or lost.
    assertEquals(input, pieces.joinToString(" "))
  }

  @Test
  fun aLongSentenceIsSplitAtItsLastCommaBefore300Characters() {
    val first = List(40) { "alpha" }.joinToString(" ") // 239 characters
    val second = List(30) { "beta" }.joinToString(" ")
    val pieces = speechPieces("$first, $second.")
    assertEquals(listOf("$first,", "$second."), pieces)
  }

  @Test
  fun textWithNothingToSayGivesNoPiece() {
    assertEquals(emptyList<String>(), speechPieces("«»"))
    assertEquals(emptyList<String>(), speechPieces("🙂 🙂"))
    assertEquals(emptyList<String>(), speechPieces("  ...  "))
  }
}
