/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Strategy for evicting events when the offline budget is exceeded.
 */
@Incubating
enum class EvictionStrategy {
    OLDEST_FIRST,
    LOWEST_SEVERITY_FIRST
}

/**
 * Configuration for offline disk budget management.
 *
 * When the device is offline, this limits how much disk space buffered events
 * can consume. This prevents unbounded disk growth in scenarios with extended
 * offline periods (e.g., industrial mobile, airplane mode).
 *
 * The offline budget is always less than or equal to the total disk buffer size.
 * When the budget is exceeded, events are evicted per the configured strategy.
 */
@Incubating
data class OfflineBudgetConfig(
    val maxOfflineDiskBytes: Long = 10L * 1024 * 1024,
    val evictionStrategy: EvictionStrategy = EvictionStrategy.OLDEST_FIRST,
    val enabled: Boolean = true
) {
    init {
        require(maxOfflineDiskBytes > 0) { "maxOfflineDiskBytes must be positive" }
    }

    companion object {
        fun default() = OfflineBudgetConfig()
        fun disabled() = OfflineBudgetConfig(enabled = false)
    }
}
