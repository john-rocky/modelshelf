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

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.window.Dialog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.gallery.ui.common.tts.TtsMessageBlock
import com.google.ai.edge.gallery.ui.common.tts.renderParagraph
import com.google.ai.edge.gallery.ui.home.MobileActionsChallengeDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Model Shelf, D-614 round 10e, line P: the app's links inside a Compose Dialog or sheet.
 *
 * A Dialog or sheet is a window of its own, and Compose provides a plain UriHandler again in each
 * window, so the handler provided at the activity's root does not reach it. A link there must still
 * go through openExternalPage: a Custom Tab, seen here by the library's session extra, which a
 * plain VIEW does not have.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ExternalPagesInWindowsTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val page = "https://huggingface.co/litert-community"
  private val label = "Open the page"

  private fun started(): Intent? = shadowOf(rule.activity).nextStartedActivity

  private fun assertOpenedThroughOpenExternalPage(intent: Intent?, url: String = page) {
    assertNotNull("no activity was started", intent)
    assertEquals(Intent.ACTION_VIEW, intent!!.action)
    assertEquals(url, intent.dataString)
    assertTrue(
      "a plain VIEW, not openExternalPage's Custom Tab (extras: ${intent.extras?.keySet()})",
      intent.hasExtra(CustomTabsIntent.EXTRA_SESSION),
    )
    assertEquals("a new task was asked for", 0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  /** Clicks the link node that a Text with one link annotation holds. */
  private fun clickTheLink(text: String = label) {
    rule.waitForIdle()
    val link: SemanticsMatcher = hasClickAction() and hasParent(hasText(text, substring = true))
    rule.onNode(link, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    rule.waitForIdle()
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun Sheet(content: @Composable () -> Unit) {
    ModalBottomSheet(
      onDismissRequest = {},
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
      content()
    }
  }

  // The test's own control: at the root of the composition the provided handler is reached, so a
  // failure below is the window's, not this test's way of seeing a Custom Tab.
  @Test
  fun aLinkAtTheRootOpensThroughOpenExternalPage() {
    rule.setContent {
      val context = LocalContext.current
      CompositionLocalProvider(
        LocalUriHandler provides remember { ExternalPageUriHandler(context) }
      ) {
        ClickableLink(url = page, linkText = label)
      }
    }
    clickTheLink()
    assertOpenedThroughOpenExternalPage(started())
  }

  @Test
  fun aClickableLinkInADialogOpensThroughOpenExternalPage() {
    rule.setContent { Dialog(onDismissRequest = {}) { ClickableLink(url = page, linkText = label) } }
    clickTheLink()
    assertOpenedThroughOpenExternalPage(started())
  }

  @Test
  fun aClickableLinkInASheetOpensThroughOpenExternalPage() {
    rule.setContent { Sheet { ClickableLink(url = page, linkText = label) } }
    clickTheLink()
    assertOpenedThroughOpenExternalPage(started())
  }

  @Test
  fun aLinkAnnotationInADialogOpensThroughOpenExternalPage() {
    rule.setContent {
      Dialog(onDismissRequest = {}) { Text(buildTrackableUrlAnnotatedString(page, label)) }
    }
    clickTheLink()
    assertOpenedThroughOpenExternalPage(started())
  }

  @Test
  fun aLinkAnnotationInASheetOpensThroughOpenExternalPage() {
    rule.setContent { Sheet { Text(buildTrackableUrlAnnotatedString(page, label)) } }
    clickTheLink()
    assertOpenedThroughOpenExternalPage(started())
  }

  // Markdown's links are LinkAnnotation.Url without a listener (the library's), opened by the
  // composition's UriHandler.
  @Test
  fun aMarkdownLinkInADialogOpensThroughOpenExternalPage() {
    rule.setContent { Dialog(onDismissRequest = {}) { MarkdownText("[$label]($page)") } }
    clickTheLink()
    assertOpenedThroughOpenExternalPage(started())
  }

  @Test
  fun aMarkdownLinkInASheetOpensThroughOpenExternalPage() {
    rule.setContent { Sheet { MarkdownText("[$label]($page)") } }
    clickTheLink()
    assertOpenedThroughOpenExternalPage(started())
  }

  // The read-aloud view draws an answer's links itself (LinkAnnotation.Url, not the markdown
  // library's).
  @Test
  fun aLinkOfAReadAloudParagraphInADialogOpensThroughOpenExternalPage() {
    val content = "[$label]($page)"
    rule.setContent {
      Dialog(onDismissRequest = {}) {
        Text(
          renderParagraph(
              content = content,
              paragraph = TtsMessageBlock.Paragraph(0, content.length, emptyList()),
              activeSentenceId = -1,
              highlightColor = Color.Yellow,
              inlineCodeBackground = Color.Gray,
              linkColor = Color.Blue,
              linkListener = rememberExternalPageLinkListener(),
              onSentenceClick = {},
            )
            .text
        )
      }
    }
    clickTheLink()
    assertOpenedThroughOpenExternalPage(started())
  }

  // An html string's link (AnnotatedString.fromHtml) in one of the app's own sheets.
  @Test
  fun aLinkOfAnHtmlStringInTheAppsSheetOpensThroughOpenExternalPage() {
    rule.setContent {
      MobileActionsChallengeDialog(onDismiss = {}, onLoadModel = {}, onSendEmail = {})
    }
    clickTheLink("this guide")
    assertOpenedThroughOpenExternalPage(
      started(),
      "https://ai.google.dev/gemma/docs/mobile-actions",
    )
  }

  @Test
  fun theSpeechStorePageFromADialogGoesToTheStoreApp() {
    var open: (() -> Unit)? = null
    rule.setContent {
      Dialog(onDismissRequest = {}) {
        open = rememberOpenSpeechServicesStorePage()
        Text(label)
      }
    }
    rule.waitForIdle()
    rule.runOnUiThread { open!!() }
    val intent = started()
    assertNotNull("no activity was started", intent)
    assertEquals("the store page went elsewhere", "com.android.vending", intent!!.`package`)
  }

  @Test
  fun aDialogsContextFindsTheActivityUnderIt() {
    var inside: Context? = null
    rule.setContent {
      Dialog(onDismissRequest = {}) {
        inside = LocalContext.current
        Text(label)
      }
    }
    rule.waitForIdle()
    assertNotSame("the dialog's context is its own wrapper", rule.activity, inside)
    rule.runOnUiThread { assertTrue(openExternalPage(inside!!, page)) }
    assertOpenedThroughOpenExternalPage(started())
  }

  @Test
  fun aSheetsContextFindsTheActivityUnderIt() {
    var inside: Context? = null
    rule.setContent {
      Sheet {
        inside = LocalContext.current
        Text(label)
      }
    }
    rule.waitForIdle()
    rule.runOnUiThread { assertTrue(openExternalPage(inside!!, page)) }
    assertOpenedThroughOpenExternalPage(started())
  }

  @Test
  fun withoutAnActivityNothingOpensAndOneLineIsLogged() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    ShadowLog.clear()
    assertFalse(openExternalPage(app, page))
    assertNull("something was started", shadowOf(app).nextStartedActivity)
    assertEquals(1, ShadowLog.getLogsForTag("AGExternalPages").size)
  }

  @Test
  fun aLinkThatIsNotAWebPageOpensNothingFromADialogsContext() {
    var inside: Context? = null
    rule.setContent {
      Dialog(onDismissRequest = {}) {
        inside = LocalContext.current
        Text(label)
      }
    }
    rule.waitForIdle()
    rule.runOnUiThread {
      for (url in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a.html")) {
        assertFalse(url, openExternalPage(inside!!, url))
      }
    }
    assertNull("something was started", started())
  }
}
