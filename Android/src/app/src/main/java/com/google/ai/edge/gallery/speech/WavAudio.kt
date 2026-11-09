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

  /** Reads [bytes]; a file longer than [maxSeconds] is refused before its samples are read. */
  fun read(bytes: ByteArray, maxSeconds: Int = Int.MAX_VALUE): WavAudio {
    if (bytes.size < 12 || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE")) {
      throw WavFormatException(
        "The file is not a WAV file (it has no RIFF/WAVE header); send a WAV file, best 16 kHz " +
          "mono 16-bit PCM."
      )
    }
    var pos = 12
    var fmt: Fmt? = null
    var dataStart = -1
    var dataLength = 0
    while (pos + 8 <= bytes.size) {
      val id = String(bytes, pos, 4, Charsets.US_ASCII)
      val size = u32(bytes, pos + 4)
      val body = pos + 8
      if (id == "fmt ") {
        fmt = parseFmt(bytes, body, size)
      } else if (id == "data") {
        // A writer that streamed the file may leave the size at 0 or 0xFFFFFFFF: read what is there.
        val available = (bytes.size - body).toLong()
        dataStart = body
        dataLength = (if (size <= 0L || size > available) available else size).toInt()
        break
      }
      if (size < 0L || body + size > bytes.size) break
      pos = (body + size + (size and 1L)).toInt()
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
    val mono = FloatArray(frames)
    for (i in 0 until frames) {
      var sum = 0.0
      val frameStart = dataStart + i * frameBytes
      for (c in 0 until f.channels) {
        sum += sample(bytes, frameStart + c * bytesPerSample, f.bits, encoding)
      }
      mono[i] = if (f.channels == 1) sum.toFloat() else (sum / f.channels).toFloat()
    }
    val samples = if (f.rateHz == SPEECH_SAMPLE_RATE_HZ) mono else Resampler.resample(mono, f.rateHz, SPEECH_SAMPLE_RATE_HZ)
    return WavAudio(samples, f.rateHz, f.channels, f.bits, encoding)
  }

  private fun parseFmt(b: ByteArray, at: Int, size: Long): Fmt {
    if (size < 16 || at + 16 > b.size) throw WavFormatException("The WAV file's \"fmt \" chunk is too short.")
    var format = u16(b, at)
    val channels = u16(b, at + 2)
    val rate = u32(b, at + 4).toInt()
    val blockAlign = u16(b, at + 12)
    val bits = u16(b, at + 14)
    if (format == FORMAT_EXTENSIBLE && size >= 40 && at + 26 <= b.size) {
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

  private fun tag(b: ByteArray, at: Int, s: String): Boolean =
    at + 4 <= b.size && String(b, at, 4, Charsets.US_ASCII) == s

  private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

  private fun u32(b: ByteArray, at: Int): Long = s32(b, at).toLong() and 0xFFFFFFFFL

  private fun s32(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or
      (b[at + 3].toInt() shl 24)
}

/** Band-limited resampling with a Blackman-windowed sinc (16 zero crossings each side). */
object Resampler {
  private const val ZERO_CROSSINGS = 16

  fun resample(input: FloatArray, fromHz: Int, toHz: Int): FloatArray {
    if (fromHz == toHz || input.isEmpty()) return input.copyOf()
    val ratio = toHz.toDouble() / fromHz
    val outLength = maxOf(1, floor(input.size * ratio).toInt())
    // The cutoff sits a little under the lower Nyquist frequency.
    val cutoff = 0.97 * min(1.0, ratio)
    val halfWidth = ZERO_CROSSINGS / cutoff
    val out = FloatArray(outLength)
    for (i in 0 until outLength) {
      val center = i / ratio
      val first = maxOf(0, ceil(center - halfWidth).toInt())
      val last = min(input.size - 1, floor(center + halfWidth).toInt())
      var acc = 0.0
      var norm = 0.0
      for (j in first..last) {
        val x = j - center
        val w = kernel(x, cutoff, halfWidth)
        acc += w * input[j]
        norm += w
      }
      out[i] = if (abs(norm) > 1e-9) (acc / norm).toFloat() else 0f
    }
    return out
  }

  private fun kernel(x: Double, cutoff: Double, halfWidth: Double): Double {
    if (abs(x) >= halfWidth) return 0.0
    val t = PI * cutoff * x
    val sinc = if (abs(t) < 1e-12) 1.0 else sin(t) / t
    val n = (x + halfWidth) / (2 * halfWidth)
    val window = 0.42 - 0.5 * cos(2 * PI * n) + 0.08 * cos(4 * PI * n)
    return sinc * window
  }
}
