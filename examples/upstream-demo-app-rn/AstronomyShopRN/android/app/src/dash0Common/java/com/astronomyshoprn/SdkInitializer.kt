package com.astronomyshoprn

import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.OtlpProtocol

object SdkInitializer {
    fun initialize(app: Application, cellId: String? = null) {
        val extraAttrs = mutableMapOf<String, String>().apply {
            put("dash0.test.export_mode", BuildConfig.DASH0_EXPORT_MODE)
            cellId?.takeIf { it.isNotBlank() }?.let { put("dash0.test.cell_id", it) }
        }
        try {
            MobileOtel.initialize(app) {
                service {
                    name = "otel-rn-android-astronomy-shop"
                    version = "0.1.0"
                }
                export {
                    endpoint = ExportConfig.grpcEndpoint
                    protocol = OtlpProtocol.GRPC
                    mode = ExportMode.valueOf(BuildConfig.DASH0_EXPORT_MODE_ENUM)
                    headers = ExportConfig.headers
                }
                instrumentations {
                    discoverAll()
                }
                extraResourceAttributes = extraAttrs
            }
            Log.i("RnSdkInit", "Initialized mode=${BuildConfig.DASH0_EXPORT_MODE_ENUM} cell=$cellId")
        } catch (e: Exception) {
            Log.e("RnSdkInit", "Failed to initialize SDK", e)
        }
    }
}
