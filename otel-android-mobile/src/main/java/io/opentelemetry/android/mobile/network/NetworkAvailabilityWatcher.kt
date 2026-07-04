/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks network availability transitions and notifies listeners only on
 * **genuine** LOST → AVAILABLE transitions.
 *
 * Why: `ConnectivityManager.NetworkCallback.onAvailable()` fires for many
 * non-transition reasons (Wi-Fi handoff, network validation, callback
 * registration). A naive "every onAvailable → flush" wiring would trigger
 * flushes constantly. This watcher applies a small state machine so that
 * only the offline→online edge produces a [Transition.Restored] event.
 *
 * Network-system wiring (the actual `ConnectivityManager.NetworkCallback`
 * adapter) is intentionally **not** in this class — it arrives in NF-002
 * so the state-machine logic can be unit-tested without Robolectric.
 *
 * See: `docs/epics/NETWORK_RESTORED_FLUSH_EPIC.md` (NF-001).
 */
class NetworkAvailabilityWatcher {

    /** Discrete network states the watcher tracks. */
    private enum class State { UNKNOWN, LOST, AVAILABLE }

    /** Transitions surfaced to listeners. Only [Restored] today; reserved enum for future events. */
    enum class Transition { Restored }

    /** Listener interface for transition events. */
    fun interface Listener {
        fun onTransition(transition: Transition)
    }

    private val state = AtomicReference(State.UNKNOWN)
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Called by the network-system adapter when the OS reports a network is available.
     * Emits [Transition.Restored] **only** if the previous state was [State.LOST].
     */
    fun onAvailable() {
        val previous = state.getAndSet(State.AVAILABLE)
        if (previous == State.LOST) {
            notifyListeners(Transition.Restored)
        }
    }

    /** Called by the network-system adapter when the OS reports the network is lost. */
    fun onLost() {
        state.set(State.LOST)
    }

    private fun notifyListeners(transition: Transition) {
        listeners.forEach { it.onTransition(transition) }
    }
}
