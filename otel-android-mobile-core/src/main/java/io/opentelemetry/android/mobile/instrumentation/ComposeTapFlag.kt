// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Coordination flag between ComposeClickInstrumentation and TapInstrumentation
 * to prevent duplicate tap events when both are active on a screen that mixes
 * Compose and View-based UI.
 *
 * When the Compose module resolves a tap target, it calls [markHandled].
 * The Tap module checks [wasHandledRecently] before emitting — if the Compose
 * module already handled this tap, the Tap module skips emission.
 *
 * Uses timestamp-based expiry as a failsafe: if the flag is not reset
 * (e.g., touch sequence interrupted), it auto-expires after [expiryNanos].
 *
 * Thread safety: both modules run on the main thread only. No synchronization needed.
 */
object ComposeTapFlag {
    /** Maximum age in nanoseconds before the flag is considered stale. Default 500ms. */
    @JvmField var expiryNanos: Long = 500_000_000L

    @JvmField var handledAtNanos: Long = 0L

    fun markHandled() {
        handledAtNanos = System.nanoTime()
    }

    fun wasHandledRecently(): Boolean {
        val elapsed = System.nanoTime() - handledAtNanos
        return elapsed in 1..expiryNanos
    }
}
