// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    private val renewalMs: Long = 30 * 60 * 1000L,
    private val meter: Meter? = null
) : MobileSessionProvider {

    private val sessionId = AtomicReference(UUID.randomUUID().toString())
    private val viewCounter = AtomicLong(0)
    private val currentViewId = AtomicReference("view-0")
    private val currentScreen = AtomicReference<String?>(null)
    private val previousScreen = AtomicReference<String?>(null)
    private val screenEnteredAtMs = AtomicLong(System.currentTimeMillis())

    private val lastBackgroundAtMs = AtomicLong(0L)

    /** Release health: true if the current session has experienced an error/crash. */
    private val hadError = AtomicBoolean(false)

    override fun getSessionId(): String = sessionId.get()

    override fun getViewId(): String = currentViewId.get()

    override fun getCurrentScreenName(): String? = currentScreen.get()

    override fun getPreviousScreenName(): String? = previousScreen.get()

    override fun getTimeOnScreenMs(): Long = System.currentTimeMillis() - screenEnteredAtMs.get()

    override fun onScreenView(screenName: String) {
        previousScreen.set(currentScreen.get())
        currentScreen.set(screenName)
        screenEnteredAtMs.set(System.currentTimeMillis())
        val count = viewCounter.incrementAndGet()
        currentViewId.set("view-$count")
    }

    override fun onAppForeground(timestampMs: Long): Boolean {
        val lastBg = lastBackgroundAtMs.get()
        if (lastBg == 0L) return false
        val elapsed = timestampMs - lastBg
        val renewed = elapsed > renewalMs
        if (renewed) {
            emitCrashFreeMetric()
            sessionId.set(UUID.randomUUID().toString())
            hadError.set(false)
        }
        return renewed
    }

    override fun onAppBackground(timestampMs: Long) {
        emitCrashFreeMetric()
        lastBackgroundAtMs.set(timestampMs)
    }

    override fun markSessionError() {
        hadError.set(true)
    }

    override fun sessionHadError(): Boolean = hadError.get()

    /**
     * Emit a `mobile.session.crash_free` gauge: 1 = no errors this session, 0 = had errors.
     * The session ID is attached as an attribute so backends can compute crash-free session rates.
     */
    private fun emitCrashFreeMetric() {
        meter?.let { m ->
            val value = if (hadError.get()) 0L else 1L
            m.gaugeBuilder("mobile.session.crash_free")
                .setDescription("1 if the session had no errors, 0 if it had errors")
                .ofLongs()
                .build()
                .set(value, Attributes.of(
                    AttributeKey.stringKey("session.id"), sessionId.get()
                ))
        }
    }
}
