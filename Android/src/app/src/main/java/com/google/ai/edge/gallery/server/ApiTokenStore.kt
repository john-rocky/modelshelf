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
import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The apps the user let use the local API: one token per app, given out by the connect screen,
 * and with it the app's secret for the check it runs before it sends anything ([ApiVerify]). Only a
 * hash of each token is kept; the secret is kept as it is (the check needs it), in this app's
 * private storage. A new token for an app replaces its old token and secret; removing the app
 * deletes both.
 */
class ApiTokenStore(private val prefs: SharedPreferences, private val random: SecureRandom = SecureRandom()) {
  constructor(context: Context) : this(context.getSharedPreferences("local_api_tokens", Context.MODE_PRIVATE))

  /** One app's permission. [packageName] is "manual" for the token copied by hand. */
  data class Grant(
    val packageName: String,
    val label: String,
    val createdMs: Long,
    val tokenHash: String,
  )

  /** What Allow gives an app: its token and its secret for the check (null for a copied token). */
  class Issued(val token: String, val verifyKey: ByteArray?)

  @Synchronized
  fun all(): List<Grant> =
    prefs.all.entries
      .filter { it.key.startsWith(KEY_PREFIX) }
      .mapNotNull { (key, value) -> parse(key.removePrefix(KEY_PREFIX), value as? String) }
      .sortedBy { it.createdMs }

  /**
   * Makes a new token for [packageName], and a new secret unless it is [MANUAL] (a token copied by
   * hand cannot be checked); the app's earlier token and secret stop working.
   */
  @Synchronized
  fun issue(packageName: String, label: String): Issued {
    val token = "msk_" + ApiVerify.encode(ByteArray(32).also { random.nextBytes(it) })
    val key = if (packageName == MANUAL) null else ByteArray(ApiVerify.KEY_BYTES).also { random.nextBytes(it) }
    val record =
      JsonObject().apply {
        addProperty("label", label)
        addProperty("created", System.currentTimeMillis())
        addProperty("hash", hash(token))
        if (key != null) addProperty("verify_key", ApiVerify.encode(key))
      }
    prefs.edit().putString(KEY_PREFIX + packageName, record.toString()).apply()
    return Issued(token, key)
  }

  /** Removes the app's token and its secret. */
  @Synchronized
  fun revoke(packageName: String) {
    prefs.edit().remove(KEY_PREFIX + packageName).apply()
  }

  /** The app a token belongs to, or null. */
  fun find(token: String): Grant? {
    val h = hash(token).toByteArray()
    return all().firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), h) }
  }

  /** The secret of a connected app ([packageName]), or null when the app is not connected. */
  @Synchronized
  fun verifyKey(packageName: String): ByteArray? {
    if (packageName == MANUAL) return null
    val value = prefs.getString(KEY_PREFIX + packageName, null) ?: return null
    return try {
      JsonParser.parseString(value).asJsonObject.get("verify_key")?.asString?.let(ApiVerify::decode)
        ?.takeIf { it.size == ApiVerify.KEY_BYTES }
    } catch (e: Exception) {
      null
    }
  }

  /** The key the check uses for a package that has none: made once, given to no app. */
  @Synchronized
  fun unknownKey(): ByteArray {
    prefs.getString(UNKNOWN_KEY, null)?.let(ApiVerify::decode)?.takeIf { it.size == ApiVerify.KEY_BYTES }?.let {
      return it
    }
    val key = ByteArray(ApiVerify.KEY_BYTES).also { random.nextBytes(it) }
    prefs.edit().putString(UNKNOWN_KEY, ApiVerify.encode(key)).apply()
    return key
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
    private const val UNKNOWN_KEY = "verify.unknown_key"
    /** The grant of the token the user copies for curl or a computer. */
    const val MANUAL = "manual"
  }
}
