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
/**
 * @param appManagedScreens When true, the app is the authority for screen
 *   identity (it calls [reportScreen] per logical/Compose screen). The SDK then
 *   does NOT emit Activity-named screen-views, and filters out `screen.render`
 *   spans that would carry only the host Activity name (e.g. the cold-launch
 *   first frame, before the app has reported a screen). Default `false` keeps
 *   that cold-launch render — it is legitimate startup telemetry — and only
 *   relabels it lazily when the app reports a screen in time.
 */
@Incubating
class ScreenViewInstrumentation(
    private val appManagedScreens: Boolean = false,
) : MobileInstrumentation {

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
                // In app-managed mode the app owns screen identity (via reportScreen),
                // so don't emit the host-Activity screen-view or set it as current.
                if (!appManagedScreens) {
                    context.sessionProvider.onScreenView(screenName)
                    logScreenView(screenName)
                }
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

    /**
     * Reports a logical, app-defined screen view — e.g. a Jetpack Compose
     * destination in a single-Activity app, where Activity-based detection only
     * ever sees the host Activity (`MainActivity`). Sets the current screen (so
     * subsequent taps / freezes are tagged with it via the session provider),
     * emits a [MobileSemconv.UI_SCREEN_VIEW] log, and starts a `page.<name>` span
     * that nests later interactions. Call this on each navigation in apps that
     * don't map screens to Activities/Fragments.
     */
    fun reportScreen(screenName: String) {
        sessionProvider?.onScreenView(screenName)
        logScreenView(screenName)
        startPageSpan(screenName)
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

    private fun startScreenRenderSpan(activity: Activity, activityScreenName: String) {
        val root = activity.window?.decorView ?: return
        val sp = sessionProvider ?: return
        // Capture the resume instant now; create the span at DRAW time so the
        // resolved screen name is known (and so app-managed mode can skip it).
        val startedAt = java.time.Instant.now()

        val listener = object : ViewTreeObserver.OnPreDrawListener {
            // Single-shot guard. A screen renders its first frame exactly once;
            // emit one render span for it, then never again for this resume.
            private var fired = false

            override fun onPreDraw(): Boolean {
                if (fired) return true
                fired = true
                // Unregister from the CURRENTLY-LIVE observer — the one actually
                // dispatching this callback — fetched fresh from the decorView.
                // The decorView's ViewTreeObserver can be swapped between when we
                // register and the first draw; removing via a stale reference
                // captured at registration silently no-ops, leaving this listener
                // attached and firing on EVERY frame. That produced a flood of
                // screen.render spans whose durations grew without bound (they all
                // shared this single `startedAt` but ended at successive frames),
                // even producing children longer than their parent page span.
                // The `fired` flag is the backstop if removal still fails.
                root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                // Resolve at DRAW time: a logical screen the app reported during
                // composition (via reportScreen) wins over the host Activity name
                // captured at resume ("MainActivity").
                val resolved = sp.getCurrentScreenName() ?: activityScreenName
                // app-managed mode: drop renders still carrying only the host
                // Activity name (e.g. the cold-launch first frame, before the app
                // reported a screen). Default mode keeps them as legit telemetry.
                if (appManagedScreens && resolved == activityScreenName) return true
                tracer?.spanBuilder(MobileSemconv.SCREEN_RENDER)
                    ?.setSpanKind(SpanKind.INTERNAL)
                    ?.setStartTimestamp(startedAt)
                    ?.setAttribute(MobileSemconv.SESSION_ID.key, sp.getSessionId())
                    ?.setAttribute(MobileSemconv.SCREEN_NAME.key, resolved)
                    ?.startSpan()
                    ?.apply { setStatus(StatusCode.OK); end() }
                return true
            }
        }
        root.viewTreeObserver.addOnPreDrawListener(listener)
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
