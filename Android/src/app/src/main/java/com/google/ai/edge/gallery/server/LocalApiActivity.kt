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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Local API: turn the API on or off, see the apps that may use it (and remove them),
 * copy a token for curl or a computer, and see the models the API can serve.
 */
class LocalApiActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { GalleryTheme { Surface(modifier = Modifier.fillMaxSize()) { LocalApiScreen() } } }
  }
}

@Composable
private fun LocalApiScreen() {
  val context = LocalContext.current
  val state by LocalApi.state.collectAsState()
  val tokens = remember { LocalApi.tokens(context) }
  var grants by remember { mutableStateOf(tokens.all()) }
  // Apps connect on their own screen while this one waits behind it: read the list again each
  // time this screen comes back to the front.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) grants = tokens.all()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  var copiedToken by remember { mutableStateOf<String?>(null) }
  var models by remember { mutableStateOf<List<ApiModelEntry>?>(null) }
  LaunchedEffect(state.running, state.loadedModel) {
    models = ApiModelCatalog(context.applicationContext).entries(refresh = true)
  }
  val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

  Column(
    modifier =
      Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp).fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("Local API", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Apps on this phone can use the models you downloaded here, through an Ollama- and " +
        "OpenAI-compatible API at ${LocalApi.BASE_URL}. Each app asks you first. The models run " +
        "on this phone and Model Shelf sends nothing off it; what an app does with the answers is " +
        "up to that app.",
      style = MaterialTheme.typography.bodyMedium,
    )

    // Status and the switch.
    Text(
      when {
        state.stopping -> "Stopping..."
        state.running -> "On · ${LocalApi.BASE_URL}"
        else -> "Off"
      },
      style = MaterialTheme.typography.titleMedium,
    )
    if (state.stopping) {
      Text(
        "The answer that runs ends, then the model unloads and the port closes.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (state.running) {
      Text(
        state.loadedLine() ?: "No model loaded",
        style = MaterialTheme.typography.bodyMedium,
      )
      Text("Requests answered or refused: ${state.requests}", style = MaterialTheme.typography.bodySmall)
    }
    state.error?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    when {
      state.stopping -> OutlinedButton(enabled = false, onClick = {}) { Text("Stopping...") }
      state.running -> OutlinedButton(onClick = { LocalApi.stop(context) }) { Text("Stop local API") }
      else -> Button(onClick = { LocalApi.start(context) }) { Text("Start local API") }
    }

    HorizontalDivider()
    Text("Connected apps", style = MaterialTheme.typography.titleMedium)
    val apps = grants.filter { it.packageName != ApiTokenStore.MANUAL }
    if (apps.isEmpty()) {
      Text("No app has asked yet.", style = MaterialTheme.typography.bodyMedium)
    }
    for (grant in apps) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
          Text(grant.label, style = MaterialTheme.typography.bodyLarge)
          Text(
            "${grant.packageName} · since ${dateFormat.format(Date(grant.createdMs))}",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        TextButton(
          onClick = {
            tokens.revoke(grant.packageName)
            grants = tokens.all()
          }
        ) {
          Text("Remove")
        }
      }
    }

    HorizontalDivider()
    Text("Token for curl or a computer", style = MaterialTheme.typography.titleMedium)
    Text(
      "Send it as \"Authorization: Bearer <token>\". A new token replaces the last one.",
      style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = {
          val token = tokens.issue(ApiTokenStore.MANUAL, "Copied token")
          copiedToken = token
          grants = tokens.all()
          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          val clip = ClipData.newPlainText("Model Shelf API token", token)
          // ClipDescription.EXTRA_IS_SENSITIVE (API 33): the clipboard preview hides the token.
          clip.description.extras =
            PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
          clipboard.setPrimaryClip(clip)
        }
      ) {
        Text("Copy a token")
      }
      if (grants.any { it.packageName == ApiTokenStore.MANUAL }) {
        TextButton(
          onClick = {
            tokens.revoke(ApiTokenStore.MANUAL)
            copiedToken = null
            grants = tokens.all()
          }
        ) {
          Text("Remove it")
        }
      }
    }
    copiedToken?.let {
      SelectionContainer {
        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
      }
    }

    HorizontalDivider()
    Text("Models the API can use", style = MaterialTheme.typography.titleMedium)
    val list = models
    when {
      list == null -> Text("Reading...", style = MaterialTheme.typography.bodySmall)
      list.isEmpty() ->
        Text(
          "No model is downloaded yet. Download one from the model list first.",
          style = MaterialTheme.typography.bodyMedium,
        )
      else -> {
        for (m in list) {
          Column {
            Text(m.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
              "${m.accelerators.joinToString("/") { it.uppercase() }} · " +
                (if (m.stateful) "a new engine for each conversation · " else "") +
                (m.checked?.let { "checked on $it" } ?: "not checked on a phone"),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
