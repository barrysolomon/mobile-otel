// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation


/**
 * Configuration for [TapInstrumentation].
 *
 * **Currently active fields:** [captureTaps], [captureLongPress], [captureSwipe], [swipeMinDistancePx].
 *
 * The output mode (log events vs. nested spans) is controlled globally via
 * [io.opentelemetry.android.mobile.config.UiTelemetryMode] in [io.opentelemetry.android.mobile.config.MobileConfig].
 *
 * **Reserved for future implementation:** [coalesceWindowMs], [bucketGridSize],
 * [maxHitTestDepth], [privacyMode], [hashSalt], [allowlistedResourceIds],
 * [denylistedResourceIds], [allowlistedViewClasses], [denylistedViewClasses].
 * Changing these fields currently has no effect.
 */
data class TapConfig(
    val captureTaps: Boolean = true,
    val captureLongPress: Boolean = true,
    val captureSwipe: Boolean = true,
    val swipeMinDistancePx: Float = 50f,
    val coalesceWindowMs: Long = 800,
    val bucketGridSize: Int = 3,
    val maxHitTestDepth: Int = 12,
    val privacyMode: PrivacyMode = PrivacyMode.STRICT,
    val hashSalt: String? = null,
    val allowlistedResourceIds: Set<String> = emptySet(),
    val denylistedResourceIds: Set<String> = emptySet(),
    val allowlistedViewClasses: Set<String> = emptySet(),
    val denylistedViewClasses: Set<String> = emptySet()
) {
    init {
        require(swipeMinDistancePx >= 0f) { "swipeMinDistancePx must be >= 0" }
        require(bucketGridSize >= 2) { "bucketGridSize must be >= 2" }
        require(maxHitTestDepth >= 1) { "maxHitTestDepth must be >= 1" }
    }
}
