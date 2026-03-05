/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.Executors

class RecoveryTracker(
    private val application: Application,
    private val logger: Logger,
    private val provider: MobileLoggerProvider,
    private val sessionTracker: SessionTracker
) : ComponentCallbacks2 {
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val backgroundExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OTel-Recovery").apply { isDaemon = true }
    }

    @Volatile
    private var lastRecoveryType: String = "clean_start"

    fun start() {
        application.registerComponentCallbacks(this)
        installCrashHandler()
        handleRecovery()
        markSessionActive()
    }

    fun stop() {
        application.unregisterComponentCallbacks(this)
        backgroundExecutor.shutdownNow()
    }

    fun markCleanShutdown() {
        prefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .putLong(KEY_LAST_SESSION_END_MS, System.currentTimeMillis())
            .apply()
    }

    fun getLastRecoveryType(): String = lastRecoveryType

    fun markCrashForNextStart() {
        prefs.edit().putBoolean(KEY_CRASH_MARKER, true).apply()
    }

    fun markLowMemoryForNextStart() {
        prefs.edit().putBoolean(KEY_LOW_MEMORY_MARKER, true).apply()
    }

    fun markAnrForNextStart() {
        prefs.edit().putBoolean(KEY_ANR_MARKER, true).apply()
    }

    fun clearAnrMarker() {
        prefs.edit().remove(KEY_ANR_MARKER).apply()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            prefs.edit().putBoolean(KEY_LOW_MEMORY_MARKER, true).apply()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() {
        prefs.edit().putBoolean(KEY_LOW_MEMORY_MARKER, true).apply()
    }

    private fun handleRecovery() {
        val sessionWasActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
        val wasCrash = prefs.getBoolean(KEY_CRASH_MARKER, false)
        val wasLowMemory = prefs.getBoolean(KEY_LOW_MEMORY_MARKER, false)
        val wasAnr = prefs.getBoolean(KEY_ANR_MARKER, false)

        lastRecoveryType = when {
            wasCrash -> "crash"
            wasAnr -> "anr_force_kill"
            wasLowMemory -> "low_memory_kill"
            sessionWasActive -> "system_force_kill"
            else -> "clean_start"
        }

        if (lastRecoveryType != "clean_start") {
            val lastSessionEnd = prefs.getLong(KEY_LAST_SESSION_END_MS, 0)
            val downtimeMs = if (lastSessionEnd > 0) System.currentTimeMillis() - lastSessionEnd else 0L

            if (wasAnr) {
                logger.logRecordBuilder()
                    .setBody("app.anr")
                    .setSeverity(Severity.ERROR)
                    .setAllAttributes(
                        Attributes.builder()
                            .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                            .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                            .put(AttributeKey.stringKey("anr.user_action"), "force_close")
                            .build()
                    )
                    .emit()
            }

            if (wasCrash) {
                logger.logRecordBuilder()
                    .setBody("app.crash")
                    .setSeverity(Severity.ERROR)
                    .setAllAttributes(
                        Attributes.builder()
                            .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                            .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                            .put(AttributeKey.stringKey("recovery_type"), lastRecoveryType)
                            .put(AttributeKey.stringKey("error.type"), "uncaught_exception")
                            .build()
                    )
                    .emit()
            }

            logger.logRecordBuilder()
                .setBody("app.recovery")
                .setSeverity(Severity.WARN)
                .setAllAttributes(
                    Attributes.builder()
                        .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                        .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                        .put(AttributeKey.stringKey("recovery_type"), lastRecoveryType)
                        .put(AttributeKey.longKey("downtime_ms"), downtimeMs)
                        .build()
                )
                .emit()

            backgroundExecutor.submit {
                provider.forceFlush(30)
            }
        }

        prefs.edit()
            .remove(KEY_CRASH_MARKER)
            .remove(KEY_LOW_MEMORY_MARKER)
            .remove(KEY_ANR_MARKER)
            .apply()
    }

    private fun markSessionActive() {
        prefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putLong(KEY_LAST_SESSION_START_MS, System.currentTimeMillis())
            .apply()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = UncaughtExceptionHandler { thread, throwable ->
            prefs.edit().putBoolean(KEY_CRASH_MARKER, true).apply()
            previous?.uncaughtException(thread, throwable)
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    companion object {
        private const val PREFS_NAME = "otel_recovery"
        private const val KEY_SESSION_ACTIVE = "session_active"
        private const val KEY_CRASH_MARKER = "crash_marker"
        private const val KEY_LOW_MEMORY_MARKER = "low_memory_marker"
        private const val KEY_ANR_MARKER = "anr_marker"
        private const val KEY_LAST_SESSION_START_MS = "last_session_start_ms"
        private const val KEY_LAST_SESSION_END_MS = "last_session_end_ms"
    }
}
