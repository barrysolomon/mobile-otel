// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import androidx.recyclerview.widget.RecyclerView
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

/**
 * Captures scroll events from RecyclerViews.
 * Attaches to the window's decor view on each activity resume.
 *
 * Thread note: onTouchEvent and RecyclerView scroll listeners fire on the main thread.
 */
@Incubating
class ScrollInstrumentation(
    private val throttleMs: Long = 500
) : MobileInstrumentation, WindowEventListener {

    override val instrumentationName = "io.opentelemetry.android.mobile.scroll"

    private var hub: WindowEventHub? = null
    private var ctx: InstrumentationContext? = null
    private var logger: Logger? = null
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    // Only accessed on main thread
    private var lastScrollAtMs: Long = 0
    // Maps each RecyclerView to its registered listener so we can remove on uninstall.
    private val trackedListeners = mutableMapOf<RecyclerView, RecyclerView.OnScrollListener>()

    /**
     * Installs the scroll instrumentation by attaching [RecyclerView.OnScrollListener] instances
     * to all RecyclerViews in each resumed activity's view hierarchy.
     *
     * @param application The host application, used to register activity lifecycle callbacks.
     * @param context Instrumentation context carrying the OTel logger and session provider.
     */
    override fun install(application: Application, context: InstrumentationContext) {
        ctx = context
        hub = context.windowEventHub
        logger = context.logger(instrumentationName)
        context.windowEventHub.addListener(this)

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val root = activity.window?.decorView ?: return
                attachTo(root as android.view.View)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    /**
     * Uninstalls the scroll instrumentation by removing all registered scroll listeners and
     * releasing held references.
     */
    override fun uninstall() {
        lifecycleCallbacks?.let { ctx?.application?.unregisterActivityLifecycleCallbacks(it) }
        hub?.removeListener(this)
        hub = null
        ctx = null
        logger = null
        lifecycleCallbacks = null
        // Remove listeners from views to prevent the views from retaining this instrumentation.
        trackedListeners.forEach { (view, listener) -> view.removeOnScrollListener(listener) }
        trackedListeners.clear()
    }

    private fun attachTo(view: android.view.View) {
        if (view is RecyclerView && !trackedListeners.containsKey(view)) {
            val listener = makeScrollListener()
            trackedListeners[view] = listener
            view.addOnScrollListener(listener)
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                attachTo(view.getChildAt(i))
            }
        }
    }

    private fun makeScrollListener() = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dx == 0 && dy == 0) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastScrollAtMs < throttleMs) return
            lastScrollAtMs = now
            emitScroll(dx, dy)
        }
    }

    /** Visible for testing — emit a scroll log record for the given deltas. */
    internal fun emitScroll(dx: Int, dy: Int) {
        val log = logger ?: return
        val context = ctx ?: return

        val direction = when {
            kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> if (dy > 0) "down" else "up"
            else -> if (dx > 0) "right" else "left"
        }
        val distance = kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy))
        val bucket = when {
            distance < 50 -> "small"
            distance < 200 -> "medium"
            else -> "large"
        }

        val sessionProvider = context.sessionProvider
        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            // @Incubating: custom mobile scroll semconv, not yet in OTel spec
            .put(io.opentelemetry.api.common.AttributeKey.stringKey("ui.scroll.direction"), direction)
            // @Incubating: custom mobile scroll semconv, not yet in OTel spec
            .put(io.opentelemetry.api.common.AttributeKey.stringKey("ui.scroll.distance_bucket"), bucket)
            .apply {
                sessionProvider.getCurrentScreenName()?.let { put(MobileSemconv.SCREEN_NAME, it) }
            }
            .build()

        when (context.uiTelemetryMode) {
            UiTelemetryMode.EVENTS -> log.logRecordBuilder()
                .setBody(MobileSemconv.UI_SCROLL).setSeverity(Severity.INFO)
                .setAllAttributes(attrs).emit()
            UiTelemetryMode.SPANS  -> context.tracer(instrumentationName)
                .spanBuilder(MobileSemconv.UI_SCROLL).startSpan()
                .apply { setAllAttributes(attrs); end() }
            UiTelemetryMode.BOTH   -> {
                log.logRecordBuilder()
                    .setBody(MobileSemconv.UI_SCROLL).setSeverity(Severity.INFO)
                    .setAllAttributes(attrs).emit()
                context.tracer(instrumentationName)
                    .spanBuilder(MobileSemconv.UI_SCROLL).startSpan()
                    .apply { setAllAttributes(attrs); end() }
            }
        }

        // Add breadcrumb for scroll events
        val screenName = sessionProvider.getCurrentScreenName() ?: "unknown"
        context.addBreadcrumb(
            JourneyBreadcrumb.userInput(
                screen = screenName,
                action = MobileSemconv.UI_SCROLL,
                attributes = mapOf("direction" to direction, "distance_bucket" to bucket)
            )
        )
    }
}
