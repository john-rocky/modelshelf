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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * An app with no model of its own. It gets answers from the models on this phone through Model
 * Shelf's local API: Ollama's form (POST /api/chat) and OpenAI's form (POST /v1/chat/completions),
 * both streamed, and the text of a WAV file (POST /v1/audio/transcriptions).
 *
 * Started with the extra [EXTRA_HARNESS_DIR], it runs a transcription test through the same request
 * code as the Transcribe button (see [startHarness] and the README).
 */
class MainActivity : Activity() {

  /** The two ways to ask. */
  private enum class Api(val label: String, val path: String) {
    OLLAMA("ollama", "/api/chat"),
    OPENAI("openai", "/v1/chat/completions"),
  }

  private class Pane(val button: Button, val status: TextView, val answer: TextView)

  /** What one request did. Filled on the request's thread, shown on the UI thread at the end. */
  private class Outcome {
    var status = -1
    var firstTextMs = -1L
    var totalMs = 0L
    var finished = false
    var refused = false
    var error: String? = null
    var retryAfter: String? = null
    var lastLine = ""
    val text = StringBuilder()
  }

  /** What one transcription request did. */
  private class Transcript {
    var status = -1
    var text = ""
    var totalMs = 0L
    var refused = false
    var error: String? = null
    /** Model Shelf's X-ModelShelf-* headers, by the rest of their name in lower case. */
    val shelf = mutableMapOf<String, String>()
  }

  /** The audio to send: its file name and bytes. */
  private class Audio(val name: String, val bytes: ByteArray)

  private lateinit var prefs: SharedPreferences
  private lateinit var connection: TextView
  private lateinit var modelField: EditText
  private lateinit var modelsLine: TextView
  private lateinit var promptField: EditText
  private lateinit var panes: Map<Api, Pane>
  private lateinit var speechModelField: EditText
  private lateinit var audioLine: TextView
  private lateinit var transcribeButton: Button
  private lateinit var transcribeStatus: TextView
  private lateinit var transcriptView: TextView
  /** The audio the Transcribe button sends (UI thread only). */
  private var audio: Audio? = null
  /** True while the test harness runs (UI thread only). */
  private var harnessRunning = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    // Keep the content clear of the status bar, the navigation bar and the keyboard.
    findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
      val bars =
        insets.getInsets(
          WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
        )
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      WindowInsets.CONSUMED
    }
    prefs = getSharedPreferences("model_shelf", MODE_PRIVATE)
    connection = findViewById(R.id.connection)
    modelField = findViewById(R.id.model)
    modelsLine = findViewById(R.id.models)
    promptField = findViewById(R.id.prompt)
    panes =
      mapOf(
        Api.OLLAMA to
          Pane(findViewById(R.id.ask_ollama), findViewById(R.id.ollama_status), findViewById(R.id.ollama_answer)),
        Api.OPENAI to
          Pane(findViewById(R.id.ask_openai), findViewById(R.id.openai_status), findViewById(R.id.openai_answer)),
      )
    speechModelField = findViewById(R.id.speech_model)
    audioLine = findViewById(R.id.audio_line)
    transcribeButton = findViewById(R.id.transcribe)
    transcribeStatus = findViewById(R.id.transcribe_status)
    transcriptView = findViewById(R.id.transcript)
    findViewById<Button>(R.id.connect).setOnClickListener { connect() }
    for ((api, pane) in panes) pane.button.setOnClickListener { ask(api) }
    findViewById<Button>(R.id.pick_wav).setOnClickListener { pickAudio() }
    findViewById<Button>(R.id.use_example).setOnClickListener { useExample() }
    transcribeButton.setOnClickListener { transcribeAudio() }
    modelField.setText(prefs.getString(KEY_MODEL, ""))
    speechModelField.setText(prefs.getString(KEY_SPEECH_MODEL, ""))
    showConnection()
    loadModels()
    if (savedInstanceState == null) startHarness(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    startHarness(intent)
  }

  private fun baseUrl(): String? = prefs.getString(KEY_BASE_URL, null)

  private fun token(): String? = prefs.getString(KEY_TOKEN, null)

  private fun showConnection(text: String? = null) {
    connection.text =
      text ?: if (token() != null) "Connected to Model Shelf · ${baseUrl()}" else "Not connected."
  }

  // ---- Connecting -------------------------------------------------------------------------------

  /** Opens Model Shelf's connect screen. On Allow, it returns the address and this app's token. */
  private fun connect() {
    val intent = Intent(ACTION_CONNECT).setPackage(MODEL_SHELF)
    try {
      Log.i(TAG, "connect: opening Model Shelf's connect screen")
      startActivityForResult(intent, REQUEST_CONNECT)
    } catch (e: ActivityNotFoundException) {
      Log.i(TAG, "connect: Model Shelf is not on this phone")
      showConnection("Model Shelf is not on this phone. Install Model Shelf, then tap Connect.")
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    when (requestCode) {
      REQUEST_CONNECT -> connected(resultCode, data)
      REQUEST_PICK_AUDIO -> if (resultCode == RESULT_OK) data?.data?.let { readPicked(it) }
    }
  }

  private fun connected(resultCode: Int, data: Intent?) {
    val baseUrl = data?.getStringExtra(EXTRA_BASE_URL)
    val token = data?.getStringExtra(EXTRA_TOKEN)
    if (resultCode == RESULT_OK && baseUrl != null && token != null) {
      prefs.edit().putString(KEY_BASE_URL, baseUrl).putString(KEY_TOKEN, token).apply()
      Log.i(TAG, "connect: connected, base_url $baseUrl")
      showConnection()
      loadModels()
      return
    }
    val error = data?.getStringExtra(EXTRA_ERROR)
    Log.i(TAG, "connect: not connected (result $resultCode, error $error)")
    val why =
      when {
        error == "denied" -> "Deny was chosen in Model Shelf."
        error != null -> "Model Shelf said: $error"
        else -> "Model Shelf closed without an answer."
      }
    showConnection(
      if (token() != null) "Connected to Model Shelf · ${baseUrl()} (the new connect request: $why)"
      else "Not connected. $why"
    )
  }

  /**
   * Fills the model fields from Model Shelf's lists when they are empty: GET /api/tags for the chat
   * models and GET /v1/models for the speech models (the entries whose "modelshelf" says
   * "kind": "transcription").
   */
  private fun loadModels() {
    val baseUrl = baseUrl() ?: return
    val token = token() ?: return
    Thread(
        {
          val chat =
            getJson(baseUrl, token, "/api/tags")?.let { tags ->
              val models = tags.optJSONArray("models") ?: JSONArray()
              (0 until models.length())
                .mapNotNull { models.optJSONObject(it)?.optString("name") }
                .filter { it.isNotEmpty() }
            }
          val speech =
            getJson(baseUrl, token, "/v1/models")?.let { list ->
              val data = list.optJSONArray("data") ?: JSONArray()
              (0 until data.length())
                .mapNotNull { data.optJSONObject(it) }
                .filter { it.optJSONObject("modelshelf")?.optString("kind") == "transcription" }
                .map { it.optString("id") }
                .filter { it.isNotEmpty() }
            }
          Log.i(TAG, "models: chat $chat, speech $speech")
          runOnUiThread {
            if (chat != null) {
              if (modelField.text.isBlank() && chat.isNotEmpty()) modelField.setText(chat.first())
              modelsLine.text =
                if (chat.isEmpty()) "Model Shelf has no model to serve yet. Download one in Model Shelf."
                else "In Model Shelf: " + chat.joinToString(", ")
            }
            if (speech != null) {
              if (speechModelField.text.isBlank() && speech.isNotEmpty()) speechModelField.setText(speech.first())
              if (transcribeStatus.text.isEmpty()) {
                transcribeStatus.text =
                  if (speech.isEmpty()) "Model Shelf has no speech model yet."
                  else "Speech models in Model Shelf: " + speech.joinToString(", ")
              }
            }
          }
        },
        "models",
      )
      .start()
  }

  /** The JSON object a GET request answers, or null (logged) when it fails. */
  private fun getJson(baseUrl: String, token: String, path: String): JSONObject? {
    var conn: HttpURLConnection? = null
    return try {
      conn = URL(baseUrl + path).openConnection() as HttpURLConnection
      conn.connectTimeout = 5_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("Authorization", "Bearer $token")
      val status = conn.responseCode
      if (status != HttpURLConnection.HTTP_OK) {
        Log.i(TAG, "GET $path: HTTP $status")
        null
      } else {
        JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
      }
    } catch (e: Exception) {
      Log.i(TAG, "GET $path: ${e.javaClass.simpleName}: ${e.message}")
      null
    } finally {
      conn?.disconnect()
    }
  }

  // ---- Asking -----------------------------------------------------------------------------------

  private fun ask(api: Api) {
    val pane = panes.getValue(api)
    val baseUrl = baseUrl()
    val token = token()
    if (baseUrl == null || token == null) {
      pane.status.text = "Tap Connect to Model Shelf first."
      return
    }
    val model = modelField.text.toString().trim()
    val prompt = promptField.text.toString().trim()
    if (model.isEmpty() || prompt.isEmpty()) {
      pane.status.text = if (model.isEmpty()) "Type a model name." else "Type a prompt."
      return
    }
    prefs.edit().putString(KEY_MODEL, model).apply()
    pane.button.isEnabled = false
    pane.status.text = "Asking Model Shelf…"
    pane.answer.text = ""
    Thread(
        {
          val out = request(api, baseUrl, token, model, prompt) { text -> runOnUiThread { pane.answer.text = text } }
          runOnUiThread { show(api, pane, out) }
        },
        "ask-${api.label}",
      )
      .start()
  }

  private fun body(api: Api, model: String, prompt: String): String {
    val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
    val body = JSONObject().put("model", model).put("messages", messages).put("stream", true)
    // Ollama's form goes without sampler settings, so Model Shelf uses the model's own defaults.
    // OpenAI's form asks for greedy answers.
    if (api == Api.OPENAI) body.put("temperature", 0)
    return body.toString()
  }

  /** Sends one streamed chat request and reads the stream to its end. Runs on a worker thread. */
  private fun request(
    api: Api,
    baseUrl: String,
    token: String,
    model: String,
    prompt: String,
    onText: (String) -> Unit,
  ): Outcome {
    val out = Outcome()
    val start = SystemClock.elapsedRealtime()
    // The log carries lengths, not what the user typed or the model answered.
    Log.i(TAG, "${api.label}: POST $baseUrl${api.path} model=$model, prompt ${prompt.length} chars")
    var conn: HttpURLConnection? = null
    try {
      conn = URL(baseUrl + api.path).openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.connectTimeout = 5_000
      // Model Shelf may first load the model, or wait for another app's request to finish.
      conn.readTimeout = 330_000
      conn.doOutput = true
      conn.setRequestProperty("Authorization", "Bearer $token")
      conn.setRequestProperty("Content-Type", "application/json")
      conn.outputStream.use { it.write(body(api, model, prompt).toByteArray(Charsets.UTF_8)) }
      out.status = conn.responseCode
      val shelfHeaders =
        conn.headerFields.filterKeys { it != null && it.startsWith(SHELF_HEADER, ignoreCase = true) }
      Log.i(TAG, "${api.label}: HTTP ${out.status} $shelfHeaders")
      if (out.status != HttpURLConnection.HTTP_OK) {
        out.retryAfter = conn.getHeaderField("Retry-After")
        out.error = errorText(conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        return out
      }
      conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isBlank()) continue
          val piece: String?
          if (api == Api.OLLAMA) {
            // NDJSON: one object per line; the last one has "done": true.
            out.lastLine = line
            val obj = JSONObject(line)
            if (obj.has("error")) {
              out.error = errorText(line)
              break
            }
            piece = obj.optJSONObject("message")?.let { stringOrNull(it, "content") }
            if (obj.optBoolean("done")) out.finished = true
          } else {
            // Server-sent events: "data: <chunk>" lines, comments start with ":", the end is
            // "data: [DONE]".
            if (!line.startsWith("data:")) continue
            out.lastLine = line
            val data = line.substring("data:".length).trim()
            if (data == "[DONE]") {
              out.finished = true
              break
            }
            val obj = JSONObject(data)
            if (obj.has("error")) {
              out.error = errorText(data)
              break
            }
            piece =
              obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.let {
                stringOrNull(it, "content")
              }
          }
          if (!piece.isNullOrEmpty()) {
            if (out.firstTextMs < 0) out.firstTextMs = SystemClock.elapsedRealtime() - start
            out.text.append(piece)
            onText(out.text.toString())
          }
        }
      }
    } catch (e: ConnectException) {
      out.refused = true
      out.error = API_OFF
    } catch (e: SocketTimeoutException) {
      out.error = "No answer in time (${e.message})."
    } catch (e: IOException) {
      out.error = "The connection failed: ${e.javaClass.simpleName}: ${e.message}"
    } catch (e: JSONException) {
      out.error = "Model Shelf sent a line this app could not read: ${e.message}"
    } catch (e: Exception) {
      out.error = "Unexpected: ${e.javaClass.simpleName}: ${e.message}"
    } finally {
      conn?.disconnect()
      out.totalMs = SystemClock.elapsedRealtime() - start
      Log.i(
        TAG,
        "${api.label}: end: HTTP ${out.status}, first text ${out.firstTextMs} ms, total ${out.totalMs} ms, " +
          "finished ${out.finished}, answer ${out.text.length} chars, error ${out.error}, " +
          "retry-after ${out.retryAfter}",
      )
      Log.i(TAG, "${api.label}: last line: ${endLine(api, out.lastLine)}")
    }
    return out
  }

  /** The stream's last line without the model's text: Ollama's last object minus its message. */
  private fun endLine(api: Api, line: String): String =
    try {
      when {
        line.isEmpty() -> "none"
        api == Api.OLLAMA -> JSONObject(line).apply { remove("message") }.toString().take(400)
        line.substring("data:".length).trim() == "[DONE]" -> "data: [DONE]"
        else -> "a data line of ${line.length} chars"
      }
    } catch (e: JSONException) {
      "a line of ${line.length} chars"
    }

  private fun show(api: Api, pane: Pane, out: Outcome) {
    pane.button.isEnabled = true
    if (out.text.isNotEmpty()) pane.answer.text = out.text
    when {
      out.refused -> {
        pane.status.text = out.error
        showConnection("Model Shelf's local API is off.")
      }
      out.status == HttpURLConnection.HTTP_UNAUTHORIZED -> {
        pane.status.text = TOKEN_REFUSED
        forgetToken()
      }
      out.status != HttpURLConnection.HTTP_OK ->
        pane.status.text = if (out.status > 0) "HTTP ${out.status} · ${out.error}" else out.error
      else -> {
        val end =
          when {
            api == Api.OLLAMA && out.finished -> "done: true"
            api == Api.OLLAMA -> "no done: true line"
            out.finished -> "data: [DONE]"
            else -> "no data: [DONE]"
          }
        val first = if (out.firstTextMs >= 0) "first text ${seconds(out.firstTextMs)} · " else ""
        pane.status.text =
          "HTTP 200 · ${first}total ${seconds(out.totalMs)} · $end" + (out.error?.let { "\n$it" } ?: "")
        if (out.finished) showConnection()
      }
    }
  }

  private fun forgetToken() {
    prefs.edit().remove(KEY_TOKEN).apply()
    showConnection("Not connected: Model Shelf did not accept this app's token.")
  }

  // ---- Speech to text ---------------------------------------------------------------------------

  private fun pickAudio() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
    try {
      startActivityForResult(intent, REQUEST_PICK_AUDIO)
    } catch (e: ActivityNotFoundException) {
      audioLine.text = "This phone has no file picker."
    }
  }

  /** Reads the picked file, at most [MAX_AUDIO_BYTES], off the UI thread. */
  private fun readPicked(uri: Uri) {
    audio = null
    audioLine.text = "Reading the file…"
    Thread(
        {
          val name = displayName(uri) ?: uri.lastPathSegment ?: "audio"
          var picked: Audio? = null
          val line =
            try {
              val stream = contentResolver.openInputStream(uri) ?: throw IOException("the file did not open")
              val bytes = stream.use { readAtMost(it, MAX_AUDIO_BYTES) }
              if (bytes == null) {
                "$name is larger than ${MAX_AUDIO_BYTES / (1024 * 1024)} MB, the most Model Shelf takes per request."
              } else {
                picked = Audio(name, bytes)
                "$name · ${bytes.size / 1024} KB"
              }
            } catch (e: Exception) {
              "$name could not be read: ${e.javaClass.simpleName}: ${e.message}"
            }
          val chosen = picked
          runOnUiThread {
            audio = chosen
            audioLine.text = line
          }
        },
        "read-audio",
      )
      .start()
  }

  private fun displayName(uri: Uri): String? =
    try {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
      }
    } catch (e: Exception) {
      null
    }

  /** The stream's bytes, or null when it holds more than [limit] bytes. */
  private fun readAtMost(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val n = input.read(buffer)
      if (n < 0) return out.toByteArray()
      if (out.size() + n > limit) return null
      out.write(buffer, 0, n)
    }
  }

  private fun useExample() {
    audio = Audio(EXAMPLE_NAME, resources.openRawResource(R.raw.example_librispeech).use { it.readBytes() })
    audioLine.text = getString(R.string.example_audio)
  }

  private fun transcribeAudio() {
    val baseUrl = baseUrl()
    val token = token()
    if (baseUrl == null || token == null) {
      transcribeStatus.text = "Tap Connect to Model Shelf first."
      return
    }
    val model = speechModelField.text.toString().trim()
    val chosen = audio
    if (model.isEmpty() || chosen == null) {
      transcribeStatus.text = if (model.isEmpty()) "Type a speech model name." else "Pick a WAV file or use the example."
      return
    }
    prefs.edit().putString(KEY_SPEECH_MODEL, model).apply()
    transcribeButton.isEnabled = false
    transcribeStatus.text = "Transcribing in Model Shelf…"
    transcriptView.text = ""
    Thread(
        {
          val out = transcribe(baseUrl, token, model, chosen)
          runOnUiThread { showTranscript(out) }
        },
        "transcribe",
      )
      .start()
  }

  /**
   * Sends one transcription request: a multipart form with the model, response_format json and the
   * file. Runs on a worker thread. The Transcribe button and the test harness both send through here.
   */
  private fun transcribe(baseUrl: String, token: String, model: String, audio: Audio): Transcript {
    val out = Transcript()
    val start = SystemClock.elapsedRealtime()
    Log.i(TAG, "transcribe: POST $baseUrl$TRANSCRIPTIONS model=$model, file ${audio.bytes.size} B")
    var conn: HttpURLConnection? = null
    try {
      val boundary = "MSCaller" + UUID.randomUUID().toString().replace("-", "")
      val head =
        (formField(boundary, "model", model) +
            formField(boundary, "response_format", "json") +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${safeFileName(audio.name)}\"\r\n" +
            "Content-Type: ${if (audio.name.endsWith(".wav", ignoreCase = true)) "audio/wav" else "application/octet-stream"}\r\n\r\n")
          .toByteArray(Charsets.UTF_8)
      val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
      conn = URL(baseUrl + TRANSCRIPTIONS).openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.connectTimeout = 5_000
      // Model Shelf may first load the model, or wait for another app's request to finish.
      conn.readTimeout = 330_000
      conn.doOutput = true
      conn.setFixedLengthStreamingMode(head.size.toLong() + audio.bytes.size + tail.size)
      conn.setRequestProperty("Authorization", "Bearer $token")
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      conn.outputStream.use {
        it.write(head)
        it.write(audio.bytes)
        it.write(tail)
      }
      out.status = conn.responseCode
      for ((key, values) in conn.headerFields) {
        if (key != null && key.startsWith(SHELF_HEADER, ignoreCase = true)) {
          out.shelf[key.substring(SHELF_HEADER.length).lowercase(Locale.US)] = values.joinToString(",")
        }
      }
      if (out.status != HttpURLConnection.HTTP_OK) {
        out.error = errorText(conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        return out
      }
      out.text = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optString("text")
    } catch (e: ConnectException) {
      out.refused = true
      out.error = API_OFF
    } catch (e: SocketTimeoutException) {
      out.error = "No answer in time (${e.message})."
    } catch (e: IOException) {
      out.error = "The connection failed: ${e.javaClass.simpleName}: ${e.message}"
    } catch (e: JSONException) {
      out.error = "Model Shelf sent an answer this app could not read: ${e.message}"
    } catch (e: Exception) {
      out.error = "Unexpected: ${e.javaClass.simpleName}: ${e.message}"
    } finally {
      conn?.disconnect()
      out.totalMs = SystemClock.elapsedRealtime() - start
      // The log carries the text's length, not the text.
      Log.i(
        TAG,
        "transcribe: end: HTTP ${out.status}, total ${out.totalMs} ms, text ${out.text.length} chars, " +
          "model shelf ${out.shelf}, error ${out.error}",
      )
    }
    return out
  }

  private fun formField(boundary: String, name: String, value: String) =
    "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"

  /** The file name with quotes, backslashes and control characters replaced, for the form header. */
  private fun safeFileName(name: String) =
    name.map { if (it == '"' || it == '\\' || it < ' ') '_' else it }.joinToString("")

  private fun showTranscript(out: Transcript) {
    transcribeButton.isEnabled = !harnessRunning
    transcriptView.text = out.text
    transcribeStatus.text =
      when {
        out.refused -> out.error
        out.status == HttpURLConnection.HTTP_UNAUTHORIZED -> TOKEN_REFUSED
        out.status != HttpURLConnection.HTTP_OK -> if (out.status > 0) "HTTP ${out.status} · ${out.error}" else out.error
        else -> transcriptLine(out)
      }
    when {
      out.refused -> showConnection("Model Shelf's local API is off.")
      out.status == HttpURLConnection.HTTP_UNAUTHORIZED -> forgetToken()
    }
  }

  /** HTTP 200 · NPU · audio 3.07 s · inference 0.21 s (14.6× real time) · total 0.35 s */
  private fun transcriptLine(out: Transcript): String {
    val audioMs = out.shelf["audio-ms"]?.toLongOrNull()
    val inferenceMs = out.shelf["inference-ms"]?.toLongOrNull()
    return buildString {
      append("HTTP 200")
      out.shelf["backend"]?.let { append(" · ").append(it.uppercase(Locale.US)) }
      if (audioMs != null) append(" · audio ").append(seconds(audioMs))
      if (inferenceMs != null) {
        append(" · inference ").append(seconds(inferenceMs))
        if (audioMs != null && inferenceMs > 0) {
          append(String.format(Locale.US, " (%.1f× real time)", audioMs.toDouble() / inferenceMs))
        }
      }
      append(" · total ").append(seconds(out.totalMs))
    }
  }

  // ---- Test harness -----------------------------------------------------------------------------

  /**
   * Started with [EXTRA_HARNESS_DIR] = a folder in this app's external files folder, it sends the
   * files there one by one through [transcribe], the code the Transcribe button uses:
   * 1. the first WAV file once, not counted, when [EXTRA_HARNESS_WARM] is true (the default);
   * 2. every WAV file in name order;
   * 3. every other file in name order (Model Shelf should refuse them with HTTP 400);
   * 4. the first WAV file to [MODEL_NOT_ON_PHONE] (HTTP 404).
   * Each request is one line of results_<folder>.jsonl next to the folder. The speech model is
   * [EXTRA_HARNESS_MODEL], else the one in the speech model field.
   */
  private fun startHarness(intent: Intent?) {
    val dirName = intent?.getStringExtra(EXTRA_HARNESS_DIR) ?: return
    val model = intent.getStringExtra(EXTRA_HARNESS_MODEL) ?: speechModelField.text.toString().trim()
    val warm = intent.getBooleanExtra(EXTRA_HARNESS_WARM, true)
    val baseUrl = baseUrl()
    val token = token()
    val root = getExternalFilesDir(null)
    val files = root?.let { File(it, dirName).listFiles() }?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
    val wavs = files.filter { it.name.endsWith(".wav", ignoreCase = true) }
    val why =
      when {
        harnessRunning -> "it is running"
        baseUrl == null || token == null -> "not connected"
        model.isEmpty() -> "no speech model"
        wavs.isEmpty() -> "no WAV file in $dirName"
        else -> null
      }
    if (why != null || root == null || baseUrl == null || token == null) {
      Log.i(TAG, "harness: not started ($why)")
      transcribeStatus.text = "Test harness: not started ($why)."
      return
    }
    val plan = buildList {
      if (warm) add(Triple("warm", wavs.first(), model))
      for (f in wavs) add(Triple("utt", f, model))
      for (f in files - wavs.toSet()) add(Triple("not_wav", f, model))
      add(Triple("no_model", wavs.first(), MODEL_NOT_ON_PHONE))
    }
    val results = File(root, "results_$dirName.jsonl")
    harnessRunning = true
    transcribeButton.isEnabled = false
    speechModelField.setText(model)
    Log.i(TAG, "harness: ${plan.size} requests from $dirName, model $model, warm $warm")
    Thread(
        {
          results.delete()
          plan.forEachIndexed { i, (kind, file, m) ->
            val a = Audio(file.name, file.readBytes())
            runOnUiThread {
              audio = a
              audioLine.text = "Test harness ${i + 1}/${plan.size}: ${file.name} ($kind)"
              transcribeStatus.text = "Transcribing in Model Shelf…"
              transcriptView.text = ""
            }
            val out = transcribe(baseUrl, token, m, a)
            val line =
              JSONObject()
                .put("n", i + 1)
                .put("kind", kind)
                .put("id", file.name.substringBeforeLast('.'))
                .put("file", file.name)
                .put("bytes", a.bytes.size)
                .put("model", m)
                .put("status", out.status)
                .put("text", out.text)
                .put("error", out.error ?: JSONObject.NULL)
                .put("total_ms", out.totalMs)
                .put("shelf", JSONObject(out.shelf.toMap()))
            results.appendText(line.toString() + "\n")
            Log.i(TAG, "harness: ${i + 1}/${plan.size} $kind ${file.name}: HTTP ${out.status}")
            runOnUiThread { showTranscript(out) }
          }
          Log.i(TAG, "harness: done, ${plan.size} requests, ${results.absolutePath}")
          runOnUiThread {
            harnessRunning = false
            transcribeButton.isEnabled = true
            audioLine.text = "Test harness done: ${plan.size} requests; the results are in ${results.name}."
          }
        },
        "harness",
      )
      .start()
  }

  // ---- Helpers ----------------------------------------------------------------------------------

  private fun seconds(ms: Long) = String.format(Locale.US, "%.2f s", ms / 1000.0)

  /** A string field, or null when it is missing or JSON null. */
  private fun stringOrNull(obj: JSONObject, key: String): String? =
    if (obj.has(key) && !obj.isNull(key)) obj.optString(key) else null

  /** The sentence of an error body: Ollama's {"error": "..."} or OpenAI's {"error": {"message": "..."}}. */
  private fun errorText(body: String): String =
    try {
      when (val error = JSONObject(body).opt("error")) {
        is JSONObject -> error.optString("message", body)
        is String -> error
        else -> body
      }
    } catch (e: JSONException) {
      body.ifBlank { "No details." }
    }

  private companion object {
    const val TAG = "MSCaller"
    const val MODEL_SHELF = "io.github.johnrocky.modelshelf"
    const val ACTION_CONNECT = "io.github.johnrocky.modelshelf.action.CONNECT"
    const val EXTRA_BASE_URL = "base_url"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ERROR = "error"
    const val REQUEST_CONNECT = 1
    const val REQUEST_PICK_AUDIO = 2
    const val KEY_BASE_URL = "base_url"
    const val KEY_TOKEN = "token"
    const val KEY_MODEL = "model"
    const val KEY_SPEECH_MODEL = "speech_model"
    const val SHELF_HEADER = "X-ModelShelf-"
    const val TRANSCRIPTIONS = "/v1/audio/transcriptions"
    /** Model Shelf takes a request body of at most 25 MB; the form around the file needs a little of it. */
    const val MAX_AUDIO_BYTES = 25 * 1024 * 1024 - 64 * 1024
    const val EXAMPLE_NAME = "example_librispeech.wav"
    const val API_OFF = "Model Shelf's local API is off. Tap Connect to Model Shelf to turn it on."
    const val TOKEN_REFUSED =
      "HTTP 401 · Model Shelf did not accept this app's token. Tap Connect to Model Shelf to reconnect."
    /** Test harness extras (adb shell am start ... --es harness_dir <folder>). */
    const val EXTRA_HARNESS_DIR = "harness_dir"
    const val EXTRA_HARNESS_MODEL = "harness_model"
    const val EXTRA_HARNESS_WARM = "harness_warm"
    const val MODEL_NOT_ON_PHONE = "litert-community/not-on-this-phone"
  }
}
