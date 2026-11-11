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

package com.google.ai.edge.gallery.customtasks

import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Model Shelf, D-614 round 11, line G: Tiny Garden and Mobile Actions are not registered as tasks.
 *
 * Both models need a Hugging Face sign-in and a gated repo's agreement, which this build does not
 * have, so the two tasks stay off the home screen. Their code stays in the tree; the registration
 * comes back with a `git revert` of the commit that removed it. An upstream merge that brings a
 * module providing either task back into the task set fails here.
 */
class TaskRegistrationTest {
  @Test
  fun noModuleProvidesTinyGardenOrMobileActionsIntoTheTaskSet() {
    val root = sourceRoot()
    val into = Regex("""@(IntoSet|ElementsIntoSet)\b""")
    val task = Regex("""\b(TinyGardenTask|MobileActionsTask)\s*\(""")
    val registering =
      root
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .filter { file ->
          val code = code(file.readText())
          into.containsMatchIn(code) && task.containsMatchIn(code)
        }
        .map { it.relativeTo(root).path }
        .toSortedSet()
    assertEquals(
      "files that provide Tiny Garden or Mobile Actions into the task set",
      sortedSetOf<String>(),
      registering,
    )
  }

  // A model imported from a file offers no switch for either task (their screens are not there).
  @Test
  fun theModelImportOffersNoSwitchForEitherTask() {
    val keys = Config.createLlmImportConfigs().map { it.key }
    assertFalse("the import offers ${ConfigKeys.SUPPORT_TINY_GARDEN.label}", keys.contains(ConfigKeys.SUPPORT_TINY_GARDEN))
    assertFalse(
      "the import offers ${ConfigKeys.SUPPORT_MOBILE_ACTIONS.label}",
      keys.contains(ConfigKeys.SUPPORT_MOBILE_ACTIONS),
    )
  }

  private fun sourceRoot(): File =
    listOf("src/main/java/com/google/ai/edge/gallery", "app/src/main/java/com/google/ai/edge/gallery")
      .map { File(it) }
      .first { it.isDirectory }

  /** The source without its comment lines. */
  private fun code(text: String): String =
    text
      .lineSequence()
      .filterNot { line ->
        val t = line.trimStart()
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
      }
      .joinToString("\n")
}
