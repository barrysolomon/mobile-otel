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
 * Task 5: Deduplication matrix -- tests ErrorInstrumentation dedup behaviour
 * with varying deduplicateWindowMs settings and exception fingerprints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeduplicationMatrixTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val logger get() = otelRule.openTelemetry.logsBridge.get("dedup-matrix")

    @Before
    fun setup() {
        resetSingleton()
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    private fun init(config: ErrorConfig): ErrorInstrumentation =
        ErrorInstrumentation.initialize(config = config, logger = logger)

    private fun resetSingleton() {
        try {
            val field = ErrorInstrumentation::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {}
    }

    // ── 1. Same exception twice within window → 1 event ─────────────────────

    @Test
    fun `same exception within dedup window produces 1 event`() {
        val inst = init(ErrorConfig(
            deduplicateWindowMs = 300_000,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        val ex = RuntimeException("same error")
        inst.captureException(ex, "manual")
        inst.captureException(ex, "manual")
        assertEquals(1, otelRule.logRecords.size,
            "Same exception within 5-minute window should be deduplicated to 1 event")
    }

    // ── 2. Same exception with dedup=1ms (effectively 0) → 2 events ─────────

    @Test
    fun `same exception with minimal dedup window produces 2 events`() {
        val inst = init(ErrorConfig(
            deduplicateWindowMs = 1,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        val ex = RuntimeException("same error")
        inst.captureException(ex, "manual")
        Thread.sleep(5) // exceed the 1ms window
        inst.captureException(ex, "manual")
        assertEquals(2, otelRule.logRecords.size,
            "Same exception outside the 1ms dedup window should produce 2 events")
    }

    // ── 3. Different exceptions within window → 2 events ─────────────────────

    @Test
    fun `different exceptions within window produce 2 events`() {
        val inst = init(ErrorConfig(
            deduplicateWindowMs = 300_000,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        inst.captureException(RuntimeException("error A"), "manual")
        inst.captureException(IllegalStateException("error B"), "manual")
        assertEquals(2, otelRule.logRecords.size,
            "Different exception types should not be deduplicated")
    }

    // ── 4. Same message, different stack frame → 2 events (different fingerprint)

    @Test
    fun `same message different stack frame produces 2 events`() {
        val inst = init(ErrorConfig(
            deduplicateWindowMs = 300_000,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        // Throw from two different call sites to get different top stack frames
        fun throwFromSiteA(): RuntimeException {
            return RuntimeException("same message")
        }
        fun throwFromSiteB(): RuntimeException {
            return RuntimeException("same message")
        }
        inst.captureException(throwFromSiteA(), "manual")
        inst.captureException(throwFromSiteB(), "manual")
        assertEquals(2, otelRule.logRecords.size,
            "Same message but different stack frames should produce different fingerprints and 2 events")
    }
}
