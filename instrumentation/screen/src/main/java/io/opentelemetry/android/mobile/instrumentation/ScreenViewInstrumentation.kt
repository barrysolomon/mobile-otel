// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Scope
import java.util.WeakHashMap

/**
 * Captures screen view events as OTel logs and manages the page-level span
 * that parents all user interactions on a screen.
 *
 * Emits [MobileSemconv.UI_SCREEN_VIEW] on every [Activity.onResume] and
 * [Fragment.onResume]. Maintains a page span ("page.<screenName>") that is
 * active while the screen is visible — all taps and other interactions
 * on the same screen appear as children of this span.
 */
@Incubating
class ScreenViewInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.screen"

    private var logger: Logger? = null
    private var tracer: Tracer? = null
    private var sessionProvider: MobileSessionProvider? = null
    private var application: Application? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS

    // Page span — started on fragment/activity resume, ended on pause
    private var pageSpan: Span? = null
    private var pageScope: Scope? = null
    private val fragmentManagers = WeakHashMap<FragmentManager, Boolean>()

    /**
     * Installs the screen view instrumentation by registering activity and fragment lifecycle
     * callbacks that emit [MobileSemconv.UI_SCREEN_VIEW] log records and manage the page span.
     *
     * @param application The host application, used to register lifecycle callbacks.
     * @param context Instrumentation context carrying the OTel logger, tracer, and session provider.
     */
    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        this.logger = context.logger(instrumentationName)
        this.tracer = context.tracer(instrumentationName)
        this.sessionProvider = context.sessionProvider
        this.uiTelemetryMode = context.uiTelemetryMode

        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val screenName = activity.javaClass.simpleName
                context.sessionProvider.onScreenView(screenName)
                logScreenView(screenName)
                startScreenRenderSpan(activity, screenName)
                attachFragmentCallbacks(activity, context)
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }

        callbacks = cb
        application.registerActivityLifecycleCallbacks(cb)
    }

    /**
     * Uninstalls the screen view instrumentation, ending any active page span and releasing
     * all held references.
     */
    override fun uninstall() {
        endPageSpan()
        callbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        callbacks = null
        application = null
        logger = null
        tracer = null
        sessionProvider = null
        uiTelemetryMode = UiTelemetryMode.EVENTS
        fragmentManagers.clear()
    }

    /**
     * Ends any active page span and starts a new one named "page.[screenName]".
     *
     * The span is made current on the calling thread so that all interaction spans
     * created afterward are automatically nested under it.
     *
     * @param screenName Simple class name of the activity or fragment (e.g., "HomeFragment").
     */
    fun startPageSpan(screenName: String) {
        endPageSpan()
        val sp = sessionProvider ?: return
        // Span name follows mobile convention: "page.<ScreenName>" — custom mobile semconv (not yet standardized)
        pageSpan = tracer?.spanBuilder("page.$screenName")
            ?.setSpanKind(SpanKind.INTERNAL)
            ?.setAttribute(MobileSemconv.SESSION_ID.key, sp.getSessionId())
            ?.setAttribute(MobileSemconv.VIEW_ID.key, sp.getViewId())
            ?.setAttribute(MobileSemconv.SCREEN_NAME.key, screenName)
            ?.startSpan()
        pageScope = pageSpan?.makeCurrent()
    }

    /**
     * Ends the currently active page span and closes its [io.opentelemetry.context.Scope].
     * No-op if no page span is active.
     */
    fun endPageSpan() {
        pageScope?.close()
        pageScope = null
        pageSpan?.takeIf { it.isRecording }?.end()
        pageSpan = null
    }

    private fun logScreenView(screenName: String) {
        val sp = sessionProvider ?: return
        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sp.getSessionId())
            .put(MobileSemconv.VIEW_ID, sp.getViewId())
            .put(MobileSemconv.SCREEN_NAME, screenName)
            .apply {
                sp.getPreviousScreenName()?.let { put(MobileSemconv.PREVIOUS_SCREEN, it) }
                val timeOnScreen = sp.getTimeOnScreenMs()
                if (timeOnScreen > 0) put(MobileSemconv.TIME_ON_SCREEN_MS, timeOnScreen)
            }
            .build()
        // In SPANS mode the page span itself is the screen-view signal; skip the log.
        if (uiTelemetryMode != UiTelemetryMode.SPANS) {
            logger?.logRecordBuilder()
                ?.setBody(MobileSemconv.UI_SCREEN_VIEW)
                ?.setSeverity(Severity.INFO)
                ?.setAllAttributes(attrs)
                ?.emit()
        }
    }

    private fun startScreenRenderSpan(activity: Activity, screenName: String) {
        val root = activity.window?.decorView ?: return
        val sp = sessionProvider ?: return
        val span = tracer?.spanBuilder(MobileSemconv.SCREEN_RENDER)
            ?.setSpanKind(SpanKind.INTERNAL)
            ?.setAttribute(MobileSemconv.SESSION_ID.key, sp.getSessionId())
            ?.setAttribute(MobileSemconv.SCREEN_NAME.key, screenName)
            ?.startSpan() ?: return

        val observer = root.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                span.setStatus(StatusCode.OK)
                span.end()
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    private fun attachFragmentCallbacks(activity: Activity, context: InstrumentationContext) {
        if (activity !is FragmentActivity) return
        val manager = activity.supportFragmentManager
        if (fragmentManagers.containsKey(manager)) return
        manager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                val screenName = f.javaClass.simpleName
                context.sessionProvider.onScreenView(screenName)
                logScreenView(screenName)
                startPageSpan(screenName)
            }
            override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
                endPageSpan()
            }
        }, true)
        fragmentManagers[manager] = true
    }
}
