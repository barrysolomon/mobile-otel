// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.errors

import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Integration test: verifies that ErrorInstrumentation.captureException()
 * respects the filter list — network I/O exceptions are silently dropped,
 * real app crashes are captured.
 *
 * Uses ErrorInstrumentation.initialize() (singleton) and resets between tests.
 */
class ErrorCaptureFilterTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @After
    fun tearDown() {
        ErrorInstrumentation.reset()
    }

    private fun initInstrumentation(config: ErrorConfig = ErrorConfig()): ErrorInstrumentation {
        // Reset singleton for test isolation
        ErrorInstrumentation.reset()
        val logger = otelRule.openTelemetry.logsBridge.get("test-errors")
        return ErrorInstrumentation.initialize(config, logger)
    }

    @Test
    fun `SocketTimeoutException is silently dropped`() {
        val inst = initInstrumentation()
        inst.captureException(java.net.SocketTimeoutException("timeout"), "uncaught")
        assertEquals(0, otelRule.logRecords.size, "SocketTimeoutException should not produce app.crash")
    }

    @Test
    fun `ConnectException is silently dropped`() {
        val inst = initInstrumentation()
        inst.captureException(java.net.ConnectException("Connection refused"), "uncaught")
        assertEquals(0, otelRule.logRecords.size)
    }

    @Test
    fun `UnknownHostException is silently dropped`() {
        val inst = initInstrumentation()
        inst.captureException(java.net.UnknownHostException("no such host"), "uncaught")
        assertEquals(0, otelRule.logRecords.size)
    }

    @Test
    fun `RuntimeException IS captured as app_crash`() {
        val inst = initInstrumentation()
        inst.captureException(RuntimeException("real crash"), "uncaught")
        val crashes = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.crash" }
        assertEquals(1, crashes.size, "RuntimeException should produce exactly one app.crash")
    }

    @Test
    fun `NullPointerException IS captured as app_crash`() {
        val inst = initInstrumentation()
        inst.captureException(NullPointerException("npe"), "uncaught")
        val crashes = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.crash" }
        assertEquals(1, crashes.size)
    }

    @Test
    fun `multiple SocketTimeoutExceptions produce zero events`() {
        val inst = initInstrumentation()
        repeat(5) {
            inst.captureException(java.net.SocketTimeoutException("timeout $it"), "uncaught")
        }
        assertEquals(0, otelRule.logRecords.size, "All network timeouts should be filtered")
    }

    @Test
    fun `mixed exceptions - only real crashes captured`() {
        val inst = initInstrumentation()
        inst.captureException(java.net.SocketTimeoutException("timeout"), "uncaught")
        inst.captureException(RuntimeException("real crash"), "uncaught")
        inst.captureException(java.net.ConnectException("refused"), "uncaught")
        inst.captureException(IllegalStateException("bad state"), "uncaught")

        val crashes = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.crash" }
        assertEquals(2, crashes.size, "Only RuntimeException and IllegalStateException should be captured")
    }

    @Test
    fun `empty filter list captures all exceptions including network`() {
        val inst = initInstrumentation(ErrorConfig(filterExceptions = emptyList()))
        inst.captureException(java.net.SocketTimeoutException("timeout"), "uncaught")
        val crashes = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.crash" }
        assertEquals(1, crashes.size, "With empty filter, network exceptions should be captured")
    }
}
