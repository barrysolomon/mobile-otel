// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.api.logs.Severity

/**
 * Captures app lifecycle events as OTel logs.
 *
 * Emits:
 * - [MobileSemconv.APP_START] — once per session, either from the cold-start
 *   path ([Activity.onCreate]) or synthesized at install-time when the SDK
 *   initializes after the host Activity is already started (e.g., RN's
 *   `useEffect` deferred init).
 * - [MobileSemconv.APP_FOREGROUND] — when the process transitions to
 *   foreground, observed via `androidx.lifecycle.ProcessLifecycleOwner`.
 *   Includes the at-attach replay: if `addObserver()` runs while the
 *   lifecycle is already STARTED, the observer's `onStart` fires
 *   synchronously, giving late-init sessions their `app.foreground` for
 *   free.
 * - [MobileSemconv.APP_BACKGROUND] — when the process transitions to
 *   background.
 *
 * All events carry [MobileSemconv.SESSION_ID] and [MobileSemconv.VIEW_ID]
 * from the [MobileSessionProvider].
 */
@Incubating
@Supersedes("activity", "fragment")
class LifecycleInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.lifecycle"

    private var logger: Logger? = null
    private var sessionProvider: MobileSessionProvider? = null
    private var instrumentationContext: InstrumentationContext? = null
    private var application: Application? = null
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var lifecycleObserver: androidx.lifecycle.DefaultLifecycleObserver? = null

    @Volatile private var firstStartLogged = false
    @Volatile private var lastBackgroundAtMs = 0L
    // Wall-clock + uptime captured at install for the two duration calculations:
    // - cold-start path uses (now - installTimeWallMs) as elapsed install latency
    // - late-init path uses (installTimeUptimeMs - Process.getStartUptimeMillis())
    //   to attribute the "process up before SDK init" gap with both sides anchored
    //   to the same monotonic clock.
    private var installTimeWallMs = 0L
    private var installTimeUptimeMs = 0L

    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        this.logger = context.logger(instrumentationName)
        this.sessionProvider = context.sessionProvider
        this.instrumentationContext = context
        this.installTimeWallMs = System.currentTimeMillis()
        this.installTimeUptimeMs = android.os.SystemClock.uptimeMillis()

        // app.start synthesis for late-init: if the process is already past
        // INITIALIZED at install-time, an Activity already exists. Emit
        // app.start with type="instrumentation_late" so the session has a
        // start event regardless of when start() was called. Sets
        // firstStartLogged so onActivityCreated below doesn't re-emit.
        emitAppStartIfLateInstall()

        // ProcessLifecycleOwner has at-attach replay: if the lifecycle is
        // already STARTED when addObserver runs, onStart fires synchronously
        // before addObserver returns. That gives late-init sessions their
        // app.foreground for free. Subsequent transitions fire as usual.
        //
        // CRITICAL: addObserver() must run on the main thread —
        // LifecycleRegistry has an assertMainThread() check that throws
        // IllegalStateException otherwise. RN consumers call OTelMobile.start()
        // from the JS bridge thread, not main, so we dispatch.
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                emitForeground()
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                emitBackground()
            }
        }
        lifecycleObserver = observer

        // onActivityCreated remains the cold-start app.start signal — the
        // only event ProcessLifecycleOwner doesn't cover. For native consumers
        // calling start() from Application.onCreate, this fires when the
        // first Activity creates and emits app.start with type="cold".
        // firstStartLogged dedups against the late-install path.
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                emitAppStartIfFirstSeen(a)
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        activityCallbacks = cb
        application.registerActivityLifecycleCallbacks(cb)

        // Defer addObserver to the main thread (see CRITICAL note above).
        // If we're already on main, run synchronously; otherwise post.
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        } else {
            mainHandler.post {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            }
        }
    }

    override fun uninstall() {
        // removeObserver has the same assertMainThread constraint as
        // addObserver (see CRITICAL note in install): LifecycleRegistry
        // throws IllegalStateException off-main. stop() is legitimately
        // called from worker/JS threads during host shutdown — hop to main
        // instead of crashing the host.
        lifecycleObserver?.let { observer ->
            val remove = Runnable {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            }
            if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
                remove.run()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post(remove)
            }
        }
        activityCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        lifecycleObserver = null
        activityCallbacks = null
        application = null
        logger = null
        sessionProvider = null
        instrumentationContext = null
    }

    private fun emitAppStartIfLateInstall() {
        if (firstStartLogged) return
        // ProcessLifecycleOwner.currentState reads must happen on main, but
        // for the at-install initial check we're often off-main (RN bridge
        // thread). Use a Looper check; if we can't safely read currentState,
        // fall through and let the activity-callback cold-start path handle
        // app.start emission instead.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            // Best-effort: we can still inspect lifecycle state from off-main;
            // it's just that the docs don't strictly guarantee thread safety.
            // ProcessLifecycleOwner's internal Handler dispatches all state
            // transitions to main, so reading currentState off-main returns
            // the latest value as of the last main-thread transition — which
            // is fine for our "is the process already up?" gate.
        }
        val state = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
        if (!state.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)) return

        firstStartLogged = true
        // Both anchors are uptime millis (monotonic since boot), so the
        // subtraction yields a real "time from process start to SDK install"
        // duration. Process.getStartUptimeMillis is API 24+; project minSdk = 26.
        val processStart = android.os.Process.getStartUptimeMillis()
        val durationMs = (installTimeUptimeMs - processStart).coerceAtLeast(0L)
        emitLog(
            MobileSemconv.APP_START, Severity.INFO,
            Attributes.builder()
                .put("app.start.duration_ms", durationMs)
                .put("app.start.type", "instrumentation_late")
                .build()
        )
        instrumentationContext?.addBreadcrumb(
            JourneyBreadcrumb.lifecycle(
                screen = "<late_install>",
                action = MobileSemconv.APP_START,
                attributes = mapOf("duration_ms" to durationMs.toString())
            )
        )
    }

    private fun emitAppStartIfFirstSeen(a: Activity) {
        if (firstStartLogged) return
        firstStartLogged = true
        // Cold-start path: install was called from Application.onCreate, so
        // installTimeWallMs is "process startup ish" and the duration to first
        // activity creation is small (typically a few hundred ms).
        val durationMs = System.currentTimeMillis() - installTimeWallMs
        emitLog(
            MobileSemconv.APP_START, Severity.INFO,
            Attributes.builder()
                .put("app.start.duration_ms", durationMs)
                .put("app.start.type", "cold")
                .build()
        )
        instrumentationContext?.addBreadcrumb(
            JourneyBreadcrumb.lifecycle(
                screen = a.javaClass.simpleName,
                action = MobileSemconv.APP_START,
                attributes = mapOf("duration_ms" to durationMs.toString())
            )
        )
    }

    private fun emitForeground() {
        val ctx = instrumentationContext ?: return
        val now = System.currentTimeMillis()
        val bgDuration = if (lastBackgroundAtMs > 0L) now - lastBackgroundAtMs else 0L
        val renewed = ctx.sessionProvider.onAppForeground(now)
        emitLog(
            MobileSemconv.APP_FOREGROUND, Severity.INFO,
            Attributes.builder()
                .put(MobileSemconv.SESSION_RENEWED, renewed)
                .put(MobileSemconv.BACKGROUND_DURATION_MS, bgDuration)
                .build()
        )
        ctx.addBreadcrumb(
            JourneyBreadcrumb.lifecycle(
                screen = "<process>",
                action = MobileSemconv.APP_FOREGROUND,
                attributes = mapOf("background_duration_ms" to bgDuration.toString())
            )
        )
    }

    private fun emitBackground() {
        val ctx = instrumentationContext ?: return
        lastBackgroundAtMs = System.currentTimeMillis()
        ctx.sessionProvider.onAppBackground(lastBackgroundAtMs)
        emitLog(MobileSemconv.APP_BACKGROUND, Severity.INFO)
        ctx.addBreadcrumb(
            JourneyBreadcrumb.lifecycle(
                screen = "<process>",
                action = MobileSemconv.APP_BACKGROUND
            )
        )
    }

    private fun emitLog(name: String, severity: Severity, extra: Attributes = Attributes.empty()) {
        val sp = sessionProvider ?: return
        logger?.logRecordBuilder()
            ?.setBody(name)
            ?.setSeverity(severity)
            ?.setAllAttributes(
                Attributes.builder()
                    .put(io.opentelemetry.api.common.AttributeKey.stringKey("event.name"), name)
                    .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                    .put(MobileSemconv.VIEW_ID, sp.getViewId())
                    .putAll(extra)
                    .build()
            )
            ?.emit()
    }
}
