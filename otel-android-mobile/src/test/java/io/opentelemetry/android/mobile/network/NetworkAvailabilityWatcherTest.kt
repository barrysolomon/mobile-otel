/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import org.junit.Test
import kotlin.test.assertEquals

/**
 * NF-001: The watcher emits a `Restored` event only on the LOST → AVAILABLE transition.
 *
 * Why this matters: `ConnectivityManager.NetworkCallback.onAvailable` fires for many
 * non-transition reasons (Wi-Fi handoff, network validation, callback registration).
 * We must filter those out so we don't trigger a flush on every callback.
 *
 * See: docs/epics/NETWORK_RESTORED_FLUSH_EPIC.md
 */
class NetworkAvailabilityWatcherTest {

    private class RecordingListener : NetworkAvailabilityWatcher.Listener {
        val events = mutableListOf<NetworkAvailabilityWatcher.Transition>()
        override fun onTransition(transition: NetworkAvailabilityWatcher.Transition) {
            events.add(transition)
        }
    }

    @Test
    fun `LOST then AVAILABLE emits one Restored transition`() {
        val listener = RecordingListener()
        val watcher = NetworkAvailabilityWatcher()
        watcher.addListener(listener)

        // Initial state is unknown; first AVAILABLE alone is not a "restored" event
        watcher.onAvailable()
        assertEquals(0, listener.events.size, "First onAvailable from unknown state must not emit Restored")

        watcher.onLost()
        watcher.onAvailable()

        assertEquals(1, listener.events.size, "LOST → AVAILABLE must emit exactly one Restored event")
        assertEquals(NetworkAvailabilityWatcher.Transition.Restored, listener.events[0])
    }

    @Test
    fun `back-to-back AVAILABLE calls emit no transitions`() {
        val listener = RecordingListener()
        val watcher = NetworkAvailabilityWatcher()
        watcher.addListener(listener)

        watcher.onAvailable()
        watcher.onAvailable()
        watcher.onAvailable()

        assertEquals(0, listener.events.size, "Consecutive AVAILABLE callbacks must not emit Restored")
    }

    @Test
    fun `back-to-back LOST calls do not emit duplicate transitions`() {
        val listener = RecordingListener()
        val watcher = NetworkAvailabilityWatcher()
        watcher.addListener(listener)

        watcher.onLost()
        watcher.onLost()
        watcher.onAvailable()

        assertEquals(1, listener.events.size, "Duplicate LOST then AVAILABLE must still emit only one Restored")
    }

    @Test
    fun `multiple LOST AVAILABLE cycles each emit one Restored`() {
        val listener = RecordingListener()
        val watcher = NetworkAvailabilityWatcher()
        watcher.addListener(listener)

        watcher.onLost(); watcher.onAvailable()
        watcher.onLost(); watcher.onAvailable()
        watcher.onLost(); watcher.onAvailable()

        assertEquals(3, listener.events.size, "Each LOST → AVAILABLE cycle must emit one Restored")
        listener.events.forEach { assertEquals(NetworkAvailabilityWatcher.Transition.Restored, it) }
    }

    @Test
    fun `removed listener stops receiving events`() {
        val listener = RecordingListener()
        val watcher = NetworkAvailabilityWatcher()
        watcher.addListener(listener)
        watcher.onLost(); watcher.onAvailable()
        assertEquals(1, listener.events.size)

        watcher.removeListener(listener)
        watcher.onLost(); watcher.onAvailable()
        assertEquals(1, listener.events.size, "After removeListener, no further transitions must arrive")
    }
}
