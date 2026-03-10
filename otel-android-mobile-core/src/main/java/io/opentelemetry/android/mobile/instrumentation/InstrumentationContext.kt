// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

/**
 * Controls how UI interactions are emitted. Duplicated here from [MobileConfig] so the
 * core module stays independent of the top-level config module.
 */
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
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
) {
    /** Convenience accessor — returns a [Tracer] scoped to [scope]. */
    fun tracer(scope: String): Tracer = openTelemetry.getTracer(scope)

    /** Convenience accessor — returns a [Logger] scoped to [scope]. */
    fun logger(scope: String): Logger = openTelemetry.logsBridge.get(scope)

    /** Convenience accessor — returns a [Meter] scoped to [scope]. */
    fun meter(scope: String): Meter = openTelemetry.getMeter(scope)
}
