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
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
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
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * An app with no model of its own. It gets answers from the models on this phone through Model
 * Shelf's local API: Ollama's form (POST /api/chat) and OpenAI's form (POST /v1/chat/completions),
 * both streamed, the text of a WAV file (POST /v1/audio/transcriptions), and speech for a text it
 * plays (POST /v1/audio/speech).
 *
 * Every request goes through [ModelShelfClient]: on one connection it first checks that the server
 * holds the secret Model Shelf gave this app on Connect, and sends the token, the prompt or the
 * audio only when it does.
 *
 * Started with the extra [EXTRA_HARNESS_DIR], it runs a transcription test through the same request
 * code as the Transcribe button (see [startHarness] and the README); with [EXTRA_SPEECH_HARNESS_DIR],
 * a text-to-speech test and its round trip through the code of the Speak and Transcribe buttons
 * (see [startSpeechHarness]); with [EXTRA_STALL_HARNESS_DIR], a speech request whose answer it does
 * not read (see [startStallHarness]).
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

  /** What one speech request did. */
  private class Speech {
    var status = -1
    /** The response body: a WAV file or headerless PCM. */
    var bytes = ByteArray(0)
    var totalMs = 0L
    var refused = false
    var error: String? = null
    var retryAfter: String? = null
    /** Model Shelf's X-ModelShelf-* headers, by the rest of their name in lower case. */
    val shelf = mutableMapOf<String, String>()
  }

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
  private lateinit var ttsModelField: EditText
  private lateinit var voiceField: EditText
  private lateinit var sayField: EditText
  private lateinit var speakButton: Button
  private lateinit var speakStatus: TextView
  /** The audio the Transcribe button sends (UI thread only). */
  private var audio: Audio? = null
  /** True while a test harness runs (UI thread only). */
  private var harnessRunning = false
  /** The track that plays the last speech; replaced by the next Speak. */
  @Volatile private var track: AudioTrack? = null

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
    ttsModelField = findViewById(R.id.tts_model)
    voiceField = findViewById(R.id.voice)
    sayField = findViewById(R.id.say_text)
    speakButton = findViewById(R.id.speak)
    speakStatus = findViewById(R.id.speak_status)
    findViewById<Button>(R.id.connect).setOnClickListener { connect() }
    for ((api, pane) in panes) pane.button.setOnClickListener { ask(api) }
    findViewById<Button>(R.id.pick_wav).setOnClickListener { pickAudio() }
    findViewById<Button>(R.id.use_example).setOnClickListener { useExample() }
    transcribeButton.setOnClickListener { transcribeAudio() }
    speakButton.setOnClickListener { speakText() }
    modelField.setText(prefs.getString(KEY_MODEL, ""))
    speechModelField.setText(prefs.getString(KEY_SPEECH_MODEL, ""))
    ttsModelField.setText(prefs.getString(KEY_TTS_MODEL, ""))
    voiceField.setText(prefs.getString(KEY_VOICE, ""))
    showConnection()
    loadModels()
    if (savedInstanceState == null) startHarnesses(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    startHarnesses(intent)
  }

  override fun onDestroy() {
    // The worker in play() owns the track and releases it when it sees it is no longer the current one.
    track = null
    super.onDestroy()
  }

  private fun startHarnesses(intent: Intent?) {
    startHarness(intent)
    startSpeechHarness(intent)
    startStallHarness(intent)
  }

  private fun baseUrl(): String? = prefs.getString(KEY_BASE_URL, null)

  private fun token(): String? = prefs.getString(KEY_TOKEN, null)

  /** The secret Model Shelf gave this app on Connect (32 bytes), or null. */
  private fun verifyKey(): ByteArray? =
    prefs.getString(KEY_VERIFY_KEY, null)
      ?.let { runCatching { Base64.decode(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull() }
      ?.takeIf { it.size == 32 }

  /** The client that checks the server first; null when this app has no secret to check it with. */
  private fun client(baseUrl: String): ModelShelfClient? =
    verifyKey()?.let { ModelShelfClient(baseUrl, packageName, it) }

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
    val key = data?.getStringExtra(EXTRA_VERIFY_KEY)
    if (resultCode == RESULT_OK && baseUrl != null && token != null) {
      val edit = prefs.edit().putString(KEY_BASE_URL, baseUrl).putString(KEY_TOKEN, token)
      if (key != null) edit.putString(KEY_VERIFY_KEY, key) else edit.remove(KEY_VERIFY_KEY)
      edit.apply()
      Log.i(TAG, "connect: connected, base_url $baseUrl, verify key ${if (key != null) "given" else "not given"}")
      showConnection(if (key == null) NO_KEY else null)
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
   * "kind": "transcription") and the voice models ("kind": "speech", with their default voice).
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
          val v1 =
            getJson(baseUrl, token, "/v1/models")?.let { list ->
              val data = list.optJSONArray("data") ?: JSONArray()
              (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            }
          fun kind(m: JSONObject) = m.optJSONObject("modelshelf")?.optString("kind")
          val speech = v1?.filter { kind(it) == "transcription" }?.map { it.optString("id") }?.filter { it.isNotEmpty() }
          val voices = v1?.filter { kind(it) == "speech" && it.optString("id").isNotEmpty() }
          Log.i(TAG, "models: chat $chat, speech $speech, voice ${voices?.map { it.optString("id") }}")
          runOnUiThread {
            if (voices != null) {
              val first = voices.firstOrNull()
              if (ttsModelField.text.isBlank() && first != null) {
                ttsModelField.setText(first.optString("id"))
                if (voiceField.text.isBlank()) {
                  voiceField.setText(first.optJSONObject("modelshelf")?.optString("default_voice").orEmpty())
                }
              }
              if (speakStatus.text.isEmpty()) {
                speakStatus.text =
                  if (voices.isEmpty()) "Model Shelf has no voice model yet."
                  else "Voice models in Model Shelf: " + voices.joinToString(", ") { it.optString("id") }
              }
            }
          }
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
    val client = client(baseUrl)
    if (client == null) {
      Log.i(TAG, "GET $path: not sent (no verify key)")
      return null
    }
    return try {
      client.send("GET", path, listOf("Authorization" to "Bearer $token")).use { r ->
        if (r.status != HttpURLConnection.HTTP_OK) {
          Log.i(TAG, "GET $path: HTTP ${r.status}")
          null
        } else {
          JSONObject(r.body.readBytes().toString(Charsets.UTF_8))
        }
      }
    } catch (e: Exception) {
      Log.i(TAG, "GET $path: ${e.javaClass.simpleName}: ${e.message}")
      null
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
    var response: ModelShelfClient.Response? = null
    try {
      val client = client(baseUrl) ?: return out.apply { error = NO_KEY }
      val bytes = body(api, model, prompt).toByteArray(Charsets.UTF_8)
      // Model Shelf may first load the model, or wait for another app's request to finish.
      val r =
        client.send(
          "POST",
          api.path,
          listOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
          bytes.size.toLong(),
          readTimeoutMs = 330_000,
        ) {
          it.write(bytes)
        }
      response = r
      out.status = r.status
      Log.i(TAG, "${api.label}: HTTP ${out.status} ${r.shelfHeaders()}")
      if (out.status != HttpURLConnection.HTTP_OK) {
        out.retryAfter = r.header("Retry-After")
        out.error = errorText(r.body.readBytes().toString(Charsets.UTF_8))
        return out
      }
      r.body.bufferedReader(Charsets.UTF_8).use { reader ->
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
    } catch (e: ModelShelfClient.NotModelShelf) {
      out.error = e.message
    } catch (e: SocketTimeoutException) {
      out.error = "No answer in time (${e.message})."
    } catch (e: IOException) {
      out.error = "The connection failed: ${e.javaClass.simpleName}: ${e.message}"
    } catch (e: JSONException) {
      out.error = "Model Shelf sent a line this app could not read: ${e.message}"
    } catch (e: Exception) {
      out.error = "Unexpected: ${e.javaClass.simpleName}: ${e.message}"
    } finally {
      response?.close()
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
    prefs.edit().remove(KEY_TOKEN).remove(KEY_VERIFY_KEY).apply()
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
    var response: ModelShelfClient.Response? = null
    try {
      val client = client(baseUrl) ?: return out.apply { error = NO_KEY }
      val boundary = "MSCaller" + UUID.randomUUID().toString().replace("-", "")
      val head =
        (formField(boundary, "model", model) +
            formField(boundary, "response_format", "json") +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${safeFileName(audio.name)}\"\r\n" +
            "Content-Type: ${if (audio.name.endsWith(".wav", ignoreCase = true)) "audio/wav" else "application/octet-stream"}\r\n\r\n")
          .toByteArray(Charsets.UTF_8)
      val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
      // Model Shelf may first load the model, or wait for another app's request to finish.
      val r =
        client.send(
          "POST",
          TRANSCRIPTIONS,
          listOf("Authorization" to "Bearer $token", "Content-Type" to "multipart/form-data; boundary=$boundary"),
          head.size.toLong() + audio.bytes.size + tail.size,
          readTimeoutMs = 330_000,
        ) {
          it.write(head)
          it.write(audio.bytes)
          it.write(tail)
        }
      response = r
      out.status = r.status
      out.shelf.putAll(r.shelfHeaders())
      if (out.status != HttpURLConnection.HTTP_OK) {
        out.error = errorText(r.body.readBytes().toString(Charsets.UTF_8))
        return out
      }
      out.text = JSONObject(r.body.readBytes().toString(Charsets.UTF_8)).optString("text")
    } catch (e: ConnectException) {
      out.refused = true
      out.error = API_OFF
    } catch (e: ModelShelfClient.NotModelShelf) {
      out.error = e.message
    } catch (e: SocketTimeoutException) {
      out.error = "No answer in time (${e.message})."
    } catch (e: IOException) {
      out.error = "The connection failed: ${e.javaClass.simpleName}: ${e.message}"
    } catch (e: JSONException) {
      out.error = "Model Shelf sent an answer this app could not read: ${e.message}"
    } catch (e: Exception) {
      out.error = "Unexpected: ${e.javaClass.simpleName}: ${e.message}"
    } finally {
      response?.close()
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

  // ---- Text to speech ---------------------------------------------------------------------------

  private fun speakText() {
    val baseUrl = baseUrl()
    val token = token()
    if (baseUrl == null || token == null) {
      speakStatus.text = "Tap Connect to Model Shelf first."
      return
    }
    val model = ttsModelField.text.toString().trim()
    val voice = voiceField.text.toString().trim()
    val text = sayField.text.toString().trim()
    if (model.isEmpty() || text.isEmpty()) {
      speakStatus.text = if (model.isEmpty()) "Type a voice model name." else "Type something to speak."
      return
    }
    prefs.edit().putString(KEY_TTS_MODEL, model).putString(KEY_VOICE, voice).apply()
    speakButton.isEnabled = false
    speakStatus.text = "Speaking in Model Shelf…"
    Thread(
        {
          val out = synthesize(baseUrl, token, model, voice.ifEmpty { null }, text, FORMAT_WAV)
          val line = if (out.status == HttpURLConnection.HTTP_OK) speechLine(out) else null
          runOnUiThread { showSpeech(out, if (line != null) "$line · playing…" else null) }
          if (line != null) {
            val played = play(out.bytes)
            runOnUiThread {
              speakButton.isEnabled = !harnessRunning
              speakStatus.text = "$line · $played"
            }
          }
        },
        "speak",
      )
      .start()
  }

  /**
   * Sends one speech request (POST /v1/audio/speech) with [format] wav or pcm and reads the whole
   * body. Runs on a worker thread. The Speak button and the test harness both send through here.
   * [voice] null = the model's default voice.
   */
  private fun synthesize(baseUrl: String, token: String, model: String, voice: String?, text: String, format: String): Speech {
    val out = Speech()
    val start = SystemClock.elapsedRealtime()
    // The log carries the text's length, not the text.
    Log.i(TAG, "speak: POST $baseUrl$SPEECH model=$model, voice=${voice ?: "(default)"}, $format, text ${text.length} chars")
    var response: ModelShelfClient.Response? = null
    try {
      val client = client(baseUrl) ?: return out.apply { error = NO_KEY }
      val body = JSONObject().put("model", model).put("input", text).put("response_format", format)
      if (voice != null) body.put("voice", voice)
      val bytes = body.toString().toByteArray(Charsets.UTF_8)
      // A WAV answer comes after the whole text is spoken (up to Model Shelf's 300 s limit), and
      // Model Shelf may first load the model or wait for another app's request to finish.
      val r =
        client.send(
          "POST",
          SPEECH,
          listOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
          bytes.size.toLong(),
          readTimeoutMs = 400_000,
        ) {
          it.write(bytes)
        }
      response = r
      out.status = r.status
      out.shelf.putAll(r.shelfHeaders())
      if (out.status != HttpURLConnection.HTTP_OK) {
        out.retryAfter = r.header("Retry-After")
        out.error = errorText(r.body.readBytes().toString(Charsets.UTF_8))
        return out
      }
      out.bytes = r.body.readBytes()
    } catch (e: ConnectException) {
      out.refused = true
      out.error = API_OFF
    } catch (e: ModelShelfClient.NotModelShelf) {
      out.error = e.message
    } catch (e: SocketTimeoutException) {
      out.error = "No answer in time (${e.message})."
    } catch (e: IOException) {
      out.error = "The connection failed: ${e.javaClass.simpleName}: ${e.message}"
    } catch (e: Exception) {
      out.error = "Unexpected: ${e.javaClass.simpleName}: ${e.message}"
    } finally {
      response?.close()
      out.totalMs = SystemClock.elapsedRealtime() - start
      Log.i(
        TAG,
        "speak: end: HTTP ${out.status}, total ${out.totalMs} ms, ${out.bytes.size} bytes, " +
          "model shelf ${out.shelf}, error ${out.error}",
      )
    }
    return out
  }

  /** HTTP 200 · GPU · voice af_heart · audio 3.21 s · synthesis 0.45 s (7.1× real time) · total 0.62 s */
  private fun speechLine(out: Speech): String {
    val audioMs = out.shelf["audio-ms"]?.toLongOrNull()
    val synthMs = out.shelf["inference-ms"]?.toLongOrNull()
    return buildString {
      append("HTTP 200")
      out.shelf["backend"]?.let { append(" · ").append(it.uppercase(Locale.US)) }
      out.shelf["voice"]?.let { append(" · voice ").append(it) }
      if (audioMs != null) append(" · audio ").append(seconds(audioMs))
      if (synthMs != null) {
        append(" · synthesis ").append(seconds(synthMs))
        if (audioMs != null && synthMs > 0) {
          append(String.format(Locale.US, " (%.1f× real time)", audioMs.toDouble() / synthMs))
        }
      }
      append(" · total ").append(seconds(out.totalMs))
    }
  }

  private fun showSpeech(out: Speech, line: String?) {
    speakButton.isEnabled = !harnessRunning && line == null
    speakStatus.text =
      when {
        line != null -> line
        out.refused -> out.error
        out.status == HttpURLConnection.HTTP_UNAUTHORIZED -> TOKEN_REFUSED
        out.status > 0 -> "HTTP ${out.status} · ${out.error}"
        else -> out.error
      }
    when {
      out.refused -> showConnection("Model Shelf's local API is off.")
      out.status == HttpURLConnection.HTTP_UNAUTHORIZED -> forgetToken()
    }
  }

  /**
   * Plays a WAV body of 24 kHz mono 16-bit PCM to its end on the media stream. Runs on a worker
   * thread. Returns what happened, for the status line.
   */
  private fun play(wav: ByteArray): String {
    val pcm = wavData(wav) ?: return "not played: the answer is not a 16-bit mono WAV file"
    if (pcm.bytes.isEmpty()) return "not played: no audio"
    // The thread that plays the previous track sees it is no longer the current one and releases it.
    track = null
    val t =
      try {
        AudioTrack.Builder()
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(pcm.rateHz)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .build()
          )
          .setTransferMode(AudioTrack.MODE_STATIC)
          .setBufferSizeInBytes(pcm.bytes.size)
          .build()
      } catch (e: Exception) {
        // A static track holds the whole audio: a very long answer may not fit.
        return "not played: ${e.message ?: e.javaClass.simpleName}"
      }
    track = t
    val frames = pcm.bytes.size / 2
    // This thread owns the track and releases it: the activity only lets go of it (onDestroy, or the
    // next Speak), so the track is never released under this loop.
    var played = 0
    try {
      t.write(pcm.bytes, 0, pcm.bytes.size)
      Log.i(TAG, "speak: playing ${frames} frames at ${pcm.rateHz} Hz")
      t.play()
      val deadline = SystemClock.elapsedRealtime() + frames * 1000L / pcm.rateHz + 2_000
      while (t.playbackHeadPosition < frames && SystemClock.elapsedRealtime() < deadline && track === t) {
        SystemClock.sleep(50)
      }
      played = t.playbackHeadPosition
    } catch (e: IllegalStateException) {
      Log.i(TAG, "speak: the track stopped: ${e.message}")
    } finally {
      runCatching { t.stop() }
      t.release()
      if (track === t) track = null
    }
    Log.i(TAG, "speak: played $played of $frames frames")
    return if (played >= frames) "played" else "stopped"
  }

  private class Pcm(val bytes: ByteArray, val rateHz: Int)

  /** The samples of a 16-bit mono PCM WAV file, or null when it is not one. */
  private fun wavData(wav: ByteArray): Pcm? {
    fun tag(at: Int, s: String) = at + 4 <= wav.size && String(wav, at, 4, Charsets.US_ASCII) == s
    fun u16(at: Int) = (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8)
    fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
    if (wav.size < 12 || !tag(0, "RIFF") || !tag(8, "WAVE")) return null
    var at = 12
    var rate = 0
    var ok = false
    while (at + 8 <= wav.size) {
      val size = u32(at + 4)
      if (size < 0) return null
      val body = at + 8
      if (tag(at, "fmt ") && body + 16 <= wav.size) {
        ok = u16(body) == 1 && u16(body + 2) == 1 && u16(body + 14) == 16
        rate = u32(body + 4)
      } else if (tag(at, "data")) {
        if (!ok || rate <= 0) return null
        val end = minOf(wav.size.toLong(), body.toLong() + size).toInt()
        return Pcm(wav.copyOfRange(body, end - (end - body) % 2), rate)
      }
      // Long arithmetic: a chunk size near 2 GB must not wrap the position around.
      val next = body.toLong() + size + (size and 1)
      if (next > wav.size) return null
      at = next.toInt()
    }
    return null
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
    val dirName = harnessFolder(intent, EXTRA_HARNESS_DIR) ?: return
    val model = intent?.getStringExtra(EXTRA_HARNESS_MODEL) ?: speechModelField.text.toString().trim()
    val warm = intent?.getBooleanExtra(EXTRA_HARNESS_WARM, true) ?: true
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

  /**
   * Started with [EXTRA_SPEECH_HARNESS_DIR] = a folder in this app's external files folder holding
   * sentences.txt (one sentence per line), it sends through [synthesize], the code the Speak button
   * uses, and through [transcribe], the code of the Transcribe button:
   * 1. sentence 1 once, not counted (the model loads);
   * 2. every sentence as WAV, saved as speech_<n>.wav in the folder;
   * 3. sentence 1 as pcm, saved as speech_1.pcm;
   * 4. the failure paths: an empty text, a voice the model does not have, a text of 4097
   *    characters, a voice model that is not on the phone;
   * 5. the round trip: each speech_<n>.wav to the speech recognition model.
   * Nothing is played. Each request is one line of results_<folder>.jsonl next to the folder. The
   * models are [EXTRA_SPEECH_HARNESS_TTS] and [EXTRA_SPEECH_HARNESS_ASR], else the ones in the fields.
   */
  private fun startSpeechHarness(intent: Intent?) {
    val dirName = harnessFolder(intent, EXTRA_SPEECH_HARNESS_DIR) ?: return
    val ttsModel = intent?.getStringExtra(EXTRA_SPEECH_HARNESS_TTS) ?: ttsModelField.text.toString().trim()
    val asrModel = intent?.getStringExtra(EXTRA_SPEECH_HARNESS_ASR) ?: speechModelField.text.toString().trim()
    val voice = intent?.getStringExtra(EXTRA_SPEECH_HARNESS_VOICE)?.trim()?.ifEmpty { null }
    val baseUrl = baseUrl()
    val token = token()
    val root = getExternalFilesDir(null)
    val dir = root?.let { File(it, dirName) }
    val sentences =
      dir?.let { File(it, "sentences.txt") }?.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val why =
      when {
        harnessRunning -> "a harness is running"
        baseUrl == null || token == null -> "not connected"
        ttsModel.isEmpty() -> "no voice model"
        asrModel.isEmpty() -> "no speech recognition model"
        sentences.isEmpty() -> "no sentences.txt in $dirName"
        else -> null
      }
    if (why != null || root == null || dir == null || baseUrl == null || token == null) {
      Log.i(TAG, "speech harness: not started ($why)")
      speakStatus.text = "Speech test harness: not started ($why)."
      return
    }
    class Step(val kind: String, val n: Int, val text: String, val model: String, val voice: String?, val format: String)
    val plan = buildList {
      add(Step("warm", 1, sentences[0], ttsModel, voice, FORMAT_WAV))
      sentences.forEachIndexed { i, s -> add(Step("speak", i + 1, s, ttsModel, voice, FORMAT_WAV)) }
      add(Step("pcm", 1, sentences[0], ttsModel, voice, FORMAT_PCM))
      add(Step("empty_input", 0, "", ttsModel, voice, FORMAT_WAV))
      add(Step("no_voice", 1, sentences[0], ttsModel, NO_SUCH_VOICE, FORMAT_WAV))
      add(Step("too_long", 0, "a".repeat(4097), ttsModel, voice, FORMAT_WAV))
      add(Step("no_model", 1, sentences[0], MODEL_NOT_ON_PHONE, voice, FORMAT_WAV))
    }
    val results = File(root, "results_$dirName.jsonl")
    harnessRunning = true
    speakButton.isEnabled = false
    transcribeButton.isEnabled = false
    ttsModelField.setText(ttsModel)
    speechModelField.setText(asrModel)
    Log.i(TAG, "speech harness: ${plan.size} speech requests and ${sentences.size} round trips from $dirName, voice model $ttsModel, speech model $asrModel")
    Thread(
        {
          results.delete()
          var count = 0
          val total = plan.size + sentences.size
          for (step in plan) {
            count++
            runOnUiThread { speakStatus.text = "Speech test harness $count/$total: ${step.kind} ${step.n}…" }
            // The empty text is sent as is: the request code must not stop it before Model Shelf.
            val out = synthesize(baseUrl, token, step.model, step.voice, step.text, step.format)
            var saved: String? = null
            if (out.status == HttpURLConnection.HTTP_OK && (step.kind == "speak" || step.kind == "pcm")) {
              val name = "speech_${step.n}.${if (step.format == FORMAT_PCM) "pcm" else "wav"}"
              File(dir, name).writeBytes(out.bytes)
              saved = name
            }
            val line =
              JSONObject()
                .put("n", count)
                .put("kind", step.kind)
                .put("sentence", step.n)
                .put("chars", step.text.length)
                .put("model", step.model)
                .put("voice", step.voice ?: JSONObject.NULL)
                .put("format", step.format)
                .put("status", out.status)
                .put("bytes", out.bytes.size)
                .put("saved", saved ?: JSONObject.NULL)
                .put("error", out.error ?: JSONObject.NULL)
                .put("retry_after", out.retryAfter ?: JSONObject.NULL)
                .put("total_ms", out.totalMs)
                .put("shelf", JSONObject(out.shelf.toMap()))
            results.appendText(line.toString() + "\n")
            Log.i(TAG, "speech harness: $count/$total ${step.kind} ${step.n}: HTTP ${out.status}")
            runOnUiThread { showSpeech(out, if (out.status == HttpURLConnection.HTTP_OK) speechLine(out) else null) }
          }
          for (i in sentences.indices) {
            count++
            val file = File(dir, "speech_${i + 1}.wav")
            val a = if (file.isFile) Audio(file.name, file.readBytes()) else Audio(file.name, ByteArray(0))
            runOnUiThread { transcribeStatus.text = "Speech test harness $count/$total: round trip ${i + 1}…" }
            val out = if (a.bytes.isEmpty()) Transcript().apply { error = "no WAV file was saved" } else transcribe(baseUrl, token, asrModel, a)
            val line =
              JSONObject()
                .put("n", count)
                .put("kind", "round_trip")
                .put("sentence", i + 1)
                .put("file", file.name)
                .put("model", asrModel)
                .put("status", out.status)
                .put("text", out.text)
                .put("error", out.error ?: JSONObject.NULL)
                .put("total_ms", out.totalMs)
                .put("shelf", JSONObject(out.shelf.toMap()))
            results.appendText(line.toString() + "\n")
            Log.i(TAG, "speech harness: $count/$total round_trip ${i + 1}: HTTP ${out.status}")
            runOnUiThread { showTranscript(out) }
          }
          Log.i(TAG, "speech harness: done, $total requests, ${results.absolutePath}")
          runOnUiThread {
            harnessRunning = false
            speakButton.isEnabled = true
            transcribeButton.isEnabled = true
            speakStatus.text = "Speech test harness done: $total requests; the results are in ${results.name}."
          }
        },
        "speech-harness",
      )
      .start()
  }

  /**
   * Started with [EXTRA_STALL_HARNESS_DIR] = a folder holding text.txt, it plays an app that stopped
   * reading: it sends the text as one speech request (WAV) through [ModelShelfClient] (the check
   * first), then reads nothing for [EXTRA_STALL_HOLD_S] seconds (default 240). Then it reads what it
   * can and writes stall_<folder>.json next to the folder: the status and Content-Length it got,
   * the bytes, and how the read ended (Model Shelf cuts an answer nobody reads).
   */
  private fun startStallHarness(intent: Intent?) {
    val dirName = harnessFolder(intent, EXTRA_STALL_HARNESS_DIR) ?: return
    val ttsModel = intent?.getStringExtra(EXTRA_SPEECH_HARNESS_TTS) ?: ttsModelField.text.toString().trim()
    val holdS = intent?.getIntExtra(EXTRA_STALL_HOLD_S, 240) ?: 240
    val baseUrl = baseUrl()
    val token = token()
    val root = getExternalFilesDir(null)
    val text = root?.let { File(File(it, dirName), "text.txt") }?.takeIf { it.isFile }?.readText()?.trim().orEmpty()
    val client = baseUrl?.let { client(it) }
    val why =
      when {
        harnessRunning -> "a harness is running"
        baseUrl == null || token == null -> "not connected"
        client == null -> "no verify key"
        ttsModel.isEmpty() -> "no voice model"
        text.isEmpty() -> "no text.txt in $dirName"
        else -> null
      }
    if (why != null || root == null || client == null || token == null) {
      Log.i(TAG, "stall harness: not started ($why)")
      speakStatus.text = "Stall test harness: not started ($why)."
      return
    }
    harnessRunning = true
    speakButton.isEnabled = false
    speakStatus.text = "Stall test harness: sending ${text.length} characters, then not reading for $holdS s…"
    Thread(
        {
          val result = JSONObject().put("chars", text.length).put("model", ttsModel).put("hold_s", holdS)
          val t0 = SystemClock.elapsedRealtime()
          var socket: java.net.Socket? = null
          try {
            val body =
              JSONObject().put("model", ttsModel).put("input", text).put("response_format", FORMAT_WAV).toString()
                .toByteArray(Charsets.UTF_8)
            val opened =
              client.open(
                "POST",
                SPEECH,
                listOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
                body.size.toLong(),
              ) {
                it.write(body)
              }
            socket = opened.socket
            result.put("sent_ms", SystemClock.elapsedRealtime() - t0)
            Log.i(TAG, "stall harness: sent ${text.length} characters; not reading for $holdS s")
            SystemClock.sleep(holdS * 1000L)
            socket.soTimeout = 5_000
            val buf = ByteArray(64 * 1024)
            val head = ByteArrayOutputStream()
            var got = 0L
            val ended =
              try {
                while (true) {
                  val n = opened.input.read(buf)
                  if (n < 0) break
                  if (head.size() < 4096) head.write(buf, 0, minOf(n, 4096 - head.size()))
                  got += n
                }
                "end of stream"
              } catch (e: SocketTimeoutException) {
                "no byte for 5 s (the connection is still open)"
              } catch (e: IOException) {
                "${e.javaClass.simpleName}: ${e.message}"
              }
            val headText = head.toString("ISO-8859-1")
            val headEnd = headText.indexOf("\r\n\r\n")
            result
              .put("status", Regex("^HTTP/1\\.1 (\\d{3})").find(headText)?.groupValues?.get(1) ?: JSONObject.NULL)
              .put("content_length", Regex("(?im)^content-length:\\s*(\\d+)").find(headText)?.groupValues?.get(1) ?: JSONObject.NULL)
              .put("bytes_after_hold", got)
              .put("body_bytes_after_hold", if (headEnd >= 0) got - headEnd - 4 else JSONObject.NULL)
              .put("read_ended", ended)
          } catch (e: Exception) {
            result.put("error", "${e.javaClass.simpleName}: ${e.message}")
          } finally {
            runCatching { socket?.close() }
          }
          result.put("total_ms", SystemClock.elapsedRealtime() - t0)
          val file = File(root, "stall_$dirName.json")
          file.writeText(result.toString(2))
          Log.i(TAG, "stall harness: done, $result")
          runOnUiThread {
            harnessRunning = false
            speakButton.isEnabled = true
            speakStatus.text = "Stall test harness done; the result is in ${file.name}."
          }
        },
        "stall-harness",
      )
      .start()
  }

  /**
   * The harness folder an intent names in [extra], or null. This activity is the exported launcher,
   * so any app can start it: the harnesses run only in a debuggable build, and only on one plain
   * folder name inside this app's external files folder (no "/", not "." or "..").
   */
  private fun harnessFolder(intent: Intent?, extra: String): String? {
    val name = intent?.getStringExtra(extra) ?: return null
    val why =
      when {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 -> "this build is not debuggable"
        !HARNESS_FOLDER.matches(name) || name == "." || name == ".." -> "the folder name is not a plain name"
        else -> return name
      }
    Log.i(TAG, "harness: not started ($why)")
    return null
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
    /** The secret Model Shelf gives on Allow (base64url, 32 bytes), to check the server with. */
    const val EXTRA_VERIFY_KEY = "verify_key"
    const val REQUEST_CONNECT = 1
    const val REQUEST_PICK_AUDIO = 2
    const val KEY_BASE_URL = "base_url"
    const val KEY_TOKEN = "token"
    const val KEY_VERIFY_KEY = "verify_key"
    const val KEY_MODEL = "model"
    const val KEY_SPEECH_MODEL = "speech_model"
    const val KEY_TTS_MODEL = "tts_model"
    const val KEY_VOICE = "voice"
    const val SHELF_HEADER = "X-ModelShelf-"
    const val TRANSCRIPTIONS = "/v1/audio/transcriptions"
    const val SPEECH = "/v1/audio/speech"
    const val FORMAT_WAV = "wav"
    const val FORMAT_PCM = "pcm"
    /** Model Shelf takes a request body of at most 25 MB; the form around the file needs a little of it. */
    const val MAX_AUDIO_BYTES = 25 * 1024 * 1024 - 64 * 1024
    const val EXAMPLE_NAME = "example_librispeech.wav"
    const val API_OFF = "Model Shelf's local API is off. Tap Connect to Model Shelf to turn it on."
    const val NO_KEY =
      "This app has no key to check Model Shelf with (a copied token has none), so nothing was sent; tap Connect to Model Shelf."
    const val TOKEN_REFUSED =
      "HTTP 401 · Model Shelf did not accept this app's token. Tap Connect to Model Shelf to reconnect."
    /** Test harness extras (adb shell am start ... --es harness_dir <folder>). */
    const val EXTRA_HARNESS_DIR = "harness_dir"
    const val EXTRA_HARNESS_MODEL = "harness_model"
    const val EXTRA_HARNESS_WARM = "harness_warm"
    const val MODEL_NOT_ON_PHONE = "litert-community/not-on-this-phone"
    /** A harness folder: one plain name, no path. */
    val HARNESS_FOLDER = Regex("[A-Za-z0-9._-]{1,64}")
    /** Speech test harness extras (adb shell am start ... --es speech_harness_dir <folder>). */
    const val EXTRA_SPEECH_HARNESS_DIR = "speech_harness_dir"
    const val EXTRA_SPEECH_HARNESS_TTS = "speech_harness_tts_model"
    const val EXTRA_SPEECH_HARNESS_ASR = "speech_harness_asr_model"
    const val EXTRA_SPEECH_HARNESS_VOICE = "speech_harness_voice"
    const val NO_SUCH_VOICE = "no_such_voice"
    const val EXTRA_STALL_HARNESS_DIR = "stall_harness_dir"
    const val EXTRA_STALL_HOLD_S = "stall_hold_s"
  }
}
