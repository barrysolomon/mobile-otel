// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import io.opentelemetry.android.mobile.errors.ErrorConfig
import io.opentelemetry.android.mobile.errors.ErrorInstrumentation
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Task 4: Error filter matrix — 6 exception types x 3 filter configs.
 *
 * Tests that [ErrorInstrumentation] correctly captures or filters exceptions
 * based on [ErrorConfig] filter settings.
 *
 * Exception types:
 *  1. RuntimeException — always captured (never filtered)
 *  2. IllegalStateException — always captured
 *  3. NullPointerException — always captured
 *  4. java.net.SocketTimeoutException — filtered by default config
 *  5. java.util.concurrent.CancellationException — filtered by production config
 *  6. ArithmeticException — always captured
 *
 * Filter configs:
 *  A. default — filters network I/O exceptions (SocketTimeout, Connect, etc.)
 *  B. production — filters CancellationException variants
 *  C. permissive (empty filterExceptions) — captures everything
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorFilterMatrixTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val logger get() = otelRule.openTelemetry.logsBridge.get("error-matrix")

    @Before
    fun setup() {
        resetSingleton()
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun initInstrumentation(config: ErrorConfig): ErrorInstrumentation {
        return ErrorInstrumentation.initialize(
            config = config,
            logger = logger
        )
    }

    private fun defaultConfig() = ErrorConfig.default()

    private fun productionConfig() = ErrorConfig.production()

    private fun permissiveConfig() = ErrorConfig(
        filterExceptions = emptyList(),
        captureUncaughtExceptions = false // avoid installing handler in tests
    )

    private fun resetSingleton() {
        try {
            val field = ErrorInstrumentation::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // Ignore
        }
    }

    // ── Default config (filters network I/O exceptions) ─────────────────────

    @Test
    fun `default config captures RuntimeException`() {
        val inst = initInstrumentation(defaultConfig())
        inst.captureException(RuntimeException("boom"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "RuntimeException should be captured by default config")
    }

    @Test
    fun `default config captures IllegalStateException`() {
        val inst = initInstrumentation(defaultConfig())
        inst.captureException(IllegalStateException("bad state"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "IllegalStateException should be captured by default config")
    }

    @Test
    fun `default config captures NullPointerException`() {
        val inst = initInstrumentation(defaultConfig())
        inst.captureException(NullPointerException("null ref"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "NullPointerException should be captured by default config")
    }

    @Test
    fun `default config filters SocketTimeoutException`() {
        val inst = initInstrumentation(defaultConfig())
        inst.captureException(java.net.SocketTimeoutException("timed out"), "uncaught")
        assertEquals(0, otelRule.logRecords.size, "SocketTimeoutException should be filtered by default config")
    }

    @Test
    fun `default config captures CancellationException`() {
        val inst = initInstrumentation(defaultConfig())
        inst.captureException(java.util.concurrent.CancellationException("cancelled"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "CancellationException should NOT be filtered by default config")
    }

    @Test
    fun `default config captures ArithmeticException`() {
        val inst = initInstrumentation(defaultConfig())
        inst.captureException(ArithmeticException("divide by zero"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "ArithmeticException should be captured by default config")
    }

    // ── Production config (filters CancellationException) ───────────────────

    @Test
    fun `production config captures RuntimeException`() {
        val inst = initInstrumentation(productionConfig())
        inst.captureException(RuntimeException("boom"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "RuntimeException should be captured by production config")
    }

    @Test
    fun `production config captures IllegalStateException`() {
        val inst = initInstrumentation(productionConfig())
        inst.captureException(IllegalStateException("bad state"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "IllegalStateException should be captured by production config")
    }

    @Test
    fun `production config captures NullPointerException`() {
        val inst = initInstrumentation(productionConfig())
        inst.captureException(NullPointerException("null ref"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "NullPointerException should be captured by production config")
    }

    @Test
    fun `production config captures SocketTimeoutException`() {
        // Production config does NOT include SocketTimeoutException in its filter list
        val inst = initInstrumentation(productionConfig())
        inst.captureException(java.net.SocketTimeoutException("timed out"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "SocketTimeoutException should be captured by production config")
    }

    @Test
    fun `production config filters CancellationException`() {
        val inst = initInstrumentation(productionConfig())
        inst.captureException(java.util.concurrent.CancellationException("cancelled"), "uncaught")
        assertEquals(0, otelRule.logRecords.size, "CancellationException should be filtered by production config")
    }

    @Test
    fun `production config captures ArithmeticException`() {
        val inst = initInstrumentation(productionConfig())
        inst.captureException(ArithmeticException("divide by zero"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "ArithmeticException should be captured by production config")
    }

    // ── Permissive config (empty filter — captures everything) ──────────────

    @Test
    fun `permissive config captures RuntimeException`() {
        val inst = initInstrumentation(permissiveConfig())
        inst.captureException(RuntimeException("boom"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "RuntimeException should be captured by permissive config")
    }

    @Test
    fun `permissive config captures IllegalStateException`() {
        val inst = initInstrumentation(permissiveConfig())
        inst.captureException(IllegalStateException("bad state"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "IllegalStateException should be captured by permissive config")
    }

    @Test
    fun `permissive config captures NullPointerException`() {
        val inst = initInstrumentation(permissiveConfig())
        inst.captureException(NullPointerException("null ref"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "NullPointerException should be captured by permissive config")
    }

    @Test
    fun `permissive config captures SocketTimeoutException`() {
        val inst = initInstrumentation(permissiveConfig())
        inst.captureException(java.net.SocketTimeoutException("timed out"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "SocketTimeoutException should be captured by permissive config")
    }

    @Test
    fun `permissive config captures CancellationException`() {
        val inst = initInstrumentation(permissiveConfig())
        inst.captureException(java.util.concurrent.CancellationException("cancelled"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "CancellationException should be captured by permissive config")
    }

    @Test
    fun `permissive config captures ArithmeticException`() {
        val inst = initInstrumentation(permissiveConfig())
        inst.captureException(ArithmeticException("divide by zero"), "uncaught")
        assertEquals(1, otelRule.logRecords.size, "ArithmeticException should be captured by permissive config")
    }
}
