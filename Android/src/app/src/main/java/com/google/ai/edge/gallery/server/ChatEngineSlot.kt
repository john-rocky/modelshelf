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

import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The app's chat engines: one at a time in the process, however many screens are open, none
 * included.
 *
 * A chat engine of a large model takes gigabytes, and a second one (made by a second Model Shelf
 * screen, or left behind by a screen that is gone) got Model Shelf killed for memory in the
 * foreground on an 8 GB phone. So every chat engine is made through [make], which first closes every
 * chat engine still alive (another screen's, a closed screen's, or an old one of the same model)
 * and makes one engine at a time. [close] closes an engine once, whoever asks first. While a chat
 * engine answers, [make] closes nothing and makes nothing, and says whose engine answers.
 *
 * The local API's engines are not counted here: [EngineOwnership] keeps the app's chat and the
 * local API off one model file, as before.
 *
 * @param K the owner of an engine (the app's model object), compared by identity
 * @param I an engine, compared by identity
 * @param isAnswering whether an engine runs an answer now
 * @param closeEngine closes an engine of an owner; called once per engine, with the slot's lock held
 */
class ChatEngineSlot<K : Any, I : Any>(
  private val isAnswering: (I) -> Boolean,
  private val closeEngine: (owner: K, engine: I) -> Unit,
) {
  private val lock = ReentrantLock()
  private val madeOrFailed = lock.newCondition()
  private var making = false
  private val live = IdentityHashMap<I, K>()

  // Model Shelf (D-614 round 10d): the owner of the engine being made, and whether its screen went
  // away while it was made ([screensGone]); and whether a link holds the slot ([hold]).
  private var makingOwner: K? = null
  private var makingGone = false
  // Volatile: [release] runs on the main thread and takes no lock (review finding 11).
  @Volatile private var held = false

  /** What [make] did. */
  sealed interface Result<out K, out I> {
    /** [engine] is made and alive; the engines of [closedFirst] were closed before it was made. */
    data class Made<K, I>(val engine: I, val closedFirst: List<K>) : Result<K, I>

    /** Nothing was closed or made: the engine of [owner] is answering. */
    data class Answering<K>(val owner: K) : Result<K, Nothing>

    /**
     * The engine was made, but [owner]'s screen went away while it was made ([screensGone]): it
     * was closed at once, and no engine is alive.
     */
    data class Gone<K>(val owner: K) : Result<K, Nothing>
  }

  /** The chat engines alive now: 0 or 1. */
  val liveCount: Int
    get() = lock.withLock { live.size }

  /** Whether a chat engine alive now answers. */
  fun anyAnswering(): Boolean = lock.withLock { live.keys.any { isAnswering(it) } }

  /**
   * Makes [owner]'s engine with [create], after closing every chat engine alive ([closing] is told
   * each owner right after its engine is closed), unless one of them answers: then nothing is
   * closed or made. [publish] gets the new engine before any other caller can close it. One make
   * runs at a time; another one waits for it. If [create] throws, no engine is alive and the
   * exception goes to the caller.
   */
  fun make(
    owner: K,
    create: () -> I,
    publish: (I) -> Unit = {},
    closing: (K) -> Unit = {},
  ): Result<K, I> {
    val closedFirst = mutableListOf<K>()
    lock.withLock {
      while (making) madeOrFailed.awaitUninterruptibly()
      for ((engine, other) in live) {
        if (isAnswering(engine)) return Result.Answering(other)
      }
      for ((engine, other) in live.entries.toList()) {
        live.remove(engine)
        closeEngine(other, engine)
        closedFirst.add(other)
        closing(other)
      }
      making = true
      makingOwner = owner
      makingGone = false
    }
    try {
      val engine = create()
      lock.withLock {
        if (makingGone) {
          // Model Shelf: its screen is gone for good; no screen would ever close it (D-614 round
          // 10d, review finding 10).
          closeEngine(owner, engine)
          return Result.Gone(owner)
        }
        live[engine] = owner
        publish(engine)
      }
      return Result.Made(engine, closedFirst)
    } finally {
      lock.withLock {
        making = false
        makingOwner = null
        makingGone = false
        madeOrFailed.signalAll()
      }
    }
  }

  /**
   * Model Shelf: the screens of the owners [isOwner] picks are gone for good. Returns the engines
   * they hold now (for the caller to close); an engine still being made for one of them is closed
   * as soon as it is made ([Result.Gone]).
   */
  fun screensGone(isOwner: (K) -> Boolean): List<I> =
    lock.withLock {
      if (making && makingOwner?.let(isOwner) == true) makingGone = true
      live.entries.filter { isOwner(it.value) }.map { it.key }
    }

  /**
   * Model Shelf: starts a turn on [engine] under the slot's lock: [start] raises the engine's
   * "answering" mark, so [make], [anyAnswering] and [hold] see it before anything is sent (D-614
   * round 10d, review finding 7). Returns null, and [start] does not run, when the engine is not
   * alive (closed) or the slot is held.
   */
  fun <R> startTurn(engine: I, start: () -> R): R? =
    lock.withLock { if (held || !live.containsKey(engine)) null else start() }

  /**
   * Model Shelf: holds the slot, unless [refusal] (told whether an engine alive answers, and how
   * many are alive) gives a reason, which is returned. While the slot is held no turn starts
   * ([startTurn]); [release] ends it. A link holds it while it replaces the open screen.
   */
  fun <R : Any> hold(refusal: (answering: Boolean, liveCount: Int) -> R?): R? =
    lock.withLock {
      val reason = refusal(live.keys.any { isAnswering(it) }, live.size)
      if (reason == null) held = true
      reason
    }

  /** Model Shelf: ends [hold], without waiting for the lock (an engine may be closing under it). */
  fun release() {
    held = false
  }

  /** Closes [engine] if it is alive. False when it was closed already, or was never made here. */
  fun close(engine: I): Boolean =
    lock.withLock {
      val owner = live.remove(engine) ?: return@withLock false
      closeEngine(owner, engine)
      true
    }

  /** The engines alive now whose owner [isOwner] picks. */
  fun enginesOf(isOwner: (K) -> Boolean): List<I> =
    lock.withLock { live.entries.filter { isOwner(it.value) }.map { it.key } }

  /** The owner of [engine] while it is alive, else null. */
  fun ownerOf(engine: I): K? = lock.withLock { live[engine] }
}

/** Thrown by [makeForChat] when the local API holds the model file. */
class ApiHoldsModelFile(path: String) : IllegalStateException("The local API holds $path")

/**
 * The app chat's way to an engine for the model file at [path]: the chat-and-API rule of
 * [EngineOwnership], then the slot. While the local API holds the file, nothing is closed or made
 * ([ApiHoldsModelFile]). Else [ChatEngineSlot.make]: the file is reserved for the chat of [name]
 * right before [create], after the other chat engines are closed (closing one gives back its
 * reservation), and given back when no engine is made.
 */
fun <K : Any, I : Any> ChatEngineSlot<K, I>.makeForChat(
  owner: K,
  name: String,
  path: String,
  create: () -> I,
  publish: (I) -> Unit = {},
  closing: (K) -> Unit = {},
): ChatEngineSlot.Result<K, I> {
  if (EngineOwnership.apiHolds(path)) throw ApiHoldsModelFile(path)
  try {
    return make(
      owner = owner,
      create = {
        if (!EngineOwnership.reserveForChat(name, path)) throw ApiHoldsModelFile(path)
        create()
      },
      publish = publish,
      closing = closing,
    )
  } catch (e: Exception) {
    EngineOwnership.releaseForChat(name)
    throw e
  }
}
