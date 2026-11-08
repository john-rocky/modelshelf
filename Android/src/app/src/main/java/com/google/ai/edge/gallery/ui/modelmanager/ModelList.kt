/*
 * Copyright 2025 Google LLC
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

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
// import com.google.ai.edge.gallery.ui.preview.TASK_TEST1
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.supportModelBenchmark
import com.google.ai.edge.gallery.huggingface.GatedRepo
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.RevealingText
import com.google.ai.edge.gallery.ui.common.TaskIcon
import com.google.ai.edge.gallery.ui.common.getTaskBgColor
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.modelitem.ModelItem
import com.google.ai.edge.gallery.ui.common.rememberDelayedAnimationProgress
import com.google.ai.edge.gallery.ui.theme.bodyLargeNarrow
import com.google.ai.edge.gallery.ui.theme.headlineLargeMedium

private const val TAG = "AGModelList"
private val CONTENT_ANIMATION_OFFSET = 16.dp
private const val ANIMATION_INIT_DELAY = 80L
private const val TASK_DESCRIPTION_SECTION_ANIMATION_START = 400
private const val MODEL_LIST_ANIMATION_START = TASK_DESCRIPTION_SECTION_ANIMATION_START + 150
private const val DEFAULT_ANIMATION_DURATION = 700
private const val TASK_ICON_ANIMATION_DURATION = 1100

/** The list of models in the model manager. */
@Composable
fun ModelList(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  contentPadding: PaddingValues,
  enableAnimation: Boolean,
  onModelClicked: (Model) -> Unit,
  onBenchmarkClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
) {
  // This is just to update "models" list when task.updateTrigger is updated so that the UI can
  // be properly updated.
  val models by
    remember(task) {
      derivedStateOf {
        val trigger = task.updateTrigger.value
        if (trigger >= 0) {
          task.models.toList().filter { !it.downloadInfo.imported }
        } else {
          listOf()
        }
      }
    }
  val importedModels by
    remember(task) {
      derivedStateOf {
        val trigger = task.updateTrigger.value
        if (trigger >= 0) {
          task.models.toList().filter { it.downloadInfo.imported }
        } else {
          listOf()
        }
      }
    }
  val modelVariants by
    remember(task) {
      derivedStateOf {
        val trigger = task.updateTrigger.value
        if (trigger >= 0) {
          task.models
            .toList()
            .filter { it.isVariant }
            .groupBy { it.hierarchy.parentModelName.orEmpty() }
        } else {
          mapOf()
        }
      }
    }

  // The shelf (litert-community on Hugging Face) gets its own section below Google's models.
  val liveListState by modelManagerViewModel.liveListState.collectAsState()
  val galleryModels = models.filter { it.name !in liveListState.modelNames }
  val shelfModels = models.filter { it.name in liveListState.modelNames }
  // Files checked on a phone with their sampler first; the rest and the gated repos are folded.
  val shelfMain = shelfModels.filter { it.name !in liveListState.foldedNames }
  val shelfFolded = shelfModels.filter { it.name in liveListState.foldedNames }
  val shelfGated =
    if (task.id == BuiltInTaskId.LLM_CHAT) liveListState.gatedRepos else emptyList()
  var shelfFoldedOpen by rememberSaveable { mutableStateOf(false) }
  var shelfGatedOpen by rememberSaveable { mutableStateOf(false) }
  val showShelfSection =
    shelfModels.isNotEmpty() ||
      shelfGated.isNotEmpty() ||
      (task.id == BuiltInTaskId.LLM_CHAT && liveListState.status.isNotEmpty())

  val listState = rememberLazyListState()

  val headerAnimation = rememberTaskHeaderAnimation(enableAnimation = enableAnimation)
  val modelListProgress = headerAnimation.modelListProgress
  val modelItemExpandedStates = remember { mutableStateMapOf<String, Boolean>() }

  Box(
    contentAlignment = Alignment.BottomEnd,
    modifier = Modifier.background(color = getTaskBgColor(task = task)),
  ) {
    LazyColumn(
      modifier = modifier.padding(horizontal = 16.dp),
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(8.dp),
      state = listState,
    ) {
      // Task header area.
      item(key = "taskHeader") {
        TaskHeader(
          task = task,
          modelCount = models.size + importedModels.size,
          animation = headerAnimation,
        )
      }

      // Title for recommended models.
      if (!galleryModels.isEmpty()) {
        item(key = "recommendedModelsTitle") {
          Text(
            stringResource(R.string.model_list_recommended_models_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier =
              Modifier.padding(horizontal = 16.dp, vertical = 8.dp).graphicsLayer {
                alpha = modelListProgress
                translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
              },
          )
        }
      }

      // List of models within a task.
      items(items = galleryModels) { model ->
        if (!model.isVariant) {
          val expanded = modelItemExpandedStates.getOrDefault(model.name, null)
          ModelItem(
            model = model,
            modelVariants = modelVariants.getOrDefault(model.name, listOf()),
            task = task,
            modelManagerViewModel = modelManagerViewModel,
            onModelClicked = onModelClicked,
            onBenchmarkClicked = onBenchmarkClicked,
            expanded = expanded,
            onExpanded = { modelItemExpandedStates[model.name] = it },
            isBenchmarkSupported = model.supportModelBenchmark,
            modifier =
              Modifier.graphicsLayer {
                alpha = modelListProgress
                translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
              },
          )
        }
      }

      // The shelf: title, status line, then one item per file with its note.
      if (showShelfSection) {
        item(key = "shelfModelsTitle") {
          Column(
            modifier =
              Modifier.padding(horizontal = 16.dp)
                .padding(top = 32.dp, bottom = 8.dp)
                .graphicsLayer {
                  alpha = modelListProgress
                  translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
                }
          ) {
            Text(
              stringResource(R.string.model_list_shelf_models_title),
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.labelLarge,
            )
            if (liveListState.status.isNotEmpty()) {
              Text(
                liveListState.status,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
              )
            }
          }
        }
      }
      items(items = shelfMain, key = { "shelf_${it.name}" }) { model ->
        ShelfModelItem(
          model = model,
          note = liveListState.notes[model.name],
          task = task,
          modelManagerViewModel = modelManagerViewModel,
          onModelClicked = onModelClicked,
          onBenchmarkClicked = onBenchmarkClicked,
          modifier =
            Modifier.graphicsLayer {
              alpha = modelListProgress
              translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
            },
        )
      }

      // Folded: files not checked on a phone with their sampler, and state models.
      if (shelfFolded.isNotEmpty()) {
        item(key = "shelfFoldedTitle") {
          ShelfFoldTitle(
            title = stringResource(R.string.model_list_shelf_folded_title, shelfFolded.size),
            open = shelfFoldedOpen,
            onClick = { shelfFoldedOpen = !shelfFoldedOpen },
          )
        }
        if (shelfFoldedOpen) {
          items(items = shelfFolded, key = { "shelf_folded_${it.name}" }) { model ->
            ShelfModelItem(
              model = model,
              note = liveListState.notes[model.name],
              task = task,
              modelManagerViewModel = modelManagerViewModel,
              onModelClicked = onModelClicked,
              onBenchmarkClicked = onBenchmarkClicked,
            )
          }
        }
      }

      // Folded: gated repos, each with a link to its page; this app cannot download them.
      if (shelfGated.isNotEmpty()) {
        item(key = "shelfGatedTitle") {
          ShelfFoldTitle(
            title = stringResource(R.string.model_list_shelf_gated_title, shelfGated.size),
            open = shelfGatedOpen,
            onClick = { shelfGatedOpen = !shelfGatedOpen },
          )
        }
        if (shelfGatedOpen) {
          items(items = shelfGated, key = { "shelf_gated_${it.id}" }) { repo -> GatedRepoItem(repo) }
        }
      }

      // Title for imported models.
      if (importedModels.isNotEmpty()) {
        item(key = "importedModelsTitle") {
          Text(
            stringResource(R.string.model_list_imported_models_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier =
              Modifier.padding(horizontal = 16.dp)
                .padding(top = 32.dp, bottom = 8.dp)
                .graphicsLayer {
                  alpha = modelListProgress
                  translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
                },
          )
        }
      }

      // List of imported models within a task.
      items(items = importedModels, key = { it.name }) { model ->
        Box {
          ModelItem(
            model = model,
            task = task,
            modelManagerViewModel = modelManagerViewModel,
            onModelClicked = onModelClicked,
            onBenchmarkClicked = onBenchmarkClicked,
            isBenchmarkSupported = model.supportModelBenchmark,
            modifier =
              Modifier.graphicsLayer {
                alpha = modelListProgress
                translationY = (CONTENT_ANIMATION_OFFSET * (1 - modelListProgress)).toPx()
              },
          )
        }
      }
    }

    // Gradient overlay at the bottom.
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(contentPadding.calculateBottomPadding())
          .background(
            Brush.verticalGradient(
              colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
            )
          )
          .align(Alignment.BottomCenter)
    )
  }
}

/** A shelf file: the regular model item and the line under it (check, defaults, version). */
@Composable
private fun ShelfModelItem(
  model: Model,
  note: String?,
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  onModelClicked: (Model) -> Unit,
  onBenchmarkClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    ModelItem(
      model = model,
      task = task,
      modelManagerViewModel = modelManagerViewModel,
      onModelClicked = onModelClicked,
      onBenchmarkClicked = onBenchmarkClicked,
      isBenchmarkSupported = model.supportModelBenchmark,
    )
    note?.let {
      Text(
        it,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
  }
}

/** The title of a folded part of the shelf; a tap opens or closes it. */
@Composable
private fun ShelfFoldTitle(title: String, open: Boolean, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp)
        .padding(top = 24.dp, bottom = 8.dp),
  ) {
    Text(
      title,
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.weight(1f),
    )
    Icon(
      if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
      contentDescription =
        stringResource(
          if (open) R.string.model_list_shelf_fold_close else R.string.model_list_shelf_fold_open
        ),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** A gated repo: its name, the badge, why there is no download here, and a link to its page. */
@Composable
private fun GatedRepoItem(repo: GatedRepo) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          shape = RoundedCornerShape(16.dp),
        )
        .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Text(
      repo.id.substringAfter('/'),
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      stringResource(R.string.model_list_shelf_gated_badge),
      color = MaterialTheme.colorScheme.tertiary,
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(top = 4.dp),
    )
    Text(
      stringResource(R.string.model_list_shelf_gated_body),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(top = 4.dp),
    )
    ClickableLink(
      url = repo.url,
      linkText = stringResource(R.string.model_list_shelf_gated_link),
      textAlign = TextAlign.Start,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

/** Progress of the staggered entrance animation of [TaskHeader] and the list below it. */
data class TaskHeaderAnimation(
  val taskIconProgress: Float,
  val taskLabelProgress: Float,
  val descriptionProgress: Float,
  val modelListProgress: Float,
)

/** Runs the entrance animation of [TaskHeader], or skips it when [enableAnimation] is false. */
@Composable
fun rememberTaskHeaderAnimation(enableAnimation: Boolean): TaskHeaderAnimation {
  val taskIconProgress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = ANIMATION_INIT_DELAY,
        animationDurationMs = TASK_ICON_ANIMATION_DURATION,
        animationLabel = "task icon",
      )
    }

  val taskLabelProgress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = ANIMATION_INIT_DELAY + 300,
        animationDurationMs = TASK_ICON_ANIMATION_DURATION,
        animationLabel = "task label",
      )
    }

  val descriptionProgress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = ANIMATION_INIT_DELAY + TASK_DESCRIPTION_SECTION_ANIMATION_START,
        animationDurationMs = DEFAULT_ANIMATION_DURATION,
        animationLabel = "description",
      )
    }

  val modelListProgress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = ANIMATION_INIT_DELAY + MODEL_LIST_ANIMATION_START,
        animationDurationMs = DEFAULT_ANIMATION_DURATION,
        animationLabel = "model_list",
      )
    }
  return TaskHeaderAnimation(
    taskIconProgress = taskIconProgress,
    taskLabelProgress = taskLabelProgress,
    descriptionProgress = descriptionProgress,
    modelListProgress = modelListProgress,
  )
}

/**
 * The header at the top of a task's model list: the task icon, name, description, links, and the
 * number of available models.
 *
 * @param modelCountText Replaces the "N models available" line, for example with per-kind counts.
 */
@Composable
fun TaskHeader(
  task: Task,
  modelCount: Int,
  animation: TaskHeaderAnimation,
  modifier: Modifier = Modifier,
  modelCountText: String? = null,
) {
  Column(modifier = modifier) {
    Spacer(modifier = Modifier.height(32.dp))
    Column(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
    ) {
      // Task icon.
      TaskIcon(task = task, width = 64.dp, animationProgress = animation.taskIconProgress)

      // Task name.
      Box(
        modifier =
          Modifier.offset(x = (20f * (1f - animation.taskIconProgress)).dp).semantics {
            contentDescription = task.label
          }
      ) {
        RevealingText(
          text = task.label,
          style =
            headlineLargeMedium.copy(
              brush = Brush.linearGradient(getTaskBgGradientColors(task = task))
            ),
          textAlign = TextAlign.Center,
          animationProgress = animation.taskIconProgress,
        )
        RevealingText(
          text = task.label,
          style = headlineLargeMedium,
          textAlign = TextAlign.Center,
          animationProgress = animation.taskLabelProgress,
        )
      }

      // Experimental pill
      if (task.experimental) {
        Box(modifier = Modifier.fillMaxWidth()) {
          Surface(
            shape = CircleShape, // This creates the "pill" effect
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier =
              Modifier.align(Alignment.Center).graphicsLayer {
                alpha = animation.descriptionProgress
                translationY =
                  (CONTENT_ANIMATION_OFFSET * (1 - animation.descriptionProgress)).toPx()
              },
          ) {
            Text(
              text = stringResource(R.string.model_list_experimental_label),
              style = bodyLargeNarrow.copy(fontWeight = FontWeight.Bold),
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
          }
        }
      }

      // Description.
      Text(
        task.description,
        textAlign = TextAlign.Center,
        style = bodyLargeNarrow,
        modifier =
          Modifier.graphicsLayer {
            alpha = animation.descriptionProgress
            translationY = (CONTENT_ANIMATION_OFFSET * (1 - animation.descriptionProgress)).toPx()
          },
      )

      // Urls.
      if (task.docUrl.isNotEmpty() || task.sourceCodeUrl.isNotEmpty()) {
        Box(
          modifier =
            Modifier.padding(vertical = 8.dp).graphicsLayer {
              alpha = animation.descriptionProgress
              translationY = (CONTENT_ANIMATION_OFFSET * (1 - animation.descriptionProgress)).toPx()
            }
        ) {
          Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (task.docUrl.isNotEmpty()) {
              ClickableLink(
                url = task.docUrl,
                linkText = stringResource(R.string.api_doc),
                icon = Icons.Outlined.Description,
              )
            }
            if (task.sourceCodeUrl.isNotEmpty()) {
              ClickableLink(
                url = task.sourceCodeUrl,
                linkText = stringResource(R.string.example_code),
                icon = Icons.Outlined.Code,
              )
            }
          }
        }
      }

      // Models available.
      val resources = LocalContext.current.resources
      Text(
        modelCountText
          ?: resources.getQuantityString(
            R.plurals.model_list_number_of_models_available,
            modelCount,
            modelCount,
          ),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
          Modifier.alpha(0.6f).graphicsLayer {
            alpha = animation.descriptionProgress * 0.6f
            translationY = (CONTENT_ANIMATION_OFFSET * (1 - animation.descriptionProgress)).toPx()
          },
      )
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun ModelListPreview() {
//   val context = LocalContext.current

//   GalleryTheme {
//     ModelList(
//       task = TASK_TEST1,
//       modelManagerViewModel = PreviewModelManagerViewModel(context = context),
//       onModelClicked = {},
//       contentPadding = PaddingValues(all = 16.dp),
//     )
//   }
// }
