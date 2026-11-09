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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MSConnect"

/**
 * The connect screen (DESIGN.md §g-2): another app opens it with
 * `startActivityForResult(Intent("io.github.johnrocky.modelshelf.action.CONNECT"))`. Model Shelf
 * names the app and asks the user. Allow turns the API on and returns `base_url` and `token`;
 * Deny returns RESULT_CANCELED with `error` = "denied".
 */
class ConnectActivity : ComponentActivity() {
  private var busy by mutableStateOf(false)
  private var error by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val caller = callingPackage
    val label =
      caller?.let {
        try {
          packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
        } catch (e: Exception) {
          it
        }
      }
    Log.i(TAG, "Connect asked by ${caller ?: "an app that did not ask for a result"} ($label)")
    if (caller == null) {
      setResult(RESULT_CANCELED, Intent().putExtra(LocalApi.EXTRA_ERROR, "no_caller"))
    }
    setContent {
      GalleryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier = Modifier.systemBarsPadding().padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            if (caller == null || label == null) {
              Text("Connect an app", style = MaterialTheme.typography.headlineSmall)
              Text(
                "Open this screen from the app that wants to use your models. That app asks " +
                  "Model Shelf for access, and you choose here.",
                style = MaterialTheme.typography.bodyLarge,
              )
              Button(onClick = { finish() }) { Text("Close") }
            } else {
              Text(
                "Allow $label to use the models on this phone?",
                style = MaterialTheme.typography.headlineSmall,
              )
              Text(
                "$label ($caller) will be able to send text to the models you downloaded in " +
                  "Model Shelf and read the answers, through Model Shelf's local API " +
                  "(${LocalApi.BASE_URL}). Nothing leaves this phone. You can remove the access " +
                  "in Settings > Local API.",
                style = MaterialTheme.typography.bodyLarge,
              )
              error?.let {
                Text(
                  it,
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
              if (busy) {
                Text("Turning on the local API...", style = MaterialTheme.typography.bodyMedium)
              }
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, onClick = { deny() }) { Text("Deny") }
                Button(enabled = !busy, onClick = { allow(caller, label) }) { Text("Allow") }
              }
            }
          }
        }
      }
    }
  }

  private fun deny() {
    Log.i(TAG, "Denied ${callingPackage}")
    setResult(RESULT_CANCELED, Intent().putExtra(LocalApi.EXTRA_ERROR, "denied"))
    finish()
  }

  private fun allow(caller: String, label: String) {
    busy = true
    error = null
    val token = LocalApi.tokens(this).issue(caller, label)
    LocalApi.start(this)
    lifecycleScope.launch {
      val state =
        withTimeoutOrNull(15_000L) { LocalApi.state.first { it.running || it.error != null } }
      if (state?.running == true) {
        Log.i(TAG, "Allowed $caller ($label); the local API is on")
        setResult(
          RESULT_OK,
          Intent()
            .putExtra(LocalApi.EXTRA_BASE_URL, LocalApi.BASE_URL)
            .putExtra(LocalApi.EXTRA_TOKEN, token),
        )
        finish()
      } else {
        LocalApi.tokens(this@ConnectActivity).revoke(caller)
        val why = state?.error ?: "The local API did not start within 15 s."
        Log.w(TAG, "Allowed $caller but the API is off: $why")
        error = why
        busy = false
        setResult(RESULT_CANCELED, Intent().putExtra(LocalApi.EXTRA_ERROR, "server_failed: $why"))
      }
    }
  }
}
