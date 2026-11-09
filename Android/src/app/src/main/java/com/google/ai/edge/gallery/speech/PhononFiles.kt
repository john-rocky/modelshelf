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

import android.content.Context
import android.os.Build
import com.google.ai.edge.gallery.common.getModelStorageDir
import java.io.File

/**
 * Where Phonon-2.5-English's files live on the phone, and which encoder this phone can run.
 *
 * Layout (the app's model storage, as for a downloaded model): `<models>/Phonon-2.5-English/<commit>/`
 * with the files of litert-community/Phonon-2.5-English: `tokenizer.json`, the decoder, the seven
 * mel front ends, and one or more of the three encoders (NPU for the SM8850, f16 for the GPU, i8 for
 * the CPU).
 */
object PhononFiles {
  const val REPO = "litert-community/Phonon-2.5-English"
  /** The Hub commit the app reads (2026-11-08, the card read on 2026-11-10). */
  const val COMMIT = "28adb6f4599ecaec916ae91adf4a214228cc7952"
  const val DIR_NAME = "Phonon-2.5-English"
  const val DISPLAY_NAME = "Phonon-2.5-English"
  const val TOKENIZER = "tokenizer.json"
  const val DECODER = "phonon25en_decoder_i8.tflite"
  const val ENCODER_NPU = "phonon25en_encoder_f16_sm8850.tflite"
  const val ENCODER_GPU = "phonon25en_encoder_f16.tflite"
  const val ENCODER_CPU = "phonon25en_encoder_i8.tflite"
  /** The SoC the NPU encoder is compiled for (Galaxy S26: Snapdragon 8 Elite Gen 5, Hexagon v81). */
  const val NPU_SOC = "SM8850"

  /** Window lengths in seconds, as the mel files name them. */
  val WINDOW_NAMES = listOf("5", "7.5", "10", "15", "20", "25", "30")

  fun melFile(name: String) = "phonon25en_mel_${name}s.tflite"

  /** The folder of the files. */
  fun dir(context: Context): File = File(getModelStorageDir(context), "$DIR_NAME/$COMMIT")

  /** The files present: null when the tokenizer, the decoder or a mel file is missing, or no encoder is. */
  fun present(context: Context): Present? = present(dir(context))

  fun present(dir: File): Present? {
    fun ok(name: String) = File(dir, name).let { it.isFile && it.length() > 0L }
    if (!ok(TOKENIZER) || !ok(DECODER) || WINDOW_NAMES.any { !ok(melFile(it)) }) return null
    val encoders = buildList {
      if (ok(ENCODER_NPU)) add(Backend.NPU)
      if (ok(ENCODER_GPU)) add(Backend.GPU)
      if (ok(ENCODER_CPU)) add(Backend.CPU)
    }
    if (encoders.isEmpty()) return null
    return Present(dir, encoders)
  }

  /** Whether this phone's SoC is the one the NPU encoder is compiled for. */
  fun phoneHasNpuSoc(): Boolean = Build.SOC_MODEL.equals(NPU_SOC, ignoreCase = true)

  enum class Backend(val label: String, val file: String) {
    NPU("npu", ENCODER_NPU),
    GPU("gpu", ENCODER_GPU),
    CPU("cpu", ENCODER_CPU),
  }

  class Present(val dir: File, val encoders: List<Backend>) {
    /** The encoders to try on this phone, in order: the NPU one only on its SoC. */
    fun usable(): List<Backend> = encoders.filter { it != Backend.NPU || phoneHasNpuSoc() }

    /** The file a fingerprint and a status report point at: the first usable encoder. */
    fun mainFile(): File? = usable().firstOrNull()?.let { File(dir, it.file) }

    val sizeBytes: Long
      get() = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
  }
}
