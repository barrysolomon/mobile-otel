// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.data.api

import android.content.Context
import android.util.Log
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.network.NetworkConfig
import io.opentelemetry.android.mobile.network.OTelNetworkInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the scheduling demo app.
 *
 * Uses OkHttp with [OTelNetworkInterceptor] so every outbound call is automatically
 * traced (span per request) and logged (http.error on 4xx/5xx).
 *
 * Base URLs:
 *  - Success path : https://jsonplaceholder.typicode.com  (free fake REST API)
 *  - Forced errors: https://httpbin.org/status/<code>
 */
class SchedulingApiClient private constructor(context: Context) {

    private val appContext = context.applicationContext

    val client: OkHttpClient by lazy {
        val sdk = OTelMobile.getLoggerProvider().getOpenTelemetrySdk()
        OkHttpClient.Builder()
            .addInterceptor(
                OTelNetworkInterceptor.create(
                    context    = appContext,
                    config     = NetworkConfig.production(),
                    tracer     = OTelMobile.getTracer("schedulr.network"),
                    propagator = sdk.propagators.textMapPropagator,
                    logger     = OTelMobile.getLogger("schedulr.network")
                )
            )
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** GET — returns response body string or throws on non-2xx */
    fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            Log.w(TAG, "GET $url failed: ${response.code}")
            throw HttpException(response.code, body)
        }
        return body
    }

    /** GET with a single extra header (e.g. User-Agent for Nominatim). */
    fun getWithHeader(url: String, header: Pair<String, String>): String {
        val request = Request.Builder()
            .url(url)
            .header(header.first, header.second)
            .get()
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            Log.w(TAG, "GET $url failed: ${response.code}")
            throw HttpException(response.code, body)
        }
        return body
    }

    /** POST JSON — returns response body string or throws on non-2xx */
    fun post(url: String, json: String): String {
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            Log.w(TAG, "POST $url failed: ${response.code}")
            throw HttpException(response.code, responseBody)
        }
        return responseBody
    }

    /** DELETE — returns response body string or throws on non-2xx */
    fun delete(url: String): String {
        val request = Request.Builder().url(url).delete().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            Log.w(TAG, "DELETE $url failed: ${response.code}")
            throw HttpException(response.code, body)
        }
        return body
    }

    class HttpException(val code: Int, message: String) : Exception(message)

    companion object {
        private const val TAG = "SchedulingApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile private var instance: SchedulingApiClient? = null

        fun getInstance(context: Context): SchedulingApiClient =
            instance ?: synchronized(this) {
                instance ?: SchedulingApiClient(context).also { instance = it }
            }
    }
}
