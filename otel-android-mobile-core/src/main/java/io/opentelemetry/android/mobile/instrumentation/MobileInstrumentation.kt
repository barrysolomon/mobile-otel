// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application

/**
 * Contract for a single unit of mobile auto-instrumentation.
 *
 * Mirrors [io.opentelemetry.android.instrumentation.AndroidInstrumentation]
 * from the opentelemetry-android library, extended with identity fields and
 * an [InstrumentationContext] carrier that provides shared state (session,
 * window events, OpenTelemetry instance).
 *
 * Each capability (taps, scrolls, lifecycle, errors, …) ships as a separate
 * implementation in its own Gradle module and is discoverable via the Java
 * [java.util.ServiceLoader] SPI.
 */
@Incubating
interface MobileInstrumentation {
    /** Unique name identifying this instrumentation, e.g. "io.opentelemetry.android.mobile.tap". */
    val instrumentationName: String

    /** Version of this instrumentation. */
    val instrumentationVersion: String get() = "1.0.0"

    /**
     * Called by [InstrumentationRegistry] to activate this instrumentation.
     * Implementations should register any callbacks or observers they need
     * and retain only weak references to [application] beyond this call.
     */
    fun install(application: Application, context: InstrumentationContext)

    /**
     * Called by [InstrumentationRegistry] to deactivate and clean up.
     * Implementations must unregister all callbacks registered in [install].
     */
    fun uninstall() {}
}
