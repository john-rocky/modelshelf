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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The check a calling app runs before it sends its token or its user's text and audio:
 * GET /modelshelf/v1/verify?app=<package name>&nonce=<16 random bytes, base64url>, with no token,
 * answered with HMAC-SHA256(key, [PURPOSE] ‖ 0x00 ‖ package name ‖ 0x00 ‖ nonce). The key is the
 * secret Model Shelf gave that app on Allow ([ApiTokenStore]); a package it does not know (or one
 * that was removed) gets a proof made with a key no app has, in the same form, so the answer tells
 * nothing about which apps are connected. The caller then sends its request on the same TCP
 * connection, so the program that passed the check is the one that gets the request. A token
 * copied by hand has no key and cannot be checked.
 */
object ApiVerify {
  const val PATH = "/modelshelf/v1/verify"
  const val PURPOSE = "ModelShelf local API verify v1"
  const val NONCE_BYTES = 16
  const val KEY_BYTES = 32

  /** An Android package name: letters, digits, "_" and ".", at most 255. */
  private val PACKAGE = Regex("[A-Za-z0-9_.]{1,255}")

  fun proof(key: ByteArray, app: String, nonce: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.update(PURPOSE.toByteArray(Charsets.UTF_8))
    mac.update(0)
    mac.update(app.toByteArray(Charsets.UTF_8))
    mac.update(0)
    mac.update(nonce)
    return mac.doFinal()
  }

  fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  fun decode(text: String): ByteArray? =
    try {
      Base64.getUrlDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
      null
    }

  /** The nonce of a check: base64url of exactly [NONCE_BYTES] bytes, else null. */
  fun nonce(text: String?): ByteArray? =
    text?.takeIf { it.length in 22..24 }?.let(::decode)?.takeIf { it.size == NONCE_BYTES }

  fun validApp(app: String?): Boolean = app != null && PACKAGE.matches(app)

  /** The answer to a check. */
  fun answer(app: String, proof: ByteArray): String =
    JsonObject()
      .apply {
        addProperty("service", "Model Shelf")
        addProperty("verify", 1)
        addProperty("app", app)
        addProperty("proof", encode(proof))
      }
      .toString()

  // ---- What a calling app does (the caller sample has the same steps; the tests use these) -----

  /** 16 random bytes for one check. */
  fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

  /** Whether [answer] proves the server holds [key]: the caller sends its request only then. */
  fun check(answer: String, key: ByteArray, app: String, nonce: ByteArray): Boolean =
    try {
      val o = JsonParser.parseString(answer).asJsonObject
      val got = decode(o.get("proof").asString)
      got != null &&
        o.get("service").asString == "Model Shelf" &&
        o.get("verify").asInt == 1 &&
        o.get("app").asString == app &&
        MessageDigest.isEqual(proof(key, app, nonce), got)
    } catch (e: Exception) {
      false
    }
}
