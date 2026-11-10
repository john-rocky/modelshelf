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
import com.google.ai.edge.gallery.data.DEEP_LINK_SCHEME
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task

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

/** Handles a link: opens its screen, or says why not. */
internal fun handleLink(link: LinkParts, screens: LinkScreens) {
  screens.log(
    Log.DEBUG,
    "navigation link clicked: ${link.text} (new intents so far: ${link.number})",
  )
  // 1. Precise model deep links: <DEEP_LINK_SCHEME>://model/<taskId>/<modelName>
  if (link.text.startsWith("$DEEP_LINK_SCHEME://model/")) {
    if (link.pathSegments.size >= 2) {
      val taskId = link.pathSegments[link.pathSegments.size - 2]
      val modelName = link.pathSegments.last()
      val queryStr = link.query
      screens.modelByName(modelName)?.let { model ->
        val route =
          if (!queryStr.isNullOrEmpty()) {
            "$ROUTE_MODEL/${taskId}/${model.name}?query=${screens.encodeQuery(queryStr)}"
          } else {
            "$ROUTE_MODEL/${taskId}/${model.name}"
          }
        // Model Shelf: a link to the chat that is open already (no question) leaves it as it is
        // instead of opening a second copy of it on top.
        val current = screens.openModelScreen()?.takeIf { it.onTop }
        val sameChatOpen =
          queryStr.isNullOrEmpty() &&
            current != null &&
            current.taskId == taskId &&
            current.modelName == model.name
        val sentence = if (sameChatOpen) null else screens.gate(model)
        if (sameChatOpen) {
          screens.log(Log.DEBUG, "The link's chat is open already: $route")
        } else if (sentence != null) {
          screens.log(Log.INFO, "The link's screen does not open: $sentence ($route)")
          screens.showSentence(sentence)
        } else {
          screens.navigate(route)
        }
      }
    } else {
      screens.log(Log.ERROR, "Malformed deep link URI received: ${link.text}")
    }
  } else if (link.host == "global_model_manager" || link.host == "import") {
    val route = ROUTE_MODEL_MANAGER
    screens.navigate(route)
  } else {
    // 2. Dynamic task-level deep links: <DEEP_LINK_SCHEME>://<taskId>
    val host = link.host
    if (host != null) {
      val queryStr = link.query
      val task = screens.tasks.find { it.id == host }
      if (task != null) {
        // Pick the first successfully downloaded model or the default active model for this task
        val defaultModel =
          task.models.firstOrNull { model -> screens.downloadSucceeded(model) }
            ?: task.models.firstOrNull()

        if (defaultModel != null) {
          val route =
            if (!queryStr.isNullOrEmpty()) {
              "$ROUTE_MODEL/${task.id}/${defaultModel.name}?query=${screens.encodeQuery(queryStr)}"
            } else {
              "$ROUTE_MODEL/${task.id}/${defaultModel.name}"
            }
          val sentence = screens.gate(defaultModel)
          if (sentence != null) {
            screens.log(Log.INFO, "The link's screen does not open: $sentence ($route)")
            screens.showSentence(sentence)
          } else {
            screens.navigate(route)
          }
        } else {
          screens.log(Log.ERROR, "No available model found for task: $host")
        }
      }
    }
  }
}

/** The model manager opens [model] under [task]. */
internal fun openModelFromManager(task: Task, model: Model, screens: LinkScreens) {
  screens.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
}

/**
 * Model Shelf: where the link of the intent that reached the activity last waits until the screens
 * read it. One place: an intent that comes before the screens read the last one replaces it (as the
 * activity's own intent did).
 */
class LinkInbox<T : Any> {
  private var waiting: T? = null

  /** The link of the intent that reached the activity now (null when it carries none). */
  @Synchronized
  fun offer(link: T?) {
    waiting = link
  }

  /** The link waiting to be read, or null; it is read once. */
  @Synchronized
  fun take(): T? {
    val link = waiting
    waiting = null
    return link
  }
}
