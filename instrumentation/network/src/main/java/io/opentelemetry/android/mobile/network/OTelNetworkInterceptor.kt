/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context as OTelContext
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.semconv.HttpAttributes
import io.opentelemetry.semconv.ServerAttributes
import io.opentelemetry.semconv.UrlAttributes
import io.opentelemetry.android.mobile.instrumentation.Incubating
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.Charset

/**
 * OkHttp interceptor for OpenTelemetry network instrumentation.
 *
 * Features:
 * - Creates spans for HTTP requests
 * - Propagates W3C trace context
 * - Captures request/response headers (allowlist)
 * - Detects network type (WiFi/Cellular)
 * - Buckets request/response sizes
 * - Scrubs URLs for privacy
 * - Reports network errors
 *
 * Usage:
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(OTelNetworkInterceptor.create(context, config, tracer, propagator))
 *     .build()
 * ```
 */
@Incubating
class OTelNetworkInterceptor private constructor(
    private val context: Context,
    private val config: NetworkConfig,
    private val tracer: Tracer,
    private val propagator: TextMapPropagator,
    private val logger: Logger? = null
) : Interceptor {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Check if we should instrument this host
        if (!config.shouldInstrumentHost(request.url.host)) {
            return chain.proceed(request)
        }

        val startTime = System.currentTimeMillis()

        // Create span for this request
        val spanBuilder = tracer.spanBuilder(getSpanName(request))
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, request.method)
            .setAttribute(UrlAttributes.URL_FULL, scrubUrl(request.url.toString()))
            .setAttribute(ServerAttributes.SERVER_ADDRESS, request.url.host)
            .setAttribute(ServerAttributes.SERVER_PORT, request.url.port.toLong())

        // Add network type if enabled
        if (config.detectNetworkType) {
            getNetworkType()?.let { networkType ->
                spanBuilder.setAttribute(AttributeKey.stringKey("network.connection.type"), networkType)
            }
        }

        // Capture request headers
        config.captureRequestHeaders.forEach { headerName ->
            if (config.shouldCaptureRequestHeader(headerName)) {
                request.header(headerName)?.let { value ->
                    spanBuilder.setAttribute("http.request.header.$headerName", value)
                }
            }
        }

        // Capture request body if enabled
        if (config.captureRequestBody) {
            captureRequestBody(request)?.let { body ->
                spanBuilder.setAttribute("http.request.body", body)
            }
        }

        // Start the span
        val span = spanBuilder.startSpan()

        // Propagate trace context only to allowed hosts
        val newRequest = if (config.shouldPropagateContext(request.url.host)) {
            injectTraceContext(request, span)
        } else {
            request
        }

        // Execute request
        val response: Response
        try {
            response = chain.proceed(newRequest)
        } catch (e: IOException) {
            // Record error and rethrow
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, "Network error: ${e.message}")
            span.end()
            throw e
        }

        val duration = System.currentTimeMillis() - startTime

        // Check if request meets minimum duration threshold
        if (duration < config.minDurationMs) {
            span.end()
            return response
        }

        // Add response attributes
        span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, response.code.toLong())
        span.setAttribute("http.response.duration_ms", duration)

        // Determine status
        if (response.code >= config.errorStatusThreshold) {
            span.setStatus(StatusCode.ERROR, "HTTP ${response.code}")
            // Emit a log record so the http-error-detector policy can trigger a conditional flush.
            logger?.logRecordBuilder()
                ?.setBody("http.error")
                ?.setSeverity(Severity.ERROR)
                ?.setAllAttributes(
                    Attributes.builder()
                        .put(AttributeKey.stringKey("url.full"), scrubUrl(request.url.toString()))
                        .put(AttributeKey.longKey("http.response.status_code"), response.code.toLong())
                        .put(AttributeKey.stringKey("http.request.method"), request.method)
                        .put(AttributeKey.longKey("http.request.duration_ms"), duration)
                        .build()
                )
                ?.emit()
        } else {
            span.setStatus(StatusCode.OK)
        }

        // Capture response headers
        config.captureResponseHeaders.forEach { headerName ->
            if (config.shouldCaptureResponseHeader(headerName)) {
                response.header(headerName)?.let { value ->
                    span.setAttribute("http.response.header.$headerName", value)
                }
            }
        }

        // Add size buckets
        if (config.bucketSizes) {
            request.body?.contentLength()?.let { size ->
                if (size >= 0) {
                    span.setAttribute("http.request.size_bucket", config.getSizeBucket(size))
                }
            }

            response.body?.contentLength()?.let { size ->
                if (size >= 0) {
                    span.setAttribute("http.response.size_bucket", config.getSizeBucket(size))
                }
            }
        }

        // Capture response body if enabled
        if (config.captureResponseBody) {
            captureResponseBody(response)?.let { body ->
                span.setAttribute("http.response.body", body)
            }
        }

        span.end()
        return response
    }

    /**
     * Generate span name from request.
     */
    private fun getSpanName(request: Request): String {
        return "${request.method} ${request.url.encodedPath}"
    }

    /**
     * Scrub URL for privacy.
     */
    private fun scrubUrl(url: String): String {
        if (!config.scrubUrls) {
            return url
        }

        return try {
            val urlObj = java.net.URL(url)
            val path = urlObj.path
            val scrubbed = io.opentelemetry.android.mobile.core.PiiScrubber.scrubUrl(url)
            scrubbed
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Inject W3C trace context into request headers.
     */
    private fun injectTraceContext(request: Request, span: Span): Request {
        val builder = request.newBuilder()
        val context = OTelContext.current().with(span)

        propagator.inject(context, builder) { carrier, key, value ->
            carrier?.header(key, value)
        }

        return builder.build()
    }

    /**
     * Get current network type.
     */
    private fun getNetworkType(): String? {
        val cm = connectivityManager ?: return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return "none"
            val capabilities = cm.getNetworkCapabilities(network) ?: return "unknown"

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                else -> "other"
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo ?: return "none"
            @Suppress("DEPRECATION")
            when (networkInfo.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                ConnectivityManager.TYPE_BLUETOOTH -> "bluetooth"
                else -> "other"
            }
        }
    }

    /**
     * Capture request body as string (limited by maxBodyCaptureBytes).
     */
    private fun captureRequestBody(request: Request): String? {
        val body = request.body ?: return null

        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val charset = body.contentType()?.charset(Charset.forName("UTF-8")) ?: Charset.forName("UTF-8")
            val bytes = minOf(buffer.size, config.maxBodyCaptureBytes.toLong())
            buffer.readString(bytes, charset)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Capture response body as string (limited by maxBodyCaptureBytes).
     */
    private fun captureResponseBody(response: Response): String? {
        val body = response.body ?: return null

        return try {
            val source = body.source()
            source.request(config.maxBodyCaptureBytes.toLong())
            val buffer = source.buffer
            val charset = body.contentType()?.charset(Charset.forName("UTF-8")) ?: Charset.forName("UTF-8")
            val bytes = minOf(buffer.size, config.maxBodyCaptureBytes.toLong())
            buffer.clone().readString(bytes, charset)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /**
         * Create a new OTel network interceptor.
         *
         * @param context Android application context
         * @param config Network configuration
         * @param tracer OpenTelemetry tracer
         * @param propagator Trace context propagator
         */
        fun create(
            context: Context,
            config: NetworkConfig,
            tracer: Tracer,
            propagator: TextMapPropagator,
            logger: Logger? = null
        ): OTelNetworkInterceptor {
            return OTelNetworkInterceptor(context.applicationContext, config, tracer, propagator, logger)
        }
    }
}
