/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.app.Application
import io.opentelemetry.android.mobile.autocapture.AutoCaptureManager
import io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.instrumentation.OTelMobileBuilder
import io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle
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
object OTelMobile {
    @Volatile
    private var provider: MobileLoggerProvider? = null

    @Volatile
    private var autoCaptureManager: AutoCaptureManager? = null

    fun start(application: Application, config: MobileConfig, options: AutoCaptureOptions = AutoCaptureOptions()) {
        synchronized(this) {
            if (provider == null) {
                // Initialize all modules through MobileOtel facade
                // This wires: SessionManager, BreadcrumbManager, ErrorInstrumentation,
                // VitalsCollector, PredictiveExportPolicy, HealthMetricsCollector
                val instance = MobileOtel.initialize(application, config)
                provider = instance

                // Start auto-capture (taps, scrolls, freezes, ANR, lifecycle, recovery)
                autoCaptureManager = AutoCaptureManager(application, instance, options).also {
                    it.start()
                }
            }
        }
    }

    fun stop(timeoutSeconds: Long = 30) {
        autoCaptureManager?.stop()
        autoCaptureManager = null
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

    fun getLastRecoveryType(): String? = autoCaptureManager?.getLastRecoveryType()

    fun markCrashForNextStart() {
        autoCaptureManager?.markCrashForNextStart()
    }

    fun markLowMemoryForNextStart() {
        autoCaptureManager?.markLowMemoryForNextStart()
    }

    fun markAnrForNextStart() {
        autoCaptureManager?.markAnrForNextStart()
    }

    /**
     * Ends the current page span and starts a fresh one for the same screen.
     * Call this after an API action completes so the next user interaction starts a clean span.
     */
    fun restartPageSpan(screenName: String) {
        autoCaptureManager?.startPageSpan(screenName)
    }

    fun startJourney(name: String): Span {
        val manager = autoCaptureManager
        return manager?.startJourney(name)
            ?: getTracer("journey").spanBuilder(name).startSpan()
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
