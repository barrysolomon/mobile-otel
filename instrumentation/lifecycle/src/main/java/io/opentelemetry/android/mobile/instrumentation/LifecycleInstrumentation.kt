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
    private var installTimeMs = 0L

    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        this.logger = context.logger(instrumentationName)
        this.sessionProvider = context.sessionProvider
        this.instrumentationContext = context
        this.installTimeMs = System.currentTimeMillis()

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
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                emitForeground()
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                emitBackground()
            }
        }
        lifecycleObserver = observer
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

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
    }

    override fun uninstall() {
        lifecycleObserver?.let {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
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
        val state = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
        if (!state.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)) return

        firstStartLogged = true
        // Process.getStartUptimeMillis is API 24+; project minSdk = 26.
        val processStart = android.os.Process.getStartUptimeMillis()
        val durationMs = (installTimeMs - processStart).coerceAtLeast(0L)
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
        val durationMs = System.currentTimeMillis() - installTimeMs
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
                    .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                    .put(MobileSemconv.VIEW_ID, sp.getViewId())
                    .putAll(extra)
                    .build()
            )
            ?.emit()
    }
}
