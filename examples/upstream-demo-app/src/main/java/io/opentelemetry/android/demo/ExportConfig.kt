/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo

import android.content.Context
import org.json.JSONObject

object ExportConfig {
    lateinit var endpoint: String
    lateinit var grpcEndpoint: String
    var headers: Map<String, String> = emptyMap()

    fun load(context: Context) {
        try {
            val json = context.assets.open("otel-config.json").bufferedReader().readText()
            val config = JSONObject(json)
            endpoint = config.getString("endpoint")
            grpcEndpoint = endpoint // Cloud endpoints handle both protocols on same URL
            val headerObj = config.optJSONObject("headers")
            headers = headerObj?.keys()?.asSequence()
                ?.associateWith { headerObj.getString(it) } ?: emptyMap()
        } catch (e: Exception) {
            // Fallback to local collector
            endpoint = "http://10.0.2.2:4318"        // HTTP/protobuf for upstream
            grpcEndpoint = "http://10.0.2.2:4317"    // gRPC for dash0
            headers = emptyMap()
        }
    }
}
