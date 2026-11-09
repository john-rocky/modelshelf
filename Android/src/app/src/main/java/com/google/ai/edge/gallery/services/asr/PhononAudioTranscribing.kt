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

package com.google.ai.edge.gallery.services.asr

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.speech.PhononEngine
import com.google.ai.edge.gallery.speech.PhononFiles
import com.google.ai.edge.gallery.speech.PhononPipeline
import com.google.ai.edge.gallery.speech.SPEECH_SAMPLE_RATE_HZ
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriberResult
import java.io.File

private const val TAG = "MSPhononAsr"

/** A new interim text after this much new audio. */
private const val INTERIM_EVERY_SAMPLES = 24_000

/**
 * The app's voice input on Phonon-2.5-English ([PhononEngine]), behind the same [AudioTranscribing]
 * calls as MediaPipe's AudioTranscriber: audio arrives in small chunks; every 1.5 s the window so far
 * is transcribed again as an interim text; a full 30 s window, or the end of the stream, gives a
 * final text. Phonon decodes a window from scratch, so an interim text can change until it is final.
 */
internal class PhononAudioTranscribing(context: Context) : AudioTranscribing {
  private val held = PhononEngine.acquireBlocking(context) { Log.i(TAG, it) }
  private var buffer = FloatArray(PhononPipeline.WINDOW_SAMPLES)
  private var filled = 0
  private var sinceInterim = 0
  /** Samples finalized before the current window, for the segments' timestamps. */
  private var doneSamples = 0L
  @Volatile private var closed = false

  init {
    Log.i(TAG, "Voice input on Phonon-2.5-English, encoder on ${held.backendNote}")
  }

  override fun transcribe(audioSamples: FloatArray, endOfStream: Boolean): AudioTranscriberResult {
    val segments = mutableListOf<AudioTranscriberResult.Segment>()
    var offset = 0
    while (offset < audioSamples.size) {
      val n = minOf(audioSamples.size - offset, buffer.size - filled)
      System.arraycopy(audioSamples, offset, buffer, filled, n)
      filled += n
      sinceInterim += n
      offset += n
      if (filled == buffer.size) segments.addAll(finalSegment())
    }
    if (endOfStream) {
      segments.addAll(finalSegment())
    } else if (sinceInterim >= INTERIM_EVERY_SAMPLES && filled >= PhononPipeline.MIN_WINDOW_SAMPLES) {
      sinceInterim = 0
      val text = run(buffer.copyOf(filled))
      if (text.isNotEmpty()) segments.add(segment(text, isFinal = false, filled))
    }
    return AudioTranscriberResult.create(segments, msOf(doneSamples + filled))
  }

  override fun finishStream(): AudioTranscriberResult {
    val segments = finalSegment()
    val result = AudioTranscriberResult.create(segments, msOf(doneSamples))
    reset()
    return result
  }

  override fun prewarm() {
    reset()
  }

  override fun close() {
    if (closed) return
    closed = true
    PhononEngine.releaseBlocking("voice input closed")
  }

  private fun finalSegment(): List<AudioTranscriberResult.Segment> {
    if (filled < PhononPipeline.MIN_WINDOW_SAMPLES) {
      doneSamples += filled
      filled = 0
      sinceInterim = 0
      return emptyList()
    }
    val text = run(buffer.copyOf(filled))
    val seg = segment(text, isFinal = true, filled)
    doneSamples += filled
    filled = 0
    sinceInterim = 0
    return if (text.isEmpty()) emptyList() else listOf(seg)
  }

  private fun segment(text: String, isFinal: Boolean, samples: Int): AudioTranscriberResult.Segment =
    AudioTranscriberResult.Segment.create(text, isFinal, msOf(doneSamples), msOf(doneSamples + samples))

  private fun run(audio: FloatArray): String = PhononEngine.runBlocking { it.transcribe(audio).text }

  private fun reset() {
    filled = 0
    sinceInterim = 0
    doneSamples = 0L
  }

  private fun msOf(samples: Long): Long = samples * 1000L / SPEECH_SAMPLE_RATE_HZ

  private fun msOf(samples: Int): Long = msOf(samples.toLong())

  companion object {
    /** Whether [file] is one of Phonon-2.5-English's encoders (what the registry resolves for it). */
    fun isPhononFile(file: File): Boolean =
      file.name in PhononFiles.Backend.entries.map { it.file } &&
        file.parentFile?.parentFile?.name == PhononFiles.DIR_NAME
  }
}
