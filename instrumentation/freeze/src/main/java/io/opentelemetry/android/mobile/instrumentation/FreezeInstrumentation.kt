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
import io.opentelemetry.android.mobile.instrumentation.Incubating
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Detects main-thread freeze (jank / ANR) events.
 *
 * Uses a background watchdog that posts a tick to the main thread every 250ms.
 * If the tick is delayed by more than [FreezeConfig.freezeThresholdMs], a freeze is detected.
 *
 * **One event per freeze incident:** once a freeze is detected, [freezeInProgress] is set and
 * no further events are emitted until the main thread recovers (runs the pending tick). The event
 * is emitted *after* recovery, reporting the total freeze duration. This prevents the "freeze cycle"
 * where a blocked main thread causes repeated events with ever-growing duration_ms.
 */
@Incubating
@Supersedes("slowrendering")
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
    @Volatile private var running: Boolean = false
    // True while the main thread is known to be frozen — suppresses repeat events.
    @Volatile private var freezeInProgress: Boolean = false
    // Wall-clock time when the freeze started (last successful tick time).
    @Volatile private var freezeStartMs: Long = 0

    @Volatile private var watchdogFuture: ScheduledFuture<*>? = null

    private val tickRunnable = Runnable {
        val now = SystemClock.uptimeMillis()
        if (freezeInProgress) {
            // Main thread just recovered — compute actual duration and emit exactly one event.
            val duration = now - freezeStartMs
            freezeInProgress = false
            emitFreeze(duration, duration >= config.anrThresholdMs, ctx?.sessionProvider?.getCurrentScreenName())
        }
        lastTickAtMs = now
    }

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
            // Main thread is healthy — keep the heartbeat alive.
            freezeInProgress = false
            mainHandler.post(tickRunnable)
            return
        }

        if (!freezeInProgress) {
            // First detection of this freeze incident — record start and queue recovery callback.
            freezeInProgress = true
            freezeStartMs = lastTickAtMs
            // Post the tick to main thread; it will run when the freeze ends and emit the event.
            mainHandler.post(tickRunnable)
        }
        // freezeInProgress == true: main thread still blocked, do nothing more.
    }

    private fun emitFreeze(delayMs: Long, isAnr: Boolean, screenName: String?) {
        val log = logger ?: return
        val context = ctx ?: return
        val sessionProvider = context.sessionProvider

        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .put(AttributeKey.longKey("mobile.freeze.duration_ms"), delayMs)
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
