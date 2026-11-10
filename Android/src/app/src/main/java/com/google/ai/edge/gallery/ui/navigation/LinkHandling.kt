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

package com.google.ai.edge.gallery.ui.navigation

import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import com.google.ai.edge.gallery.data.DEEP_LINK_SCHEME
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * Model Shelf: a link that reached the app, in the parts the app reads (Android's Uri gives them;
 * the path segments and the query are decoded).
 *
 * @param text the whole link
 * @param number how many links reached the running screen before this one
 */
internal data class LinkParts(
  val text: String,
  val host: String?,
  val pathSegments: List<String>,
  val query: String?,
  val number: Int,
)

/**
 * Model Shelf: a model screen in the back stack.
 *
 * @param onTop whether it is the screen on top
 */
internal data class OpenModelScreen(
  val route: String,
  val taskId: String,
  val modelName: String,
  val onTop: Boolean,
)

/**
 * Model Shelf: what a link (or the model manager) can read and do to the app's screens. The nav
 * graph gives the real ones; the JVM tests record them.
 */
internal interface LinkScreens {
  /** The app's tasks. */
  val tasks: List<Task>

  fun modelByName(name: String): Model?

  fun downloadSucceeded(model: Model): Boolean

  fun encodeQuery(query: String): String

  /** The route of the screen on top, or null. */
  fun topRoute(): String?

  /** The model screen nearest the top of the back stack, or null. */
  fun openModelScreen(): OpenModelScreen?

  /** The sentence shown instead of a screen of [model], or null when the screen may open. */
  fun gate(model: Model): String?

  /** Ends what [gate] started when it returned null. */
  fun endGate()

  fun showSentence(sentence: String)

  fun selectModel(model: Model)

  /** Leaves [screen] the way Back leaves it (its task's models are cleaned up); returns after. */
  suspend fun leave(screen: OpenModelScreen)

  /** Opens [route]; first pops the back stack down to [popUpToRoute] and that screen too. */
  fun navigate(route: String, popUpToRoute: String? = null)

  /** Pops the back stack down to [route], which stays. */
  fun popBackTo(route: String)

  /** Gives [query] to the open screen of [taskId] and [modelName]. */
  fun deliverQuery(taskId: String, modelName: String, query: String)

  fun log(priority: Int, line: String)
}

/** Model Shelf: the sentence a link gets when it opens no screen of the app. */
internal const val LINK_OPENS_NOTHING = "This link does not open a screen of Model Shelf."

/**
 * Handles a link: opens its screen, or says why not (D-614 round 10d).
 *
 * - `<scheme>://model/<task id>/<model name>[?query=…]`: the task must be one of the app's and list
 *   the model; `<scheme>://<task id>[?query=…]`: the task's first downloaded model (else its first);
 *   then [openModelScreen].
 * - `<scheme>://global_model_manager` and `<scheme>://import`: the model manager, unless it is on
 *   top already.
 * - Anything else opens nothing and the user reads [LINK_OPENS_NOTHING].
 *
 * No log line carries the link or its question (review finding 14): the lines name only a task id
 * and a model name of the app.
 */
internal suspend fun handleLink(link: LinkParts, screens: LinkScreens) {
  val label = "Link ${link.number}"
  screens.log(Log.INFO, "$label arrived")
  if (link.text.startsWith("$DEEP_LINK_SCHEME://model/")) {
    // 1. Precise model deep links: <DEEP_LINK_SCHEME>://model/<taskId>/<modelName>
    val task = link.pathSegments.getOrNull(0)?.let { id -> screens.tasks.find { it.id == id } }
    val model = link.pathSegments.getOrNull(1)?.let { name -> task?.models?.find { it.name == name } }
    when {
      link.pathSegments.size != 2 -> opensNothing(label, "not a task and a model", screens)
      task == null -> opensNothing(label, "no such task", screens)
      model == null -> opensNothing(label, "the task does not list that model", screens)
      else -> openModelScreen(task, model, link.query, label, screens)
    }
  } else if (link.host == "global_model_manager" || link.host == "import") {
    if (screens.topRoute()?.startsWith(ROUTE_MODEL_MANAGER) == true) {
      screens.log(Log.INFO, "$label: the model manager is open already")
    } else {
      navigateOrSay(ROUTE_MODEL_MANAGER, popUpToRoute = null, label, screens)
      screens.log(Log.INFO, "$label: opened the model manager")
    }
  } else {
    // 2. Dynamic task-level deep links: <DEEP_LINK_SCHEME>://<taskId>
    val task = link.host?.let { id -> screens.tasks.find { it.id == id } }
    if (task == null) {
      opensNothing(label, "no such task", screens)
      return
    }
    // Pick the first successfully downloaded model or the default active model for this task
    val model =
      task.models.firstOrNull { model -> screens.downloadSucceeded(model) }
        ?: task.models.firstOrNull()
    if (model == null) {
      opensNothing(label, "the task lists no model", screens)
    } else {
      openModelScreen(task, model, link.query, label, screens)
    }
  }
}

/**
 * The model manager opens [model] under [task]: the same way as a link (the gate, and the open
 * model screen is left first), so the manager can no longer stack a screen on a chat or replace a
 * chat's session with a refused load (review finding 3).
 */
internal suspend fun openModelFromManager(task: Task, model: Model, screens: LinkScreens) {
  openModelScreen(task, model, query = null, label = "The model manager", screens = screens)
}

/**
 * Model Shelf: the one way a link or the model manager opens a model screen (D-614 round 10d). The
 * app keeps one model screen: a second one on top shared the first one's model object, engine and
 * executor session, and leaving either broke the other (review findings 1-4).
 *
 * - The open model screen is [task]'s with [model]: it stays (the manager or anything above it
 *   closes); a question goes to it, and Agent Chat asks the user before sending it.
 * - Else the gate: while a chat engine answers, or the local API holds [model]'s file while a chat
 *   engine is alive, nothing changes and the user reads the gate's sentence.
 * - Else the open model screen is left the way Back leaves it (its models cleaned up, waited for),
 *   [model] is selected (so the new screen loads it and nothing else, finding 6), and [task]'s
 *   screen of [model] replaces the open one.
 */
internal suspend fun openModelScreen(
  task: Task,
  model: Model,
  query: String?,
  label: String,
  screens: LinkScreens,
) {
  val open = screens.openModelScreen()
  if (open != null && open.taskId == task.id && open.modelName == model.name) {
    if (!open.onTop) screens.popBackTo(open.route)
    if (!query.isNullOrEmpty()) screens.deliverQuery(task.id, model.name, query)
    screens.log(Log.INFO, "$label: the chat of ${task.id}/${model.name} is open already")
    return
  }
  // The gate takes the chat-engine slot's lock, under which an engine may be closing: never on the
  // main thread (review finding 11).
  val sentence = withContext(Dispatchers.Default) { screens.gate(model) }
  if (sentence != null) {
    screens.log(Log.INFO, "$label: no screen opens ($sentence)")
    screens.showSentence(sentence)
    return
  }
  try {
    if (open != null) {
      screens.log(Log.INFO, "$label: leaving the chat of ${open.taskId}/${open.modelName} first")
      screens.leave(open)
    }
    screens.selectModel(model)
    val route =
      if (!query.isNullOrEmpty()) {
        "$ROUTE_MODEL/${task.id}/${model.name}?query=${screens.encodeQuery(query)}"
      } else {
        "$ROUTE_MODEL/${task.id}/${model.name}"
      }
    if (navigateOrSay(route, popUpToRoute = open?.route, label, screens)) {
      screens.log(Log.INFO, "$label: opened ${task.id}/${model.name}")
    }
  } finally {
    screens.endGate()
  }
}

private fun opensNothing(label: String, why: String, screens: LinkScreens) {
  screens.log(Log.INFO, "$label: opens no screen ($why)")
  screens.showSentence(LINK_OPENS_NOTHING)
}

/** Navigates; a route no screen matches opens nothing and says so instead of ending the process. */
private fun navigateOrSay(
  route: String,
  popUpToRoute: String?,
  label: String,
  screens: LinkScreens,
): Boolean =
  try {
    screens.navigate(route, popUpToRoute)
    true
  } catch (e: IllegalArgumentException) {
    opensNothing(label, "no screen has its route", screens)
    false
  }

/**
 * Model Shelf: where the links of the intents that reached the activity wait until the screens read
 * them, in the order they came (D-614 round 10d: one place before, so a link that came before the
 * screens read the last one replaced it and the first was lost, review finding 13). An intent with
 * no link takes nothing away.
 */
class LinkInbox<T : Any> {
  private val links = Channel<T>(Channel.UNLIMITED)

  /** The link of an intent that reached the activity (null when it carries none). */
  fun offer(link: T?) {
    if (link != null) links.trySend(link)
  }

  /** The link that came first and is not read yet, or null. */
  fun take(): T? = links.tryReceive().getOrNull()

  /** Waits for a link and returns it. */
  suspend fun receive(): T = links.receive()
}

/**
 * Model Shelf: whether the model screen around a composable is being left because a link (or the
 * model manager) replaces it. Its model-load effects then start nothing: while it slides out it
 * still sees the model selected for the screen that replaces it, and would load that model under
 * its own task (D-614 round 10d).
 */
val LocalModelScreenLeft = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Model Shelf: the key of [LocalModelScreenLeft]'s flag in a model screen's saved state. */
internal const val MODEL_SCREEN_LEFT_KEY = "model_shelf_screen_left"
