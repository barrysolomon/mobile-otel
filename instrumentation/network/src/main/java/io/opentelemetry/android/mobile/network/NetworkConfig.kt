/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Configuration for network instrumentation.
 *
 * Defines behavior for OkHttp interceptor including:
 * - Trace context propagation
 * - Header capture (allowlist)
 * - URL scrubbing for privacy
 * - Request/response body capture
 * - Network type detection
 * - Size bucketing
 *
 * Usage:
 * ```kotlin
 * val networkConfig = NetworkConfig(
 *     enabled = true,
 *     propagateTraceContext = true,
 *     captureRequestHeaders = listOf("Content-Type", "User-Agent"),
 *     scrubUrls = true
 * )
 * ```
 *
 * @property enabled Whether network instrumentation is enabled (default: true)
 * @property propagateTraceContext Inject W3C trace context headers (default: true)
 * @property captureRequestHeaders List of request headers to capture (default: empty)
 * @property captureResponseHeaders List of response headers to capture (default: Content-Type only)
 * @property captureRequestBody Capture request body for debugging (default: false, privacy risk)
 * @property captureResponseBody Capture response body for debugging (default: false, privacy risk)
 * @property maxBodyCaptureBytes Maximum bytes to capture from body (default: 1024)
 * @property scrubUrls Remove query parameters and sensitive path segments (default: true)
 * @property scrubHeaders Remove sensitive headers like Authorization (default: true)
 * @property detectNetworkType Detect and report network type (WiFi/Cellular/etc) (default: true)
 * @property reportNetworkSpeed Report estimated network speed (default: false)
 * @property bucketSizes Group request/response sizes into buckets (default: true)
 * @property minDurationMs Only trace requests slower than this threshold in ms (default: 0 = all)
 * @property errorStatusThreshold HTTP status code threshold for errors (default: 400)
 * @property allowedHosts List of hosts to instrument, empty = all (default: empty = all)
 * @property blockedHosts List of hosts to NOT instrument (default: empty)
 */
@Incubating
data class NetworkConfig(
    val enabled: Boolean = true,

    // Trace context
    val propagateTraceContext: Boolean = true,

    // Header capture
    val captureRequestHeaders: List<String> = emptyList(),
    val captureResponseHeaders: List<String> = listOf("Content-Type"),

    // Body capture (use with caution)
    val captureRequestBody: Boolean = false,
    val captureResponseBody: Boolean = false,
    val maxBodyCaptureBytes: Int = 1024,

    // Privacy
    val scrubUrls: Boolean = true,
    val scrubHeaders: Boolean = true,

    // Network detection
    val detectNetworkType: Boolean = true,
    val reportNetworkSpeed: Boolean = false,

    // Size bucketing
    val bucketSizes: Boolean = true,

    // Performance filtering
    val minDurationMs: Long = 0,
    val errorStatusThreshold: Int = 400,

    // Host filtering
    val allowedHosts: List<String> = emptyList(),
    val blockedHosts: List<String> = emptyList()
) {
    init {
        require(maxBodyCaptureBytes > 0) { "maxBodyCaptureBytes must be positive" }
        require(minDurationMs >= 0) { "minDurationMs must be non-negative" }
        require(errorStatusThreshold in 100..599) { "errorStatusThreshold must be a valid HTTP status code" }
    }

    /**
     * Check if a header should be captured.
     */
    fun shouldCaptureRequestHeader(headerName: String): Boolean {
        if (!enabled) return false
        if (scrubHeaders && isSensitiveHeader(headerName)) return false
        return captureRequestHeaders.any { it.equals(headerName, ignoreCase = true) }
    }

    /**
     * Check if a response header should be captured.
     */
    fun shouldCaptureResponseHeader(headerName: String): Boolean {
        if (!enabled) return false
        if (scrubHeaders && isSensitiveHeader(headerName)) return false
        return captureResponseHeaders.any { it.equals(headerName, ignoreCase = true) }
    }

    /**
     * Check if a host should be instrumented.
     */
    fun shouldInstrumentHost(host: String): Boolean {
        if (!enabled) return false

        // Check blocked list first
        if (blockedHosts.any { it.equals(host, ignoreCase = true) || host.endsWith(".$it") }) {
            return false
        }

        // If allowlist is empty, allow all (except blocked)
        if (allowedHosts.isEmpty()) {
            return true
        }

        // Check if host is in allowlist
        return allowedHosts.any { it.equals(host, ignoreCase = true) || host.endsWith(".$it") }
    }

    /**
     * Check if a header is considered sensitive.
     */
    private fun isSensitiveHeader(headerName: String): Boolean {
        val sensitive = listOf(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "X-API-Key",
            "X-Auth-Token",
            "Proxy-Authorization",
            "WWW-Authenticate"
        )
        return sensitive.any { it.equals(headerName, ignoreCase = true) }
    }

    /**
     * Get size bucket for byte count.
     */
    fun getSizeBucket(bytes: Long): String {
        if (!bucketSizes) {
            return bytes.toString()
        }

        return when {
            bytes < 1024 -> "<1KB"
            bytes < 10 * 1024 -> "1-10KB"
            bytes < 100 * 1024 -> "10-100KB"
            bytes < 1024 * 1024 -> "100KB-1MB"
            bytes < 10 * 1024 * 1024 -> "1-10MB"
            else -> ">10MB"
        }
    }

    companion object {
        /**
         * Default configuration with privacy-first defaults.
         */
        fun default(): NetworkConfig = NetworkConfig()

        /**
         * Minimal configuration - only basic tracing.
         */
        fun minimal(): NetworkConfig = NetworkConfig(
            propagateTraceContext = true,
            captureRequestHeaders = emptyList(),
            captureResponseHeaders = emptyList(),
            scrubUrls = true,
            scrubHeaders = true,
            detectNetworkType = false,
            reportNetworkSpeed = false,
            bucketSizes = false
        )

        /**
         * Debug configuration - capture more details.
         */
        fun debug(): NetworkConfig = NetworkConfig(
            propagateTraceContext = true,
            captureRequestHeaders = listOf("Content-Type", "User-Agent", "Accept"),
            captureResponseHeaders = listOf("Content-Type", "Content-Length"),
            captureRequestBody = true,
            captureResponseBody = true,
            maxBodyCaptureBytes = 4096,
            scrubUrls = true,
            scrubHeaders = true,
            detectNetworkType = true,
            reportNetworkSpeed = true,
            bucketSizes = true
        )

        /**
         * Production configuration - balanced privacy and observability.
         */
        fun production(): NetworkConfig = NetworkConfig(
            propagateTraceContext = true,
            captureRequestHeaders = listOf("Content-Type"),
            captureResponseHeaders = listOf("Content-Type"),
            captureRequestBody = false,
            captureResponseBody = false,
            scrubUrls = true,
            scrubHeaders = true,
            detectNetworkType = true,
            reportNetworkSpeed = false,
            bucketSizes = true,
            minDurationMs = 100  // Only trace slow requests
        )
    }
}
