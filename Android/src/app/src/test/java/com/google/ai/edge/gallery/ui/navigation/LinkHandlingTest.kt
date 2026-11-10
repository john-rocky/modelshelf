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

import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.DEEP_LINK_SCHEME
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a link (and the model manager) opens a model screen (D-614 round 10d, review findings 1, 2, 3,
 * 5, 6, 13 and 14), on [handleLink] and [openModelFromManager] with screens that record what is
 * done to them.
 */
class LinkHandlingTest {
  private val gemma = Model(name = "Gemma-4-E2B-it")
  private val small = Model(name = "Gemma3-1B-IT")
  private val chat = task("llm_chat", gemma, small)
  private val askImage = task("llm_ask_image", gemma)
  private val agent = task("llm_agent_chat", gemma, small)
  private val allTasks = listOf(chat, askImage, agent)

  private fun task(id: String, vararg models: Model) =
    Task(
      id = id,
      label = id,
      category = Category.LLM,
      description = "",
      models = models.toMutableList(),
    )

  /** The app's screens, as a list of routes from the bottom, and what was done to them. */
  private inner class Screens(
    vararg stack: String,
    private val gateSentence: String? = null,
  ) : LinkScreens {
    val routes = stack.toMutableList()
    val done = mutableListOf<String>()
    val logs = mutableListOf<String>()
    val gateThreads: MutableList<Thread> = java.util.Collections.synchronizedList(mutableListOf())

    override val tasks: List<Task>
      get() = allTasks

    override fun modelByName(name: String): Model? =
      allTasks.flatMap { it.models }.firstOrNull { it.name == name }

    override fun downloadSucceeded(model: Model): Boolean = true

    override fun encodeQuery(query: String): String = query

    override fun topRoute(): String? = routes.lastOrNull()

    override fun openModelScreen(): OpenModelScreen? {
      val index = routes.indexOfLast { it.startsWith("$ROUTE_MODEL/") }
      if (index < 0) return null
      val parts = routes[index].substringBefore('?').split('/')
      return OpenModelScreen(
        route = routes[index],
        taskId = parts[1],
        modelName = parts[2],
        onTop = index == routes.size - 1,
      )
    }

    override fun gate(model: Model): String? {
      gateThreads.add(Thread.currentThread())
      done.add("gate ${model.name}")
      return gateSentence
    }

    override fun endGate() {
      done.add("end gate")
    }

    override fun showSentence(sentence: String) {
      done.add("sentence $sentence")
    }

    override fun selectModel(model: Model) {
      done.add("select ${model.name}")
    }

    override suspend fun leave(screen: OpenModelScreen) {
      done.add("leave ${screen.route}")
    }

    override fun navigate(route: String, popUpToRoute: String?) {
      done.add(if (popUpToRoute == null) "open $route" else "open $route over $popUpToRoute")
      if (popUpToRoute != null) {
        val index = routes.lastIndexOf(popUpToRoute)
        if (index >= 0) while (routes.size > index) routes.removeAt(routes.size - 1)
      }
      routes.add(route)
    }

    override fun popBackTo(route: String) {
      done.add("back to $route")
      val index = routes.lastIndexOf(route)
      if (index >= 0) while (routes.size > index + 1) routes.removeAt(routes.size - 1)
    }

    override fun deliverQuery(taskId: String, modelName: String, query: String) {
      done.add("query to $taskId/$modelName")
    }

    override fun log(priority: Int, line: String) {
      logs.add(line)
    }

    fun opened(): List<String> = done.filter { it.startsWith("open ") }
  }

  private fun link(text: String, number: Int = 1): LinkParts {
    val afterScheme = text.removePrefix("$DEEP_LINK_SCHEME://")
    val beforeQuery = afterScheme.substringBefore('?')
    val query = afterScheme.substringAfter('?', "").takeIf { it.startsWith("query=") }
    return LinkParts(
      text = text,
      host = beforeQuery.substringBefore('/'),
      pathSegments = beforeQuery.substringAfter('/', "").split('/').filter { it.isNotEmpty() },
      query = query?.removePrefix("query="),
      number = number,
    )
  }

  private fun handle(text: String, screens: Screens) = runBlocking {
    handleLink(link(text), screens)
  }

  private fun handleParts(parts: LinkParts, screens: Screens) = runBlocking {
    handleLink(parts, screens)
  }

  private val home = "homepage"
  private val chatGemma = "$ROUTE_MODEL/llm_chat/${gemma.name}"
  private val agentGemma = "$ROUTE_MODEL/llm_agent_chat/${gemma.name}"
  private val manager = ROUTE_MODEL_MANAGER

  // Finding 1: AI Chat is open with Gemma; a link opens Gemma under Agent Chat (the other
  // executor). At 35e5905 the Agent Chat screen opened on top and skipped the load, with no session.
  // After: the open chat is left the way Back leaves it, then the link's screen replaces it.
  @Test
  fun aLinkToTheSameModelUnderTheOtherExecutorLeavesTheOpenChatFirst() {
    val screens = Screens(home, chatGemma)
    handle("$DEEP_LINK_SCHEME://model/llm_agent_chat/${gemma.name}", screens)
    assertEquals(
      listOf("open $agentGemma over $chatGemma"),
      screens.opened(),
    )
    assertTrue(
      "the open chat was not left before: ${screens.done}",
      screens.done.indexOf("leave $chatGemma") in 0 until screens.done.indexOf(screens.opened()[0]),
    )
    assertEquals(listOf(home, agentGemma), screens.routes)
  }

  // Finding 2, the task link: AI Chat is open with the task's first downloaded model. At 35e5905 a
  // second copy of the same chat opened on top. After: nothing opens.
  @Test
  fun aTaskLinkToTheChatThatIsOpenOpensNothing() {
    val screens = Screens(home, chatGemma)
    handle("$DEEP_LINK_SCHEME://llm_chat", screens)
    assertEquals(emptyList<String>(), screens.opened())
    assertEquals(listOf(home, chatGemma), screens.routes)
  }

  // Finding 2, a model link with a question to AI Chat (which reads no question). At 35e5905 it
  // never counted as the open chat. After: nothing opens.
  @Test
  fun aModelLinkWithAQuestionToTheOpenAiChatOpensNothing() {
    val screens = Screens(home, chatGemma)
    handle("$DEEP_LINK_SCHEME://model/llm_chat/${gemma.name}?query=x", screens)
    assertEquals(emptyList<String>(), screens.opened())
    assertEquals(listOf(home, chatGemma), screens.routes)
  }

  // Finding 2, the same model under another task of the same executor (Ask Image). At 35e5905 its
  // screen opened on top and shared the open chat's engine and session. After: the open chat is
  // left first and the new screen replaces it.
  @Test
  fun aLinkToTheSameModelUnderAnotherTaskReplacesTheOpenChat() {
    val screens = Screens(home, chatGemma)
    handle("$DEEP_LINK_SCHEME://model/llm_ask_image/${gemma.name}", screens)
    val askImageGemma = "$ROUTE_MODEL/llm_ask_image/${gemma.name}"
    assertEquals(listOf("open $askImageGemma over $chatGemma"), screens.opened())
    assertTrue("not left first: ${screens.done}", "leave $chatGemma" in screens.done)
    assertEquals(listOf(home, askImageGemma), screens.routes)
  }

  // Finding 2, Agent Chat's reminder (a task link with a question) while that Agent Chat is open.
  // At 35e5905 a second Agent Chat opened on top on the same engine. After: the question goes to
  // the open Agent Chat, which asks the user before sending it, and nothing opens.
  @Test
  fun aReminderToTheOpenAgentChatGoesToThatChat() {
    val screens = Screens(home, agentGemma)
    handle("$DEEP_LINK_SCHEME://llm_agent_chat/?query=water%20the%20plants", screens)
    assertEquals(emptyList<String>(), screens.opened())
    assertTrue("the question did not reach it: ${screens.done}", "query to llm_agent_chat/${gemma.name}" in screens.done)
    assertEquals(listOf(home, agentGemma), screens.routes)
  }

  // Finding 3: the manager is open over chat A (a link opened it) and another chat engine answers.
  // At 35e5905 the manager opened model B with no gate, and the refused load took A's session.
  // After: the manager goes through the same gate as a link; B does not open.
  @Test
  fun theManagerOpensAModelOnlyThroughTheGate() {
    val screens = Screens(home, chatGemma, manager, gateSentence = "Another chat is still answering.")
    runBlocking { openModelFromManager(chat, small, screens) }
    assertEquals(emptyList<String>(), screens.opened())
    assertTrue("no sentence: ${screens.done}", "sentence Another chat is still answering." in screens.done)
  }

  // Finding 3: the manager over chat A opens model B. At 35e5905 B's screen went on top of the
  // manager and of A. After: A is left first and B replaces both.
  @Test
  fun theManagerLeavesTheChatBelowBeforeOpeningAnotherModel() {
    val screens = Screens(home, chatGemma, manager)
    runBlocking { openModelFromManager(chat, small, screens) }
    val chatSmall = "$ROUTE_MODEL/llm_chat/${small.name}"
    assertEquals(listOf("open $chatSmall over $chatGemma"), screens.opened())
    assertTrue("A was not left: ${screens.done}", "leave $chatGemma" in screens.done)
    assertEquals(listOf(home, chatSmall), screens.routes)
  }

  // Finding 3: the manager over chat A opens A itself. At 35e5905 a second screen of A opened.
  // After: the manager closes and A's chat is on top again.
  @Test
  fun theManagerGoesBackToTheOpenChatForItsOwnModel() {
    val screens = Screens(home, chatGemma, manager)
    runBlocking { openModelFromManager(chat, gemma, screens) }
    assertEquals(emptyList<String>(), screens.opened())
    assertEquals(listOf(home, chatGemma), screens.routes)
  }

  // Finding 5: a task id with an encoded '/', '?' or '#' (Android decodes the path segment). At
  // 35e5905 its route matched no screen and navigate threw during composition. After: nothing
  // opens and the user reads one sentence.
  @Test
  fun aTaskIdWithAnEncodedSlashQuestionMarkOrHashOpensNothingAndSaysSo() {
    for (taskId in listOf("a/b", "a?b", "a#b")) {
      val screens = Screens(home)
      handleParts(
        LinkParts(
          text = "$DEEP_LINK_SCHEME://model/x/${gemma.name}",
          host = "model",
          pathSegments = listOf(taskId, gemma.name),
          query = null,
          number = 1,
        ),
        screens,
      )
      assertEquals("task id '$taskId'", emptyList<String>(), screens.opened())
      assertTrue("no sentence for '$taskId': ${screens.done}", screens.done.any { it.startsWith("sentence ") })
    }
  }

  // Finding 5: a task id the app does not have, and a model the task does not list. At 35e5905 the
  // first opened an empty screen and the second opened the model under a task that does not list
  // it. After: neither opens; one sentence.
  @Test
  fun anUnknownTaskOrAModelTheTaskDoesNotListOpensNothingAndSaysSo() {
    for (text in
      listOf(
        "$DEEP_LINK_SCHEME://model/no_such_task/${gemma.name}",
        "$DEEP_LINK_SCHEME://model/llm_ask_image/${small.name}",
      )) {
      val screens = Screens(home)
      handle(text, screens)
      assertEquals(text, emptyList<String>(), screens.opened())
      assertTrue("no sentence for $text: ${screens.done}", screens.done.any { it.startsWith("sentence ") })
    }
  }

  // Finding 6: a link's screen first loaded the model selected before, then its own. At 35e5905 the
  // link opened its screen without selecting its model. After: its model is selected first.
  @Test
  fun aLinkSelectsItsModelBeforeItsScreenOpens() {
    val screens = Screens(home)
    handle("$DEEP_LINK_SCHEME://model/llm_chat/${small.name}", screens)
    val open = screens.opened().single()
    assertTrue(
      "not selected before: ${screens.done}",
      screens.done.indexOf("select ${small.name}") in 0 until screens.done.indexOf(open),
    )
  }

  // Finding 13: each manager link pushed another manager screen. After: a manager link while the
  // manager is on top opens nothing.
  @Test
  fun aManagerLinkWhileTheManagerIsOnTopOpensNothing() {
    val screens = Screens(home, manager)
    handle("$DEEP_LINK_SCHEME://global_model_manager", screens)
    assertEquals(emptyList<String>(), screens.opened())
    assertEquals(listOf(home, manager), screens.routes)
  }

  // Finding 13: two links that reach the activity before the screens read once. At 35e5905 the
  // second replaced the first, which was lost. After: both are read, in order.
  @Test
  fun twoLinksBeforeOneReadAreBothReadInOrder() {
    val inbox = LinkInbox<String>()
    inbox.offer("first")
    inbox.offer("second")
    assertEquals("first", inbox.take())
    assertEquals("second", inbox.take())
    assertEquals(null, inbox.take())
  }

  // Finding 14: the link (with its question) was written to logcat. After: no log line carries the
  // link or the question, whatever the link does.
  @Test
  fun noLogLineCarriesTheLinkOrItsQuestion() {
    val secret = "PRIVATEWORDS"
    val texts =
      listOf(
        "$DEEP_LINK_SCHEME://model/llm_chat/${small.name}?query=$secret",
        "$DEEP_LINK_SCHEME://llm_agent_chat/?query=$secret",
        "$DEEP_LINK_SCHEME://model/$secret",
        "$DEEP_LINK_SCHEME://$secret",
      )
    for (text in texts) {
      for (gateSentence in listOf(null, "Another chat is still answering.")) {
        val screens = Screens(home, chatGemma, gateSentence = gateSentence)
        handle(text, screens)
        val leaked = screens.logs.filter { secret in it }
        assertEquals("lines with the link's words for $text", emptyList<String>(), leaked)
      }
    }
  }

  // Finding 11: the gate takes the chat-engine slot's lock, under which an engine may be closing
  // (up to 0.9 s in round 10c's logs). At 35e5905 it was asked during composition, on the main
  // thread. After: never on the thread that reads the links (the main thread in the app).
  @Test
  fun theGateIsNotAskedOnTheThreadThatReadsTheLinks() {
    val screens = Screens(home)
    val reader = runBlocking {
      handleLink(link("$DEEP_LINK_SCHEME://model/llm_chat/${small.name}"), screens)
      Thread.currentThread()
    }
    assertEquals(1, screens.gateThreads.size)
    assertTrue(
      "the gate was asked on the thread that reads the links",
      screens.gateThreads.none { it === reader },
    )
  }
}
