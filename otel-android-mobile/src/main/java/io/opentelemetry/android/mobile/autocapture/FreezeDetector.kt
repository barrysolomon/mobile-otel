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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class FreezeDetector(
    private val logger: Logger,
    private val provider: MobileLoggerProvider,
    private val sessionTracker: SessionTracker,
    private val options: AutoCaptureOptions,
    private val onAnrDetected: (() -> Unit)? = null,
    private val onAnrRecovered: (() -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OTel-FreezeWatchdog").apply { isDaemon = true }
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
        lastTickAtMs = SystemClock.uptimeMillis()
        mainHandler.post(tick)
        future = scheduler.scheduleAtFixedRate(
            { checkFreeze() },
            250,
            250,
            TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        future?.cancel(false)
        scheduler.shutdownNow()
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
        val pending = synchronized(pendingLock) {
            val value = pendingFreeze
            pendingFreeze = null
            value
        } ?: return

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
            .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
            .put(AttributeKey.longKey("ui.freeze.delay_ms"), pending.delayMs)
            .apply {
                if (pending.screenName != null) {
                    put(AttributeKey.stringKey("screen.name"), pending.screenName)
                }
            }
            .build()

        logger.logRecordBuilder()
            .setBody("ui.freeze")
            .setSeverity(Severity.WARN)
            .setAllAttributes(attributes)
            .emit()

        if (pending.isAnr) {
            val anrAttributes = Attributes.builder()
                .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                .put(AttributeKey.longKey("anr.delay_ms"), pending.delayMs)
                .put(AttributeKey.stringKey("anr.user_action"), "user_waited")
                .apply {
                    if (pending.screenName != null) {
                        put(AttributeKey.stringKey("screen.name"), pending.screenName)
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

        scheduler.execute {
            provider.forceFlush()
        }
    }

    private data class PendingFreeze(
        val delayMs: Long,
        val isAnr: Boolean,
        val screenName: String?
    )
}
