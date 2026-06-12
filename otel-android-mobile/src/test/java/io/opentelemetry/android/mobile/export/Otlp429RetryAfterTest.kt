/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.export

import com.sun.net.httpserver.HttpServer
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.sdk.testing.logs.TestLogRecordData
import io.opentelemetry.sdk.resources.Resource
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 429 / Retry-After proof (TEST_HARDENING_PLAN P1): when the collector
 * rate-limits, the export path must (a) retry rather than drop, and (b) wait
 * at least the server-mandated `Retry-After` before retrying — otherwise a
 * fleet that just got rate-limited hammers the collector again in lockstep
 * and keeps itself rate-limited.
 *
 * Retrying lives one layer below [io.opentelemetry.android.mobile.buffering.RetryableExporter]:
 * the upstream OTLP exporter's built-in retry policy (enabled by default in
 * otel-java, handling 429/502/503/504). The SDK relies on that default —
 * nothing in MobileLoggerProvider configures it — so this class pins the
 * relied-upon behavior AGAINST A REAL HTTP EXCHANGE in exactly the
 * configuration MobileLoggerProvider builds (endpoint + timeout, no explicit
 * RetryPolicy). Writing it found that (b) does NOT hold — see the pinned-gap
 * test below. If an otel-java upgrade ever turns the default retry off, the
 * other tests fail instead of production fleets discovering it during their
 * next collector brownout.
 */
class Otlp429RetryAfterTest {

    private lateinit var server: HttpServer
    private val requestTimes = Collections.synchronizedList(mutableListOf<Long>())

    @Before
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = null
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun endpoint() = "http://127.0.0.1:${server.address.port}"

    private fun record() = TestLogRecordData.builder()
        .setResource(Resource.empty())
        .setBody("rate-limited-event")
        .setTimestamp(Instant.now())
        .setObservedTimestamp(Instant.now())
        .build()

    /** Exporter built the way MobileLoggerProvider builds it: endpoint + timeout only. */
    private fun exporter() = OtlpHttpLogRecordExporter.builder()
        .setEndpoint("${endpoint()}/v1/logs")
        .setTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * KNOWN GAP, pinned: otel-java's OkHttp sender retries a 429 with its own
     * exponential backoff but does NOT read the Retry-After header (verified
     * against the sender jar — RetryInterceptor has no Retry-After handling
     * through at least 1.59). We cannot honor it from our layer either:
     * neither RetryableExporter nor the OTLP builder ever sees response
     * headers. Mitigation for fleet-synchronized retry storms is therefore
     * RetryableExporter's full-jitter backoff (SR-009), not server-mandated
     * pacing.
     *
     * This test pins BOTH halves of that reality: the 429 IS retried with
     * backoff (don't drop), and the retry arrives EARLIER than the mandated
     * Retry-After. If an otel-java upgrade starts honoring Retry-After, the
     * second assertion fails on purpose — tighten this test into a real
     * Retry-After conformance check and update TEST_HARDENING_PLAN.
     */
    @Test
    fun `429 with Retry-After is retried with backoff - upstream ignores the header (pinned gap)`() {
        val retryAfterSeconds = 5L
        server.createContext("/v1/logs") { exchange ->
            requestTimes.add(System.nanoTime())
            if (requestTimes.size == 1) {
                exchange.responseHeaders.add("Retry-After", retryAfterSeconds.toString())
                exchange.sendResponseHeaders(429, -1)
            } else {
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.close()
        }

        val result = exporter().export(listOf(record())).join(30, TimeUnit.SECONDS)

        assertTrue("Export must eventually succeed after the 429 (retry, don't drop)", result.isSuccess)
        assertTrue(
            "Expected at least 2 requests (initial + retry), saw ${requestTimes.size}",
            requestTimes.size >= 2,
        )
        val gapMs = (requestTimes[1] - requestTimes[0]) / 1_000_000
        assertTrue("Retry after a 429 must back off (>=100ms): ${gapMs}ms", gapMs >= 100)
        assertTrue(
            "Retry waited ${gapMs}ms >= Retry-After ${retryAfterSeconds}s — upstream otel-java " +
                "appears to now HONOR Retry-After. Good news: tighten this test into a real " +
                "conformance check and update the known-gap notes in TEST_HARDENING_PLAN.",
            gapMs < retryAfterSeconds * 1000,
        )
    }

    @Test
    fun `429 without Retry-After is still retried with backoff`() {
        server.createContext("/v1/logs") { exchange ->
            requestTimes.add(System.nanoTime())
            if (requestTimes.size == 1) {
                exchange.sendResponseHeaders(429, -1)
            } else {
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.close()
        }

        val result = exporter().export(listOf(record())).join(30, TimeUnit.SECONDS)

        assertTrue("Export must eventually succeed after a bare 429", result.isSuccess)
        assertTrue(
            "Expected a retry after the bare 429, saw ${requestTimes.size} request(s)",
            requestTimes.size >= 2,
        )
        val gapMs = (requestTimes[1] - requestTimes[0]) / 1_000_000
        assertTrue(
            "Retry after a bare 429 must back off (>=100ms), not hammer immediately: ${gapMs}ms",
            gapMs >= 100,
        )
    }

    @Test
    fun `non-retryable 400 is not retried`() {
        server.createContext("/v1/logs") { exchange ->
            requestTimes.add(System.nanoTime())
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
        }

        val result = exporter().export(listOf(record())).join(30, TimeUnit.SECONDS)

        assertTrue("A 400 (client bug) must fail, not loop", !result.isSuccess)
        assertTrue(
            "A 400 must not be retried (saw ${requestTimes.size} requests) — retrying a " +
                "malformed payload wastes battery and collector capacity",
            requestTimes.size == 1,
        )
    }
}
