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
    fun initialize(app: Application, cellId: String? = null) {
        val extraAttrs = mutableMapOf<String, String>().apply {
            put("dash0.test.export_mode", BuildConfig.DASH0_EXPORT_MODE)
            cellId?.takeIf { it.isNotBlank() }?.let { put("dash0.test.cell_id", it) }
        }
        try {
            val mobile = MobileOtel.initialize(app) {
                service {
                    // Mirrored from iOS `otel-ios-astronomy-shop`. The
                    // `otel-<platform>-astronomy-shop` convention lets a
                    // single Dash0 filter bracket both platforms:
                    //   service.name =~ "otel-.*-astronomy-shop"
                    // Details: docs/design/shop-telemetry-contract.md
                    name = "otel-android-astronomy-shop"
                    version = "0.1.0"
                }
                export {
                    endpoint = ExportConfig.grpcEndpoint
                    mode = ExportMode.valueOf(BuildConfig.DASH0_EXPORT_MODE_ENUM)
                    headers = ExportConfig.headers
                }
                instrumentations {
                    discoverAll()
                }
                extraResourceAttributes = extraAttrs
            }
            OtelDemoApplication.openTelemetry = mobile.openTelemetry
            OtelDemoApplication.sessionId = mobile.sessionId
            OtelDemoApplication.resourceAttributesSnapshot = extraAttrs.toMap()
            Log.i("SdkInit", "Initialized with extraResourceAttributes=$extraAttrs")
        } catch (e: Exception) {
            Log.e("SdkInit", "Failed to initialize Dash0 SDK", e)
        }
    }
}
