// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Default [MobileSessionProvider] implementation.
 *
 * Session IDs are UUID v4 strings. Sessions renew after the app has been
 * in the background for longer than [renewalMs] (default: 30 minutes).
 * View IDs are monotonically incrementing strings that change on each
 * [onScreenView] call.
 */
class DefaultMobileSessionProvider(
    private val renewalMs: Long = 30 * 60 * 1000L
) : MobileSessionProvider {

    private val sessionId = AtomicReference(UUID.randomUUID().toString())
    private val viewCounter = AtomicLong(0)
    private val currentViewId = AtomicReference("view-0")
    private val currentScreen = AtomicReference<String?>(null)

    private val lastBackgroundAtMs = AtomicLong(0L)

    override fun getSessionId(): String = sessionId.get()

    override fun getViewId(): String = currentViewId.get()

    override fun getCurrentScreenName(): String? = currentScreen.get()

    override fun onScreenView(screenName: String) {
        currentScreen.set(screenName)
        val count = viewCounter.incrementAndGet()
        currentViewId.set("view-$count")
    }

    override fun onAppForeground(timestampMs: Long): Boolean {
        val lastBg = lastBackgroundAtMs.get()
        if (lastBg == 0L) return false
        val elapsed = timestampMs - lastBg
        val renewed = elapsed > renewalMs
        if (renewed) {
            sessionId.set(UUID.randomUUID().toString())
        }
        return renewed
    }

    override fun onAppBackground(timestampMs: Long) {
        lastBackgroundAtMs.set(timestampMs)
    }
}
