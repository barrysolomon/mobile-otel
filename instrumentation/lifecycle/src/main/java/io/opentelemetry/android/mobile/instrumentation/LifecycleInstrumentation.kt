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
 * - [MobileSemconv.APP_START] — once, on the first [Activity.onCreate]
 * - [MobileSemconv.APP_FOREGROUND] — when the app transitions to foreground
 * - [MobileSemconv.APP_BACKGROUND] — when the app transitions to background
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
    private var callbacks: Application.ActivityLifecycleCallbacks? = null

    @Volatile private var firstStartLogged = false
    @Volatile private var activeActivities = 0
    @Volatile private var lastBackgroundAtMs = 0L
    private var installTimeMs = 0L

    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        this.logger = context.logger(instrumentationName)
        this.sessionProvider = context.sessionProvider
        this.instrumentationContext = context
        this.installTimeMs = System.currentTimeMillis()

        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                if (!firstStartLogged) {
                    firstStartLogged = true
                    val startDurationMs = System.currentTimeMillis() - installTimeMs
                    emitLog(MobileSemconv.APP_START, Severity.INFO,
                        Attributes.builder()
                            .put("app.start.duration_ms", startDurationMs)
                            .put("app.start.type", if (startDurationMs > 0) "cold" else "unknown")
                            .build()
                    )
                    instrumentationContext?.addBreadcrumb(
                        JourneyBreadcrumb.lifecycle(
                            screen = a.javaClass.simpleName,
                            action = MobileSemconv.APP_START,
                            attributes = mapOf("duration_ms" to startDurationMs.toString())
                        )
                    )
                }
            }

            override fun onActivityStarted(a: Activity) {
                activeActivities++
                if (activeActivities == 1) {
                    val now = System.currentTimeMillis()
                    val bgDuration = if (lastBackgroundAtMs > 0L) now - lastBackgroundAtMs else 0L
                    val renewed = context.sessionProvider.onAppForeground(now)
                    emitLog(
                        MobileSemconv.APP_FOREGROUND, Severity.INFO,
                        Attributes.builder()
                            .put(MobileSemconv.SESSION_RENEWED, renewed)
                            .put(MobileSemconv.BACKGROUND_DURATION_MS, bgDuration)
                            .build()
                    )
                    instrumentationContext?.addBreadcrumb(
                        JourneyBreadcrumb.lifecycle(
                            screen = a.javaClass.simpleName,
                            action = MobileSemconv.APP_FOREGROUND,
                            attributes = mapOf("background_duration_ms" to bgDuration.toString())
                        )
                    )
                }
            }

            override fun onActivityStopped(a: Activity) {
                activeActivities--
                if (activeActivities == 0) {
                    lastBackgroundAtMs = System.currentTimeMillis()
                    context.sessionProvider.onAppBackground(lastBackgroundAtMs)
                    emitLog(MobileSemconv.APP_BACKGROUND, Severity.INFO)
                    instrumentationContext?.addBreadcrumb(
                        JourneyBreadcrumb.lifecycle(
                            screen = a.javaClass.simpleName,
                            action = MobileSemconv.APP_BACKGROUND
                        )
                    )
                }
            }

            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }

        callbacks = cb
        application.registerActivityLifecycleCallbacks(cb)
    }

    override fun uninstall() {
        callbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        callbacks = null
        application = null
        logger = null
        sessionProvider = null
        instrumentationContext = null
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
