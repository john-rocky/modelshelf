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

package io.github.johnrocky.modelshelf.caller

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONException
import org.json.JSONObject

/**
 * HTTP/1.1 to Model Shelf's local API that checks the server before anything that matters goes out.
 *
 * Each request opens one TCP connection and first sends GET /modelshelf/v1/verify with this app's
 * package name and 16 random bytes. Model Shelf answers with an HMAC-SHA256 over them, keyed with
 * the secret it gave this app on Connect. Only when that answer is right does the request (the
 * token, the prompt, the audio) go out, on the same connection, so a program that took the port
 * gets the check and nothing else. A token copied by hand comes without a secret and cannot be
 * checked, so this client does not send with one.
 */
class ModelShelfClient(baseUrl: String, private val app: String, private val key: ByteArray) {
  private val host: String
  private val port: Int

  init {
    val uri = URI(baseUrl)
    host = uri.host ?: "127.0.0.1"
    port = if (uri.port > 0) uri.port else 11434
  }

  /** The server did not pass the check: nothing but the check was sent to it. */
  class NotModelShelf : IOException(NOT_MODEL_SHELF)

  /** A response; [body] reads exactly its bytes and throws [EOFException] when it is cut. */
  class Response(
    private val socket: Socket,
    val status: Int,
    private val headers: Map<String, String>,
    val body: InputStream,
  ) : Closeable {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    /** The X-ModelShelf-* headers, without the prefix, in lower case. */
    fun shelfHeaders(): Map<String, String> =
      headers.filterKeys { it.startsWith(SHELF_PREFIX) }.mapKeys { it.key.removePrefix(SHELF_PREFIX) }

    override fun close() {
      runCatching { socket.close() }
    }
  }

  /**
   * Checks the server, then sends [method] [path] with [headers] and the [bodyLength] bytes
   * [writeBody] writes, on the same connection. Throws [NotModelShelf] when the check fails, and
   * [java.net.ConnectException] when nothing listens (the local API is off).
   */
  fun send(
    method: String,
    path: String,
    headers: List<Pair<String, String>>,
    bodyLength: Long = 0,
    readTimeoutMs: Int = 30_000,
    writeBody: (OutputStream) -> Unit = {},
  ): Response {
    val opened = open(method, path, headers, bodyLength, writeBody)
    try {
      opened.socket.soTimeout = readTimeoutMs
      val (status, h) = readHead(opened.input)
      return Response(opened.socket, status, h, bodyOf(opened.input, h))
    } catch (e: Throwable) {
      runCatching { opened.socket.close() }
      throw e
    }
  }

  /** A connection that passed the check and carries a sent request whose answer is not read yet. */
  class Opened(val socket: Socket, val input: InputStream)

  /**
   * [send] without reading the answer (a test harness uses it to play an app that stopped reading).
   * The caller closes [Opened.socket].
   */
  fun open(
    method: String,
    path: String,
    headers: List<Pair<String, String>>,
    bodyLength: Long = 0,
    writeBody: (OutputStream) -> Unit = {},
  ): Opened {
    val socket = Socket()
    try {
      socket.connect(InetSocketAddress(host, port), 5_000)
      socket.soTimeout = VERIFY_TIMEOUT_MS
      val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
      val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
      verify(input, output)
      val head = StringBuilder()
      head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
      head.append("Host: ").append(host).append(':').append(port).append("\r\n")
      for ((k, v) in headers) head.append(k).append(": ").append(v).append("\r\n")
      if (method != "GET") head.append("Content-Length: ").append(bodyLength).append("\r\n")
      head.append("Connection: close\r\n\r\n")
      output.write(head.toString().toByteArray(Charsets.UTF_8))
      writeBody(output)
      output.flush()
      return Opened(socket, input)
    } catch (e: Throwable) {
      runCatching { socket.close() }
      throw e
    }
  }

  private fun verify(input: InputStream, output: OutputStream) {
    val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
    output.write(
      ("GET $VERIFY_PATH?app=${URLEncoder.encode(app, "UTF-8")}&nonce=${b64(nonce)} HTTP/1.1\r\n" +
          "Host: $host:$port\r\nAccept: application/json\r\n\r\n")
        .toByteArray(Charsets.US_ASCII)
    )
    output.flush()
    val answer =
      try {
        val (status, h) = readHead(input)
        val length = h["content-length"]?.toIntOrNull()
        if (status != 200 || length == null || length > 4096) throw NotModelShelf()
        JSONObject(String(readExactly(input, length), Charsets.UTF_8))
      } catch (e: NotModelShelf) {
        throw e
      } catch (e: IOException) {
        throw NotModelShelf()
      } catch (e: JSONException) {
        throw NotModelShelf()
      }
    val got =
      try {
        Base64.decode(answer.optString("proof"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
      } catch (e: IllegalArgumentException) {
        null
      }
    val ok =
      answer.optString("service") == "Model Shelf" &&
        answer.optInt("verify") == 1 &&
        answer.optString("app") == app &&
        got != null &&
        MessageDigest.isEqual(proof(key, app, nonce), got)
    if (!ok) throw NotModelShelf()
  }

  companion object {
    const val VERIFY_PATH = "/modelshelf/v1/verify"
    const val NOT_MODEL_SHELF = "The server on this port did not prove it is Model Shelf, so nothing was sent."
    private const val PURPOSE = "ModelShelf local API verify v1"
    private const val NONCE_BYTES = 16
    private const val VERIFY_TIMEOUT_MS = 15_000
    private const val SHELF_PREFIX = "x-modelshelf-"
    private const val MAX_HEAD_BYTES = 64 * 1024
    private val random = SecureRandom()

    /** HMAC-SHA256(key, purpose ‖ 0 ‖ package name ‖ 0 ‖ nonce): what Model Shelf answers. */
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

    fun b64(bytes: ByteArray): String =
      Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** The status and the headers (names in lower case; repeated ones joined with ","). */
    private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
      var read = 0
      fun line(): String {
        val l = readLine(input) ?: throw EOFException("The connection closed before an answer.")
        read += l.length + 2
        if (read > MAX_HEAD_BYTES) throw IOException("The answer's head is too long.")
        return l
      }
      var statusLine = line()
      // A 100 Continue is not the answer.
      while (statusLine.startsWith("HTTP/1.1 100") || statusLine.isEmpty()) statusLine = line()
      val parts = statusLine.split(' ')
      if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Not an HTTP answer.")
      val status = parts[1].toIntOrNull() ?: throw IOException("Not an HTTP answer.")
      val headers = mutableMapOf<String, String>()
      while (true) {
        val l = line()
        if (l.isEmpty()) break
        val colon = l.indexOf(':')
        if (colon <= 0) continue
        val name = l.substring(0, colon).trim().lowercase(Locale.US)
        val value = l.substring(colon + 1).trim()
        headers[name] = headers[name]?.let { "$it,$value" } ?: value
      }
      return status to headers
    }

    /** A line without its CRLF; null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
      val out = ByteArrayOutputStream()
      while (true) {
        val b = input.read()
        if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
        if (b == '\n'.code) break
        out.write(b)
      }
      val bytes = out.toByteArray()
      val n = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
      return String(bytes, 0, n, Charsets.UTF_8)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
      val b = ByteArray(n)
      var at = 0
      while (at < n) {
        val r = input.read(b, at, n - at)
        if (r < 0) throw EOFException("The answer ended after $at of its $n bytes.")
        at += r
      }
      return b
    }

    private fun bodyOf(input: InputStream, headers: Map<String, String>): InputStream =
      when {
        headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> ChunkedBody(input)
        headers["content-length"] != null ->
          SizedBody(input, headers["content-length"]!!.toLongOrNull() ?: throw IOException("A bad Content-Length."))
        else -> input
      }
  }

  /** A body of a known length; a stream that ends early throws [EOFException]. */
  private class SizedBody(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
      val b = ByteArray(1)
      return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (left <= 0) return -1
      val n = input.read(b, off, minOf(len.toLong(), left).toInt())
      if (n < 0) throw EOFException("The answer ended $left bytes before its Content-Length.")
      left -= n
      return n
    }
  }

  /** A chunked body; one whose last chunk never comes (the answer was cut) throws [EOFException]. */
  private class ChunkedBody(private val input: InputStream) : InputStream() {
    private var left = 0L
    private var done = false

    override fun read(): Int {
      val b = ByteArray(1)
      return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (done) return -1
      if (left == 0L) {
        val size = readLine(input) ?: throw EOFException("The answer was cut before its end.")
        left = size.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("A bad chunk size.")
        if (left == 0L) {
          // Trailers, then the empty line that ends the body.
          while (true) {
            val t = readLine(input) ?: break
            if (t.isEmpty()) break
          }
          done = true
          return -1
        }
      }
      val n = input.read(b, off, minOf(len.toLong(), left).toInt())
      if (n < 0) throw EOFException("The answer was cut before its end.")
      left -= n
      if (left == 0L) readLine(input)
      return n
    }
  }
}
