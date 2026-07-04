/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import android.net.Network
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals

/**
 * NF-002: Adapter forwards Android's `ConnectivityManager.NetworkCallback` events
 * to a [NetworkAvailabilityWatcher].
 *
 * The adapter is intentionally trivial — its only job is to bridge the Android
 * callback surface to the watcher's pure state-machine API. Production code
 * registers it with `ConnectivityManager.registerDefaultNetworkCallback(...)`;
 * tests instantiate it directly and invoke the callback methods.
 */
class NetworkAvailabilityCallbackAdapterTest {

    private class RecordingWatcherListener : NetworkAvailabilityWatcher.Listener {
        val events = mutableListOf<NetworkAvailabilityWatcher.Transition>()
        override fun onTransition(transition: NetworkAvailabilityWatcher.Transition) {
            events.add(transition)
        }
    }

    @Test
    fun `onAvailable after onLost forwards to watcher and emits Restored`() {
        val watcher = NetworkAvailabilityWatcher()
        val listener = RecordingWatcherListener()
        watcher.addListener(listener)
        val adapter = NetworkAvailabilityCallbackAdapter(watcher)
        val fakeNetwork = mockk<Network>(relaxed = true)

        adapter.onLost(fakeNetwork)
        adapter.onAvailable(fakeNetwork)

        assertEquals(1, listener.events.size)
        assertEquals(NetworkAvailabilityWatcher.Transition.Restored, listener.events[0])
    }

    @Test
    fun `onAvailable without prior onLost emits nothing`() {
        val watcher = NetworkAvailabilityWatcher()
        val listener = RecordingWatcherListener()
        watcher.addListener(listener)
        val adapter = NetworkAvailabilityCallbackAdapter(watcher)
        val fakeNetwork = mockk<Network>(relaxed = true)

        adapter.onAvailable(fakeNetwork)
        adapter.onAvailable(fakeNetwork)

        assertEquals(0, listener.events.size)
    }
}
