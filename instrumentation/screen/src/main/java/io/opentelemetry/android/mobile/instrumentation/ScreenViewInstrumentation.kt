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
import io.opentelemetry.api.trace.StatusCode
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

    fun startPageSpan(screenName: String) {
        endPageSpan()
        val sp = sessionProvider ?: return
        pageSpan = tracer?.spanBuilder("page.$screenName")
            ?.setAttribute(MobileSemconv.SESSION_ID.key, sp.getSessionId())
            ?.setAttribute(MobileSemconv.VIEW_ID.key, sp.getViewId())
            ?.setAttribute(MobileSemconv.SCREEN_NAME.key, screenName)
            ?.startSpan()
        pageScope = pageSpan?.makeCurrent()
    }

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
