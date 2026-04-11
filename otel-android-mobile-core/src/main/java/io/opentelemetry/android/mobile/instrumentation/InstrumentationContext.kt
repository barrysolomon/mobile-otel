// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.Clock

/**
 * Controls how UI interactions are emitted. Duplicated here from [MobileConfig] so the
 * core module stays independent of the top-level config module.
 */
@Incubating
enum class UiTelemetryMode {
    /** Emit UI interactions as OTel log records (default). */
    EVENTS,
    /** Emit UI interactions as zero-duration child spans nested under the page span. */
    SPANS,
    /** Emit UI interactions as both log records and child spans. */
    BOTH
}

/**
 * Shared context passed to every [MobileInstrumentation] at install time.
 *
 * Carries all shared state required by instrumentation modules:
 * the OpenTelemetry SDK instance, a swappable [MobileSessionProvider],
 * the [WindowEventHub] for UI event fan-out, and the [Application].
 *
 * Instrumentation implementations must not hold strong references to
 * mutable state inside this context beyond their [MobileInstrumentation.uninstall]
 * call.
 */
@Incubating
class InstrumentationContext(
    val openTelemetry: OpenTelemetry,
    val sessionProvider: MobileSessionProvider,
    val windowEventHub: WindowEventHub,
    val application: Application,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val breadcrumbManager: BreadcrumbManager? = if (BreadcrumbManager.isInitialized()) BreadcrumbManager else null,
    val clock: Clock? = null
) {
    companion object {
        fun fromInstallationContext(ctx: InstallationContext): InstrumentationContext {
            val app = ctx.application
                ?: throw IllegalStateException("Application context required")
            val hub = WindowEventHub()
            WindowEventHubInstaller(app, hub).install()
            return InstrumentationContext(
                openTelemetry = ctx.openTelemetry,
                sessionProvider = UpstreamSessionProviderAdapter(ctx.sessionProvider),
                windowEventHub = hub,
                application = app,
                clock = ctx.clock
            )
        }
    }

    /**
     * Convenience method — adds a breadcrumb if the [BreadcrumbManager] is available.
     * Safe to call even when breadcrumbs are not configured (no-op in that case).
     */
    fun addBreadcrumb(breadcrumb: JourneyBreadcrumb) {
        breadcrumbManager?.add(breadcrumb)
    }
    /** Convenience accessor — returns a [Tracer] scoped to [scope]. */
    fun tracer(scope: String): Tracer = openTelemetry.getTracer(scope)

    /** Convenience accessor — returns a [Logger] scoped to [scope]. */
    fun logger(scope: String): Logger = openTelemetry.logsBridge.get(scope)

    /** Convenience accessor — returns a [Meter] scoped to [scope]. */
    fun meter(scope: String): Meter = openTelemetry.getMeter(scope)
}
