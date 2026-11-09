# Caller Sample

An Android app with no model of its own. It gets answers from the models on the same phone
through Model Shelf's local API, without a network connection.

One screen: **Connect to Model Shelf**, a prompt, **Ask via Ollama API** and **Ask via OpenAI API**.
The answer streams onto the screen with the HTTP status, the time to the first text and the total
time.

## Build and run

1. Install Model Shelf (`Android/src` in this repo) and download a model in it.
2. From this folder: `./gradlew assembleDebug`, then
   `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Open Caller Sample, tap **Connect to Model Shelf**, then **Allow** in Model Shelf.
4. Tap **Ask via Ollama API** or **Ask via OpenAI API**.

The app uses only Android's own classes (`HttpURLConnection`, `org.json`); it has no library.

## How it talks to Model Shelf

| Step | What happens |
|---|---|
| Connect | `startActivityForResult(Intent("io.github.johnrocky.modelshelf.action.CONNECT").setPackage("io.github.johnrocky.modelshelf"))`. Model Shelf names the calling app and asks the user. On **Allow** it starts its local API and returns `RESULT_OK` with the extras `base_url` (`http://127.0.0.1:11434`) and `token`. On **Deny** it returns `RESULT_CANCELED` with `error` = `denied`. |
| Every request | `Authorization: Bearer <token>`. The token belongs to this app; the user can remove it in Model Shelf (Settings > Local API). |
| Model list | `GET /api/tags` fills the model field. |
| Ollama's form | `POST /api/chat` with `"stream": true` and no sampler settings: Model Shelf uses the model's own defaults. The answer comes as one JSON object per line; the last line has `"done": true`. |
| OpenAI's form | `POST /v1/chat/completions` with `"stream": true` and `"temperature": 0`. The answer comes as server-sent events; the last one is `data: [DONE]`. |

What the app shows when a request fails:

| Case | Model Shelf's answer | The app says |
|---|---|---|
| The token was removed in Model Shelf | HTTP 401 | Tap Connect to Model Shelf to reconnect. |
| The local API is off | connection refused | Model Shelf's local API is off. Tap Connect to Model Shelf to turn it on. |
| The model is not on the phone | HTTP 404 and one sentence | That sentence. |
| Model Shelf is busy with other requests | HTTP 503 and `Retry-After` | Try again in N s. |

Plain HTTP to `127.0.0.1` needs `res/xml/network_security_config.xml`, which allows cleartext to
`127.0.0.1` and `localhost` only. The requests never leave the phone.
