/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [MobileConfig.buildOtlpHttpUrl], the OTLP/HTTP per-signal
 * URL-builder that mirrors the iOS `OTLPExporterFactory` so Android and iOS
 * resolve the same ingest endpoint from one `collectorEndpoint`.
 *
 * Pure JVM test (no Android/Robolectric) — the helper is plain string logic.
 */
class OtlpHttpUrlTest {

    @Test
    fun `appends v1 logs to a bare base endpoint`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/logs",
            MobileConfig.buildOtlpHttpUrl("https://ingress.us-west-2.aws.dash0.com", "/v1/logs")
        )
    }

    @Test
    fun `appends v1 traces to a bare base endpoint`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/traces",
            MobileConfig.buildOtlpHttpUrl("https://ingress.us-west-2.aws.dash0.com", "/v1/traces")
        )
    }

    @Test
    fun `appends v1 metrics to a bare base endpoint`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/metrics",
            MobileConfig.buildOtlpHttpUrl("https://ingress.us-west-2.aws.dash0.com", "/v1/metrics")
        )
    }

    @Test
    fun `appends signal when endpoint has an explicit port`() {
        assertEquals(
            "https://collector.example.com:4318/v1/logs",
            MobileConfig.buildOtlpHttpUrl("https://collector.example.com:4318", "/v1/logs")
        )
    }

    @Test
    fun `single trailing slash on base does not double the slash`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/logs",
            MobileConfig.buildOtlpHttpUrl("https://ingress.us-west-2.aws.dash0.com/", "/v1/logs")
        )
    }

    @Test
    fun `multiple trailing slashes are collapsed before appending`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/traces",
            MobileConfig.buildOtlpHttpUrl("https://ingress.us-west-2.aws.dash0.com///", "/v1/traces")
        )
    }

    @Test
    fun `endpoint already suffixed with the signal is left untouched`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/logs",
            MobileConfig.buildOtlpHttpUrl("https://ingress.us-west-2.aws.dash0.com/v1/logs", "/v1/logs")
        )
    }

    @Test
    fun `endpoint suffixed with the signal and a trailing slash is normalized once`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/metrics",
            MobileConfig.buildOtlpHttpUrl("https://ingress.us-west-2.aws.dash0.com/v1/metrics/", "/v1/metrics")
        )
    }

    @Test
    fun `base path prefix is preserved when appending the signal`() {
        // A proxy may mount the collector under a sub-path; the suffix must be
        // appended after that path, not replace it.
        assertEquals(
            "https://proxy.example.com/otel/v1/traces",
            MobileConfig.buildOtlpHttpUrl("https://proxy.example.com/otel", "/v1/traces")
        )
    }

    @Test
    fun `query string is preserved and the signal lands on the path`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/logs?dataset=mobile",
            MobileConfig.buildOtlpHttpUrl("https://ingress.us-west-2.aws.dash0.com?dataset=mobile", "/v1/logs")
        )
    }

    @Test
    fun `surrounding whitespace on the endpoint is trimmed`() {
        assertEquals(
            "https://ingress.us-west-2.aws.dash0.com/v1/logs",
            MobileConfig.buildOtlpHttpUrl("  https://ingress.us-west-2.aws.dash0.com  ", "/v1/logs")
        )
    }
}
