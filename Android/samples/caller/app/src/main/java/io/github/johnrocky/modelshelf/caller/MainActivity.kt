package io.github.johnrocky.modelshelf.caller

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * An app with no model of its own. It gets answers from the models on this phone through Model
 * Shelf's local API: Ollama's form (POST /api/chat) and OpenAI's form (POST /v1/chat/completions),
 * both streamed.
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

  private lateinit var prefs: SharedPreferences
  private lateinit var connection: TextView
  private lateinit var modelField: EditText
  private lateinit var modelsLine: TextView
  private lateinit var promptField: EditText
  private lateinit var panes: Map<Api, Pane>

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
    findViewById<Button>(R.id.connect).setOnClickListener { connect() }
    for ((api, pane) in panes) pane.button.setOnClickListener { ask(api) }
    modelField.setText(prefs.getString(KEY_MODEL, ""))
    showConnection()
    loadModels()
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
    if (requestCode != REQUEST_CONNECT) return
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

  /** Fills the model field from Model Shelf's list (GET /api/tags) when the field is empty. */
  private fun loadModels() {
    val baseUrl = baseUrl() ?: return
    val token = token() ?: return
    Thread(
        {
          try {
            val conn = URL("$baseUrl/api/tags").openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
              Log.i(TAG, "tags: HTTP $status")
              conn.disconnect()
              return@Thread
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val models = JSONObject(text).optJSONArray("models") ?: JSONArray()
            val names =
              (0 until models.length())
                .mapNotNull { models.optJSONObject(it)?.optString("name") }
                .filter { it.isNotEmpty() }
            Log.i(TAG, "tags: ${names.size} models: $names")
            runOnUiThread {
              if (modelField.text.isBlank() && names.isNotEmpty()) modelField.setText(names.first())
              modelsLine.text =
                if (names.isEmpty()) "Model Shelf has no model to serve yet. Download one in Model Shelf."
                else "In Model Shelf: " + names.joinToString(", ")
            }
          } catch (e: Exception) {
            Log.i(TAG, "tags: ${e.javaClass.simpleName}: ${e.message}")
          }
        },
        "tags",
      )
      .start()
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
    Log.i(TAG, "${api.label}: POST $baseUrl${api.path} model=$model prompt=\"$prompt\"")
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
        conn.headerFields.filterKeys { it != null && it.startsWith("X-ModelShelf", ignoreCase = true) }
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
      out.error = "Model Shelf's local API is off. Tap Connect to Model Shelf to turn it on."
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
          "finished ${out.finished}, error ${out.error}, retry-after ${out.retryAfter}",
      )
      Log.i(TAG, "${api.label}: last line: ${out.lastLine.take(400)}")
      Log.i(TAG, "${api.label}: answer: ${out.text}")
    }
    return out
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
        prefs.edit().remove(KEY_TOKEN).apply()
        pane.status.text =
          "HTTP 401 · Model Shelf did not accept this app's token. Tap Connect to Model Shelf to reconnect."
        showConnection("Not connected: Model Shelf did not accept this app's token.")
      }
      out.status == HttpURLConnection.HTTP_UNAVAILABLE ->
        pane.status.text = "HTTP 503 · ${out.error} Try again in ${out.retryAfter ?: "a few"} s."
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
    const val KEY_BASE_URL = "base_url"
    const val KEY_TOKEN = "token"
    const val KEY_MODEL = "model"
  }
}
