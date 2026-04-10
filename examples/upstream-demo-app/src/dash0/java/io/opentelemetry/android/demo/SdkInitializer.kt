/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo

import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.config.ExportMode

object SdkInitializer {
    fun initialize(app: Application) {
        try {
            val mobile = MobileOtel.initialize(app) {
                service {
                    name = "astronomy-shop"
                    version = "1.0.0"
                }
                export {
                    endpoint = ExportConfig.grpcEndpoint
                    mode = ExportMode.CONDITIONAL
                    headers = ExportConfig.headers
                }
                instrumentations {
                    discoverAll()
                }
            }
            OtelDemoApplication.openTelemetry = mobile.openTelemetry
            OtelDemoApplication.sessionId = mobile.sessionId
        } catch (e: Exception) {
            Log.e("SdkInit", "Failed to initialize Dash0 SDK", e)
        }
    }
}
