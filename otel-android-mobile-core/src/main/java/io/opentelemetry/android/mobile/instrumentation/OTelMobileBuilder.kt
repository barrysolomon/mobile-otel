// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.api.OpenTelemetry
import java.util.ServiceLoader

/**
 * Builder for the Mobile OTel SDK.
 *
 * Accepts an [OpenTelemetry] instance (already configured with exporters,
 * processors, and resources by the caller), a [MobileSessionProvider], and
 * a list of [MobileInstrumentation] instances. [build] creates the
 * [InstrumentationRegistry], installs all instrumentations, and returns an
 * [OTelMobileHandle].
 *
 * Usage — explicit instrumentation selection:
 * ```kotlin
 * val handle = OTelMobileBuilder(app, openTelemetry)
 *     .addInstrumentation(LifecycleInstrumentation())
 *     .addInstrumentation(TapInstrumentation())
 *     .build()
 * ```
 *
 * Usage — SPI discovery (discovers all instrumentation modules on the classpath):
 * ```kotlin
 * val handle = OTelMobileBuilder(app, openTelemetry)
 *     .discoverInstrumentations()
 *     .build()
 * ```
 */
@Incubating
class OTelMobileBuilder(
    private val application: Application,
    private val openTelemetry: OpenTelemetry
) {
    private val instrumentations = mutableListOf<MobileInstrumentation>()
    private var sessionProvider: MobileSessionProvider = DefaultMobileSessionProvider()
    private var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS

    /** Replaces the default [DefaultMobileSessionProvider] with a custom implementation. */
    fun setSessionProvider(provider: MobileSessionProvider): OTelMobileBuilder = apply {
        sessionProvider = provider
    }

    /** Sets the UI telemetry mode — controls whether interactions emit log events, spans, or both. */
    fun setUiTelemetryMode(mode: UiTelemetryMode): OTelMobileBuilder = apply {
        uiTelemetryMode = mode
    }

    /** Registers [instrumentation] to be installed when [build] is called. */
    fun addInstrumentation(instrumentation: MobileInstrumentation): OTelMobileBuilder = apply {
        instrumentations.add(instrumentation)
    }

    /**
     * Discovers and adds all [MobileInstrumentation] implementations available
     * on the classpath via [ServiceLoader] (META-INF/services SPI).
     */
    fun discoverInstrumentations(): OTelMobileBuilder = apply {
        ServiceLoader.load(
            MobileInstrumentation::class.java,
            MobileInstrumentation::class.java.classLoader
        ).forEach { instrumentations.add(it) }
    }

    /**
     * Creates the [InstrumentationRegistry], installs all added instrumentations,
     * and returns the active [OTelMobileHandle].
     */
    fun build(): OTelMobileHandle {
        val hub = WindowEventHub()
        val hubInstaller = WindowEventHubInstaller(application, hub)
        hubInstaller.install()
        val context = InstrumentationContext(openTelemetry, sessionProvider, hub, application, uiTelemetryMode)
        val registry = InstrumentationRegistry(instrumentations.toList())
        registry.install(application, context)
        return OTelMobileHandle(openTelemetry, registry, hubInstaller)
    }
}
