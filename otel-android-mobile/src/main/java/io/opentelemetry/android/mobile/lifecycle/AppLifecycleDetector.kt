/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.lifecycle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors

/**
 * Detects app lifecycle events: start, background, foreground, force close.
 *
 * Automatically logs lifecycle events and triggers device metrics capture.
 * Integrates with Application.ActivityLifecycleCallbacks for automatic detection.
 *
 * Usage:
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *
 *         val loggerProvider = MobileLoggerProvider.getInstance(this, config)
 *         val logger = loggerProvider.get("lifecycle")
 *
 *         // Register lifecycle detector
 *         AppLifecycleDetector.register(this, logger, config.deviceMetricsCaptureConfig)
 *     }
 * }
 * ```
 */
class AppLifecycleDetector private constructor(
    private val context: Context,
    private val logger: Logger,
    private val captureConfig: io.opentelemetry.android.mobile.metrics.DeviceMetricsCaptureConfig,
    private val metricsCollector: io.opentelemetry.android.mobile.metrics.DeviceMetricsCollector? = null,
    private val enableCrashRecoveryFlush: Boolean = true
) : Application.ActivityLifecycleCallbacks {

    private val isFirstStart = AtomicBoolean(true)
    private val activeActivities = AtomicLong(0)
    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private var lastBackgroundTime: Long = 0

    // Background executor for non-blocking flush operations
    private val backgroundExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLifecycle-Flush").apply {
            isDaemon = true
        }
    }

    companion object {
        private const val TAG = "AppLifecycleDetector"
        private const val PREFS_NAME = "otel_lifecycle"
        private const val KEY_CLEAN_SHUTDOWN = "clean_shutdown"
        private const val KEY_LAST_SESSION_END = "last_session_end"

        @Volatile
        private var instance: AppLifecycleDetector? = null

        /**
         * Registers the lifecycle detector with the application.
         *
         * Should be called in Application.onCreate().
         *
         * @param app Application instance
         * @param logger Logger for lifecycle events
         * @param captureConfig Configuration for when to capture device metrics
         * @param metricsCollector Optional metrics collector (if null, metrics not captured)
         * @param enableCrashRecoveryFlush Enable immediate flush of crash recovery data (default: true)
         */
        fun register(
            app: Application,
            logger: Logger,
            captureConfig: io.opentelemetry.android.mobile.metrics.DeviceMetricsCaptureConfig,
            metricsCollector: io.opentelemetry.android.mobile.metrics.DeviceMetricsCollector? = null,
            enableCrashRecoveryFlush: Boolean = true
        ) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val detector = AppLifecycleDetector(app, logger, captureConfig, metricsCollector, enableCrashRecoveryFlush)
                        app.registerActivityLifecycleCallbacks(detector)
                        instance = detector

                        // Defer force close check to background thread to avoid blocking startup
                        detector.deferredCheckForceClose()
                    }
                }
            }
        }

        /**
         * Gets the current instance if registered.
         */
        fun getInstance(): AppLifecycleDetector? = instance
    }

    /**
     * Deferred check for force close on background thread.
     * This avoids blocking app startup and gives time for providers to initialize.
     */
    private fun deferredCheckForceClose() {
        backgroundExecutor.submit {
            try {
                // Give app time to initialize
                Thread.sleep(1000)
                checkForceClose()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error in deferred force close check", e)
            }
        }
    }

    /**
     * Checks if previous session ended with force close (unclean shutdown).
     */
    private fun checkForceClose() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cleanShutdown = prefs.getBoolean(KEY_CLEAN_SHUTDOWN, true)
        val lastSessionEnd = prefs.getLong(KEY_LAST_SESSION_END, 0)

        if (!cleanShutdown && lastSessionEnd > 0) {
            val timeSinceForceClose = System.currentTimeMillis() - lastSessionEnd

            // Log force close event
            logger.logRecordBuilder()
                .setBody("app.force_close")
                .setSeverity(Severity.WARN)
                .setAllAttributes(
                    Attributes.of(
                        AttributeKey.longKey("time_since_force_close_ms"), timeSinceForceClose,
                        AttributeKey.longKey("last_session_end_timestamp"), lastSessionEnd,
                        AttributeKey.stringKey("lifecycle.event"), "force_close_detected"
                    )
                )
                .emit()

            android.util.Log.w(TAG, "Force close detected: app did not shut down cleanly")

            // Capture device metrics if configured
            if (captureConfig.onForceClose && metricsCollector != null) {
                metricsCollector.captureMetrics(
                    io.opentelemetry.android.mobile.metrics.CaptureReason.FORCE_CLOSE,
                    force = true
                )
            }

            // Force flush all buffered data (logs, traces, metrics) that survived the crash
            // This ensures disk-buffered spans/transactions from the crashed session are sent
            // Run on background thread to avoid blocking app startup
            if (!enableCrashRecoveryFlush) {
                android.util.Log.i(TAG, "Crash recovery flush disabled, data will be sent on next regular flush")
                return
            }

            backgroundExecutor.submit {
                try {
                    // Wait a bit for the app to fully initialize before attempting flush
                    Thread.sleep(2000)

                    android.util.Log.i(TAG, "Attempting to flush disk-buffered data from crashed session (background)")

                    // Try multiple times with delays in case provider isn't ready yet
                    var attempts = 0
                    val maxAttempts = 5
                    var provider: io.opentelemetry.android.mobile.MobileLoggerProvider? = null

                    while (attempts < maxAttempts && provider == null) {
                        provider = io.opentelemetry.android.mobile.MobileLoggerProvider.getInstanceOrNull()
                        if (provider == null) {
                            attempts++
                            android.util.Log.d(TAG, "Provider not ready yet, attempt $attempts/$maxAttempts")
                            Thread.sleep(1000)
                        }
                    }

                    if (provider != null) {
                        android.util.Log.i(TAG, "Provider ready, starting crash recovery flush (5-minute window)")
                        val flushResult = provider.forceFlush()
                        flushResult.whenComplete {
                            if (flushResult.isSuccess) {
                                android.util.Log.i(TAG, "Successfully flushed crash recovery data (5-minute window)")
                            } else {
                                android.util.Log.w(TAG, "Failed to flush crash recovery data")
                            }
                        }
                    } else {
                        android.util.Log.w(TAG, "MobileLoggerProvider not available after $maxAttempts attempts, skipping crash recovery flush")
                    }
                } catch (e: InterruptedException) {
                    android.util.Log.w(TAG, "Background flush interrupted", e)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error during background flush", e)
                }
            }
        }

        // Mark this session as not cleanly shut down (will be set to true on clean shutdown)
        prefs.edit()
            .putBoolean(KEY_CLEAN_SHUTDOWN, false)
            .putLong(KEY_LAST_SESSION_END, System.currentTimeMillis())
            .apply()
    }

    /**
     * Marks the app as cleanly shut down.
     *
     * Should be called in onTerminate() or when app is backgrounded gracefully.
     */
    fun markCleanShutdown() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_CLEAN_SHUTDOWN, true)
            .putLong(KEY_LAST_SESSION_END, System.currentTimeMillis())
            .apply()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Log app start only on first activity creation
        if (isFirstStart.compareAndSet(true, false)) {
            logAppStart()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        val count = activeActivities.incrementAndGet()

        // App came to foreground (from background)
        if (count == 1L && lastBackgroundTime > 0) {
            val backgroundDuration = System.currentTimeMillis() - lastBackgroundTime
            logAppForeground(backgroundDuration)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        // Activity is now visible and interactive
    }

    override fun onActivityPaused(activity: Activity) {
        // Activity is losing focus
    }

    override fun onActivityStopped(activity: Activity) {
        val count = activeActivities.decrementAndGet()

        // App went to background (no activities visible)
        if (count == 0L) {
            lastBackgroundTime = System.currentTimeMillis()
            val sessionDuration = lastBackgroundTime - sessionStartTime.get()
            logAppBackground(sessionDuration)
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // Save instance state
    }

    override fun onActivityDestroyed(activity: Activity) {
        // Activity is being destroyed
    }

    /**
     * Logs app start event with device metrics.
     */
    private fun logAppStart() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val installTime = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.firstInstallTime
        } catch (e: Exception) {
            0L
        }

        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)
        if (isFirstLaunch) {
            prefs.edit().putBoolean("is_first_launch", false).apply()
        }

        logger.logRecordBuilder()
            .setBody("app.start")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.longKey("session.start_timestamp"), sessionStartTime.get(),
                    AttributeKey.booleanKey("app.first_launch"), isFirstLaunch,
                    AttributeKey.longKey("app.install_timestamp"), installTime,
                    AttributeKey.stringKey("lifecycle.event"), "app_start"
                )
            )
            .emit()

        android.util.Log.i(TAG, "App started: first_launch=$isFirstLaunch")

        // Capture device metrics if configured
        if (captureConfig.onAppStart && metricsCollector != null) {
            metricsCollector.captureMetrics(
                io.opentelemetry.android.mobile.metrics.CaptureReason.APP_START,
                force = false  // Respect rate limiting
            )
        }
    }

    /**
     * Logs app foreground event (came back from background).
     */
    private fun logAppForeground(backgroundDuration: Long) {
        logger.logRecordBuilder()
            .setBody("app.foreground")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("event.name"), "app.foreground",
                    AttributeKey.longKey("mobile.background_duration_ms"), backgroundDuration,
                    AttributeKey.longKey("mobile.session.resume_timestamp"), System.currentTimeMillis(),
                    AttributeKey.stringKey("mobile.lifecycle.event"), "app_foreground"
                )
            )
            .emit()

        android.util.Log.i(TAG, "App foregrounded: background_duration=${backgroundDuration}ms")
    }

    /**
     * Logs app background event.
     */
    private fun logAppBackground(sessionDuration: Long) {
        logger.logRecordBuilder()
            .setBody("app.background")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("event.name"), "app.background",
                    AttributeKey.longKey("session_duration_ms"), sessionDuration,
                    AttributeKey.longKey("background_timestamp"), lastBackgroundTime,
                    AttributeKey.stringKey("lifecycle.event"), "app_background"
                )
            )
            .emit()

        android.util.Log.i(TAG, "App backgrounded: session_duration=${sessionDuration}ms")

        // Mark clean shutdown when going to background
        markCleanShutdown()
    }

    /**
     * Gets session duration in milliseconds.
     */
    fun getSessionDuration(): Long {
        return System.currentTimeMillis() - sessionStartTime.get()
    }

    /**
     * Checks if app is currently in foreground.
     */
    fun isAppInForeground(): Boolean {
        return activeActivities.get() > 0
    }
}
