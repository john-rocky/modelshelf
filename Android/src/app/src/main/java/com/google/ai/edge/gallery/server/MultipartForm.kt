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

/**
 * One field of a multipart/form-data body: [size] bytes of [data] from [offset]. The bytes stay in
 * the body's array (a 25 MB audio file is not copied).
 */
class FormPart(
  val name: String,
  /** The file name the client sent, for a file field; null for a text field. */
  val filename: String?,
  val contentType: String?,
  val data: ByteArray,
  val offset: Int,
  val size: Int,
) {
  constructor(
    name: String,
    filename: String?,
    contentType: String?,
    bytes: ByteArray,
  ) : this(name, filename, contentType, bytes, 0, bytes.size)

  fun text(): String = String(data, offset, size, Charsets.UTF_8)

  /** A copy of the bytes (tests). */
  fun copyBytes(): ByteArray = data.copyOfRange(offset, offset + size)
}

/** The most fields one form may have (OpenAI's audio forms have about ten). */
const val MAX_FORM_PARTS = 64

/** The most bytes of header lines one part may have. */
const val MAX_PART_HEADER_BYTES = 16 * 1024

/**
 * A reader of multipart/form-data bodies (RFC 7578), as OpenAI's audio endpoints take them:
 * `file` plus text fields. Lines end with CRLF; a body whose lines end with LF alone is read too.
 */
object MultipartForm {
  /** The boundary of a multipart/form-data Content-Type; throws [ApiError] 400 otherwise. */
  fun boundaryOf(contentType: String?): String {
    val ct = contentType ?: throw notMultipart()
    if (!ct.trim().lowercase().startsWith("multipart/form-data")) throw notMultipart()
    for (param in ct.split(';').drop(1)) {
      val eq = param.indexOf('=')
      if (eq < 0) continue
      if (param.substring(0, eq).trim().equals("boundary", ignoreCase = true)) {
        val value = param.substring(eq + 1).trim().removeSurrounding("\"")
        if (value.isNotEmpty() && value.length <= 200) return value
      }
    }
    throw ApiError(400, "The multipart/form-data Content-Type has no boundary.")
  }

  /**
   * The fields of the first [size] bytes of [body], in order. Throws [ApiError] 400 when the body is
   * not well formed or has more than [MAX_FORM_PARTS] fields. Each byte is looked at a bounded
   * number of times: a part's header lines are looked for only in its first
   * [MAX_PART_HEADER_BYTES] bytes before the next boundary.
   */
  fun parse(contentType: String?, body: ByteArray, size: Int = body.size): List<FormPart> {
    val boundary = boundaryOf(contentType)
    val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
    var pos = indexOf(body, delimiter, 0, size)
    if (pos < 0) throw malformed("no part starts with the boundary")
    val parts = mutableListOf<FormPart>()
    while (true) {
      pos += delimiter.size
      // "--" after a delimiter closes the body.
      if (pos + 2 <= size && body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) break
      if (parts.size >= MAX_FORM_PARTS) {
        throw ApiError(400, "The form has more than $MAX_FORM_PARTS fields.")
      }
      pos = skipLineEnd(body, pos, size) ?: throw malformed("a boundary line does not end")
      val next = indexOf(body, delimiter, pos, size)
      if (next < 0) throw malformed("a part does not end with the boundary")
      val headerEnd =
        findHeaderEnd(body, pos, minOf(next, pos + MAX_PART_HEADER_BYTES))
          ?: throw malformed("a part's headers do not end")
      val headers = String(body, pos, headerEnd.first - pos, Charsets.UTF_8)
      val contentStart = headerEnd.second
      var contentEnd = next
      // The line end before the delimiter belongs to the delimiter.
      if (contentEnd - 2 >= contentStart && body[contentEnd - 2] == CR && body[contentEnd - 1] == LF) contentEnd -= 2
      else if (contentEnd - 1 >= contentStart && body[contentEnd - 1] == LF) contentEnd -= 1
      if (contentEnd < contentStart) contentEnd = contentStart
      parts.add(toPart(headers, body, contentStart, contentEnd - contentStart))
      pos = next
    }
    return parts
  }

  private fun toPart(headers: String, body: ByteArray, offset: Int, length: Int): FormPart {
    var name: String? = null
    var filename: String? = null
    var contentType: String? = null
    for (line in headers.split('\n')) {
      val l = line.trimEnd('\r')
      val colon = l.indexOf(':')
      if (colon < 0) continue
      val key = l.substring(0, colon).trim().lowercase()
      val value = l.substring(colon + 1).trim()
      when (key) {
        "content-disposition" -> {
          val params = dispositionParams(value)
          name = params["name"]
          filename = params["filename*"]?.let { decodeExtended(it) } ?: params["filename"]
        }
        "content-type" -> contentType = value
      }
    }
    return FormPart(name ?: throw malformed("a part has no name"), filename, contentType, body, offset, length)
  }

  /** form-data; name="file"; filename="a b.wav" -> {name: file, filename: a b.wav}. */
  private fun dispositionParams(value: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var i = value.indexOf(';')
    while (i in value.indices) {
      i++
      while (i < value.length && value[i] == ' ') i++
      val eq = value.indexOf('=', i)
      if (eq < 0) break
      val key = value.substring(i, eq).trim().lowercase()
      var j = eq + 1
      val v: String
      if (j < value.length && value[j] == '"') {
        val sb = StringBuilder()
        j++
        while (j < value.length && value[j] != '"') {
          if (value[j] == '\\' && j + 1 < value.length) j++
          sb.append(value[j])
          j++
        }
        v = sb.toString()
        j++
      } else {
        val end = value.indexOf(';', j).let { if (it < 0) value.length else it }
        v = value.substring(j, end).trim()
        j = end
      }
      out[key] = v
      i = value.indexOf(';', j)
    }
    return out
  }

  /** RFC 5987: UTF-8''a%20b.wav -> a b.wav. */
  private fun decodeExtended(v: String): String {
    val quote = v.indexOf("''")
    val encoded = if (quote >= 0) v.substring(quote + 2) else v
    return try {
      java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
      encoded
    }
  }

  private fun skipLineEnd(b: ByteArray, at: Int, end: Int): Int? =
    when {
      at + 1 < end && b[at] == CR && b[at + 1] == LF -> at + 2
      at < end && b[at] == LF -> at + 1
      else -> null
    }

  /** (end of the headers, start of the content), looked for in [from, until). */
  private fun findHeaderEnd(b: ByteArray, from: Int, until: Int): Pair<Int, Int>? {
    val crlf = indexOf(b, byteArrayOf(CR, LF, CR, LF), from, until)
    val lf = indexOf(b, byteArrayOf(LF, LF), from, until)
    return when {
      crlf >= 0 && (lf < 0 || crlf <= lf) -> crlf to crlf + 4
      lf >= 0 -> lf to lf + 2
      else -> null
    }
  }

  /** The first [needle] in [haystack] that lies wholly in [from, until), or -1. */
  fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, until: Int = haystack.size): Int {
    if (needle.isEmpty()) return from
    val first = needle[0]
    val last = minOf(until, haystack.size) - needle.size
    var i = maxOf(0, from)
    outer@ while (i <= last) {
      if (haystack[i] != first) {
        i++
        continue
      }
      for (k in 1 until needle.size) {
        if (haystack[i + k] != needle[k]) {
          i++
          continue@outer
        }
      }
      return i
    }
    return -1
  }

  private fun notMultipart() =
    ApiError(
      400,
      "Send the audio as multipart/form-data with a \"file\" field (a WAV file) and a \"model\" field.",
    )

  private fun malformed(what: String) = ApiError(400, "The multipart/form-data body is not well formed: $what.")

  private const val CR = '\r'.code.toByte()
  private const val LF = '\n'.code.toByte()
}
