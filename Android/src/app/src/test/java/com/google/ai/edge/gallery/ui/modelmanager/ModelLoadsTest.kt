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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.litertlm.Contents
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loads and clean-ups of the screens' models (D-614 round 10d, review findings 2 and 4) on
 * [ModelLoads], the code of the model manager, with a task whose engine closes the way the app's
 * chat engine closes: the model reads loaded until the close ends.
 */
class ModelLoadsTest {
  /** One engine's close takes [closeMs]; a load takes [loadMs]. */
  private class SlowEngineTask(
    override val task: Task,
    private val closeMs: Long,
    private val loadMs: Long,
  ) : CustomTask {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun initializeModelFn(
      context: Context,
      coroutineScope: CoroutineScope,
      model: Model,
      systemInstruction: Contents?,
      onDone: (error: String) -> Unit,
    ) {
      coroutineScope.launch(Dispatchers.Default) {
        events.add("load ${model.name}")
        delay(loadMs)
        model.markInitialized(Any())
        events.add("loaded ${model.name}")
        onDone("")
      }
    }

    override fun cleanUpModelFn(
      context: Context,
      coroutineScope: CoroutineScope,
      model: Model,
      onDone: () -> Unit,
    ) {
      events.add("close ${model.name}")
      Thread.sleep(closeMs)
      model.resetInitialization()
      events.add("closed ${model.name}")
      onDone()
    }

    @Composable override fun MainScreen(data: Any) {}
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val context = ContextWrapper(null)

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun loads(task: SlowEngineTask) =
    ModelLoads(
      scope = scope,
      taskById = { id -> if (id == task.task.id) task else null },
      systemPromptOf = { "" },
      onLoaded = {},
    )

  private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      if (condition()) return true
      Thread.sleep(20)
    }
    return condition()
  }

  private fun Model.loaded(): Boolean =
    initStatusFlow.value is Model.InitializationStatus.Initialized && instance != null

  // Finding 2: the screen of model M is left and, about 0.1 s later (in the logs), a screen of M
  // loads it again while M's engine still closes. At 35e5905 the load saw M "loaded" and skipped, and
  // the close then left M with no engine and no session: that chat's next question crashed. After:
  // the load waits for the clean-up of the screen that was left, then loads M.
  @Test
  fun aLoadRightAfterTheSameModelsScreenIsLeftEndsWithTheModelLoaded() {
    val m = Model(name = "leave-then-load")
    val task =
      SlowEngineTask(
        task =
          Task(
            id = "llm_chat",
            label = "AI Chat",
            category = Category.LLM,
            description = "",
            models = mutableListOf(m),
          ),
        closeMs = 400,
        loadMs = 50,
      )
    val loads = loads(task)
    loads.initializeModel(context, task.task, m)
    assertTrue("M did not load first", waitFor(3_000) { m.loaded() })

    loads.leaveTask(context, task.task)
    Thread.sleep(100)
    loads.initializeModel(context, task.task, m)

    assertTrue("M never closed: ${task.events}", waitFor(3_000) { "closed ${m.name}" in task.events })
    assertTrue(
      "M is not loaded after the screen of M loaded it again: ${m.initStatusFlow.value}, " +
        "${task.events}",
      waitFor(3_000) { m.loaded() },
    )
  }

  // Finding 4: the screen of model T is left while the chat of model L (after T in the task's list)
  // starts loading. At 35e5905 the clean-up loop reached L while L loaded and marked it to be cleaned
  // up after its load, so L ended "Model cleaned up after initialization" with its engine alive.
  // After: L's load waits for the clean-up of the screen that was left, and L ends loaded.
  @Test
  fun aModelLoadingWhileAnotherScreenIsLeftEndsLoaded() {
    val t = Model(name = "left-top")
    val l = Model(name = "loading-below")
    val task =
      SlowEngineTask(
        task =
          Task(
            id = "llm_chat",
            label = "AI Chat",
            category = Category.LLM,
            description = "",
            models = mutableListOf(t, l),
          ),
        closeMs = 300,
        loadMs = 400,
      )
    val loads = loads(task)
    loads.initializeModel(context, task.task, t)
    assertTrue("T did not load first", waitFor(3_000) { t.loaded() })

    loads.leaveTask(context, task.task)
    Thread.sleep(100)
    loads.initializeModel(context, task.task, l)

    assertTrue(
      "L is not loaded: ${l.initStatusFlow.value}, ${task.events}",
      waitFor(3_000) { l.loaded() && "loaded ${l.name}" in task.events },
    )
    Thread.sleep(300)
    assertTrue(
      "L did not stay loaded: ${l.initStatusFlow.value}, ${task.events}",
      l.loaded(),
    )
  }
}
