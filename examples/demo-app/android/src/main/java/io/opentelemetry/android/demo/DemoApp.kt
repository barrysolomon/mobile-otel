// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.OTelMobile

class DemoApp : Application() {

    companion object {
        var handle: io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle? = null
    }

    override fun onCreate() {
        super.onCreate()

        // Force CONTINUOUS mode so exports happen on schedule without a policy gateway.
        // Overwrites any cached CONDITIONAL value from SharedPreferences.
        getSharedPreferences("otel_config", android.content.Context.MODE_PRIVATE)
            .edit().putString("export_mode", "CONTINUOUS").apply()

        val config = ConfigManager.loadConfig(this)

        /*
         * Alternative: use OTelMobileBuilder for explicit instrumentation control.
         *
         * The builder API gives fine-grained control over which instrumentation modules are
         * registered, rather than relying on the all-in-one AutoCaptureOptions flags.
         * Example usage (requires a pre-configured OpenTelemetry SDK instance):
         *
         *   val otelSdk: OpenTelemetry = ... // build your OTel SDK here
         *   handle = OTelMobile.builder(this, otelSdk)
         *       .addInstrumentation(LifecycleInstrumentation())
         *       .addInstrumentation(ScreenViewInstrumentation())
         *       .addInstrumentation(TapInstrumentation(TapConfig(captureSwipe = true)))
         *       .addInstrumentation(ScrollInstrumentation())
         *       .addInstrumentation(FreezeInstrumentation())
         *       .build()
         *
         * Available instrumentation types:
         *   - LifecycleInstrumentation   : Activity/Fragment lifecycle events
         *   - ScreenViewInstrumentation  : Screen view tracking
         *   - TapInstrumentation         : Tap and long-press events (TapConfig controls captureSwipe)
         *   - ScrollInstrumentation      : Scroll gesture events
         *   - FreezeInstrumentation      : UI freeze / ANR detection
         *
         * The handle returned by build() can be used to stop instrumentation:
         *   handle?.stop()
         *
         * NOTE: The OTelMobile.start() call below is the actual runtime path for this demo.
         * It handles auto-capture with the per-feature flags loaded from ConfigManager above,
         * and also wires errors, vitals, and predictive export — features not yet exposed
         * through the builder API.
         */

        OTelMobile.start(application = this, config = config)

        Log.i("OTELDemoApp", "OTelMobile started (auto-capture enabled)")
    }
}
