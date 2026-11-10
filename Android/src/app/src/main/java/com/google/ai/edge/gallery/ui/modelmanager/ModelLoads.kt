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
import android.util.Log
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.litertlm.Contents
import java.util.Collections
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGModelManagerViewModel"

/**
 * Model Shelf: how a screen's model is loaded and cleaned up, and how a screen's models are cleaned
 * up when the screen is left: [ModelManagerViewModel.initializeModel] and
 * [ModelManagerViewModel.cleanupModel] as they were, and the clean-up loop the chat screen ran on
 * Back, moved here so that the JVM tests run them without the view model's dependencies.
 *
 * @param scope the view model's scope
 * @param taskById the app's task of an id
 * @param systemPromptOf the system prompt a task's model is loaded with
 * @param onLoaded told each model that loaded (the view model notes its accelerator)
 */
internal class ModelLoads(
  private val scope: CoroutineScope,
  private val taskById: (String) -> CustomTask?,
  private val systemPromptOf: suspend (Task) -> String,
  private val onLoaded: (Model) -> Unit,
) {
  /** The clean-ups of the screens being left now (see [leaveTask]). */
  private val leaving: MutableList<Job> = Collections.synchronizedList(mutableListOf())

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    scope.launch {
      // Model Shelf: a load waits until the screens being left have cleaned up their models (D-614
      // round 10d). A load that came while the engine of the screen left last still closed saw its
      // model "loaded" and skipped, and the close then left it with no engine and no session (review
      // finding 2); a load that started while the clean-up loop ran was marked to be cleaned up
      // after it (finding 4).
      awaitLeaves()

      // Skip if initialized already.
      if (!force && model.initStatusFlow.value is Model.InitializationStatus.Initialized) {
        Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
        onDone()
        return@launch
      }

      // Skip if initialization is in progress.
      if (model.initializing) {
        model.cleanUpAfterInit = false
        Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
        return@launch
      }

      // Clean up.
      cleanupModel(context = context, task = task, model = model)

      // Start initialization.
      Log.d(TAG, "Initializing model '${model.name}'...")
      model.markInitializationStarted()

      val onDoneFn: (error: String) -> Unit = { error ->
        if (model.instance != null) {
          Log.d(TAG, "Model '${model.name}' initialized successfully")
          onLoaded(model)

          if (model.cleanUpAfterInit) {
            model.markInitializationFailed(
              IllegalStateException("Model cleaned up after initialization")
            )
            Log.d(TAG, "Model '${model.name}' needs cleaning up after init.")
            cleanupModel(context = context, task = task, model = model)
          } else {
            model.markInitialized()
          }
          onDone()
        } else if (error.isNotEmpty()) {
          model.markInitializationFailed(error)
          Log.d(TAG, "Model '${model.name}' failed to initialize")
          onError(error)
        } else {
          model.markInitialized(null)
        }
      }

      // Call the model initialization function.
      val systemPrompt = systemPromptOf(task)
      withContext(Dispatchers.IO) {
        taskById(task.id)
          ?.initializeModelFn(
            context = context,
            coroutineScope = scope,
            model = model,
            systemInstruction = Contents.of(systemPrompt),
            onDone = onDoneFn,
          )
      }
    }
  }

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) {
    if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
      Log.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
      onDone()
      return
    }

    if (model.instance != null) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Cleaning up model '${model.name}'...")
      val onDoneFn: () -> Unit = {
        model.resetInitialization()
        Log.d(TAG, "Clean up model '${model.name}' done")
        onDone()
      }
      taskById(task.id)
        ?.cleanUpModelFn(
          context = context,
          coroutineScope = scope,
          model = model,
          onDone = onDoneFn,
        )
    } else {
      // When model is being initialized and we are trying to clean it up at same time, we mark it
      // to clean up and it will be cleaned up after initialization is done.
      if (model.initializing) {
        Log.d(
          TAG,
          "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
        )
        model.cleanUpAfterInit = true
      }
      onDone()
    }
  }

  /**
   * A screen of [task] is left (Back, or a link that replaces it): every model of the task is
   * cleaned up, one after the other (a task that keeps its models alive keeps them). The job ends
   * when every clean-up has ended; loads wait for it.
   */
  fun leaveTask(context: Context, task: Task): Job {
    val keepAlive = taskById(task.id)?.keepModelAlive == true
    val job =
      scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        if (keepAlive) return@launch
        // clean up all models.
        for (model in task.models) {
          val ended =
            withTimeoutOrNull(LEAVE_CLEANUP_WAIT_MS) {
              suspendCancellableCoroutine<Unit> { done ->
                cleanupModel(
                  context = context,
                  task = task,
                  model = model,
                  onDone = { if (done.isActive) done.resume(Unit) },
                )
              }
            }
          if (ended == null) {
            Log.w(TAG, "The clean-up of '${model.name}' did not end in $LEAVE_CLEANUP_WAIT_MS ms")
          }
        }
      }
    leaving.add(job)
    job.invokeOnCompletion { leaving.remove(job) }
    job.start()
    return job
  }

  private suspend fun awaitLeaves() {
    while (true) {
      val jobs = synchronized(leaving) { leaving.toList() }
      if (jobs.isEmpty()) return
      jobs.joinAll()
      synchronized(leaving) { leaving.removeAll(jobs) }
    }
  }
}

/** Model Shelf: how long a screen's leave waits for one model's clean-up (an engine's close). */
private const val LEAVE_CLEANUP_WAIT_MS = 15_000L
