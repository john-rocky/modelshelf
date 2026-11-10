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

package com.google.ai.edge.gallery.ui.navigation

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.DEEP_LINK_SCHEME
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelSelectionTarget
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.isLegacyTasks
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.benchmark.BenchmarkScreen
import com.google.ai.edge.gallery.ui.common.ErrorDialog
import com.google.ai.edge.gallery.ui.common.LocalTestAllowlistDialog
import com.google.ai.edge.gallery.ui.common.ModelPageAppBar
import com.google.ai.edge.gallery.ui.common.OnAppBackgroundedEffect
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.home.HomeScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.MODEL_ALLOWLIST_TEST_FILE_PATH
import com.google.ai.edge.gallery.ui.modelmanager.ModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.SectionedModelSelectionScreen
import com.google.ai.edge.gallery.ui.modelmanager.asModelSelectionHost
import com.google.ai.edge.gallery.ui.modelmanager.isSavedModelSelectionComplete
import com.google.ai.edge.gallery.ui.modelmanager.stopActiveVoicePreview
import com.google.ai.edge.gallery.ui.notifications.NotificationsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGGalleryNavGraph"
private const val ROUTE_HOMESCREEN = "homepage"
private const val ROUTE_MODEL_LIST = "model_list"
internal const val ROUTE_MODEL = "route_model"
private const val ROUTE_BENCHMARK = "benchmark"
internal const val ROUTE_MODEL_MANAGER = "model_manager"
private const val ARG_IMPORT_URL = "import_url"
private const val ROUTE_NOTIFICATIONS = "notifications"
private const val ENTER_ANIMATION_DURATION_MS = 500
private val ENTER_ANIMATION_EASING = EaseOutExpo
private const val ENTER_ANIMATION_DELAY_MS = 100

private const val EXIT_ANIMATION_DURATION_MS = 500
private val EXIT_ANIMATION_EASING = EaseOutExpo

private fun enterTween(): FiniteAnimationSpec<IntOffset> {
  return tween(
    ENTER_ANIMATION_DURATION_MS,
    easing = ENTER_ANIMATION_EASING,
    delayMillis = ENTER_ANIMATION_DELAY_MS,
  )
}

private fun exitTween(): FiniteAnimationSpec<IntOffset> {
  return tween(EXIT_ANIMATION_DURATION_MS, easing = EXIT_ANIMATION_EASING)
}

private fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Left,
  )
}

private fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Right,
  )
}

private fun AnimatedContentTransitionScope<*>.slideUpEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Up,
  )
}

private fun AnimatedContentTransitionScope<*>.slideDownExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Down,
  )
}

/** Navigation routes. */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel = hiltViewModel(),
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var pickedTask by remember { mutableStateOf<Task?>(null) }
  var enableHomeScreenAnimation by remember { mutableStateOf(true) }
  var enableModelListAnimation by remember { mutableStateOf(true) }
  // The section the model selection screen expands when a task sends the user back to it.
  var modelSelectionTargetToExpand by remember { mutableStateOf<ModelSelectionTarget?>(null) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  // Model Shelf: links and the model manager open model screens one way (LinkHandling.kt), one at a
  // time (D-614 round 10d).
  val linkContext = LocalContext.current
  var linkSentence by remember { mutableStateOf<String?>(null) }
  val linkScope = rememberCoroutineScope()
  val screensMutex = remember { Mutex() }
  val linkScreens =
    remember(navController, modelManagerViewModel, linkContext) {
      NavLinkScreens(
        navController = navController,
        modelManagerViewModel = modelManagerViewModel,
        context = linkContext,
        onSentence = { linkSentence = it },
      )
    }

  // Track whether app is in foreground.
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> {
          modelManagerViewModel.setAppInForeground(foreground = true)
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> {
          modelManagerViewModel.setAppInForeground(foreground = false)
        }
        else -> {
          /* Do nothing for other events */
        }
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Don't keep playing a voice preview after the app leaves the foreground.
  OnAppBackgroundedEffect { modelManagerViewModel.stopActiveVoicePreview() }

  NavHost(
    navController = navController,
    startDestination = ROUTE_HOMESCREEN,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    // Home screen.
    composable(route = ROUTE_HOMESCREEN) {
      HomeScreen(
        modelManagerViewModel = modelManagerViewModel,
        tosViewModel = tosViewModel,
        enableAnimation = enableHomeScreenAnimation,
        navigateToTaskScreen = { task ->
          pickedTask = task
          enableModelListAnimation = true
          navController.navigate(ROUTE_MODEL_LIST)
          firebaseAnalytics?.logEvent(
            GalleryEvent.CAPABILITY_SELECT.id,
            Bundle().apply { putString("capability_name", task.id) },
          )
        },
        onModelsClicked = { navController.navigate(ROUTE_MODEL_MANAGER) },
        onNotificationsClicked = { navController.navigate(ROUTE_NOTIFICATIONS) },
        modifier = modifier,
      )
    }

    // Model list.
    composable(
      route = ROUTE_MODEL_LIST,
      enterTransition = {
        if (initialState.destination.route == ROUTE_HOMESCREEN) {
          slideEnter()
        } else {
          EnterTransition.None
        }
      },
      exitTransition = {
        if (targetState.destination.route == ROUTE_HOMESCREEN) {
          slideExit()
        } else {
          ExitTransition.None
        }
      },
    ) {
      pickedTask?.let {
        val selectionConfig =
          modelManagerViewModel.getCustomTaskByTaskId(it.id)?.modelSelectionConfig
        if (selectionConfig != null) {
          // Tasks that need several models picked together get the sectioned selection screen.
          SectionedModelSelectionScreen(
            task = it,
            config = selectionConfig,
            modelManagerViewModel = modelManagerViewModel,
            enableAnimation = enableModelListAnimation,
            navigateUp = {
              enableHomeScreenAnimation = false
              navController.navigateUp()
            },
            onLaunch = { model ->
              modelManagerViewModel.selectModel(model)
              navController.navigate("$ROUTE_MODEL/${it.id}/${model.name}")
            },
            expandTarget = modelSelectionTargetToExpand,
            onExpandTargetHandled = { modelSelectionTargetToExpand = null },
          )
        } else {
          ModelManager(
            viewModel = modelManagerViewModel,
            task = it,
            enableAnimation = enableModelListAnimation,
            onModelClicked = { model ->
              modelManagerViewModel.selectModel(model)
              navController.navigate("$ROUTE_MODEL/${it.id}/${model.name}")
            },
            onBenchmarkClicked = { model ->
              firebaseAnalytics?.logEvent(
                GalleryEvent.CAPABILITY_SELECT.id,
                Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
              )
              navController.navigate("$ROUTE_BENCHMARK/${model.name}")
            },
            navigateUp = {
              enableHomeScreenAnimation = false
              navController.navigateUp()
            },
          )
        }
      }
    }

    // Model page.
    composable(
      route = "$ROUTE_MODEL/{taskId}/{modelName}?query={query}",
      arguments =
        listOf(
          navArgument("taskId") { type = NavType.StringType },
          navArgument("modelName") { type = NavType.StringType },
          navArgument("query") {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          },
        ),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
      val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
      // Model Shelf: a question a link brought to this open screen (it stays; D-614 round 10d) comes
      // in place of the route's own.
      val linkQuery by modelManagerViewModel.linkQuery.collectAsState()
      val queryParam =
        linkQuery?.takeIf { it.taskId == taskId && it.modelName == modelName }?.query
          ?: backStackEntry.arguments?.getString("query")
      val scope = rememberCoroutineScope()
      val context = LocalContext.current

      // Model Shelf: set when a link (or the model manager) replaces this screen (D-614 round 10d).
      val screenLeft =
        remember(backStackEntry) {
          backStackEntry.savedStateHandle.getStateFlow(MODEL_SCREEN_LEFT_KEY, false)
        }
      CompositionLocalProvider(LocalModelScreenLeft provides { screenLeft.value }) {
      modelManagerViewModel.getModelByName(name = modelName)?.let { initialModel ->
        val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
        // A task with a model selection screen only starts with a complete saved selection. Entry
        // points that skip the selection screen (the global model manager, deep links, download
        // notifications and STARTUP_SCREEN) are redirected to it otherwise. This is checked once on
        // entry, so later status changes don't pull the user out of a running task.
        val needsModelSelection =
          remember(backStackEntry) {
            val selectionConfig = customTask?.modelSelectionConfig
            customTask != null &&
              selectionConfig != null &&
              !modelManagerViewModel
                .asModelSelectionHost()
                .isSavedModelSelectionComplete(
                  task = customTask.task,
                  config = selectionConfig,
                  mainModel = initialModel,
                )
          }
        if (needsModelSelection && customTask != null) {
          LaunchedEffect(backStackEntry) {
            Log.d(TAG, "Incomplete model selection for '${customTask.task.id}'. Opening selection.")
            pickedTask = customTask.task
            enableModelListAnimation = true
            navController.navigate(ROUTE_MODEL_LIST) {
              popUpTo(backStackEntry.destination.id) { inclusive = true }
            }
          }
        } else {
          LaunchedEffect(modelName) { modelManagerViewModel.selectModel(initialModel) }

          if (customTask != null) {
            if (isLegacyTasks(customTask.task.id)) {
              customTask.MainScreen(
                data =
                  CustomTaskDataForBuiltinTask(
                    modelManagerViewModel = modelManagerViewModel,
                    onNavUp = {
                      enableModelListAnimation = false
                      navController.navigateUp()
                    },
                    initialQuery = queryParam,
                  )
              )
            } else {
              var disableAppBarControls by remember { mutableStateOf(false) }
              var disableAppBarActions by remember { mutableStateOf(false) }
              var hideTopBar by remember { mutableStateOf(false) }
              var customNavigateUpCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
              var customPromptProvider by remember { mutableStateOf<(() -> String)?>(null) }
              var customOnPromptChanged by remember { mutableStateOf<((String) -> Unit)?>(null) }
              var customHistoryClickHandler by remember { mutableStateOf<(() -> Unit)?>(null) }
              var customDefaultPromptProvider by remember { mutableStateOf<(() -> String)?>(null) }
              fun cleanUpModelsOnLeave() {
                if (!customTask.keepModelAlive) {
                  // clean up all models.
                  for (curModel in customTask.task.models) {
                    val instanceToCleanUp = curModel.instance
                    scope.launch(Dispatchers.Default) {
                      modelManagerViewModel.cleanupModel(
                        context = context,
                        task = customTask.task,
                        model = curModel,
                        instanceToCleanUp = instanceToCleanUp,
                      )
                    }
                  }
                }
              }
              CustomTaskScreen(
                task = customTask.task,
                initialModel = initialModel,
                modelManagerViewModel = modelManagerViewModel,
                disableAppBarControls = disableAppBarControls,
                disableAppBarActions = disableAppBarActions,
                hideTopBar = hideTopBar,
                useThemeColor = customTask.task.useThemeColor,
                onNavigateUp = {
                  if (customNavigateUpCallback != null) {
                    customNavigateUpCallback?.invoke()
                  } else {
                    enableModelListAnimation = false
                    navController.navigateUp()
                    cleanUpModelsOnLeave()
                  }
                },
                allowEditingSystemPrompt = customPromptProvider != null,
                curSystemPrompt =
                  customPromptProvider?.invoke() ?: customTask.task.defaultSystemPrompt,
                defaultSystemPrompt =
                  customDefaultPromptProvider?.invoke() ?: customTask.task.defaultSystemPrompt,
                onSystemPromptChanged = { newPrompt -> customOnPromptChanged?.invoke(newPrompt) },
                onHistoryClicked = customHistoryClickHandler,
              ) { bottomPadding ->
                customTask.MainScreen(
                  data =
                    CustomTaskData(
                      modelManagerViewModel = modelManagerViewModel,
                      bottomPadding = bottomPadding,
                      setAppBarControlsDisabled = { disableAppBarControls = it },
                      setAppBarActionsDisabled = { disableAppBarActions = it },
                      setTopBarVisible = { hideTopBar = !it },
                      setCustomNavigateUpCallback = { customNavigateUpCallback = it },
                      setSystemPromptProvider = { provider, onPromptChanged ->
                        customPromptProvider = provider
                        customOnPromptChanged = onPromptChanged
                      },
                      setHistoryClickHandler = { customHistoryClickHandler = it },
                      setDefaultSystemPromptProvider = { customDefaultPromptProvider = it },
                      navigateToModelSelection = { target ->
                        if (customTask.modelSelectionConfig != null) {
                          Log.d(TAG, "Opening the model selection of '${customTask.task.id}'")
                          pickedTask = customTask.task
                          modelSelectionTargetToExpand = target
                          enableModelListAnimation = false
                          // Return to the selection screen the task was launched from, keeping
                          // its selection, or open it if the task was opened from elsewhere.
                          if (!navController.popBackStack(ROUTE_MODEL_LIST, inclusive = false)) {
                            navController.navigate(ROUTE_MODEL_LIST) {
                              popUpTo(backStackEntry.destination.id) { inclusive = true }
                            }
                          }
                          cleanUpModelsOnLeave()
                        }
                      },
                    )
                )
              }
            }
          }
        }
      }
      }
    }

    // Global model manager page.
    composable(
      route = "$ROUTE_MODEL_MANAGER?$ARG_IMPORT_URL={$ARG_IMPORT_URL}",
      arguments =
        listOf(
          navArgument(ARG_IMPORT_URL) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
          }
        ),
      enterTransition = {
        if (
          initialState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            initialState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideUpEnter()
        }
      },
      exitTransition = {
        if (
          targetState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            targetState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideDownExit()
        }
      },
    ) { backStackEntry ->
      val importUrl = backStackEntry.arguments?.getString(ARG_IMPORT_URL)
      GlobalModelManager(
        viewModel = modelManagerViewModel,
        tosViewModel = tosViewModel,
        initialImportUrl = importUrl,
        supplementaryModelsViewModel = hiltViewModel(),
        navigateUp = {
          enableHomeScreenAnimation = false
          navController.navigateUp()
        },
        onModelSelected = { task, model ->
          linkScope.launch {
            screensMutex.withLock {
              openModelFromManager(task = task, model = model, screens = linkScreens)
            }
          }
        },
        onBenchmarkClicked = { model ->
          firebaseAnalytics?.logEvent(
            GalleryEvent.CAPABILITY_SELECT.id,
            Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
          )
          navController.navigate("$ROUTE_BENCHMARK/${model.name}")
        },
      )
    }

    // Notifications page.
    composable(
      route = ROUTE_NOTIFICATIONS,
      enterTransition = { slideUpEnter() },
      exitTransition = { slideDownExit() },
    ) {
      NotificationsScreen(navigateUp = { navController.navigateUp() })
    }

    // Benchmark creation page.
    composable(
      route = "$ROUTE_BENCHMARK/{modelName}",
      arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""

      modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
        BenchmarkScreen(
          initialModel = model,
          modelManagerViewModel = modelManagerViewModel,
          onBackClicked = {
            enableModelListAnimation = false
            navController.navigateUp()
          },
        )
      }
    }
  }

  // Shown on top of whichever screen is active, since the app may skip the home screen at launch
  // (e.g. via the STARTUP_SCREEN flag or a deep link).
  if (modelManagerUiState.showLocalTestAllowlistDialog) {
    LocalTestAllowlistDialog(
      filePath = MODEL_ALLOWLIST_TEST_FILE_PATH,
      onDismiss = { modelManagerViewModel.dismissLocalTestAllowlistDialog() },
    )
  }

  // Model Shelf: the sentence a link (or the model manager) got instead of its screen, shown over
  // the current screen until Close.
  linkSentence?.let { sentence -> ErrorDialog(error = sentence, onDismiss = { linkSentence = null }) }

  // Handle incoming intents for deep links
  // Model Shelf: MainActivity is singleTask (one screen, so one chat engine), so a link that comes
  // while the screen runs arrives through onNewIntent. The links wait in the model manager's inbox
  // and are read here one at a time, in the order they came, off the composition: opening a link's
  // screen may first wait for the open chat's engine to close (D-614 round 10d).
  LaunchedEffect(Unit) {
    // Wait until the model manager has been initialized and the tasks are available.
    modelManagerViewModel.uiState.first { it.tasks.isNotEmpty() }
    var number = 0
    while (true) {
      val data = modelManagerViewModel.linkInbox.receive()
      number++
      val link =
        LinkParts(
          text = data.toString(),
          host = data.host,
          pathSegments = data.pathSegments,
          query = if (data.isHierarchical) data.getQueryParameter("query") else null,
          number = number,
        )
      screensMutex.withLock { handleLink(link = link, screens = linkScreens) }
    }
  }
}

@Composable
private fun CustomTaskScreen(
  task: Task,
  initialModel: Model,
  modelManagerViewModel: ModelManagerViewModel,
  disableAppBarControls: Boolean,
  disableAppBarActions: Boolean,
  hideTopBar: Boolean,
  useThemeColor: Boolean,
  onNavigateUp: () -> Unit,
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  defaultSystemPrompt: String = task.defaultSystemPrompt,
  onSystemPromptChanged: (String) -> Unit = {},
  onHistoryClicked: (() -> Unit)? = null,
  content: @Composable (bottomPadding: Dp) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  // Use currentModel on initial composition to prevent reading stale selectedModel from
  // ViewModel before selectModel() runs. Once ViewModel synchronizes with the current model,
  // use the live model instance from uiState to capture ongoing configuration/state updates.
  var currentModel by remember(initialModel.name) { mutableStateOf(initialModel) }
  val selectedModel =
    if (modelManagerUiState.selectedModel.name == currentModel.name) {
      modelManagerUiState.selectedModel
    } else {
      currentModel
    }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var appBarHeight by remember { mutableIntStateOf(0) }

  val handleNavigateUp = {
    navigatingUp = true
    onNavigateUp()
  }

  // Handle system's edge swipe.
  BackHandler { handleNavigateUp() }

  // Initialize model when model/download state changes.
  // Model Shelf: a screen a link replaces loads nothing while it slides out (D-614 round 10d).
  val screenLeft = LocalModelScreenLeft.current
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp && !screenLeft()) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from CustomTaskScreen launched effect",
        )
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  val modelInitStatus by selectedModel.initStatusFlow.collectAsState()
  LaunchedEffect(modelInitStatus) {
    showErrorDialog = modelInitStatus is Model.InitializationStatus.Failed
  }

  Scaffold(
    topBar = {
      AnimatedVisibility(
        !hideTopBar,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
      ) {
        ModelPageAppBar(
          task = task,
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          inProgress = disableAppBarControls,
          modelPreparing = disableAppBarControls,
          actionsDisabled = disableAppBarActions,
          shouldShowHistoryButton = onHistoryClicked != null,
          onHistoryClicked = { onHistoryClicked?.invoke() },
          useThemeColor = useThemeColor,
          modifier =
            Modifier.onGloballyPositioned { coordinates -> appBarHeight = coordinates.size.height },
          hideModelSelector = task.models.size <= 1,
          onConfigChanged = { _, _ -> },
          onBackClicked = { handleNavigateUp() },
          onModelSelected = { prevModel, newSelectedModel ->
            currentModel = newSelectedModel
            val instanceToCleanUp = prevModel.instance
            scope.launch(Dispatchers.Default) {
              // Clean up prev model.
              if (prevModel.name != newSelectedModel.name) {
                modelManagerViewModel.cleanupModel(
                  context = context,
                  task = task,
                  model = prevModel,
                  instanceToCleanUp = instanceToCleanUp,
                )
              }

              // Update selected model.
              Log.d(TAG, "from model picker. new: ${newSelectedModel.name}")
              modelManagerViewModel.selectModel(model = newSelectedModel)
            }
          },
          allowEditingSystemPrompt = allowEditingSystemPrompt,
          curSystemPrompt = curSystemPrompt,
          defaultSystemPrompt = defaultSystemPrompt,
          onSystemPromptChanged = onSystemPromptChanged,
        )
      }
    }
  ) { innerPadding ->
    // Calculate the target height in Dp for the content's top padding.
    val targetPaddingDp =
      if (!hideTopBar && appBarHeight > 0) {
        // Convert measured pixel height to Dp
        with(LocalDensity.current) { appBarHeight.toDp() }
      } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      }

    // Animate the actual top padding value.
    val animatedTopPadding by
      animateDpAsState(
        targetValue = targetPaddingDp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "TopPaddingAnimation",
      )

    Box(
      modifier =
        Modifier.padding(
          top = if (!hideTopBar) innerPadding.calculateTopPadding() else animatedTopPadding,
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      AnimatedContent(
        targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      ) { targetState ->
        when (targetState) {
          // Main UI when model is downloaded.
          true -> content(innerPadding.calculateBottomPadding())
          // Model download
          false ->
            ModelDownloadStatusInfoPanel(
              model = selectedModel,
              task = task,
              modelManagerViewModel = modelManagerViewModel,
            )
        }
      }
    }
  }

  if (showErrorDialog) {
    val initErrorMessage =
      (modelInitStatus as? Model.InitializationStatus.Failed)?.error?.message ?: ""
    ErrorDialog(
      error = initErrorMessage,
      onDismiss = {
        showErrorDialog = false
        onNavigateUp()
      },
    )
  }
}

/** Model Shelf: the app's screens as a link sees them (see [LinkScreens]). */
private class NavLinkScreens(
  private val navController: NavHostController,
  private val modelManagerViewModel: ModelManagerViewModel,
  private val context: Context,
  private val onSentence: (String) -> Unit,
) : LinkScreens {
  override val tasks: List<Task>
    get() = modelManagerViewModel.uiState.value.tasks

  override fun modelByName(name: String): Model? = modelManagerViewModel.getModelByName(name = name)

  override fun downloadSucceeded(model: Model): Boolean =
    modelManagerViewModel.uiState.value.modelDownloadStatus[model.name]?.status ==
      ModelDownloadStatusType.SUCCEEDED

  override fun encodeQuery(query: String): String = Uri.encode(query)

  override fun topRoute(): String? = navController.currentBackStackEntry?.destination?.route

  override fun openModelScreen(): OpenModelScreen? {
    val top = navController.currentBackStackEntry
    val entry =
      navController.currentBackStack.value.lastOrNull {
        it.destination.route?.startsWith("$ROUTE_MODEL/") == true
      } ?: return null
    return OpenModelScreen(
      route = entry.destination.route ?: "",
      taskId = entry.arguments?.getString("taskId") ?: "",
      modelName = entry.arguments?.getString("modelName") ?: "",
      onTop = entry === top,
    )
  }

  override fun gate(model: Model): String? =
    LlmChatModelHelper.sentenceInsteadOfLinkedScreen(context, model)

  override fun endGate() {}

  override fun showSentence(sentence: String) = onSentence(sentence)

  override fun selectModel(model: Model) = modelManagerViewModel.selectModel(model)

  override suspend fun leave(screen: OpenModelScreen) {
    // The screen loads nothing more (it would see the next screen's model selected).
    navController.currentBackStack.value
      .lastOrNull { it.destination.route == screen.route }
      ?.savedStateHandle
      ?.set(MODEL_SCREEN_LEFT_KEY, true)
    modelManagerViewModel.getTaskById(screen.taskId)?.let { task ->
      modelManagerViewModel.leaveTask(context = context, task = task).join()
    }
  }

  override fun navigate(route: String, popUpToRoute: String?) {
    if (popUpToRoute == null) {
      navController.navigate(route)
    } else {
      navController.navigate(route) { popUpTo(popUpToRoute) { inclusive = true } }
    }
  }

  override fun popBackTo(route: String) {
    navController.popBackStack(route = route, inclusive = false)
  }

  override fun deliverQuery(taskId: String, modelName: String, query: String) =
    modelManagerViewModel.deliverLinkQuery(taskId = taskId, modelName = modelName, query = query)

  override fun log(priority: Int, line: String) {
    Log.println(priority, TAG, line)
  }
}
