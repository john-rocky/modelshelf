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
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.data.DownloadAuthError
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Model Shelf, D-614 round 11, line H: what counts as a Hugging Face OAuth app in [ProjectConfig],
 * and that a build with one still starts the sign-in as before (the gate stops it only when there
 * is none; [HuggingFaceSignInGateTest] covers that side).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class HuggingFaceSignInSetUpTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun thePlaceholdersOrABlankValueAreNoOAuthApp() {
    assertFalse(
      ProjectConfig.isSetUp(
        clientId = "REPLACE_WITH_YOUR_CLIENT_ID_IN_HUGGINGFACE_APP",
        redirectUri = "REPLACE_WITH_YOUR_REDIRECT_URI_IN_HUGGINGFACE_APP",
      )
    )
    assertFalse(
      ProjectConfig.isSetUp(
        clientId = "0a1b2c3d",
        redirectUri = "REPLACE_WITH_YOUR_REDIRECT_URI_IN_HUGGINGFACE_APP",
      )
    )
    assertFalse(
      ProjectConfig.isSetUp(clientId = " ", redirectUri = "io.github.johnrocky.modelshelf:/oauth2redirect")
    )
    assertFalse("this build has an OAuth app", ProjectConfig.isHuggingFaceSignInSetUp)
  }

  @Test
  fun aClientIdAndARedirectUriAreAnOAuthApp() {
    assertTrue(
      ProjectConfig.isSetUp(clientId = "0a1b2c3d", redirectUri = "io.github.johnrocky.modelshelf:/oauth2redirect")
    )
  }

  @Test
  fun aBuildWithAnOAuthAppStillStartsTheSignIn() {
    val auth = RecordingLauncher<Intent>()
    val gates =
      ModelDownloadGates(
        context = rule.activity,
        modelManagerViewModel = gateViewModel(rule.activity),
        isHuggingFaceSignInSetUp = { true },
      )
    gates.launchers =
      ModelDownloadGates.Launchers(
        permission = RecordingLauncher(),
        auth = auth,
        agreement = RecordingLauncher(),
      )
    rule.runOnIdle {
      gates.resolveAuthError(
        model =
          Model(
            name = "Gemma-3n-E2B-it",
            downloadInfo =
              ModelDownloadInfo(
                url =
                  "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm"
              ),
          ),
        authError = DownloadAuthError.NEEDS_SIGN_IN,
        onAccessible = { _, _ -> },
        onAborted = {},
      )
    }
    rule.runOnIdle {
      assertEquals(listOf(MarkerAuthorizationService.MARKER), auth.launched.map { it.action })
      assertFalse("the no-sign-in dialog is up", gates.signInUnavailable)
    }
  }
}
