// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.util.Log

@Incubating
@Supersedes("compose.click")
class ComposeClickInstrumentation(
    private val config: ComposeClickConfig = ComposeClickConfig(),
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.compose.click"

    private var detector: ComposeClickDetector? = null

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return
        try {
            Class.forName("androidx.compose.ui.platform.AndroidComposeView")
            val det = ComposeClickDetector(config, context)
            det.install(application)
            detector = det
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Compose not on classpath -- skipping compose click instrumentation")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install compose click instrumentation", e)
        }
    }

    override fun uninstall() {
        detector?.uninstall()
        detector = null
    }

    companion object {
        private const val TAG = "ComposeClickInstr"
    }
}
