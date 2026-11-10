# Caller Sample

An Android app with no model of its own. It gets answers from the models on the same phone
through Model Shelf's local API, without a network connection.

One screen: **Connect to Model Shelf**, a prompt, **Ask via Ollama API** and **Ask via OpenAI API**.
The answer streams onto the screen with the HTTP status, the time to the first text and the total
time.

Below them, **Speech to text**: **Pick a WAV file** or **Use the example**, then **Transcribe**.
The text comes back with the HTTP status, the backend Model Shelf ran the speech model on, the
audio length, the inference time and the total time. The example is LibriSpeech test-clean
utterance 5142-33396-0056 (3.1 s, CC BY 4.0, `res/raw/example_librispeech.wav`).

Below that, **Text to speech**: a voice model (filled from Model Shelf's list), a voice (empty = the
model's default), your text, then **Speak**. The audio plays with the HTTP status, the backend, the
voice, the audio length, the synthesis time and the total time.

## Build and run

1. Install Model Shelf (`Android/src` in this repo) and download a model in it.
2. From this folder: `./gradlew assembleDebug`, then
   `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Open Caller Sample, tap **Connect to Model Shelf**, then **Allow** in Model Shelf.
4. Tap **Ask via Ollama API** or **Ask via OpenAI API**, **Transcribe** with a speech model in
   Model Shelf, or **Speak** with a voice model in Model Shelf.

The app uses only Android's own classes (`java.net.Socket`, `javax.crypto`, `org.json`); it has no
library.

## How it talks to Model Shelf

| Step | What happens |
|---|---|
| Connect | `startActivityForResult(Intent("io.github.johnrocky.modelshelf.action.CONNECT").setPackage("io.github.johnrocky.modelshelf"))`. Model Shelf names the calling app and asks the user. On **Allow** it starts its local API and returns `RESULT_OK` with the extras `base_url` (`http://127.0.0.1:11434`), `token` and `verify_key` (this app's secret for the check below, 32 bytes as base64url). On **Deny** it returns `RESULT_CANCELED` with `error` = `denied`. |
| Every request | First the check below, then, on the same connection, the request with `Authorization: Bearer <token>`. The token belongs to this app; the user can remove it in Model Shelf (Settings > Local API). |
| Model lists | `GET /api/tags` fills the model field. `GET /v1/models` fills the speech model field (the entries whose `modelshelf.kind` is `transcription`) and the voice model field (`speech`, with its `default_voice`). |
| Ollama's form | `POST /api/chat` with `"stream": true` and no sampler settings: Model Shelf uses the model's own defaults. The answer comes as one JSON object per line; the last line has `"done": true`. |
| OpenAI's form | `POST /v1/chat/completions` with `"stream": true` and `"temperature": 0`. The answer comes as server-sent events; the last one is `data: [DONE]`. |
| Speech to text | `POST /v1/audio/transcriptions`, `multipart/form-data` with `model`, `response_format` = `json` and `file`. The answer is `{"text": "..."}`; the headers `X-ModelShelf-Backend`, `X-ModelShelf-Audio-Ms` and `X-ModelShelf-Inference-Ms` give the backend, the audio length and the inference time. |
| Text to speech | `POST /v1/audio/speech` with `model`, `input` (at most 4,096 characters), `voice` when the field is not empty, and `response_format` = `wav`. Model Shelf speaks the text one sentence at a time. The WAV file (24 kHz, mono, 16-bit) comes after the last sentence, so the read timeout must cover the whole synthesis (Model Shelf stops at 300 s; this app waits 400 s). With `response_format` = `pcm`, each sentence comes as soon as it is spoken (chunked). The app plays the WAV with `AudioTrack`; the headers `X-ModelShelf-Backend`, `X-ModelShelf-Voice`, `X-ModelShelf-Audio-Ms` and `X-ModelShelf-Inference-Ms` give the backend, the voice, the audio length and the synthesis time. An answer the app stops reading is cut after 55 s; a cut answer ends short of its `Content-Length` or without its last chunk, so a reader can tell it from a whole one. |

What the app shows when a request fails:

| Case | Model Shelf's answer | The app says |
|---|---|---|
| The token was removed in Model Shelf | HTTP 401 | Tap Connect to Model Shelf to reconnect. |
| The local API is off | connection refused | Model Shelf's local API is off. Tap Connect to Model Shelf to turn it on. |
| The model is not on the phone | HTTP 404 and one sentence | That sentence. |
| The file to transcribe is not a WAV file | HTTP 400 and one sentence | That sentence. |
| The text to speak is empty or longer than 4,096 characters, or the voice is not in the model | HTTP 400 and one sentence | That sentence. |
| Model Shelf is busy with other requests | HTTP 503 and `Retry-After` | Model Shelf's sentence, which says when to try again. |
| Model Shelf is reading aloud to its user (text to speech) | HTTP 503 and `Retry-After` | Model Shelf's sentence, which says when to try again. |
| Another program holds the port (Model Shelf's API is off) | the check fails | The server on this port did not prove it is Model Shelf, so nothing was sent. |
| The app has no `verify_key` (a token copied by hand has none) | nothing is sent | Tap Connect to Model Shelf. |

## Checking that it is Model Shelf

Any app on the phone can listen on `127.0.0.1:11434` while Model Shelf's local API is off. Before
the token or the user's text and audio go out, this app checks that the server holds the secret
Model Shelf gave it on **Allow** (`ModelShelfClient.kt`):

1. Open one TCP connection and send `GET /modelshelf/v1/verify?app=<package name>&nonce=<n>`, where
   `<n>` is 16 random bytes as base64url. This request has no token.
2. Model Shelf answers `{"service": "Model Shelf", "verify": 1, "app": "<package name>", "proof": "<p>"}`,
   where `<p>` is base64url of HMAC-SHA256(`verify_key`, `ModelShelf local API verify v1` ‖ 0x00 ‖
   package name ‖ 0x00 ‖ nonce), the texts in UTF-8.
3. Compute the same HMAC and compare in constant time. Only when it matches, send the request on
   the same connection (with `Connection: close`). Otherwise close the connection and send nothing.

The request must use the connection the check used: a check on one connection says nothing about
the program on the next one. Model Shelf answers the check for any package name in the same form
(a package it does not know gets a proof made with a key nobody has), so the check tells a caller
nothing about which apps are connected. Removing the app in Model Shelf deletes its key. A token
copied by hand in Model Shelf comes without a key and cannot be checked; use it only from a computer
you control (curl), never in an app.

Plain HTTP to `127.0.0.1` needs `res/xml/network_security_config.xml`, which allows cleartext to
`127.0.0.1` and `localhost` only. The requests never leave the phone.

## Test harnesses

For tests only, and only in a debuggable build (`assembleDebug`): the activity is the exported
launcher, so any app can start it, and a release build ignores the extras below. The folder is one
plain name inside the app's external files folder (letters, digits, `.`, `_` and `-`; no `/`).

### Transcription

Started with the extra `harness_dir`, the app sends every file in that folder through the same
request code as **Transcribe**, one at a time, and writes one JSON line per request to
`results_<folder>.jsonl` next to the folder.

```sh
adb push audio/ /sdcard/Android/data/io.github.johnrocky.modelshelf.caller/files/audio/
adb shell am start -S -n io.github.johnrocky.modelshelf.caller/.MainActivity \
  --es harness_dir audio --es harness_model <speech model> --ez harness_warm true
adb pull /sdcard/Android/data/io.github.johnrocky.modelshelf.caller/files/results_audio.jsonl
```

Order: the first WAV file once, not counted (`harness_warm`, default true); every WAV file in name
order; every other file (Model Shelf should answer HTTP 400); the first WAV file to the model
`litert-community/not-on-this-phone` (HTTP 404). Connect to Model Shelf in the app first.

### Text to speech and the round trip

Started with the extra `speech_harness_dir`, the app reads `sentences.txt` (one sentence per line)
in that folder and sends through the same request code as **Speak** (nothing is played), then
through the code of **Transcribe**:

```sh
adb push sentences.txt /sdcard/Android/data/io.github.johnrocky.modelshelf.caller/files/speech/
adb shell am start -S -n io.github.johnrocky.modelshelf.caller/.MainActivity \
  --es speech_harness_dir speech --es speech_harness_tts_model <voice model> \
  --es speech_harness_asr_model <speech model>
adb pull /sdcard/Android/data/io.github.johnrocky.modelshelf.caller/files/results_speech.jsonl
```

Order: sentence 1 once, not counted (the model loads); every sentence as WAV, saved as
`speech_<n>.wav` in the folder; sentence 1 as `pcm`, saved as `speech_1.pcm`; an empty text, the
voice `no_such_voice`, a text of 4,097 characters and the model `litert-community/not-on-this-phone`
(Model Shelf should answer HTTP 400, 400, 400 and 404); then each `speech_<n>.wav` to the speech
model. `speech_harness_voice` picks a voice; without it Model Shelf uses the model's default.

### An app that stops reading

Started with the extra `stall_harness_dir`, the app sends `text.txt` from that folder as one speech
request (WAV, after the check) and then reads nothing for `stall_hold_s` seconds (default 240), as
an app that was frozen in the background would. Then it reads what is left and writes
`stall_<folder>.json` next to the folder: the status, the `Content-Length`, the bytes it got and how
the read ended (Model Shelf cuts an answer nobody reads 55 s after the synthesis ends).

```sh
adb push text.txt /sdcard/Android/data/io.github.johnrocky.modelshelf.caller/files/stall/
adb shell am start -S -n io.github.johnrocky.modelshelf.caller/.MainActivity \
  --es stall_harness_dir stall --es speech_harness_tts_model <voice model> --ei stall_hold_s 240
adb pull /sdcard/Android/data/io.github.johnrocky.modelshelf.caller/files/stall_stall.json
```
