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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Strings a caller sent reach logcat only filtered and bounded (review 11). */
class LogSafeTest {
  @Test
  fun namesOfModelsAndVoicesStayAsTheyAre() {
    assertEquals("af_heart", logName("af_heart"))
    assertEquals("Kokoro-82M", logName("Kokoro-82M"))
    assertEquals("litert-community/Phonon-2.5-English", logName("litert-community/Phonon-2.5-English"))
    assertEquals("hf.co/litert-community/Kokoro-82M:latest", logName("hf.co/litert-community/Kokoro-82M:latest"))
  }

  @Test
  fun textInANameFieldIsFilteredAndBounded() {
    assertEquals("pleasereadmydiary…(20 characters)", logName("please read my diary"))
    assertEquals("x".repeat(64) + "…(1000 characters)", logName("x".repeat(1_000)))
  }

  @Test
  fun quotedPartsOfAnErrorMessageAreFiltered() {
    val message =
      "Voice \"tell my doctor about the rash\" is not in Kokoro-82M, which has af_heart (GET /v1/models lists them all)."
    val logged = logText(message)
    val sent = "tell my doctor about the rash"
    assertTrue(logged, logged.startsWith("Voice \"tellmydoctorabouttherash…(${sent.length} characters)\" is not in Kokoro-82M"))
    assertFalse(logged.contains("my doctor"))
    assertTrue(logText("\"" + "y".repeat(5_000) + "\" " + "z".repeat(5_000)).length <= 300)
  }

  @Test
  fun aReadAloudRefusalIsOneSentenceWithRetryAfter() {
    val before = EngineOwnership.readAloudActive
    try {
      EngineOwnership.readAloudActive = { true }
      try {
        EngineOwnership.checkReadAloudIdle()
        fail("expected a 503")
      } catch (e: ApiError) {
        assertEquals(503, e.status)
        assertEquals("busy", e.code)
        assertEquals(READ_ALOUD_RETRY_S, e.retryAfterS)
        assertEquals("Model Shelf is reading aloud to its user; try again in 10 s.", e.message)
      }
      EngineOwnership.readAloudActive = { false }
      EngineOwnership.checkReadAloudIdle()
    } finally {
      EngineOwnership.readAloudActive = before
    }
  }
}
