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

import android.content.Context
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The apps the user let use the local API: one token per app, given out by the
 * connect screen. Only a hash of each token is kept. A new token for an app replaces its old one.
 */
class ApiTokenStore(context: Context) {
  private val prefs = context.getSharedPreferences("local_api_tokens", Context.MODE_PRIVATE)
  private val random = SecureRandom()

  /** One app's permission. [packageName] is "manual" for the token copied by hand. */
  data class Grant(
    val packageName: String,
    val label: String,
    val createdMs: Long,
    val tokenHash: String,
  )

  @Synchronized
  fun all(): List<Grant> =
    prefs.all.entries
      .filter { it.key.startsWith(KEY_PREFIX) }
      .mapNotNull { (key, value) -> parse(key.removePrefix(KEY_PREFIX), value as? String) }
      .sortedBy { it.createdMs }

  /** Makes a new token for [packageName] and returns it; the app's earlier token stops working. */
  @Synchronized
  fun issue(packageName: String, label: String): String {
    val bytes = ByteArray(32).also { random.nextBytes(it) }
    val token =
      "msk_" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    val record =
      JsonObject().apply {
        addProperty("label", label)
        addProperty("created", System.currentTimeMillis())
        addProperty("hash", hash(token))
      }
    prefs.edit().putString(KEY_PREFIX + packageName, record.toString()).apply()
    return token
  }

  @Synchronized
  fun revoke(packageName: String) {
    prefs.edit().remove(KEY_PREFIX + packageName).apply()
  }

  /** The app a token belongs to, or null. */
  fun find(token: String): Grant? {
    val h = hash(token).toByteArray()
    return all().firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), h) }
  }

  private fun parse(packageName: String, value: String?): Grant? {
    if (value == null) return null
    return try {
      val o = JsonParser.parseString(value).asJsonObject
      Grant(
        packageName = packageName,
        label = o.get("label")?.asString ?: packageName,
        createdMs = o.get("created")?.asLong ?: 0L,
        tokenHash = o.get("hash")?.asString ?: return null,
      )
    } catch (e: Exception) {
      null
    }
  }

  private fun hash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
      "%02x".format(it)
    }

  companion object {
    private const val KEY_PREFIX = "grant."
    /** The grant of the token the user copies for curl or a computer. */
    const val MANUAL = "manual"
  }
}
