package com.astronomyshoprn

import android.content.Context
import org.json.JSONObject

object ExportConfig {
    lateinit var grpcEndpoint: String
    var headers: Map<String, String> = emptyMap()

    fun load(context: Context) {
        try {
            val json = context.assets.open("otel-config.json").bufferedReader().readText()
            val config = JSONObject(json)
            val endpoint = config.getString("endpoint")
            grpcEndpoint = endpoint.replace(":4318", ":4317")
            val token = config.optString("authToken", "")
            val dataset = config.optString("dataset", "")
            headers = buildMap {
                if (token.isNotBlank()) put("Authorization", "Bearer $token")
                if (dataset.isNotBlank()) put("Dash0-Dataset", dataset)
            }
        } catch (_: Exception) {
            grpcEndpoint = "http://10.0.2.2:4317"
            headers = emptyMap()
        }
    }
}
