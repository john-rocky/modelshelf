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

package com.google.ai.edge.gallery.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import com.google.ai.edge.gallery.data.DEEP_LINK_SCHEME
import com.google.ai.edge.gallery.notifications.reminderLink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model Shelf, D-614 round 10d, line O2: the web pages the app opens. Only http(s) pages open, only
 * from an activity (no new task), and every web page the app opens goes through openExternalPage.
 */
class ExternalPagesTest {
  private class FakeActivity : Activity() {
    val started = mutableListOf<Intent?>()

    override fun startActivity(intent: Intent?) {
      started.add(intent)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
      started.add(intent)
    }
  }

  /** A composable's context: a wrapper around the activity. */
  private class Wrapper(private val base: Context?) : ContextWrapper(base) {
    override fun getBaseContext(): Context? = base
  }

  @Test
  fun onlyAnHttpPageWithAHostOpens() {
    for (url in
      listOf(
        "https://huggingface.co/litert-community",
        "https://ai.google.dev/gemma",
        "http://example.com/a?b=c",
        "HTTPS://AI.GOOGLE.DEV/gemma",
        " https://ai.google.dev/gemma ",
        "https://play.google.com/about",
      )) {
      assertEquals(url, PageRoute.CUSTOM_TAB, pageRoute(url))
    }
    assertEquals(
      PageRoute.STORE,
      pageRoute("https://play.google.com/store/apps/details?id=com.google.android.tts"),
    )
    for (url in
      listOf(
        "javascript:alert(1)",
        "file:///sdcard/a.html",
        "content://media/external/1",
        "intent://x#Intent;scheme=https;end",
        "market://details?id=x",
        "mailto:a@example.com",
        "tel:123",
        "geo:0,0?q=a",
        "$DEEP_LINK_SCHEME://model/llm_chat/x",
        "https://",
        "https:///path",
        "not a link",
        "",
        "https://exa mple.com",
      )) {
      assertEquals(url, PageRoute.NOT_A_PAGE, pageRoute(url))
    }
  }

  @Test
  fun aPageOpensFromTheActivityAndNothingOpensWithoutOne() {
    val activity = FakeActivity()
    assertTrue(openExternalPage(activity, "https://ai.google.dev/gemma"))
    assertEquals(1, activity.started.size)

    // A composable's context: the activity under it is found.
    val below = FakeActivity()
    assertTrue(openExternalPage(Wrapper(below), "https://huggingface.co/litert-community"))
    assertEquals(1, below.started.size)

    // No activity: nothing opens (no new task is asked for instead).
    assertFalse(openExternalPage(Wrapper(null), "https://ai.google.dev/gemma"))

    // Not a web page: nothing opens, from an activity either.
    val other = FakeActivity()
    for (url in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a.html")) {
      assertFalse(url, openExternalPage(other, url))
    }
    assertEquals(0, other.started.size)
  }

  @Test
  fun thePagePathAsksForNoNewTask() {
    val code = code(mainSource("ui/common/ExternalPages.kt"))
    for (word in listOf("FLAG_ACTIVITY_NEW_TASK", "addFlags", "setFlags", ".flags")) {
      assertFalse(word, code.contains(word))
    }
  }

  // Every file of the app's code that opens something with a VIEW intent, a Custom Tab or the
  // composition's UriHandler, and why it may. A new one fails this test until it goes through
  // openExternalPage or is added here with its reason.
  private val openers =
    mapOf(
      "ui/common/ExternalPages.kt" to
        "the one function: a Custom Tab from the activity; a store page in the Play Store app",
      "MainActivity.kt" to "provides ExternalPageUriHandler at the root of the composition",
      "ui/common/ClickableLink.kt" to "through the composition's UriHandler",
      "ui/common/SpeechEngineErrorDialog.kt" to "the store page, through the composition's UriHandler",
      "customtasks/agentchat/AddSkillFromFeaturedListBottomSheet.kt" to
        "a skill's page, through the composition's UriHandler",
      "customtasks/agentchat/SkillManagerBottomSheet.kt" to
        "a skill's page, through the composition's UriHandler",
      "ui/common/ModelDownloadGates.kt" to
        "a model's agreement page: a Custom Tab started for a result from the activity (as before)",
      "customtasks/mobileactions/MobileActionsViewModel.kt" to "a geo: map link, not a web page",
      "notifications/NotificationReceiver.kt" to
        "a reminder's tap: Model Shelf's activity, with a link of its own scheme only",
      "data/DownloadRepository.kt" to "a download notification's tap: Model Shelf's own links",
    )

  @Test
  fun everyWebPageTheAppOpensGoesThroughOpenExternalPage() {
    val root = sourceRoot()
    val opens = Regex("""ACTION_VIEW|CustomTabsIntent|\.openUri\(|LocalUriHandler provides|AndroidUriHandler""")
    val found =
      root
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .filter { file -> code(file.readText()).lineSequence().any { opens.containsMatchIn(it) } }
        .map { it.relativeTo(root).path }
        .toSortedSet()
    assertEquals(openers.keys.toSortedSet(), found)
  }

  // The "deeplink" extra that opened any page is gone, and no extra of the intent is read
  // (review findings 9 and 14).
  @Test
  fun mainActivityReadsNoExtra() {
    val code = code(mainSource("MainActivity.kt"))
    for (word in listOf("\"deeplink\"", "getStringExtra(", ".extras", "getExtras(", "keySet(")) {
      assertFalse(word, code.contains(word))
    }
  }

  @Test
  fun aReminderOpensOnlyALinkOfModelShelf() {
    val own = "$DEEP_LINK_SCHEME://model/llm_chat/x?query=y"
    assertEquals(own, reminderLink(own))
    for (link in listOf("https://example.com/", "intent://x#Intent;end", "javascript:a", "")) {
      assertNull(link, reminderLink(link))
    }
  }

  private fun sourceRoot(): File =
    listOf("src/main/java/com/google/ai/edge/gallery", "app/src/main/java/com/google/ai/edge/gallery")
      .map { File(it) }
      .first { it.isDirectory }

  private fun mainSource(path: String): String = File(sourceRoot(), path).readText()

  /** The source without its comment lines. */
  private fun code(text: String): String =
    text.lineSequence()
      .filterNot { line ->
        val t = line.trim()
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
      }
      .joinToString("\n")
}
