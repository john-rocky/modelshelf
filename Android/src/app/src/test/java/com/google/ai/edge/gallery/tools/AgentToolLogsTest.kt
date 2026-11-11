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

package com.google.ai.edge.gallery.tools

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.gallery.intents.IntentHandler
import com.google.ai.edge.gallery.mcp.McpServerState
import com.google.ai.edge.gallery.mcp.McpServersProvider
import com.google.ai.edge.gallery.proto.McpServer
import com.google.ai.edge.gallery.proto.McpTool
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.skills.NoOpSkillsProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Model Shelf, D-614 round 10e, line R1: Agent Chat's tools write no argument, result or script
 * data of the user to the log, in any build: a tool's name and lengths only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AgentToolLogsTest {
  private val word = "PRIVATEWORDS"
  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @Before
  fun clearTheLog() {
    ShadowLog.clear()
  }

  /** Every log line so far, a throwable's message and stack included. */
  private fun logged(): List<String> =
    ShadowLog.getLogs().map { item ->
      item.tag + ": " + item.msg + " " + (item.throwable?.let { Log.getStackTraceString(it) } ?: "")
    }

  private fun assertNoLineHasTheWords(what: String) {
    assertEquals(what, emptyList<String>(), logged().filter { it.contains(word) })
  }

  @Test
  fun anIntentsParametersAreNotLogged() {
    val parameters = """{"extra_email":"$word","extra_subject":"$word","extra_text":"$word"}"""
    RunIntentTool(context, NoOpSkillsProvider()).runIntent("send_email", parameters)
    assertNoLineHasTheWords("send_email")
  }

  @Test
  fun anIntentThatDoesNotParseDoesNotLogItsParameters() = runBlocking {
    for (action in
      listOf(
        "send_email",
        "send_sms",
        "create_calendar_event",
        "read_calendar_events",
        "schedule_notification",
      )) {
      IntentHandler.handleAction(context, action, "{$word") { true }
      assertNoLineHasTheWords(action)
    }
  }

  @Test
  fun aDateThatDoesNotParseIsNotLogged() = runBlocking {
    val event = """{"title":"t","description":"d","begin_time":"$word","end_time":"$word"}"""
    IntentHandler.handleAction(context, "create_calendar_event", event) { true }
    assertNoLineHasTheWords("create_calendar_event")
    IntentHandler.handleAction(context, "read_calendar_events", """{"date":"$word"}""") { true }
    assertNoLineHasTheWords("read_calendar_events")
  }

  /** A provider whose one skill runs a script at a fixed place. */
  private class OneSkill : NoOpSkillsProvider() {
    override suspend fun loadSkill(skillName: String): Skill =
      Skill.newBuilder().setName(skillName).setSkillUrl("https://example.com/skill").build()
  }

  @Test
  fun aScriptsDataAndResultAreNotLogged() {
    val tool = RunJsTool(OneSkill(), DATA_STORE_NOT_READ)
    val channel = Channel<ToolAction>(Channel.UNLIMITED)
    tool.onAttach(ToolExecutionContext(taskId = "task", actionChannel = channel))
    CoroutineScope(Dispatchers.Default).launch {
      for (action in channel) {
        if (action is CallJsToolAction) {
          action.result.complete("""{"result":"$word"}""")
        }
      }
    }
    tool.runJs(skillName = "skill", scriptName = "index.html", data = word)
    channel.close()
    assertNoLineHasTheWords("runJs")
  }

  @Test
  fun anMcpToolsInputIsNotLogged() {
    val provider =
      object : McpServersProvider {
        override val mcpServers =
          listOf(
            McpServerState(
              mcpServer =
                McpServer.newBuilder().addTools(McpTool.newBuilder().setName("a_tool")).build(),
              client = null,
            )
          )
      }
    RunMcpTool(provider, NoOpSkillsProvider(), "task").runMcpTool("a_tool", word)
    assertNoLineHasTheWords("runMcpTool")
  }

  // The lines a JVM test cannot reach (a real MCP client's result, an error from a server, a
  // skill's page): in these files no log call names the argument, the result, a page's console
  // line or a caught exception.
  @Test
  fun noLogCallOfTheToolsNamesTheirContent() {
    val checks =
      mapOf(
        "tools/RunIntentTool.kt" to listOf("\$parameters", "\${parameters}"),
        "tools/RunJsTool.kt" to
          listOf(
            "\$data",
            "\${data}",
            "\$url",
            "\$webviewUrl",
            "\${resultJson.result}",
            "secret.substring",
          ),
        "tools/RunMcpTool.kt" to listOf("\$input", "\$errorText", "\$successText", ", e)"),
        "tools/LoadSkillTool.kt" to listOf("\$skillContent"),
        "intents/IntentHandler.kt" to
          listOf("\$parameters", "params.date", "\$argument", "\$currentDateAndTime", ", e)"),
        "customtasks/agentchat/AgentChatScreen.kt" to
          listOf("\$result", "curConsoleMessage.message()"),
        "ui/common/GalleryWebView.kt" to listOf("consoleMessage?.message()"),
        "agent/DefaultAgentRuntimeExecutor.kt" to listOf("\$event"),
      )
    val root =
      listOf("src/main/java/com/google/ai/edge/gallery", "app/src/main/java/com/google/ai/edge/gallery")
        .map { File(it) }
        .first { it.isDirectory }
    val bad = mutableListOf<String>()
    for ((path, words) in checks) {
      for (call in logCalls(File(root, path).readText())) {
        for (w in words) {
          if (call.contains(w)) {
            bad.add("$path: $call")
          }
        }
      }
    }
    assertEquals(emptyList<String>(), bad)
  }

  /** The text of each Log.x(...) call of a source file, parentheses balanced. */
  private fun logCalls(source: String): List<String> {
    val calls = mutableListOf<String>()
    for (m in Regex("""\bLog\.[vdiwe]\(""").findAll(source)) {
      var depth = 0
      var i = m.range.last
      var inString = false
      while (i < source.length) {
        val c = source[i]
        if (c == '"' && source[i - 1] != '\\') inString = !inString
        if (!inString) {
          if (c == '(') depth++
          if (c == ')') {
            depth--
            if (depth == 0) break
          }
        }
        i++
      }
      calls.add(source.substring(m.range.first, minOf(i + 1, source.length)))
    }
    return calls
  }

  private companion object {
    // The script of this test needs no secret: RunJsTool reads the store only for one that does.
    val DATA_STORE_NOT_READ: com.google.ai.edge.gallery.data.DataStoreRepository
      get() =
        java.lang.reflect.Proxy.newProxyInstance(
          AgentToolLogsTest::class.java.classLoader,
          arrayOf(com.google.ai.edge.gallery.data.DataStoreRepository::class.java),
        ) { _, method, _ ->
          error("the data store was read: ${method.name}")
        } as com.google.ai.edge.gallery.data.DataStoreRepository
  }
}
