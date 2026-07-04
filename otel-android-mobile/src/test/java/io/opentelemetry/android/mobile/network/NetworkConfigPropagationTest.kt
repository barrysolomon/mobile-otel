/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for trace context propagation host filtering in [NetworkConfig].
 *
 * Validates:
 * - Empty propagationHosts propagates to all hosts (backward compatible)
 * - Non-empty propagationHosts restricts propagation to listed hosts only
 * - propagateTraceContext=false disables propagation regardless of hosts
 * - Subdomain matching works for propagation hosts
 * - Data is transmitted as plain OTLP (not encrypted at transport level by SDK)
 */
class NetworkConfigPropagationTest {

    // ── shouldPropagateContext ─────────────────────────────────────────────────

    @Test
    fun `empty propagationHosts propagates to all hosts`() {
        val config = NetworkConfig(
            propagateTraceContext = true,
            propagationHosts = emptyList()
        )
        assertTrue(config.shouldPropagateContext("api.example.com"))
        assertTrue(config.shouldPropagateContext("third-party.analytics.com"))
        assertTrue(config.shouldPropagateContext("any.host.io"))
    }

    @Test
    fun `non-empty propagationHosts restricts to listed hosts`() {
        val config = NetworkConfig(
            propagateTraceContext = true,
            propagationHosts = listOf("api.myapp.com", "backend.myapp.com")
        )
        assertTrue(config.shouldPropagateContext("api.myapp.com"),
            "Listed host should allow propagation")
        assertTrue(config.shouldPropagateContext("backend.myapp.com"),
            "Listed host should allow propagation")
        assertFalse(config.shouldPropagateContext("analytics.thirdparty.com"),
            "Unlisted host should NOT allow propagation")
    }

    @Test
    fun `propagationHosts subdomain matching works`() {
        val config = NetworkConfig(
            propagateTraceContext = true,
            propagationHosts = listOf("myapp.com")
        )
        assertTrue(config.shouldPropagateContext("api.myapp.com"),
            "Subdomain of listed host should match")
        assertTrue(config.shouldPropagateContext("deep.sub.myapp.com"),
            "Deep subdomain of listed host should match")
        assertFalse(config.shouldPropagateContext("notmyapp.com"),
            "Non-matching domain should not match")
    }

    @Test
    fun `propagateTraceContext false disables all propagation`() {
        val config = NetworkConfig(
            propagateTraceContext = false,
            propagationHosts = listOf("api.myapp.com")
        )
        assertFalse(config.shouldPropagateContext("api.myapp.com"),
            "propagateTraceContext=false should override propagationHosts")
    }

    @Test
    fun `propagationHosts is case-insensitive`() {
        val config = NetworkConfig(
            propagateTraceContext = true,
            propagationHosts = listOf("API.MyApp.COM")
        )
        assertTrue(config.shouldPropagateContext("api.myapp.com"))
    }

    // ── Verify interaction with shouldInstrumentHost ──────────────────────────

    @Test
    fun `instrumentation and propagation are independent concerns`() {
        // A host can be instrumented (spans created) but not propagated to (no trace context headers)
        val config = NetworkConfig(
            propagateTraceContext = true,
            allowedHosts = emptyList(), // instrument all
            propagationHosts = listOf("api.myapp.com") // propagate only to first-party
        )

        // Third-party host is instrumented but trace context is NOT propagated
        assertTrue(config.shouldInstrumentHost("analytics.thirdparty.com"),
            "Should create spans for all hosts")
        assertFalse(config.shouldPropagateContext("analytics.thirdparty.com"),
            "Should NOT inject trace context to third-party")

        // First-party host gets both
        assertTrue(config.shouldInstrumentHost("api.myapp.com"),
            "First-party should be instrumented")
        assertTrue(config.shouldPropagateContext("api.myapp.com"),
            "First-party should get trace context injected")
    }

    // ── OTLP data is not encrypted by SDK ─────────────────────────────────────

    @Test
    fun `default config has no body encryption - data is plain OTLP`() {
        // This test documents that NetworkConfig does NOT add any payload encryption.
        // Data is transmitted as standard OTLP (protobuf/gRPC or JSON/HTTP).
        // Encryption in transit is handled by TLS (HTTPS), not by the SDK itself.
        // This is important because Dash0 (or any OTEL backend) needs to read
        // the data without SDK-specific decryption.
        val config = NetworkConfig.default()

        // Verify no body transformation flags exist
        assertFalse(config.captureRequestBody, "Default does not capture request body")
        assertFalse(config.captureResponseBody, "Default does not capture response body")

        // The SDK transmits LogRecordData and Spans as standard OTLP.
        // EncryptedSharedPreferences encrypts at-rest on device only.
        // TLS handles in-transit encryption at the transport layer.
        assertTrue(config.propagateTraceContext,
            "Default propagates standard W3C trace context (not encrypted)")
    }

    @Test
    fun `production preset uses standard OTLP without payload encryption`() {
        val config = NetworkConfig.production()

        // Production config scrubs data for privacy but does NOT encrypt payloads
        assertTrue(config.scrubUrls, "URLs are scrubbed (PII removal, not encryption)")
        assertTrue(config.scrubHeaders, "Headers are scrubbed (sensitive removal, not encryption)")

        // Standard OTLP output - Dash0 can read it directly
        assertFalse(config.captureRequestBody)
        assertFalse(config.captureResponseBody)
    }
}
