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

import android.content.SharedPreferences
import com.google.gson.JsonParser
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check a calling app runs before it sends anything (item 2, line a). */
class ApiVerifyTest {
  /** SharedPreferences in memory, enough for ApiTokenStore. */
  private class MemoryPrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = map.toMap()

    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

    override fun getInt(key: String?, defValue: Int): Int = defValue

    override fun getLong(key: String?, defValue: Long): Long = defValue

    override fun getFloat(key: String?, defValue: Float): Float = defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor =
      object : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
          puts[key] = value
          return this
        }

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
          removes.add(key)
          return this
        }

        override fun clear(): SharedPreferences.Editor {
          removes.addAll(map.keys)
          return this
        }

        override fun commit(): Boolean {
          this.apply()
          return true
        }

        override fun apply() {
          for (k in removes) map.remove(k)
          map.putAll(puts)
        }
      }
  }

  private val app = "com.example.caller"

  /** What Model Shelf answers a check from [app] with [nonce]. */
  private fun serverAnswer(store: ApiTokenStore, app: String, nonce: ByteArray): String =
    ApiVerify.answer(app, ApiVerify.proof(store.verifyKey(app) ?: store.unknownKey(), app, nonce))

  @Test
  fun theProofIsTheDocumentedHmac() {
    // HMAC-SHA256(key, "ModelShelf local API verify v1" 0x00 package 0x00 nonce), computed apart
    // (Python's hmac) for key 00..1f, package com.example.caller, nonce a0..af.
    val key = ByteArray(32) { it.toByte() }
    val nonce = ByteArray(16) { (0xA0 + it).toByte() }
    assertEquals("oKGio6SlpqeoqaqrrK2urw", ApiVerify.encode(nonce))
    assertEquals("DQcUWIbAMYNPRPGFKFcuL0dgl3fJVXn0hjOPMcv8Dp0", ApiVerify.encode(ApiVerify.proof(key, app, nonce)))
  }

  @Test
  fun aConnectedAppAcceptsTheAnswerOfModelShelf() {
    val store = ApiTokenStore(MemoryPrefs())
    val issued = store.issue(app, "Caller")
    val key = issued.verifyKey!!
    assertEquals(32, key.size)
    val nonce = ApiVerify.newNonce()
    assertTrue(ApiVerify.check(serverAnswer(store, app, nonce), key, app, nonce))
    // The token still finds its app.
    assertEquals(app, store.find(issued.token)?.packageName)
  }

  @Test
  fun aServerWithoutTheKeyFailsTheCheck() {
    val store = ApiTokenStore(MemoryPrefs())
    val key = store.issue(app, "Caller").verifyKey!!
    val nonce = ApiVerify.newNonce()
    // Another program on the port: it makes up a key, or replays an answer to another nonce.
    val impostor = ApiVerify.answer(app, ApiVerify.proof(ByteArray(32) { 7 }, app, nonce))
    assertFalse(ApiVerify.check(impostor, key, app, nonce))
    assertFalse(ApiVerify.check(serverAnswer(store, app, ApiVerify.newNonce()), key, app, nonce))
    // An answer for another package, or one that is not the answer's form.
    assertFalse(ApiVerify.check(serverAnswer(store, "com.other", nonce), key, app, nonce))
    assertFalse(ApiVerify.check("{\"service\":\"Model Shelf\",\"verify\":\"x\"}", key, app, nonce))
    assertFalse(ApiVerify.check("not json", key, app, nonce))
  }

  @Test
  fun anUnknownAppGetsAnAnswerOfTheSameFormThatNoKeyPasses() {
    val store = ApiTokenStore(MemoryPrefs())
    store.issue(app, "Caller")
    val nonce = ApiVerify.newNonce()
    val known = JsonParser.parseString(serverAnswer(store, app, nonce)).asJsonObject
    val unknown = JsonParser.parseString(serverAnswer(store, "com.not.connected", nonce)).asJsonObject
    assertEquals(known.keySet(), unknown.keySet())
    assertEquals(known.get("proof").asString.length, unknown.get("proof").asString.length)
    assertEquals("Model Shelf", unknown.get("service").asString)
    // The unknown key stays the same (the same form every time) and is no app's key.
    assertArrayEquals(store.unknownKey(), store.unknownKey())
    assertFalse(store.unknownKey().contentEquals(store.verifyKey(app)))
  }

  @Test
  fun eachNonceIs16NewBytesAndOtherSizesAreRefused() {
    val random = SecureRandom()
    val seen = HashSet<String>()
    repeat(1_000) {
      val n = ApiVerify.newNonce(random)
      assertEquals(16, n.size)
      assertTrue(seen.add(ApiVerify.encode(n)))
    }
    assertNotNull(ApiVerify.nonce(ApiVerify.encode(ByteArray(16))))
    assertNull(ApiVerify.nonce(ApiVerify.encode(ByteArray(15))))
    assertNull(ApiVerify.nonce(ApiVerify.encode(ByteArray(17))))
    assertNull(ApiVerify.nonce("not base64url!!!!!!!!!"))
    assertNull(ApiVerify.nonce(null))
    assertTrue(ApiVerify.validApp(app))
    assertFalse(ApiVerify.validApp("com.example/../x"))
    assertFalse(ApiVerify.validApp(""))
  }

  @Test
  fun afterTheAppIsRemovedTheCheckFails() {
    val store = ApiTokenStore(MemoryPrefs())
    val key = store.issue(app, "Caller").verifyKey!!
    store.revoke(app)
    assertNull(store.verifyKey(app))
    val nonce = ApiVerify.newNonce()
    assertFalse(ApiVerify.check(serverAnswer(store, app, nonce), key, app, nonce))
    // Connecting again gives a new key; the old one no longer passes.
    val again = store.issue(app, "Caller").verifyKey!!
    assertFalse(again.contentEquals(key))
    assertFalse(ApiVerify.check(serverAnswer(store, app, nonce), key, app, nonce))
    assertTrue(ApiVerify.check(serverAnswer(store, app, nonce), again, app, nonce))
  }

  @Test
  fun aCopiedTokenHasNoKey() {
    val store = ApiTokenStore(MemoryPrefs())
    val issued = store.issue(ApiTokenStore.MANUAL, "Copied token")
    assertNull(issued.verifyKey)
    assertNull(store.verifyKey(ApiTokenStore.MANUAL))
    assertEquals(ApiTokenStore.MANUAL, store.find(issued.token)?.packageName)
  }
}
