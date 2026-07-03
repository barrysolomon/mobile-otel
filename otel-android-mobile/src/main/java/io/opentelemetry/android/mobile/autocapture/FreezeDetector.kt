/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class FreezeDetector(
    private val logger: Logger,
    private val provider: MobileLoggerProvider,
    private val sessionTracker: SessionTracker,
    private val options: AutoCaptureOptions,
    private val onAnrDetected: (() -> Unit)? = null,
    private val onAnrRecovered: (() -> Unit)? = null,
    schedulerFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "OTel-FreezeWatchdog").apply { isDaemon = true }
        }
    }
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // Thread creation can fail on a thread-starved host (OutOfMemoryError:
    // "unable to create new native thread"). Instrumentation must never crash
    // the app, so a failed watchdog scheduler degrades to freeze detection
    // being disabled rather than aborting SDK init.
    private val scheduler: ScheduledExecutorService? = try {
        schedulerFactory()
    } catch (_: Throwable) {
        null
    }

    private val tick = Runnable { lastTickAtMs = SystemClock.uptimeMillis() }
    @Volatile
    private var lastTickAtMs: Long = 0

    @Volatile
    private var lastFreezeAtMs: Long = 0

    private var future: ScheduledFuture<*>? = null
    private val pendingLock = Any()
    private var pendingFreeze: PendingFreeze? = null

    fun start() {
        if (!options.freezeDetectorEnabled) return
        // Degraded mode: watchdog thread could not be created — freeze
        // detection is disabled, the host app keeps running.
        val activeScheduler = scheduler ?: return
        lastTickAtMs = SystemClock.uptimeMillis()
        mainHandler.post(tick)
        future = try {
            activeScheduler.scheduleAtFixedRate(
                { checkFreeze() },
                250,
                250,
                TimeUnit.MILLISECONDS
            )
        } catch (_: RejectedExecutionException) {
            // start() racing stop() — the watchdog simply stays off.
            null
        }
    }

    fun stop() {
        future?.cancel(false)
        scheduler?.shutdownNow()
    }

    private fun checkFreeze() {
        val now = SystemClock.uptimeMillis()
        val delay = now - lastTickAtMs
        if (delay < options.freezeThresholdMs) {
            mainHandler.post(tick)
            return
        }

        if (now - lastFreezeAtMs < options.freezeCooldownMs) {
            return
        }

        synchronized(pendingLock) {
            if (pendingFreeze != null) {
                return
            }
            lastFreezeAtMs = now
            pendingFreeze = PendingFreeze(
                delayMs = delay,
                isAnr = delay >= options.anrThresholdMs,
                screenName = sessionTracker.getCurrentScreenName()
            )
        }

        if (delay >= options.anrThresholdMs) {
            onAnrDetected?.invoke()
        }

        // Emit after main thread recovers.
        mainHandler.post { emitPendingFreeze() }
    }

    private fun emitPendingFreeze() {
        // Main thread has recovered — reset the tick baseline so the watchdog doesn't
        // immediately re-fire once the cooldown expires with an ever-growing stale delay.
        lastTickAtMs = SystemClock.uptimeMillis()
        mainHandler.post(tick)

        val pending = synchronized(pendingLock) {
            val value = pendingFreeze
            pendingFreeze = null
            value
        } ?: return

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("mobile.session.id"), sessionTracker.getSessionId())
            .put(AttributeKey.stringKey("mobile.view.id"), sessionTracker.getViewId())
            .put(AttributeKey.longKey("mobile.freeze.duration_ms"), pending.delayMs)
            .put(AttributeKey.longKey("ui.freeze.delay_ms"), pending.delayMs)  // legacy alias
            .apply {
                if (pending.screenName != null) {
                    put(AttributeKey.stringKey("mobile.screen.name"), pending.screenName)
                }
            }
            .build()

        logger.logRecordBuilder()
            .setBody("ui.freeze")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(attributes)
            .emit()

        if (pending.isAnr) {
            val anrAttributes = Attributes.builder()
                .put(AttributeKey.stringKey("mobile.session.id"), sessionTracker.getSessionId())
                .put(AttributeKey.stringKey("mobile.view.id"), sessionTracker.getViewId())
                .put(AttributeKey.longKey("mobile.freeze.duration_ms"), pending.delayMs)
                .put(AttributeKey.longKey("mobile.anr.delay_ms"), pending.delayMs)
                .put(AttributeKey.stringKey("mobile.anr.user_action"), "user_waited")
                .apply {
                    if (pending.screenName != null) {
                        put(AttributeKey.stringKey("mobile.screen.name"), pending.screenName)
                    }
                }
                .build()

            logger.logRecordBuilder()
                .setBody("app.anr")
                .setSeverity(Severity.ERROR)
                .setAllAttributes(anrAttributes)
                .emit()

            onAnrRecovered?.invoke()
        }

        // Policy evaluation (triggered by the ui.freeze / app.anr log records above) handles
        // the conditional flush in CONDITIONAL and HYBRID modes.  Calling forceFlush() here
        // would dump the entire buffer a second time and produce duplicate exports.
    }

    private data class PendingFreeze(
        val delayMs: Long,
        val isAnr: Boolean,
        val screenName: String?
    )
}
