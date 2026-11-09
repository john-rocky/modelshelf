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
import kotlinx.coroutines.launch

private const val TAG = "MSApiService"
private const val CHANNEL_ID = "local_api"
private const val NOTIFICATION_ID = 11434

/**
 * Keeps the local API's process alive while it is on (DESIGN.md §g-2): a foreground service of the
 * type specialUse with a notification that names the address and the loaded model, and a Stop
 * action.
 */
class ApiServerService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        Log.i(TAG, "Stop asked")
        scope.launch {
          LocalApi.stopServer()
          ServiceCompat.stopForeground(this@ApiServerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
          stopSelf()
        }
      }
      else -> {
        goForeground()
        scope.launch {
          val error = LocalApi.startServer(this@ApiServerService)
          if (error != null) {
            ServiceCompat.stopForeground(this@ApiServerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
          } else {
            updateNotification()
          }
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    instance = null
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
        state.loadedModel?.let { "$it loaded (${state.loadedBackend?.uppercase() ?: "?"})" }
          ?: "No model loaded"
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
