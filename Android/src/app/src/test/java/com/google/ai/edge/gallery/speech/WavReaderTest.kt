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

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WavReaderTest {
  /** A WAV file: [format] 1 = PCM, 3 = float; [frames] are interleaved sample values. */
  private fun wav(rate: Int, channels: Int, bits: Int, format: Int, samples: List<Double>, dataSize: Long? = null): ByteArray {
    val bytesPerSample = bits / 8
    val data = ByteBuffer.allocate(samples.size * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
    for (v in samples) {
      when {
        format == 3 && bits == 32 -> data.putFloat(v.toFloat())
        format == 3 && bits == 64 -> data.putDouble(v)
        bits == 8 -> data.put(((v * 128) + 128).toInt().coerceIn(0, 255).toByte())
        bits == 16 -> data.putShort((v * 32768).toInt().coerceIn(-32768, 32767).toShort())
        bits == 24 -> {
          val x = (v * 8388608).toInt().coerceIn(-8388608, 8388607)
          data.put(x.toByte())
          data.put((x shr 8).toByte())
          data.put((x shr 16).toByte())
        }
        else -> data.putInt((v * 2147483648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
      }
    }
    val out = ByteArrayOutputStream()
    fun u32(v: Long) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array())
    fun u16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
    out.write("RIFF".toByteArray())
    u32(36L + data.capacity())
    out.write("WAVE".toByteArray())
    // An extra chunk before "fmt " that a reader must skip.
    out.write("LIST".toByteArray())
    u32(4)
    out.write("INFO".toByteArray())
    out.write("fmt ".toByteArray())
    u32(16)
    u16(format)
    u16(channels)
    u32(rate.toLong())
    u32(rate.toLong() * channels * bytesPerSample)
    u16(channels * bytesPerSample)
    u16(bits)
    out.write("data".toByteArray())
    u32(dataSize ?: data.capacity().toLong())
    out.write(data.array())
    return out.toByteArray()
  }

  @Test
  fun sixteenKilohertzMono16BitGivesTheSamplesOver32768() {
    val values = listOf(0.0, 0.5, -0.5, 1.0 - 1.0 / 32768, -1.0)
    val a = WavReader.read(wav(16_000, 1, 16, 1, values))
    assertTrue(a.isNative)
    assertEquals(5, a.samples.size)
    val shorts = listOf(0, 16384, -16384, 32767, -32768)
    for (i in shorts.indices) assertEquals(shorts[i] / 32768f, a.samples[i], 0f)
  }

  @Test
  fun channelsAreMixedDownToTheirMean() {
    val a = WavReader.read(wav(16_000, 2, 16, 1, listOf(0.5, -0.5, 0.25, 0.25)))
    assertFalse(a.isNative)
    assertEquals(2, a.samples.size)
    assertEquals(0f, a.samples[0], 1e-6f)
    assertEquals(0.25f, a.samples[1], 1e-4f)
  }

  @Test
  fun otherBitDepthsAndFloatsRead() {
    for ((bits, format) in listOf(8 to 1, 24 to 1, 32 to 1, 32 to 3, 64 to 3)) {
      val a = WavReader.read(wav(16_000, 1, bits, format, listOf(0.0, 0.5, -0.5)))
      assertEquals("$bits/$format", 0.5f, a.samples[1], 0.01f)
      assertEquals("$bits/$format", -0.5f, a.samples[2], 0.01f)
    }
  }

  @Test
  fun fortyFourKilohertzIsResampledTo16Kilohertz() {
    // One second of a 440 Hz tone at 44.1 kHz.
    val tone = List(44_100) { 0.5 * sin(2 * PI * 440 * it / 44_100.0) }
    val a = WavReader.read(wav(44_100, 1, 16, 1, tone))
    assertTrue(abs(a.samples.size - 16_000) <= 1)
    assertEquals("44100 Hz, mono, 16-bit PCM", a.describeSource())
    // The tone keeps its level: the middle of the resampled signal peaks near 0.5.
    val peak = a.samples.copyOfRange(4_000, 12_000).maxOf { abs(it) }
    assertEquals(0.5f, peak, 0.03f)
  }

  @Test
  fun aStreamedFileWithoutItsDataSizeIsReadToTheEnd() {
    val a = WavReader.read(wav(16_000, 1, 16, 1, List(1000) { 0.1 }, dataSize = 0xFFFFFFFFL))
    assertEquals(1000, a.samples.size)
  }

  @Test
  fun notAWavFileSaysSoInOneSentence() {
    try {
      WavReader.read("ID3\u0004 not audio at all".toByteArray())
      fail("expected a WavFormatException")
    } catch (e: WavFormatException) {
      val message = e.message!!
      assertTrue(message.startsWith("The file is not a WAV file"))
      // One sentence: a single full stop, at the end.
      assertEquals(1, message.count { it == '.' })
      assertTrue(message.endsWith("."))
    }
  }

  @Test
  fun tooLongAudioIsRefusedBeforeItsSamplesAreRead() {
    try {
      WavReader.read(wav(16_000, 1, 16, 1, List(16_000 * 3) { 0.0 }), maxSeconds = 2)
      fail("expected a WavFormatException")
    } catch (e: WavFormatException) {
      assertEquals("The audio is 3 s long; send at most 2 s per request.", e.message)
    }
  }

  @Test
  fun floatSamplesThatAreNotNumbersAreRefusedInOneSentence() {
    for ((bits, bad) in listOf(32 to Double.NaN, 32 to Double.POSITIVE_INFINITY, 64 to Double.NEGATIVE_INFINITY)) {
      try {
        WavReader.read(wav(16_000, 1, bits, 3, listOf(0.0, 0.5, bad, 0.0)))
        fail("expected a WavFormatException for $bad in $bits-bit float")
      } catch (e: WavFormatException) {
        assertEquals("The WAV file has a sample that is not a finite number (NaN or infinity) at 0.000 s.", e.message)
      }
    }
  }

  @Test
  fun unsupportedEncodingsAreRefused() {
    try {
      WavReader.read(wav(16_000, 1, 16, 6, listOf(0.0))) // A-law
      fail("expected a WavFormatException")
    } catch (e: WavFormatException) {
      assertTrue(e.message!!.contains("format 6"))
    }
  }

  /** A RIFF/WAVE file of the given chunks, in order. */
  private fun riff(vararg chunks: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    val body = ByteArrayOutputStream()
    for ((id, data) in chunks) {
      body.write(id.toByteArray())
      body.write(le32(data.size.toLong()))
      body.write(data)
      if (data.size % 2 == 1) body.write(0) // the pad byte
    }
    out.write("RIFF".toByteArray())
    out.write(le32(4L + body.size()))
    out.write("WAVE".toByteArray())
    out.write(body.toByteArray())
    return out.toByteArray()
  }

  private fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

  private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

  private fun pcm16(values: List<Double>): ByteArray {
    val b = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (v in values) b.putShort((v * 32768).toInt().coerceIn(-32768, 32767).toShort())
    return b.array()
  }

  private fun fmt(format: Int, channels: Int, rate: Int, bits: Int, blockAlign: Int, extensibleSubFormat: Int? = null): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(le16(format))
    out.write(le16(channels))
    out.write(le32(rate.toLong()))
    out.write(le32(rate.toLong() * blockAlign))
    out.write(le16(blockAlign))
    out.write(le16(bits))
    if (extensibleSubFormat != null) {
      out.write(le16(22)) // cbSize
      out.write(le16(bits)) // valid bits
      out.write(le32(4)) // channel mask
      out.write(le16(extensibleSubFormat))
      out.write(byteArrayOf(0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
    }
    return out.toByteArray()
  }

  @Test
  fun anExtensibleFileReadsItsSubFormat() {
    val values = listOf(0.25, -0.5, 0.125)
    val a = WavReader.read(riff("fmt " to fmt(0xFFFE, 1, 16_000, 16, 2, extensibleSubFormat = 1), "data" to pcm16(values)))
    assertEquals(3, a.samples.size)
    for (i in values.indices) assertEquals(values[i].toFloat(), a.samples[i], 1e-4f)
  }

  @Test
  fun anOddSizedChunkBeforeTheDataIsSkippedWithItsPadByte() {
    val a =
      WavReader.read(
        riff("fmt " to fmt(1, 1, 16_000, 16, 2), "junk" to byteArrayOf(1, 2, 3), "data" to pcm16(listOf(0.5, 0.5)))
      )
    assertEquals(2, a.samples.size)
    assertEquals(0.5f, a.samples[0], 1e-4f)
  }

  @Test
  fun eightKilohertzIsUpsampledTo16Kilohertz() {
    val tone = List(8_000) { 0.5 * sin(2 * PI * 440 * it / 8_000.0) }
    val a = WavReader.read(wav(8_000, 1, 16, 1, tone))
    assertEquals(16_000, a.samples.size)
    val peak = a.samples.copyOfRange(4_000, 12_000).maxOf { abs(it) }
    assertEquals(0.5f, peak, 0.03f)
  }

  @Test
  fun moreThanTwoChannelsAreMixedToTheirMean() {
    val frames = List(100) { listOf(0.4, 0.2, 0.0, -0.2) }.flatten()
    val a = WavReader.read(wav(16_000, 4, 16, 1, frames))
    assertEquals(100, a.samples.size)
    for (v in a.samples) assertEquals(0.1f, v, 1e-3f)
  }

  @Test
  fun aBlockAlignWiderThanTheSamplesSkipsThePadding() {
    // 24-bit samples in 4-byte slots.
    val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    for (v in listOf(0.5, -0.25)) {
      val x = (v * 8388608).toInt()
      data.put(x.toByte())
      data.put((x shr 8).toByte())
      data.put((x shr 16).toByte())
      data.put(0x7F)
    }
    val a = WavReader.read(riff("fmt " to fmt(1, 1, 16_000, 24, 4), "data" to data.array()))
    assertEquals(2, a.samples.size)
    assertEquals(0.5f, a.samples[0], 1e-4f)
    assertEquals(-0.25f, a.samples[1], 1e-4f)
  }

  @Test
  fun aFileInsideALargerArrayReadsLikeTheFileAlone() {
    val file = wav(48_000, 2, 16, 1, List(48_000) { 0.3 * sin(it / 10.0) })
    val array = ByteArray(100) { 0x55 } + file + ByteArray(77) { 0x66 }
    val alone = WavReader.read(file)
    val inside = WavReader.read(array, offset = 100, length = file.size)
    assertEquals(alone.samples.size, inside.samples.size)
    for (i in alone.samples.indices) assertEquals(alone.samples[i], inside.samples[i], 0f)
  }

  @Test
  fun resamplingKeepsASineWithinOnePercent() {
    // 48 kHz to 16 kHz, a 1 kHz tone: away from the edges each output sample is the tone at its time.
    val input = FloatArray(48_000) { (0.5 * sin(2 * PI * 1_000 * it / 48_000.0)).toFloat() }
    val out = Resampler.resample(input, 48_000, 16_000)
    assertEquals(16_000, out.size)
    var worst = 0.0
    for (i in 1_000 until 15_000) {
      val want = 0.5 * sin(2 * PI * 1_000 * i / 16_000.0)
      worst = maxOf(worst, abs(out[i] - want))
    }
    assertTrue("worst error $worst", worst < 0.005)
  }

  @Test
  fun theStopCheckRunsBetweenBlocksAndEndsTheRead() {
    var checks = 0
    val file = wav(44_100, 1, 16, 1, List(44_100 * 2) { 0.1 })
    try {
      WavReader.read(file) {
        checks++
        if (checks == 2) throw IllegalStateException("stopped")
      }
      fail("expected the check to stop the read")
    } catch (e: IllegalStateException) {
      assertEquals("stopped", e.message)
    }
    assertEquals(2, checks)
    var checks16 = 0
    WavReader.read(wav(16_000, 1, 16, 1, List(Resampler.BLOCK * 3) { 0.1 })) { checks16++ }
    assertEquals(3, checks16)
  }
}
