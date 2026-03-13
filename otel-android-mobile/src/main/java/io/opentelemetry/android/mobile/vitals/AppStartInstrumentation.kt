/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.vitals

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Instrumentation for measuring app start times.
 *
 * Measures:
 * - Cold start: Process creation → First Activity displayed
 * - Warm start: Activity restart → First Activity displayed
 * - Time to Initial Display (TTID): When first content is rendered
 *
 * Cold start is measured from process start (using ProcessStartTime).
 * Warm start is measured when app returns from background.
 *
 * Thread-safe singleton that integrates with OpenTelemetry tracing.
 */
class AppStartInstrumentation private constructor(
    private val tracer: Tracer,
    private val vitalsCollector: VitalsCollector?
) : Application.ActivityLifecycleCallbacks {

    private val processStartTime = getProcessStartTime()
    private val coldStartMeasured = AtomicBoolean(false)
    private val warmStartTime = AtomicLong(0)
    private val isInBackground = AtomicBoolean(false)
    private val activeActivities = AtomicLong(0)

    private var coldStartSpan: Span? = null
    private var warmStartSpan: Span? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Measure cold start on first activity creation
        if (!coldStartMeasured.get()) {
            val coldStartDuration = System.currentTimeMillis() - processStartTime

            // @Incubating: mobile app start semconv not yet standardized
            coldStartSpan = tracer.spanBuilder("app.start.cold")
                .setAttribute("mobile.app.start.type", "cold")
                .setAttribute("mobile.app.start.duration_ms", coldStartDuration)
                .setAttribute("mobile.app.start.process_start_time", processStartTime)
                .setStartTimestamp(java.time.Instant.ofEpochMilli(processStartTime))
                .startSpan()

            coldStartMeasured.set(true)

            // Measure TTID when first view is drawn
            measureTtid(activity, processStartTime)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        val count = activeActivities.incrementAndGet()

        // Measure warm start when returning from background
        if (count == 1L && isInBackground.get()) {
            val warmStart = warmStartTime.get()
            if (warmStart > 0) {
                val warmStartDuration = System.currentTimeMillis() - warmStart

                // @Incubating: mobile app start semconv not yet standardized
                warmStartSpan = tracer.spanBuilder("app.start.warm")
                    .setAttribute("mobile.app.start.type", "warm")
                    .setAttribute("mobile.app.start.duration_ms", warmStartDuration)
                    .setStartTimestamp(java.time.Instant.ofEpochMilli(warmStart))
                    .startSpan()

                vitalsCollector?.recordWarmStart(warmStartDuration)

                // End span after a short delay to capture additional context
                handler.postDelayed({
                    warmStartSpan?.end()
                    warmStartSpan = null
                }, 100)
            }

            isInBackground.set(false)
            warmStartTime.set(0)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        // No-op
    }

    override fun onActivityPaused(activity: Activity) {
        // No-op
    }

    override fun onActivityStopped(activity: Activity) {
        val count = activeActivities.decrementAndGet()

        // App moved to background
        if (count == 0L) {
            isInBackground.set(true)
            warmStartTime.set(System.currentTimeMillis())
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // No-op
    }

    override fun onActivityDestroyed(activity: Activity) {
        // No-op
    }

    /**
     * Measure time to initial display (TTID).
     *
     * TTID is when the first meaningful content is rendered on screen.
     * We measure this by observing when the first view is drawn.
     */
    private fun measureTtid(activity: Activity, startTime: Long) {
        val rootView = activity.window?.decorView?.rootView

        rootView?.viewTreeObserver?.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    // Remove listener after first draw
                    rootView.viewTreeObserver?.removeOnPreDrawListener(this)

                    val ttidDuration = System.currentTimeMillis() - startTime

                    // Create TTID span
                    // @Incubating: mobile app start semconv not yet standardized
                    val ttidSpan = tracer.spanBuilder("app.ttid")
                        .setAttribute("mobile.app.start.duration_ms", ttidDuration)
                        .setStartTimestamp(java.time.Instant.ofEpochMilli(startTime))
                        .startSpan()

                    vitalsCollector?.recordTtid(ttidDuration)

                    // End cold start span now that content is displayed
                    coldStartSpan?.let { span ->
                        val coldStartDuration = System.currentTimeMillis() - processStartTime
                        span.setAttribute("mobile.app.start.duration_ms", coldStartDuration)
                        vitalsCollector?.recordColdStart(coldStartDuration)
                        span.end()
                        coldStartSpan = null
                    }

                    ttidSpan.end()

                    return true
                }
            }
        )
    }

    /**
     * Get process start time in milliseconds.
     *
     * Uses the earliest time we can detect, which is when this class is loaded.
     * For more accurate measurement, this should be called as early as possible
     * in the Application class.
     */
    private fun getProcessStartTime(): Long {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.os.Process.getStartElapsedRealtime() +
                (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime())
        } else {
            // Fallback: use class load time as approximation
            System.currentTimeMillis()
        }
    }

    companion object {
        @Volatile
        private var instance: AppStartInstrumentation? = null

        /**
         * Initialize app start instrumentation.
         *
         * Should be called as early as possible in Application.onCreate().
         *
         * @param application Application instance
         * @param tracer OpenTelemetry tracer
         * @param vitalsCollector Optional vitals collector for metrics
         */
        fun initialize(
            application: Application,
            tracer: Tracer,
            vitalsCollector: VitalsCollector?
        ): AppStartInstrumentation {
            return instance ?: synchronized(this) {
                instance ?: AppStartInstrumentation(tracer, vitalsCollector).also {
                    instance = it
                    application.registerActivityLifecycleCallbacks(it)
                }
            }
        }

        /**
         * Get the app start instrumentation instance.
         */
        fun getInstance(): AppStartInstrumentation? = instance

        /**
         * Check if app start instrumentation is initialized.
         */
        fun isInitialized(): Boolean = instance != null
    }
}
