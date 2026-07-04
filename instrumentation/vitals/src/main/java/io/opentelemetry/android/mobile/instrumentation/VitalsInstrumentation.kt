// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.vitals.VitalsCollector
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.vitals.VitalsConfig

/**
 * [MobileInstrumentation] wrapper for [VitalsCollector].
 *
 * Delegates to the existing [VitalsCollector] singleton via its
 * [VitalsCollector.initialize] / [VitalsCollector.getInstance] API.
 * If initialization fails (e.g. in a test environment without a full Android
 * runtime), the failure is logged as a warning and [install] / [uninstall]
 * complete silently so the host app does not crash.
 */
@Incubating
@Supersedes("anr", "startup")
class VitalsInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.vitals"

    override fun install(application: Application, context: InstrumentationContext) {
        try {
            VitalsCollector.initialize(
                context = application.applicationContext,
                config = VitalsConfig.default(),
                meter = context.meter(instrumentationName)
            )
        } catch (e: Exception) {
            Log.w("VitalsInstrumentation", "Failed to initialize VitalsCollector: ${e.message}")
        }
    }

    override fun uninstall() {
        // VitalsCollector is a singleton; we leave it running for the lifetime
        // of the process (consistent with the existing otel-android-mobile behaviour).
        // If a future version adds a shutdown() method it can be called here.
    }
}
