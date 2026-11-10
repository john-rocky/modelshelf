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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers the round 10b record fixed before the run (item 4). */
class MemoryRuleTest {
  @Test
  fun pixel8aUnloadsUnderTheLineTheRecordWrote() {
    val total = 7_754_700L
    assertEquals(775_470L, MemoryRule.floorKb(total))
    val kokoro = MemoryRule.needKb(294_750_530L, MemoryRule.TTS_FACTOR)
    assertEquals(575_684L, kokoro)
    assertTrue(MemoryRule.mustMakeRoom(MemoryRule.Meminfo(total, 1_351_153L), kokoro))
    assertFalse(MemoryRule.mustMakeRoom(MemoryRule.Meminfo(total, 1_351_154L), kokoro))
  }

  @Test
  fun theFloorIsAtLeast450MiB() {
    assertEquals(460_800L, MemoryRule.floorKb(2_000_000L))
    assertEquals(1_138_975L, MemoryRule.floorKb(11_389_750L))
  }

  @Test
  fun meminfoIsRead() {
    val m =
      MemoryRule.parse("MemTotal:        7754700 kB\nMemFree:          100000 kB\nMemAvailable:    2345678 kB\n")!!
    assertEquals(7_754_700L, m.totalKb)
    assertEquals(2_345_678L, m.availableKb)
    assertNull(MemoryRule.parse("MemTotal: 1 kB\n"))
  }
}
