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

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val TAG = "MSApiService"
private const val CHANNEL_ID = "local_api"
private const val NOTIFICATION_ID = 11434

/**
 * Keeps the local API's process alive while it is on: a foreground service of the type specialUse
 * with a notification that names the address and the loaded model, and a Stop action.
 *
 * Start and stop commands are handled one at a time, in the order they came; a command ends the
 * service only if no command came after it (stopSelfResult), so a Start right after a Stop keeps
 * the service and its notification.
 */
class ApiServerService : Service() {
  private class Command(val start: Boolean, val startId: Int, val startNumber: Long)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val commands = Channel<Command>(Channel.UNLIMITED)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    scope.launch { for (command in commands) handle(command) }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val start = intent?.action != ACTION_STOP
    if (start) {
      // Android wants startForeground() within seconds of startForegroundService().
      goForeground()
    } else {
      Log.i(TAG, "Stop asked")
    }
    commands.trySend(Command(start, startId, intent?.getLongExtra(EXTRA_START_NUMBER, 0L) ?: 0L))
    return START_NOT_STICKY
  }

  private suspend fun handle(command: Command) {
    if (command.start) {
      val error = LocalApi.startServer(this, command.startNumber)
      if (error != null) end(command.startId) else updateNotification()
    } else {
      LocalApi.stopServer()
      end(command.startId)
    }
  }

  /** Ends the service unless a command came after [startId]. */
  private fun end(startId: Int) {
    if (stopSelfResult(startId)) {
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
  }

  /**
   * The system asks for memory: the API unloads its idle models (voice first). On Android 14 and
   * later only UI_HIDDEN and BACKGROUND arrive; UI_HIDDEN alone says nothing about memory.
   */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    @Suppress("DEPRECATION")
    val memoryLow =
      level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
    Log.i(TAG, "onTrimMemory $level" + if (memoryLow) ": unloading idle models" else "")
    if (memoryLow) LocalApi.runningServer()?.onMemorySignal("onTrimMemory $level")
  }

  override fun onLowMemory() {
    super.onLowMemory()
    Log.i(TAG, "onLowMemory: unloading idle models")
    LocalApi.runningServer()?.onMemorySignal("onLowMemory")
  }

  override fun onDestroy() {
    instance = null
    commands.close()
    scope.launch { LocalApi.stopServer() }.invokeOnCompletion { scope.cancel() }
    super.onDestroy()
  }

  private fun goForeground() {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Local API", NotificationManager.IMPORTANCE_LOW).apply {
          description = "Shown while other apps can use your models through Model Shelf."
        }
      )
    }
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      } else {
        0
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this), type)
  }

  companion object {
    const val ACTION_START = "io.github.johnrocky.modelshelf.action.START_LOCAL_API"
    const val ACTION_STOP = "io.github.johnrocky.modelshelf.action.STOP_LOCAL_API"
    const val EXTRA_START_NUMBER = "start_number"

    @Volatile private var instance: ApiServerService? = null

    /** Rewrites the notification with the loaded model; nothing when the service is off. */
    fun updateNotification() {
      val service = instance ?: return
      if (
        ContextCompat.checkSelfPermission(service, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
      ) {
        return
      }
      NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, buildNotification(service))
    }

    private fun buildNotification(context: Context): Notification {
      val state = LocalApi.state.value
      val open =
        PendingIntent.getActivity(
          context,
          0,
          Intent(context, LocalApiActivity::class.java),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      val stop =
        PendingIntent.getService(
          context,
          1,
          Intent(context, ApiServerService::class.java).setAction(ACTION_STOP),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
      val loaded =
        if (state.stopping) {
          "Stopping: the answer that runs ends, then the model unloads"
        } else {
          state.loadedLine() ?: "No model loaded"
        }
      return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Model Shelf API · ${LocalApi.HOST}:${LocalApi.PORT}")
        .setContentText(loaded)
        .setStyle(NotificationCompat.BigTextStyle().bigText(loaded))
        .setContentIntent(open)
        .addAction(0, "Stop", stop)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
    }
  }
}
