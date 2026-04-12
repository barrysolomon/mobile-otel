// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.debug

import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Configuration for [DebugWidgetInstrumentation].
 *
 * The debug widget renders a draggable badge overlay on every activity that shows
 * live SDK state (buffer occupancy, export status, recovery type) and device health
 * (battery, memory, network). Tap the badge to expand a detail card; long-press and
 * drag to reposition.
 *
 * **This is an incubating module** -- not part of the OTel spec. Intended for development
 * and demo builds only. Disabled by default.
 *
 * @property enabled Whether the debug widget overlay is active.
 * @property refreshIntervalMs How often the widget refreshes its data, in milliseconds.
 * @property initialCorner Which screen corner the badge appears in on first launch.
 */
@Incubating
data class DebugWidgetConfig(
    val enabled: Boolean = false,
    val refreshIntervalMs: Long = 2000,
    val initialCorner: Corner = Corner.TOP_RIGHT
) {
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    init {
        require(refreshIntervalMs in 500..30_000) { "refreshIntervalMs must be in 500..30000" }
    }
}
