/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import android.net.ConnectivityManager
import android.net.Network

/**
 * Bridges Android's [ConnectivityManager.NetworkCallback] events to a
 * [NetworkAvailabilityWatcher]. Production code registers an instance via
 * `connectivityManager.registerDefaultNetworkCallback(adapter)`; the
 * watcher applies the LOST → AVAILABLE transition filter.
 *
 * See: `docs/epics/NETWORK_RESTORED_FLUSH_EPIC.md` (NF-002).
 */
class NetworkAvailabilityCallbackAdapter(
    private val watcher: NetworkAvailabilityWatcher
) : ConnectivityManager.NetworkCallback() {

    override fun onAvailable(network: Network) {
        watcher.onAvailable()
    }

    override fun onLost(network: Network) {
        watcher.onLost()
    }
}
