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

/** The two calls of a TDT decoder: the prediction network and the joint network. */
interface TdtSteps {
  /** Starts a window: the prediction network takes the blank token with zero state. */
  fun reset()

  /** The joint network's logits for encoder frame [frame] and the current prediction output. */
  fun joint(frame: Int): FloatArray

  /** Feeds an emitted token to the prediction network. */
  fun predict(token: Int)
}

/** One emitted token: its id, the encoder frame it was emitted at and its predicted duration. */
data class TdtToken(val id: Int, val frame: Int, val duration: Int)

/**
 * Greedy TDT decoding of one window, as in the Phonon-2.5-English card's "How it works" and its
 * Python runner: start from blank with zero state; at each step take the arg-max token and the
 * arg-max duration; a blank with duration 0 moves one frame; a non-blank token is emitted and fed
 * to `predict`; ten tokens on one frame move one frame; the end token ends the window; stop at the
 * last valid frame or after [maxSteps] steps.
 */
class TdtGreedyDecoder(
  /** Token logits including blank (8,192 tokens + blank for parakeet-tdt-0.6b-v3's tokenizer). */
  val vocabWithBlank: Int = 8193,
  val numDurations: Int = 5,
  val blank: Int = 8192,
  val endToken: Int = 3,
  val maxSteps: Int = 3750,
  val maxTokensPerFrame: Int = 10,
) {
  /** Steps of the last [decode], for the log. */
  var lastSteps: Int = 0
    private set

  fun decode(steps: TdtSteps, validFrames: Int): List<TdtToken> {
    val out = ArrayList<TdtToken>()
    steps.reset()
    var t = 0
    var n = 0
    var run = 0
    while (t < validFrames && n < maxSteps) {
      n++
      val logits = steps.joint(t)
      val token = argmax(logits, 0, vocabWithBlank)
      var d = argmax(logits, vocabWithBlank, vocabWithBlank + numDurations) - vocabWithBlank
      if (token == blank && d == 0) d = 1
      if (token != blank) {
        out.add(TdtToken(token, t, d))
        if (token == endToken) break
        steps.predict(token)
      }
      if (d == 0) {
        run++
        if (run >= maxTokensPerFrame) d = 1
      }
      if (d > 0) run = 0
      t += d
    }
    lastSteps = n
    return out
  }

  companion object {
    /** The first index of the largest value in [from, until), as numpy's argmax (NaN never wins here). */
    fun argmax(values: FloatArray, from: Int, until: Int): Int {
      var best = from
      var bestValue = values[from]
      for (i in from + 1 until until) {
        val v = values[i]
        if (v > bestValue || (bestValue.isNaN() && !v.isNaN())) {
          best = i
          bestValue = v
        }
      }
      return best
    }
  }
}
