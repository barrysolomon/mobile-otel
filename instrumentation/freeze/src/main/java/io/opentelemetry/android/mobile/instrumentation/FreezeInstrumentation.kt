// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Detects main-thread freeze (jank / ANR) events.
 *
 * Uses a background watchdog that posts a tick to the main thread every 250ms.
 * If the tick is delayed by more than [FreezeConfig.freezeThresholdMs], a freeze event
 * is emitted. If the delay exceeds [FreezeConfig.anrThresholdMs], an ANR event is also emitted.
 */
class FreezeInstrumentation(
    private val config: FreezeConfig = FreezeConfig()
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.freeze"

    private var ctx: InstrumentationContext? = null
    private var logger: Logger? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OTel-FreezeWatchdog").apply { isDaemon = true }
    }

    @Volatile private var lastTickAtMs: Long = 0
    @Volatile private var lastFreezeAtMs: Long = 0
    @Volatile private var running: Boolean = false

    @Volatile private var watchdogFuture: ScheduledFuture<*>? = null

    private val tickRunnable = Runnable { lastTickAtMs = SystemClock.uptimeMillis() }

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return
        ctx = context
        logger = context.logger(instrumentationName)
        startWatchdog()
    }

    override fun uninstall() {
        stopWatchdog()
        ctx = null
        logger = null
    }

    private fun startWatchdog() {
        running = true
        lastTickAtMs = SystemClock.uptimeMillis()
        mainHandler.post(tickRunnable)
        watchdogFuture = scheduler.scheduleAtFixedRate(
            { checkFreeze() },
            250L,
            250L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopWatchdog() {
        running = false
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        scheduler.shutdownNow()
    }

    private fun checkFreeze() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val delay = now - lastTickAtMs
        if (delay < config.freezeThresholdMs) {
            mainHandler.post(tickRunnable)
            return
        }
        if (now - lastFreezeAtMs < config.cooldownMs) return
        lastFreezeAtMs = now

        val isAnr = delay >= config.anrThresholdMs
        val screenName = ctx?.sessionProvider?.getCurrentScreenName()

        // Emit after main thread recovers (so we know it's unblocked)
        mainHandler.post { emitFreeze(delay, isAnr, screenName) }
    }

    private fun emitFreeze(delayMs: Long, isAnr: Boolean, screenName: String?) {
        val log = logger ?: return
        val context = ctx ?: return
        val sessionProvider = context.sessionProvider

        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .put(AttributeKey.longKey("freeze.duration_ms"), delayMs)
            .apply {
                screenName?.let { put(MobileSemconv.SCREEN_NAME, it) }
            }
            .build()

        log.logRecordBuilder()
            .setBody("ui.freeze")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(attrs)
            .emit()

        if (isAnr) {
            log.logRecordBuilder()
                .setBody("app.anr")
                .setSeverity(Severity.ERROR)
                .setAllAttributes(attrs)
                .emit()
        }
    }

    /** Visible for testing — checks if the watchdog is currently running. */
    internal val isRunning: Boolean get() = running
}
