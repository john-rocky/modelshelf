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

package com.google.ai.edge.gallery.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler
import java.net.URI

private const val TAG = "AGExternalPages"
private const val PLAY_STORE_PACKAGE = "com.android.vending"

/** Model Shelf: shown when no app on the phone opens web pages. */
internal const val NO_APP_OPENS_PAGES = "No app on this phone opens web pages."

/** Model Shelf: how [openExternalPage] opens a link (D-614 round 10d). */
internal enum class PageRoute {
  /** Not an http(s) page with a host: nothing opens. */
  NOT_A_PAGE,

  /** A Play Store page: the Play Store app, else a Custom Tab. */
  STORE,

  /** Any other http(s) page: a Custom Tab. */
  CUSTOM_TAB,
}

/** Model Shelf: the route of [url]. It uses no Android class, so JVM tests reach it. */
internal fun pageRoute(url: String): PageRoute {
  val uri =
    try {
      URI(url.trim())
    } catch (e: Exception) {
      return PageRoute.NOT_A_PAGE
    }
  val scheme = uri.scheme?.lowercase() ?: return PageRoute.NOT_A_PAGE
  if (scheme != "http" && scheme != "https") return PageRoute.NOT_A_PAGE
  val host = uri.host?.lowercase()
  if (host.isNullOrEmpty()) return PageRoute.NOT_A_PAGE
  if (host == "play.google.com" && (uri.path ?: "").startsWith("/store/")) return PageRoute.STORE
  return PageRoute.CUSTOM_TAB
}

/**
 * Model Shelf: the one way the app opens a web page (D-614 round 10d).
 *
 * Only http(s) pages open, and only from an activity: the page opens in a Custom Tab, which the
 * phone's browser draws inside Model Shelf's task, and Back returns to Model Shelf. No new task is
 * asked for; without an activity nothing opens. A Play Store page opens in the Play Store app when
 * there is one. With no browser that shows Custom Tabs, the browser opens the page the usual way
 * (the library's behaviour); with no app that opens web pages, a short message says so. No link is
 * written to the log (a link in an answer can be the user's own words).
 *
 * @return whether a page or the store was started
 */
fun openExternalPage(context: Context, url: String): Boolean {
  val route = pageRoute(url)
  if (route == PageRoute.NOT_A_PAGE) {
    Log.w(TAG, "A link that is not a web page opens nothing")
    return false
  }
  val activity = context.activityToOpenFrom()
  if (activity == null) {
    Log.w(TAG, "No activity to open the page from: nothing opens")
    return false
  }
  val uri = Uri.parse(url.trim())
  if (route == PageRoute.STORE) {
    try {
      val intent = Intent(Intent.ACTION_VIEW, uri)
      intent.setPackage(PLAY_STORE_PACKAGE)
      activity.startActivity(intent)
      return true
    } catch (e: ActivityNotFoundException) {
      Log.i(TAG, "No Play Store app: the page opens in a Custom Tab")
    }
  }
  return try {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, uri)
    true
  } catch (e: ActivityNotFoundException) {
    Log.w(TAG, "No app opens web pages")
    Toast.makeText(activity, NO_APP_OPENS_PAGES, Toast.LENGTH_SHORT).show()
    false
  }
}

/** The activity a context belongs to, if any. */
private tailrec fun Context.activityToOpenFrom(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.activityToOpenFrom()
    else -> null
  }

/**
 * Model Shelf: the app's [UriHandler], provided at the root of the composition: every link that a
 * composable opens through [androidx.compose.ui.platform.LocalUriHandler] (link text, markdown,
 * answers, skill pages, the speech engine's store page) goes through [openExternalPage].
 */
class ExternalPageUriHandler(private val context: Context) : UriHandler {
  override fun openUri(uri: String) {
    openExternalPage(context, uri)
  }
}
