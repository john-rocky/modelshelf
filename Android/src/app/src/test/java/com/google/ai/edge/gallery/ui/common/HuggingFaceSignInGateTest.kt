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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.gallery.data.DownloadAuthError
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Model Shelf, D-614 round 11, line H: a build without a Hugging Face OAuth app (the client id and
 * redirect URI in ProjectConfig are the REPLACE_WITH placeholders) starts no sign-in for a model
 * that needs one. It shows one sentence and starts no download; the app does not fail.
 *
 * The sign-in is seen at AppAuth's door: the gate asks the view model's AuthorizationService for the
 * authorization intent and launches it. The service here hands back a marker intent, and the
 * launcher records what it is asked to launch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class HuggingFaceSignInGateTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val sentence =
    "This model needs a Hugging Face sign-in, which this version of Model Shelf does not have yet."
  private val auth = RecordingLauncher<Intent>()

  @Test
  fun aModelThatNeedsASignInStartsNoSignInAndNoDownload() {
    val gates = gates()
    var accessible = 0
    rule.runOnIdle {
      gates.resolveAuthError(
        model = model(),
        authError = DownloadAuthError.NEEDS_SIGN_IN,
        onAccessible = { _, _ -> accessible++ },
        onAborted = {},
      )
    }
    rule.runOnIdle {
      assertEquals("a sign-in intent was launched", emptyList<String?>(), auth.launched.map { it.action })
      assertEquals("the download was started", 0, accessible)
    }
  }

  @Test
  fun theDialogSaysWhyInOneSentenceAndItsCloseEndsTheDownloadRequest() {
    val gates = gates()
    var aborted = 0
    rule.setContent { ModelDownloadGatesHost(gates) }
    rule.runOnIdle {
      gates.resolveAuthError(
        model = model(),
        authError = DownloadAuthError.NEEDS_SIGN_IN,
        onAccessible = { _, _ -> },
        onAborted = { aborted++ },
      )
    }
    rule.onNodeWithText(sentence).assertIsDisplayed()
    rule.onNodeWithText("Close").performClick()
    rule.runOnIdle { assertEquals("the request did not end once", 1, aborted) }
    rule.onNodeWithText(sentence).assertDoesNotExist()
    rule.runOnIdle {
      assertEquals("a sign-in intent was launched", emptyList<String?>(), auth.launched.map { it.action })
    }
  }

  private fun model() =
    Model(
      name = "Gemma-3n-E2B-it",
      downloadInfo =
        ModelDownloadInfo(
          url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm"
        ),
    )

  private fun gates(): ModelDownloadGates {
    val gates = ModelDownloadGates(context = rule.activity, modelManagerViewModel = gateViewModel(rule.activity))
    gates.launchers =
      ModelDownloadGates.Launchers(
        permission = RecordingLauncher(),
        auth = auth,
        agreement = RecordingLauncher(),
      )
    return gates
  }
}

/** An activity result launcher that records what it is asked to launch. */
internal class RecordingLauncher<I> : ActivityResultLauncher<I>() {
  val launched = mutableListOf<I>()

  override fun launch(input: I, options: ActivityOptionsCompat?) {
    launched.add(input)
  }

  override fun unregister() {}

  override val contract: ActivityResultContract<I, *>
    get() = throw UnsupportedOperationException("not used by the gate")
}

/** AppAuth's service, but the authorization intent is a marker that names the request's URL. */
internal class MarkerAuthorizationService(context: Context) : AuthorizationService(context) {
  override fun getAuthorizationRequestIntent(request: AuthorizationRequest): Intent =
    Intent(MARKER).putExtra("url", request.toUri().toString())

  companion object {
    const val MARKER = "modelshelf.test.HUGGING_FACE_SIGN_IN"
  }
}

/**
 * A view model made without running its constructor (no repository, no list load, no download
 * worker): the gate's sign-in path reads only its authService and [ModelManagerViewModel.getAuthorizationRequest].
 */
internal fun gateViewModel(context: Context): ModelManagerViewModel {
  val unsafeClass = Class.forName("sun.misc.Unsafe")
  val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
  val vm =
    unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ModelManagerViewModel::class.java)
      as ModelManagerViewModel
  ModelManagerViewModel::class
    .java
    .getDeclaredField("authService")
    .apply { isAccessible = true }
    .set(vm, MarkerAuthorizationService(context))
  return vm
}
