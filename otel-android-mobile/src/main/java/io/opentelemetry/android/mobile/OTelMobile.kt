/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.app.Application
import io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions
import io.opentelemetry.android.mobile.config.UiTelemetryMode as ConfigUiTelemetryMode
import io.opentelemetry.android.mobile.instrumentation.UiTelemetryMode as CoreUiTelemetryMode
import io.opentelemetry.android.mobile.autocapture.RecoveryTracker
import io.opentelemetry.android.mobile.autocapture.SessionTracker
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.instrumentation.BackPressInstrumentation
import io.opentelemetry.android.mobile.instrumentation.ErrorsInstrumentation
import io.opentelemetry.android.mobile.instrumentation.FreezeInstrumentation
import io.opentelemetry.android.mobile.instrumentation.LifecycleInstrumentation
import io.opentelemetry.android.mobile.instrumentation.OTelMobileBuilder
import io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle
import io.opentelemetry.android.mobile.instrumentation.ScreenViewInstrumentation
import io.opentelemetry.android.mobile.instrumentation.ScrollInstrumentation
import io.opentelemetry.android.mobile.instrumentation.TapInstrumentation
import io.opentelemetry.android.mobile.instrumentation.TextInputInstrumentation
import io.opentelemetry.android.mobile.instrumentation.VitalsInstrumentation
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer

/**
 * Public entry point for automatic mobile instrumentation.
 *
 * Calling [start] initializes the full SDK with all auto-instrumentation:
 * - **Auto-capture**: taps, scrolls, back presses, freezes/ANR, lifecycle, screen views
 * - **Error capture**: uncaught exceptions, coroutine errors, RxJava errors → auto flush
 * - **Vitals**: app start, jank, memory pressure, thermal state → OTel metrics
 * - **Predictive export**: crash/network-loss risk → pre-emptive buffer flush
 * - **Ring buffer**: RAM (5000 events) → disk (50MB, 24h TTL) → selective export
 * - **Policy evaluation**: DSL-based trigger conditions → window flush
 * - **Session & breadcrumbs**: user journey tracking, session lifecycle
 *
 * Usage:
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         OTelMobile.start(this, MobileConfig(
 *             serviceName = "my-app",
 *             serviceVersion = "1.0.0",
 *             collectorEndpoint = "https://collector.example.com:4317"
 *         ))
 *     }
 * }
 * ```
 */
private fun ConfigUiTelemetryMode.toCore(): CoreUiTelemetryMode = when (this) {
    ConfigUiTelemetryMode.EVENTS -> CoreUiTelemetryMode.EVENTS
    ConfigUiTelemetryMode.SPANS  -> CoreUiTelemetryMode.SPANS
    ConfigUiTelemetryMode.BOTH   -> CoreUiTelemetryMode.BOTH
}

object OTelMobile {
    @Volatile
    private var provider: MobileLoggerProvider? = null

    @Volatile
    private var handle: OTelMobileHandle? = null

    @Volatile
    private var recoveryTracker: RecoveryTracker? = null

    fun start(application: Application, config: MobileConfig) {
        synchronized(this) {
            if (provider == null) {
                // Initialize all modules through MobileOtel facade
                // This wires: SessionManager, BreadcrumbManager, ErrorInstrumentation,
                // VitalsCollector, PredictiveExportPolicy, HealthMetricsCollector
                val instance = MobileOtel.initialize(application, config)
                provider = instance

                // RecoveryTracker still needs SessionTracker (legacy); will be cleaned up in Task 7
                val rt = RecoveryTracker(
                    application,
                    instance.get("io.opentelemetry.android.mobile.recovery"),
                    instance,
                    SessionTracker(AutoCaptureOptions())
                )
                recoveryTracker = rt
                rt.start()

                handle = OTelMobileBuilder(application, instance.getOpenTelemetrySdk())
                    .setUiTelemetryMode(config.uiTelemetryMode.toCore())
                    .addInstrumentation(LifecycleInstrumentation())
                    .addInstrumentation(ScreenViewInstrumentation())
                    .addInstrumentation(TapInstrumentation())
                    .addInstrumentation(ScrollInstrumentation())
                    .addInstrumentation(TextInputInstrumentation(config.textInputConfig))
                    .addInstrumentation(BackPressInstrumentation())
                    .addInstrumentation(FreezeInstrumentation())
                    .addInstrumentation(ErrorsInstrumentation())
                    .addInstrumentation(VitalsInstrumentation())
                    .build()
            }
        }
    }

    fun stop(timeoutSeconds: Long = 30) {
        handle?.stop(timeoutSeconds)
        handle = null
        recoveryTracker?.let { /* no stop() needed — it lives for app lifetime */ }
        recoveryTracker = null
        MobileOtel.shutdown()
        provider = null
    }

    fun getLoggerProvider(): MobileLoggerProvider {
        return provider ?: error("OTelMobile.start must be called before accessing the provider")
    }

    fun getLogger(scope: String): Logger = getLoggerProvider().get(scope)

    fun getTracer(scope: String, version: String? = null): Tracer {
        val sdk = getLoggerProvider().getOpenTelemetrySdk()
        return if (version == null) sdk.getTracer(scope) else sdk.getTracer(scope, version)
    }

    fun getMeter(scope: String): Meter = getLoggerProvider().getOpenTelemetrySdk().getMeter(scope)

    fun getLastRecoveryType(): String? = recoveryTracker?.getLastRecoveryType()

    fun markCrashForNextStart() {
        recoveryTracker?.markCrashForNextStart()
    }

    fun markLowMemoryForNextStart() {
        recoveryTracker?.markLowMemoryForNextStart()
    }

    fun markAnrForNextStart() {
        recoveryTracker?.markAnrForNextStart()
    }

    /**
     * Ends the current page span and starts a fresh one for the same screen.
     * Page spans are now managed by ScreenViewInstrumentation; this is a no-op kept for API
     * compatibility and will be removed in the cleanup pass (Task 7).
     */
    fun restartPageSpan(screenName: String) {
        // Page spans are managed by ScreenViewInstrumentation — no-op here
    }

    fun startJourney(name: String): Span {
        return getTracer("io.opentelemetry.android.mobile.journey").spanBuilder(name)
            .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
            .startSpan()
    }

    /**
     * Returns a new [OTelMobileBuilder] for fine-grained SDK configuration.
     *
     * Use this instead of [start] when you want to control exactly which
     * instrumentations are active.
     *
     * ```kotlin
     * val handle = OTelMobile.builder(app, openTelemetry)
     *     .addInstrumentation(TapInstrumentation())
     *     .addInstrumentation(LifecycleInstrumentation())
     *     .build()
     * ```
     */
    fun builder(application: Application, openTelemetry: OpenTelemetry): OTelMobileBuilder {
        return OTelMobileBuilder(application, openTelemetry)
    }
}
