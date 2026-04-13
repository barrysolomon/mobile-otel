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
import kotlin.test.assertTrue

/**
 * Task 5: Rate limit matrix -- tests ErrorInstrumentation rate limiting
 * with dedup disabled (1ms window) and varying rateLimit values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RateLimitMatrixTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val logger get() = otelRule.openTelemetry.logsBridge.get("rate-limit-matrix")

    @Before
    fun setup() {
        resetSingleton()
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    private fun init(rateLimit: Int): ErrorInstrumentation =
        ErrorInstrumentation.initialize(
            config = ErrorConfig(
                rateLimit = rateLimit,
                deduplicateWindowMs = 1, // effectively disable dedup
                captureUncaughtExceptions = false,
                flushOnError = false
            ),
            logger = logger
        )

    private fun resetSingleton() {
        try {
            val field = ErrorInstrumentation::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {}
    }

    private fun fireExceptions(inst: ErrorInstrumentation, count: Int) {
        repeat(count) { i ->
            Thread.sleep(5) // exceed 1ms dedup window
            inst.captureException(RuntimeException("error-$i"), "manual")
        }
    }

    // ── 1. 5 exceptions, limit 10 → 5 captured ──────────────────────────────

    @Test
    fun `5 exceptions under limit 10 all captured`() {
        val inst = init(rateLimit = 10)
        fireExceptions(inst, 5)
        assertEquals(5, otelRule.logRecords.size,
            "5 exceptions under limit of 10 should all be captured")
    }

    // ── 2. 10 exceptions, limit 10 → 10 captured ────────────────────────────

    @Test
    fun `10 exceptions at limit 10 all captured`() {
        val inst = init(rateLimit = 10)
        fireExceptions(inst, 10)
        assertEquals(10, otelRule.logRecords.size,
            "10 exceptions at limit of 10 should all be captured")
    }

    // ── 3. 15 exceptions, limit 10 → capped at ≤ 10 ─────────────────────────

    @Test
    fun `15 exceptions over limit 10 capped`() {
        val inst = init(rateLimit = 10)
        fireExceptions(inst, 15)
        assertTrue(otelRule.logRecords.size <= 10,
            "15 exceptions with limit of 10 should cap at <= 10, got ${otelRule.logRecords.size}")
    }
}
