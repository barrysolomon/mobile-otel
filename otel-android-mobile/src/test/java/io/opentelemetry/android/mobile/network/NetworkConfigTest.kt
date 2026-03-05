/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkConfigTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `default config has privacy-safe values`() {
        val config = NetworkConfig.default()
        assertTrue(config.enabled)
        assertTrue(config.scrubUrls, "URLs must be scrubbed by default")
        assertTrue(config.scrubHeaders, "Headers must be scrubbed by default")
        assertFalse(config.captureRequestBody, "Request body must not be captured by default")
        assertFalse(config.captureResponseBody, "Response body must not be captured by default")
    }

    @Test
    fun `default config propagates trace context`() {
        assertTrue(NetworkConfig.default().propagateTraceContext)
    }

    @Test
    fun `default config captures Content-Type response header only`() {
        val config = NetworkConfig.default()
        assertTrue(config.captureRequestHeaders.isEmpty())
        assertEquals(listOf("Content-Type"), config.captureResponseHeaders)
    }

    // ── Preset configs ────────────────────────────────────────────────────────

    @Test
    fun `minimal config captures no headers`() {
        val config = NetworkConfig.minimal()
        assertTrue(config.captureRequestHeaders.isEmpty())
        assertTrue(config.captureResponseHeaders.isEmpty())
    }

    @Test
    fun `minimal config disables network type detection`() {
        val config = NetworkConfig.minimal()
        assertFalse(config.detectNetworkType)
        assertFalse(config.reportNetworkSpeed)
        assertFalse(config.bucketSizes)
    }

    @Test
    fun `debug config captures request and response bodies`() {
        val config = NetworkConfig.debug()
        assertTrue(config.captureRequestBody)
        assertTrue(config.captureResponseBody)
        assertEquals(4096, config.maxBodyCaptureBytes)
    }

    @Test
    fun `debug config still scrubs URLs and headers`() {
        val config = NetworkConfig.debug()
        assertTrue(config.scrubUrls, "Even debug mode must scrub URLs")
        assertTrue(config.scrubHeaders, "Even debug mode must scrub headers")
    }

    @Test
    fun `production config disables body capture`() {
        val config = NetworkConfig.production()
        assertFalse(config.captureRequestBody)
        assertFalse(config.captureResponseBody)
    }

    @Test
    fun `production config only traces slow requests`() {
        val config = NetworkConfig.production()
        assertEquals(100, config.minDurationMs)
    }

    // ── shouldCaptureRequestHeader ────────────────────────────────────────────

    @Test
    fun `shouldCaptureRequestHeader allows listed non-sensitive headers`() {
        val config = NetworkConfig(
            captureRequestHeaders = listOf("Content-Type", "User-Agent"),
            scrubHeaders = true
        )
        assertTrue(config.shouldCaptureRequestHeader("Content-Type"))
        assertTrue(config.shouldCaptureRequestHeader("User-Agent"))
    }

    @Test
    fun `shouldCaptureRequestHeader blocks Authorization even if allowlisted`() {
        val config = NetworkConfig(
            captureRequestHeaders = listOf("Authorization"),
            scrubHeaders = true
        )
        assertFalse(config.shouldCaptureRequestHeader("Authorization"))
    }

    @Test
    fun `shouldCaptureRequestHeader blocks Cookie`() {
        val config = NetworkConfig(
            captureRequestHeaders = listOf("Cookie"),
            scrubHeaders = true
        )
        assertFalse(config.shouldCaptureRequestHeader("Cookie"))
    }

    @Test
    fun `shouldCaptureRequestHeader blocks X-API-Key`() {
        val config = NetworkConfig(
            captureRequestHeaders = listOf("X-API-Key"),
            scrubHeaders = true
        )
        assertFalse(config.shouldCaptureRequestHeader("X-API-Key"))
    }

    @Test
    fun `shouldCaptureRequestHeader is case-insensitive for sensitive headers`() {
        val config = NetworkConfig(
            captureRequestHeaders = listOf("authorization"),
            scrubHeaders = true
        )
        assertFalse(config.shouldCaptureRequestHeader("authorization"))
        assertFalse(config.shouldCaptureRequestHeader("AUTHORIZATION"))
    }

    @Test
    fun `shouldCaptureRequestHeader returns false when disabled`() {
        val config = NetworkConfig(
            enabled = false,
            captureRequestHeaders = listOf("Content-Type")
        )
        assertFalse(config.shouldCaptureRequestHeader("Content-Type"))
    }

    @Test
    fun `shouldCaptureRequestHeader returns false for unlisted header`() {
        val config = NetworkConfig(captureRequestHeaders = listOf("Content-Type"))
        assertFalse(config.shouldCaptureRequestHeader("X-Custom-Header"))
    }

    @Test
    fun `shouldCaptureRequestHeader allows sensitive header when scrubHeaders is false`() {
        val config = NetworkConfig(
            captureRequestHeaders = listOf("Authorization"),
            scrubHeaders = false
        )
        assertTrue(config.shouldCaptureRequestHeader("Authorization"))
    }

    // ── shouldCaptureResponseHeader ───────────────────────────────────────────

    @Test
    fun `shouldCaptureResponseHeader allows non-sensitive listed headers`() {
        val config = NetworkConfig(captureResponseHeaders = listOf("Content-Type", "Content-Length"))
        assertTrue(config.shouldCaptureResponseHeader("Content-Type"))
        assertTrue(config.shouldCaptureResponseHeader("Content-Length"))
    }

    @Test
    fun `shouldCaptureResponseHeader blocks Set-Cookie`() {
        val config = NetworkConfig(
            captureResponseHeaders = listOf("Set-Cookie"),
            scrubHeaders = true
        )
        assertFalse(config.shouldCaptureResponseHeader("Set-Cookie"))
    }

    @Test
    fun `shouldCaptureResponseHeader blocks WWW-Authenticate`() {
        val config = NetworkConfig(
            captureResponseHeaders = listOf("WWW-Authenticate"),
            scrubHeaders = true
        )
        assertFalse(config.shouldCaptureResponseHeader("WWW-Authenticate"))
    }

    // ── shouldInstrumentHost ──────────────────────────────────────────────────

    @Test
    fun `shouldInstrumentHost allows all hosts when no lists configured`() {
        val config = NetworkConfig()
        assertTrue(config.shouldInstrumentHost("api.example.com"))
        assertTrue(config.shouldInstrumentHost("other.host.io"))
    }

    @Test
    fun `shouldInstrumentHost blocks hosts in blocked list`() {
        val config = NetworkConfig(blockedHosts = listOf("analytics.example.com"))
        assertFalse(config.shouldInstrumentHost("analytics.example.com"))
    }

    @Test
    fun `shouldInstrumentHost blocks subdomains of blocked hosts`() {
        val config = NetworkConfig(blockedHosts = listOf("example.com"))
        assertFalse(config.shouldInstrumentHost("sub.example.com"))
    }

    @Test
    fun `shouldInstrumentHost only allows listed hosts when allowlist is set`() {
        val config = NetworkConfig(allowedHosts = listOf("api.myapp.com"))
        assertTrue(config.shouldInstrumentHost("api.myapp.com"))
        assertFalse(config.shouldInstrumentHost("other.myapp.com"))
    }

    @Test
    fun `shouldInstrumentHost blocked list takes precedence over allowed list`() {
        val config = NetworkConfig(
            allowedHosts = listOf("api.example.com"),
            blockedHosts = listOf("api.example.com")
        )
        assertFalse(config.shouldInstrumentHost("api.example.com"))
    }

    @Test
    fun `shouldInstrumentHost returns false when disabled`() {
        val config = NetworkConfig(enabled = false)
        assertFalse(config.shouldInstrumentHost("api.example.com"))
    }

    @Test
    fun `shouldInstrumentHost is case-insensitive`() {
        val config = NetworkConfig(blockedHosts = listOf("ANALYTICS.EXAMPLE.COM"))
        assertFalse(config.shouldInstrumentHost("analytics.example.com"))
    }

    // ── getSizeBucket ─────────────────────────────────────────────────────────

    @Test
    fun `getSizeBucket buckets small requests`() {
        val config = NetworkConfig()
        assertEquals("<1KB", config.getSizeBucket(512))
        assertEquals("1-10KB", config.getSizeBucket(2048))
        assertEquals("10-100KB", config.getSizeBucket(50 * 1024))
        assertEquals("100KB-1MB", config.getSizeBucket(512 * 1024))
        assertEquals("1-10MB", config.getSizeBucket(5 * 1024 * 1024))
        assertEquals(">10MB", config.getSizeBucket(50 * 1024 * 1024))
    }

    @Test
    fun `getSizeBucket returns raw bytes when bucketing disabled`() {
        val config = NetworkConfig(bucketSizes = false)
        assertEquals("12345", config.getSizeBucket(12345))
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `maxBodyCaptureBytes must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            NetworkConfig(maxBodyCaptureBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkConfig(maxBodyCaptureBytes = -1)
        }
    }

    @Test
    fun `minDurationMs must be non-negative`() {
        assertFailsWith<IllegalArgumentException> {
            NetworkConfig(minDurationMs = -1)
        }
    }

    @Test
    fun `errorStatusThreshold must be valid HTTP status`() {
        assertFailsWith<IllegalArgumentException> {
            NetworkConfig(errorStatusThreshold = 99)
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkConfig(errorStatusThreshold = 600)
        }
    }

    @Test
    fun `valid errorStatusThreshold values are accepted`() {
        NetworkConfig(errorStatusThreshold = 400)
        NetworkConfig(errorStatusThreshold = 500)
        NetworkConfig(errorStatusThreshold = 100)
        NetworkConfig(errorStatusThreshold = 599)
    }
}
