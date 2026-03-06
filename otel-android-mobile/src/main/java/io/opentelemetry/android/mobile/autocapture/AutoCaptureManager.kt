/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Window
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope
import java.util.WeakHashMap

@Deprecated(
    message = "Use OTelMobileBuilder with individual MobileInstrumentation modules instead.",
    replaceWith = ReplaceWith("OTelMobileBuilder", "io.opentelemetry.android.mobile.instrumentation.OTelMobileBuilder"),
    level = DeprecationLevel.WARNING
)
class AutoCaptureManager(
    private val application: Application,
    private val provider: MobileLoggerProvider,
    private val options: AutoCaptureOptions
) : Application.ActivityLifecycleCallbacks {
    private val logger: Logger = provider.get("auto-capture")
    private val tracer = provider.getOpenTelemetrySdk().getTracer("auto-capture", "1.0.0")

    private val sessionTracker = SessionTracker(options)
    private val tapCapture = TapCapture(logger, tracer, sessionTracker, options)
    private val backPressCapture = if (options.captureBackPress) BackPressCapture(logger, sessionTracker) else null
    private val scrollCapture = ScrollCapture(logger, tracer, sessionTracker, options)
    private val textInputCapture = TextInputCapture(logger, tracer, sessionTracker, options)
    private val freezeDetector = FreezeDetector(
        logger,
        provider,
        sessionTracker,
        options,
        onAnrDetected = { recoveryTracker.markAnrForNextStart() },
        onAnrRecovered = { recoveryTracker.clearAnrMarker() }
    )
    private val recoveryTracker = RecoveryTracker(application, logger, provider, sessionTracker)

    // Current page-level span — parent for all taps, scrolls, and API calls on this screen.
    // Runs on the main thread; no locking needed.
    private var pageSpan: Span? = null
    private var pageScope: Scope? = null

    /**
     * Ends any existing page span and starts a fresh one named "page.<screenName>".
     *
     * Page spans are the root of the trace hierarchy for all user interactions on a screen:
     *
     *   page.BookFragment  ← always sampled (sampling.priority=high)
     *   ├── ui.tap          ← auto-captured child span (TapCapture)
     *   ├── ui.tap
     *   ├── booking.submit  ← manually created child span in BookFragment
     *   │   └── POST /posts ← OkHttp child span
     *   └── ui.swipe
     *
     * WHY sampling.priority=high:
     * DynamicSampler makes a probabilistic decision per trace ID (default 0.65 baseline).
     * If a page span is dropped (35% of sessions), TapCapture.emit() detects isSampled=false
     * and falls back to emitting taps as standalone log records instead of child spans.
     * This breaks the trace waterfall — taps appear flat in Dash0 with no parent context,
     * and manually-created child spans like booking.submit start a new root trace.
     *
     * Forcing page spans to always sample ensures:
     * - All taps/swipes on the screen become child spans (waterfall visible)
     * - booking.submit and similar manual spans are correctly nested under the page
     * - Sampling rate on individual taps/API spans still applies for volume control
     *
     * Called on fragment resume and explicitly by fragments after an API call completes
     * (so the next user interaction starts a clean span).
     */
    fun startPageSpan(screenName: String) {
        pageScope?.close()
        pageScope = null
        pageSpan?.takeIf { it.isRecording }?.end()
        pageSpan = tracer.spanBuilder("page.$screenName")
            .setAttribute("session.id", sessionTracker.getSessionId())
            .setAttribute("view.id", sessionTracker.getViewId())
            .setAttribute("screen.name", screenName)
            // Force page spans to always sample so that:
            //   1. TapCapture emits taps as child spans (not flat logs)
            //   2. Manually-created child spans (booking.submit, etc.) are nested correctly
            // Without this, DynamicSampler drops ~35% of page spans at the default 0.65
            // baseline, breaking the trace waterfall for all interactions on those screens.
            .setAttribute("sampling.priority", "high")
            .startSpan()
        pageScope = pageSpan!!.makeCurrent()
    }

    /** Ends the current page span (e.g. on fragment pause or explicit close). */
    fun endPageSpan() {
        pageScope?.close()
        pageScope = null
        pageSpan?.takeIf { it.isRecording }?.end()
        pageSpan = null
    }

    private val wrappedCallbacks = WeakHashMap<Window, Window.Callback>()
    private val fragmentCallbacks = FragmentCallbacks()
    private val fragmentManagers = WeakHashMap<FragmentManager, Boolean>()

    @Volatile
    private var firstStartLogged = false

    @Volatile
    private var activeActivities = 0

    @Volatile
    private var lastBackgroundAtMs: Long = 0

    @Volatile
    private var startupSpan: Span? = null

    fun start() {
        recoveryTracker.start()
        application.registerActivityLifecycleCallbacks(this)
        if (options.freezeDetectorEnabled) {
            freezeDetector.start()
        }
    }

    fun stop() {
        endPageSpan()
        application.unregisterActivityLifecycleCallbacks(this)
        recoveryTracker.stop()
        freezeDetector.stop()
        tapCapture.shutdown()
        wrappedCallbacks.forEach { (window, original) ->
            if (window.callback is WindowCallbackWrapper) {
                window.callback = original
            }
        }
        wrappedCallbacks.clear()
    }

    fun getLastRecoveryType(): String = recoveryTracker.getLastRecoveryType()

    fun markCrashForNextStart() = recoveryTracker.markCrashForNextStart()

    fun markLowMemoryForNextStart() = recoveryTracker.markLowMemoryForNextStart()

    fun markAnrForNextStart() = recoveryTracker.markAnrForNextStart()

    fun startJourney(name: String): Span {
        return tracer.spanBuilder(name)
            .setAttribute("session.id", sessionTracker.getSessionId())
            .setAttribute("view.id", sessionTracker.getViewId())
            .startSpan()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        wrapWindowCallback(activity)

        if (!firstStartLogged) {
            firstStartLogged = true
            if (options.captureLifecycle) {
                logAppStart()
            }
            startStartupSpan()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        activeActivities += 1
        if (activeActivities == 1) {
            val now = System.currentTimeMillis()
            val backgroundDuration = if (lastBackgroundAtMs > 0) now - lastBackgroundAtMs else 0
            val renewed = sessionTracker.onAppForeground(now)
            if (options.captureLifecycle) {
                logAppForeground(backgroundDuration, renewed)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        val screenName = activity.javaClass.simpleName
        sessionTracker.onScreenView(screenName)
        if (options.captureScreens) {
            logScreenView(screenName)
            attachFragmentCallbacks(activity)
        }
        startScreenRenderSpan(activity, screenName)
        endStartupSpanIfNeeded(screenName)

        val root = activity.window?.decorView
        if (root != null) {
            scrollCapture.attachTo(root)
            textInputCapture.attachTo(root)
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        activeActivities -= 1
        if (activeActivities == 0) {
            lastBackgroundAtMs = System.currentTimeMillis()
            sessionTracker.onAppBackground(lastBackgroundAtMs)
            if (options.captureLifecycle) {
                logAppBackground(lastBackgroundAtMs)
            }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (activity.isFinishing && activeActivities == 0) {
            recoveryTracker.markCleanShutdown()
        }
    }

    private fun wrapWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback
        if (current is WindowCallbackWrapper) return
        if (current == null) return

        val wrapper = WindowCallbackWrapper(window, current, tapCapture, backPressCapture)
        wrappedCallbacks[window] = current
        window.callback = wrapper
    }

    private fun attachFragmentCallbacks(activity: Activity) {
        if (!options.captureFragments) return
        if (activity is FragmentActivity) {
            val manager = activity.supportFragmentManager
            if (fragmentManagers.containsKey(manager)) return
            manager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
            fragmentManagers[manager] = true
        }
    }

    private fun logAppStart() {
        val recoveryType = recoveryTracker.getLastRecoveryType()
        logger.logRecordBuilder()
            .setBody("app.start")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                    .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                    .put(AttributeKey.stringKey("recovery_type"), recoveryType)
                    .build()
            )
            .emit()
    }

    private fun logAppForeground(backgroundDuration: Long, sessionRenewed: Boolean) {
        logger.logRecordBuilder()
            .setBody("app.foreground")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                    .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                    .put(AttributeKey.longKey("background_duration_ms"), backgroundDuration)
                    .put(AttributeKey.booleanKey("session.renewed"), sessionRenewed)
                    .build()
            )
            .emit()
    }

    private fun logAppBackground(timestampMs: Long) {
        logger.logRecordBuilder()
            .setBody("app.background")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                    .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                    .put(AttributeKey.longKey("background_timestamp_ms"), timestampMs)
                    .build()
            )
            .emit()
    }

    private fun logScreenView(screenName: String) {
        logger.logRecordBuilder()
            .setBody("ui.screen_view")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                    .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                    .put(AttributeKey.stringKey("screen.name"), screenName)
                    .build()
            )
            .emit()
    }

    private fun startStartupSpan() {
        startupSpan = tracer.spanBuilder("app.startup")
            .setAttribute("session.id", sessionTracker.getSessionId())
            .startSpan()
    }

    private fun endStartupSpanIfNeeded(screenName: String) {
        val span = startupSpan ?: return
        span.setAttribute("screen.name", screenName)
        span.setStatus(StatusCode.OK)
        span.end()
        startupSpan = null
    }

    private fun startScreenRenderSpan(activity: Activity, screenName: String) {
        val root = activity.window?.decorView ?: return
        val span = tracer.spanBuilder("screen.render")
            .setAttribute("session.id", sessionTracker.getSessionId())
            .setAttribute("view.id", sessionTracker.getViewId())
            .setAttribute("screen.name", screenName)
            .startSpan()

        val observer = root.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) {
                    observer.removeOnPreDrawListener(this)
                }
                span.setStatus(StatusCode.OK)
                span.end()
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    private inner class FragmentCallbacks : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (!options.captureFragments) return
            val screenName = f.javaClass.simpleName
            sessionTracker.onScreenView(screenName)
            logScreenView(screenName)
            startPageSpan(screenName)
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (!options.captureFragments) return
            endPageSpan()
        }
    }
}
