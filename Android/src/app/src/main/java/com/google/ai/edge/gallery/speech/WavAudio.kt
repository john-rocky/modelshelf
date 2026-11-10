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

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** The speech models of Model Shelf take 16 kHz mono audio. */
const val SPEECH_SAMPLE_RATE_HZ = 16_000

/** A file the speech models cannot read, with a sentence for the caller. */
class WavFormatException(message: String) : Exception(message)

/**
 * The audio of a WAV file as 16 kHz mono samples in [-1, 1], and what the file held.
 *
 * A 16 kHz mono 16-bit file gives exactly its samples divided by 32768 (the model card's reader).
 * Other files are mixed down to mono (the mean of the channels) and resampled to 16 kHz.
 */
class WavAudio(
  val samples: FloatArray,
  val sourceRateHz: Int,
  val sourceChannels: Int,
  val sourceBits: Int,
  /** "pcm" (integer samples) or "float". */
  val sourceEncoding: String,
) {
  val seconds: Double
    get() = samples.size / SPEECH_SAMPLE_RATE_HZ.toDouble()

  /** Whether the file needed no conversion (16 kHz, mono, 16-bit PCM). */
  val isNative: Boolean
    get() = sourceRateHz == SPEECH_SAMPLE_RATE_HZ && sourceChannels == 1 && sourceBits == 16 &&
      sourceEncoding == "pcm"

  fun describeSource(): String =
    "${sourceRateHz} Hz, ${if (sourceChannels == 1) "mono" else "$sourceChannels channels"}, " +
      "$sourceBits-bit ${if (sourceEncoding == "float") "float" else "PCM"}"
}

/** Reads WAV (RIFF/WAVE) files: PCM 8, 16, 24 or 32 bit, or float 32 or 64 bit, any channels. */
object WavReader {
  private const val FORMAT_PCM = 1
  private const val FORMAT_FLOAT = 3
  private const val FORMAT_EXTENSIBLE = 0xFFFE
  private const val MIN_RATE_HZ = 8_000
  private const val MAX_RATE_HZ = 192_000

  private class Fmt(val format: Int, val channels: Int, val rateHz: Int, val bits: Int, val blockAlign: Int)

  /**
   * Reads the [length] bytes of [bytes] from [offset] (the whole array by default); a file longer
   * than [maxSeconds] is refused before its samples are read. Only the 16 kHz output is allocated:
   * other rates are decoded and resampled block by block. [checkStopped] runs between blocks and
   * may throw (a cancelled request).
   */
  fun read(
    bytes: ByteArray,
    maxSeconds: Int = Int.MAX_VALUE,
    offset: Int = 0,
    length: Int = bytes.size - offset,
    checkStopped: () -> Unit = {},
  ): WavAudio {
    require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size)
    val end = offset + length
    if (length < 12 || !tag(bytes, offset, "RIFF", end) || !tag(bytes, offset + 8, "WAVE", end)) {
      throw WavFormatException(
        "The file is not a WAV file (it has no RIFF/WAVE header); send a WAV file, best 16 kHz " +
          "mono 16-bit PCM."
      )
    }
    var pos = offset + 12L
    var fmt: Fmt? = null
    var dataStart = -1
    var dataLength = 0
    while (pos + 8 <= end) {
      val at = pos.toInt()
      val id = String(bytes, at, 4, Charsets.US_ASCII)
      val size = u32(bytes, at + 4)
      val body = at + 8
      if (id == "fmt ") {
        fmt = parseFmt(bytes, body, size, end)
      } else if (id == "data") {
        // A writer that streamed the file may leave the size at 0 or 0xFFFFFFFF: read what is there.
        val available = (end - body).toLong()
        dataStart = body
        dataLength = (if (size <= 0L || size > available) available else size).toInt()
        break
      }
      if (body + size > end) break
      // Chunks are padded to an even size.
      pos = body + size + (size and 1L)
    }
    val f = fmt ?: throw WavFormatException("The WAV file has no \"fmt \" chunk.")
    if (dataStart < 0) throw WavFormatException("The WAV file has no \"data\" chunk.")
    val encoding =
      when {
        f.format == FORMAT_PCM && f.bits in listOf(8, 16, 24, 32) -> "pcm"
        f.format == FORMAT_FLOAT && f.bits in listOf(32, 64) -> "float"
        else ->
          throw WavFormatException(
            "The WAV file's encoding (format ${f.format}, ${f.bits}-bit) is not supported; send PCM " +
              "(8, 16, 24 or 32-bit) or float (32 or 64-bit) samples, best 16 kHz mono 16-bit PCM."
          )
      }
    if (f.channels < 1) throw WavFormatException("The WAV file says it has no channels.")
    if (f.rateHz !in MIN_RATE_HZ..MAX_RATE_HZ) {
      throw WavFormatException(
        "The WAV file's sample rate (${f.rateHz} Hz) is outside $MIN_RATE_HZ to $MAX_RATE_HZ Hz."
      )
    }
    val bytesPerSample = f.bits / 8
    val frameBytes = maxOf(f.blockAlign, bytesPerSample * f.channels)
    val frames = dataLength / frameBytes
    if (frames == 0) throw WavFormatException("The WAV file has no audio samples.")
    if (frames.toLong() > maxSeconds.toLong() * f.rateHz) {
      throw WavFormatException(
        "The audio is ${frames / f.rateHz} s long; send at most $maxSeconds s per request."
      )
    }
    // One mono sample: the mean of the channels. Float files can hold NaN and infinity: refuse them
    // here (the caller's mistake, a 400) rather than let the model fail on them.
    val decode = { i: Int ->
      var sum = 0.0
      val frameStart = dataStart + i * frameBytes
      for (c in 0 until f.channels) {
        sum += sample(bytes, frameStart + c * bytesPerSample, f.bits, encoding)
      }
      val v = if (f.channels == 1) sum.toFloat() else (sum / f.channels).toFloat()
      if (!v.isFinite()) {
        throw WavFormatException(
          "The WAV file has a sample that is not a finite number (NaN or infinity) at " +
            String.format(Locale.US, "%.3f", i.toDouble() / f.rateHz) + " s."
        )
      }
      v
    }
    val samples =
      if (f.rateHz == SPEECH_SAMPLE_RATE_HZ) {
        val out = FloatArray(frames)
        var i = 0
        while (i < frames) {
          val stop = minOf(frames, i + Resampler.BLOCK)
          while (i < stop) {
            out[i] = decode(i)
            i++
          }
          checkStopped()
        }
        out
      } else {
        Resampler.resample(frames, f.rateHz, SPEECH_SAMPLE_RATE_HZ, decode, checkStopped)
      }
    return WavAudio(samples, f.rateHz, f.channels, f.bits, encoding)
  }

  private fun parseFmt(b: ByteArray, at: Int, size: Long, end: Int): Fmt {
    if (size < 16 || at + 16 > end) throw WavFormatException("The WAV file's \"fmt \" chunk is too short.")
    var format = u16(b, at)
    val channels = u16(b, at + 2)
    val rate = u32(b, at + 4).toInt()
    val blockAlign = u16(b, at + 12)
    val bits = u16(b, at + 14)
    if (format == FORMAT_EXTENSIBLE && size >= 40 && at + 26 <= end) {
      // WAVE_FORMAT_EXTENSIBLE: the sub-format GUID starts with the real format code.
      format = u16(b, at + 24)
    }
    return Fmt(format, channels, rate, bits, blockAlign)
  }

  private fun sample(b: ByteArray, at: Int, bits: Int, encoding: String): Double =
    if (encoding == "float") {
      if (bits == 32) {
        java.lang.Float.intBitsToFloat(s32(b, at)).toDouble()
      } else {
        java.lang.Double.longBitsToDouble((s32(b, at).toLong() and 0xFFFFFFFFL) or (s32(b, at + 4).toLong() shl 32))
      }
    } else {
      when (bits) {
        8 -> ((b[at].toInt() and 0xFF) - 128) / 128.0
        16 -> ((b[at].toInt() and 0xFF) or (b[at + 1].toInt() shl 8)).toShort() / 32768.0
        24 -> {
          val v = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() shl 16)
          v / 8388608.0
        }
        else -> s32(b, at) / 2147483648.0
      }
    }

  private fun tag(b: ByteArray, at: Int, s: String, end: Int): Boolean =
    at + 4 <= end && String(b, at, 4, Charsets.US_ASCII) == s

  private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

  private fun u32(b: ByteArray, at: Int): Long = s32(b, at).toLong() and 0xFFFFFFFFL

  private fun s32(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or
      (b[at + 3].toInt() shl 24)
}

/**
 * Band-limited resampling with a Blackman-windowed sinc (16 zero crossings each side). The kernel
 * is computed once per call into a table (256 points per input sample, read with linear
 * interpolation), and the input is decoded block by block, so only the output array is allocated.
 */
object Resampler {
  private const val ZERO_CROSSINGS = 16
  private const val TABLE_POINTS_PER_SAMPLE = 256

  /** Output samples per block; [resample]'s check runs once per block. */
  const val BLOCK = 8192

  /** Resamples [input] (tests and small inputs). */
  fun resample(input: FloatArray, fromHz: Int, toHz: Int): FloatArray =
    if (fromHz == toHz || input.isEmpty()) input.copyOf() else resample(input.size, fromHz, toHz, { input[it] })

  /**
   * Resamples the [frames] samples that [sampleAt] gives (any index in 0 until [frames], in any
   * order) from [fromHz] to [toHz]. [checkStopped] runs once per [BLOCK] output samples.
   */
  fun resample(
    frames: Int,
    fromHz: Int,
    toHz: Int,
    sampleAt: (Int) -> Float,
    checkStopped: () -> Unit = {},
  ): FloatArray {
    if (frames <= 0) return FloatArray(0)
    val ratio = toHz.toDouble() / fromHz
    val outLength = maxOf(1, floor(frames * ratio).toInt())
    // The cutoff sits a little under the lower Nyquist frequency.
    val cutoff = 0.97 * min(1.0, ratio)
    val halfWidth = ZERO_CROSSINGS / cutoff
    val table = kernelTable(cutoff, halfWidth)
    val out = FloatArray(outLength)
    // The input of one block, decoded once: its centres span BLOCK / ratio samples, plus the kernel.
    val window = FloatArray(ceil(BLOCK / ratio + 2 * halfWidth).toInt() + 4)
    var o0 = 0
    while (o0 < outLength) {
      val o1 = minOf(outLength, o0 + BLOCK)
      val s0 = maxOf(0, ceil(o0 / ratio - halfWidth).toInt())
      val s1 = min(frames - 1, floor((o1 - 1) / ratio + halfWidth).toInt())
      for (j in s0..s1) window[j - s0] = sampleAt(j)
      for (i in o0 until o1) {
        val center = i / ratio
        val first = maxOf(s0, ceil(center - halfWidth).toInt())
        val last = min(s1, floor(center + halfWidth).toInt())
        var acc = 0.0
        var norm = 0.0
        for (j in first..last) {
          val w = kernel(table, abs(j - center))
          acc += w * window[j - s0]
          norm += w
        }
        out[i] = if (abs(norm) > 1e-9) (acc / norm).toFloat() else 0f
      }
      checkStopped()
      o0 = o1
    }
    return out
  }

  /** The kernel at |x| = 0, 1/256, 2/256, ... up to the half width (it is symmetric). */
  private fun kernelTable(cutoff: Double, halfWidth: Double): DoubleArray {
    val n = ceil(halfWidth * TABLE_POINTS_PER_SAMPLE).toInt() + 2
    return DoubleArray(n) { k ->
      val x = k.toDouble() / TABLE_POINTS_PER_SAMPLE
      if (x >= halfWidth) {
        0.0
      } else {
        val t = PI * cutoff * x
        val sinc = if (abs(t) < 1e-12) 1.0 else sin(t) / t
        val m = (x + halfWidth) / (2 * halfWidth)
        val window = 0.42 - 0.5 * cos(2 * PI * m) + 0.08 * cos(4 * PI * m)
        sinc * window
      }
    }
  }

  private fun kernel(table: DoubleArray, ax: Double): Double {
    val t = ax * TABLE_POINTS_PER_SAMPLE
    val k = t.toInt()
    if (k + 1 >= table.size) return 0.0
    val frac = t - k
    return table[k] + (table[k + 1] - table[k]) * frac
  }
}
