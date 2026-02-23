/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

/**
 * Configuration for automatic UI capture.
 */
data class AutoCaptureOptions(
    val captureLifecycle: Boolean = true,
    val captureScreens: Boolean = true,
    val captureTaps: Boolean = true,
    val captureLongPress: Boolean = true,
    val captureScroll: Boolean = true,
    val captureBackPress: Boolean = true,
    val captureFragments: Boolean = true,
    val freezeDetectorEnabled: Boolean = true,
    val freezeThresholdMs: Long = 2000,
    val freezeCooldownMs: Long = 30000,
    val anrThresholdMs: Long = 5000,
    val scrollThrottleMs: Long = 500,
    val tapCoalesceWindowMs: Long = 800,
    val bucketGridSize: Int = 3,
    val maxHitTestDepth: Int = 12,
    val sessionRenewalMs: Long = 30 * 60 * 1000L,
    val privacyMode: PrivacyMode = PrivacyMode.STRICT,
    val hashSalt: String? = null,
    val allowlistedResourceIds: Set<String> = emptySet(),
    val denylistedResourceIds: Set<String> = emptySet(),
    val allowlistedViewClasses: Set<String> = emptySet(),
    val denylistedViewClasses: Set<String> = emptySet()
) {
    init {
        require(bucketGridSize >= 2) { "bucketGridSize must be >= 2" }
        require(maxHitTestDepth >= 1) { "maxHitTestDepth must be >= 1" }
        require(freezeThresholdMs >= 250) { "freezeThresholdMs must be >= 250ms" }
    }
}
