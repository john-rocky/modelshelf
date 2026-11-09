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

package com.google.ai.edge.gallery.speech

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.server.makeOrClose
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "MSPhonon"

/**
 * The one Phonon-2.5-English pipeline of the process, made, used and closed on one thread, shared
 * by the local API and the app's voice input. Each user holds it ([acquire]) while it may need it
 * and lets go ([release]); the pipeline closes when nobody holds it.
 */
object PhononEngine {
  private val executor =
    Executors.newSingleThreadExecutor { r -> Thread(r, "ms-phonon").apply { isDaemon = true } }
  private val dispatcher = executor.asCoroutineDispatcher()

  // Only touched on the executor's thread.
  private var pipeline: PhononPipeline? = null
  private var holders = 0

  /** What a holder gets: how the encoder runs, and the load time when this call loaded it. */
  class Held(val backend: PhononFiles.Backend, val backendNote: String, val loadMs: Long)

  /** The backend while loaded, else null (for status lines; any thread). */
  @Volatile
  var loadedNote: String? = null
    private set

  /**
   * Holds the pipeline, loading it if nobody does. A caller cancelled while it loads still gets
   * its hold given back (the result of a plain withContext would be dropped, and the pipeline
   * would stay loaded with nobody to release it).
   */
  suspend fun acquire(context: Context, log: (String) -> Unit = {}): Held =
    makeOrClose(
      dispatcher,
      make = { acquireHere(context.applicationContext, log) },
      close = { releaseHere("its caller was cancelled while it loaded") },
    )

  fun acquireBlocking(context: Context, log: (String) -> Unit = {}): Held =
    onThread { acquireHere(context.applicationContext, log) }

  suspend fun release(why: String) = withContext(NonCancellable + dispatcher) { releaseHere(why) }

  fun releaseBlocking(why: String) = onThread { releaseHere(why) }

  /** Runs [block] with the pipeline; the caller must hold it. */
  suspend fun <T> run(block: (PhononPipeline) -> T): T = withContext(dispatcher) { block(current()) }

  fun <T> runBlocking(block: (PhononPipeline) -> T): T = onThread { block(current()) }

  private fun current(): PhononPipeline =
    pipeline ?: throw IllegalStateException("Phonon-2.5-English is not loaded")

  private fun acquireHere(context: Context, log: (String) -> Unit): Held {
    val loaded = pipeline
    if (loaded != null) {
      holders++
      return Held(loaded.backend, loaded.backendNote, 0L)
    }
    val present =
      PhononFiles.present(context)
        ?: throw IllegalStateException(
          "Phonon-2.5-English's files are not on this phone (${PhononFiles.dir(context).absolutePath})"
        )
    val p = PhononPipeline.load(context, present, log)
    pipeline = p
    holders = 1
    loadedNote = p.backendNote
    Log.i(TAG, "Loaded Phonon-2.5-English: encoder on ${p.backendNote} in ${p.loadMs} ms")
    return Held(p.backend, p.backendNote, p.loadMs)
  }

  private fun releaseHere(why: String) {
    if (holders > 0) holders--
    if (holders > 0) return
    val p = pipeline ?: return
    pipeline = null
    loadedNote = null
    p.close()
    Log.i(TAG, "Unloaded Phonon-2.5-English ($why)")
  }

  private fun <T> onThread(block: () -> T): T =
    try {
      executor.submit(Callable { block() }).get()
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    }
}
