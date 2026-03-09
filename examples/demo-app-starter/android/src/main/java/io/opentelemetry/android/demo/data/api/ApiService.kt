package io.opentelemetry.android.demo.data.api

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Schedulr demo app.
 *
 * Uses plain OkHttp. Every outbound call returns a response body string
 * or throws [HttpException] on non-2xx responses.
 *
 * Base URLs:
 *  - Success path : https://jsonplaceholder.typicode.com  (free fake REST API)
 *  - Forced errors: https://httpbin.org/status/<code>
 */
class SchedulingApiClient private constructor(context: Context) {

    @Suppress("UNUSED_PARAMETER")
    private val appContext = context.applicationContext

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** GET — returns response body string or throws on non-2xx */
    fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) throw HttpException(response.code, body)
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
        if (!response.isSuccessful) throw HttpException(response.code, body)
        return body
    }

    /** POST JSON — returns response body string or throws on non-2xx */
    fun post(url: String, json: String): String {
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) throw HttpException(response.code, responseBody)
        return responseBody
    }

    class HttpException(val code: Int, message: String) : Exception(message)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile private var instance: SchedulingApiClient? = null

        fun getInstance(context: Context): SchedulingApiClient =
            instance ?: synchronized(this) {
                instance ?: SchedulingApiClient(context).also { instance = it }
            }
    }
}
