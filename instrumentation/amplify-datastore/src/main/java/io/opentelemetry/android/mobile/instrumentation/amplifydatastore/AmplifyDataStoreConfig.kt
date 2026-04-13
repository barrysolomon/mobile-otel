// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import io.opentelemetry.android.mobile.instrumentation.Incubating

@Incubating
data class AmplifyDataStoreConfig(
    val enabled: Boolean = true,
    val captureOutboxEvents: Boolean = true,
    val captureSyncSpans: Boolean = true,
    val captureSyncMetrics: Boolean = true,
    val captureConflicts: Boolean = true,
    val attachNetworkState: Boolean = true,
    val syncTimeoutMs: Long = 60_000L
) {
    init {
        require(syncTimeoutMs > 0) { "syncTimeoutMs must be > 0, got $syncTimeoutMs" }
    }
}
