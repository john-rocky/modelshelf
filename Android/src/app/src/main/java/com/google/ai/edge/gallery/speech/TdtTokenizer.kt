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
import java.io.File
import java.io.Reader

/**
 * Token ids to text with a Hugging Face `tokenizer.json` whose decoder is Metaspace (the tokenizer
 * of parakeet-tdt-0.6b-v3, which Phonon-2.5-English keeps), the way the card's `tdt_text.py` decodes
 * (`Tokenizer.decode(ids, skip_special_tokens=True)`): special added tokens are skipped, the other
 * ids become their pieces, the replacement character becomes a space, and the first piece loses its
 * replacement characters (prepend scheme "always").
 */
class TdtTokenizer private constructor(
  private val pieces: Array<String?>,
  private val special: BooleanArray,
  private val replacement: Char,
  private val stripFirst: Boolean,
) {
  val size: Int
    get() = pieces.size

  /** The text of [ids]; ids in [dropIds] are left out (the end token, so it never shows as text). */
  fun decode(ids: List<Int>, dropIds: Set<Int> = emptySet()): String {
    val sb = StringBuilder()
    var index = 0
    for (id in ids) {
      if (id in dropIds) continue
      if (id < 0 || id >= pieces.size) continue
      if (special[id]) continue
      val piece = pieces[id] ?: continue
      for (c in piece) {
        if (c == replacement) {
          if (!(index == 0 && stripFirst)) sb.append(' ')
        } else {
          sb.append(c)
        }
      }
      index++
    }
    return sb.toString()
  }

  companion object {
    fun load(file: File): TdtTokenizer = file.reader(Charsets.UTF_8).use { parse(it) }

    fun parse(reader: Reader): TdtTokenizer {
      val root = JsonParser.parseReader(reader).asJsonObject
      val model = root.getAsJsonObject("model") ?: throw IllegalArgumentException("tokenizer.json has no model")
      val vocab = model.get("vocab")
      val byId = HashMap<Int, String>()
      if (vocab != null && vocab.isJsonObject) {
        for ((piece, id) in vocab.asJsonObject.entrySet()) byId[id.asInt] = piece
      } else if (vocab != null && vocab.isJsonArray) {
        // A Unigram vocabulary: [piece, score] by id.
        vocab.asJsonArray.forEachIndexed { i, e -> byId[i] = e.asJsonArray[0].asString }
      } else {
        throw IllegalArgumentException("tokenizer.json has no vocabulary")
      }
      val specialIds = HashSet<Int>()
      root.getAsJsonArray("added_tokens")?.forEach { e ->
        val o = e.asJsonObject
        val id = o.get("id").asInt
        byId[id] = o.get("content").asString
        if (o.get("special")?.asBoolean == true) specialIds.add(id)
      }
      val decoder = root.get("decoder")?.takeIf { it.isJsonObject }?.asJsonObject
      val (replacement, stripFirst) = metaspace(decoder)
      val size = (byId.keys.maxOrNull() ?: -1) + 1
      val pieces = arrayOfNulls<String>(size)
      for ((id, piece) in byId) pieces[id] = piece
      val special = BooleanArray(size) { it in specialIds }
      return TdtTokenizer(pieces, special, replacement, stripFirst)
    }

    private fun metaspace(decoder: JsonObject?): Pair<Char, Boolean> {
      val type = decoder?.get("type")?.asString
      require(type == "Metaspace") { "tokenizer.json's decoder is $type; only Metaspace is supported" }
      val replacement = decoder.get("replacement")?.asString?.firstOrNull() ?: '▁'
      val scheme = decoder.get("prepend_scheme")?.asString ?: "always"
      return replacement to (scheme != "never")
    }
  }
}
