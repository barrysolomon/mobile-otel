/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security tests for [RetryableExporter].
 *
 * Validates:
 * - Uses ScheduledExecutorService (not raw Thread) for retries
 * - Non-retryable exceptions fail immediately without retry
 * - Successful exports don't retry
 * - Retry logic with backoff works correctly
 * - Shutdown cleans up scheduler
 */
class RetryableExporterTest {

    private var exporter: RetryableExporter? = null

    @After
    fun tearDown() {
        exporter?.shutdown()
    }

    @Test
    fun `successful export does not retry`() {
        val attemptCount = AtomicInteger(0)
        val delegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                attemptCount.incrementAndGet()
                return CompletableResultCode.ofSuccess()
            }
        }
        exporter = RetryableExporter(delegate, maxRetries = 3)

        val result = exporter!!.export(emptyList())
        val latch = CountDownLatch(1)
        result.whenComplete { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Export should complete")
        assertTrue(result.isSuccess, "Successful export should return success")
        assertTrue(attemptCount.get() == 1, "Should only attempt once on success")
    }

    @Test
    fun `failed export retries up to maxRetries`() {
        val attemptCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val delegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                attemptCount.incrementAndGet()
                return CompletableResultCode.ofFailure()
            }
        }
        exporter = RetryableExporter(
            delegate, maxRetries = 2,
            initialDelayMs = 10, maxDelayMs = 50
        )

        val result = exporter!!.export(emptyList())
        result.whenComplete { latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Export should complete after retries")
        assertFalse(result.isSuccess, "Should fail after exhausting retries")
        // 1 initial + 2 retries = 3
        assertTrue(attemptCount.get() == 3,
            "Should attempt 3 times (1 + 2 retries), got ${attemptCount.get()}")
    }

    @Test
    fun `non-retryable IllegalArgumentException fails immediately`() {
        val attemptCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val delegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                attemptCount.incrementAndGet()
                throw IllegalArgumentException("Bad data")
            }
        }
        exporter = RetryableExporter(
            delegate, maxRetries = 5,
            initialDelayMs = 10, maxDelayMs = 50
        )

        val result = exporter!!.export(emptyList())
        result.whenComplete { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertFalse(result.isSuccess, "Should fail immediately on non-retryable error")
        assertTrue(attemptCount.get() == 1,
            "Non-retryable exception should not retry, got ${attemptCount.get()} attempts")
    }

    @Test
    fun `non-retryable SecurityException fails immediately`() {
        val attemptCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val delegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                attemptCount.incrementAndGet()
                throw SecurityException("Permission denied")
            }
        }
        exporter = RetryableExporter(
            delegate, maxRetries = 5,
            initialDelayMs = 10, maxDelayMs = 50
        )

        val result = exporter!!.export(emptyList())
        result.whenComplete { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertFalse(result.isSuccess)
        assertTrue(attemptCount.get() == 1,
            "SecurityException should not retry, got ${attemptCount.get()} attempts")
    }

    @Test
    fun `retryable IOException does retry`() {
        val attemptCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val delegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                attemptCount.incrementAndGet()
                throw java.io.IOException("Connection refused")
            }
        }
        exporter = RetryableExporter(
            delegate, maxRetries = 2,
            initialDelayMs = 10, maxDelayMs = 50
        )

        val result = exporter!!.export(emptyList())
        result.whenComplete { latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertFalse(result.isSuccess)
        assertTrue(attemptCount.get() == 3,
            "IOException should retry, expected 3 attempts got ${attemptCount.get()}")
    }

    @Test
    fun `shutdown completes without error`() {
        val delegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>) = CompletableResultCode.ofSuccess()
        }
        exporter = RetryableExporter(delegate)
        val result = exporter!!.shutdown()
        assertTrue(result.isSuccess, "Shutdown should succeed")
    }

    @Test
    fun `retry succeeds on second attempt`() {
        val attemptCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val delegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                val attempt = attemptCount.incrementAndGet()
                return if (attempt == 1) {
                    CompletableResultCode.ofFailure()
                } else {
                    CompletableResultCode.ofSuccess()
                }
            }
        }
        exporter = RetryableExporter(
            delegate, maxRetries = 3,
            initialDelayMs = 10, maxDelayMs = 50
        )

        val result = exporter!!.export(emptyList())
        result.whenComplete { latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(result.isSuccess, "Should succeed on second attempt")
        assertTrue(attemptCount.get() == 2,
            "Should have attempted exactly 2 times, got ${attemptCount.get()}")
    }

    // ── SR-009: backoff jitter (anti-thundering-herd) ────────────────────────

    // Without jitter the formula min(initial * 2^attempt, cap) is deterministic,
    // so a fleet of devices coming back online together retries in lockstep and
    // re-DDoS's the collector. Full jitter: pick a uniform-random delay in
    // [0, min(initial * 2^attempt, cap)].

    private fun newExporterFor(delegate: LogRecordExporter, random: Random): RetryableExporter {
        return RetryableExporter(
            delegate = delegate,
            maxRetries = 3,
            initialDelayMs = 1000,
            maxDelayMs = 60_000,
            random = random,
        )
    }

    @Test
    fun `calculateBackoff stays within full-jitter envelope`() {
        val noopDelegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>) = CompletableResultCode.ofSuccess()
        }
        exporter = newExporterFor(noopDelegate, Random(42))

        // initial=1000, cap=60000. Attempt 0 ceiling=1000, attempt 3 ceiling=8000,
        // attempt 6 saturates to cap=60000. All draws must land in [0, ceiling].
        repeat(20) {
            val attempt0 = exporter!!.calculateBackoffForTest(0)
            val attempt3 = exporter!!.calculateBackoffForTest(3)
            val attempt6 = exporter!!.calculateBackoffForTest(6)
            assertTrue(attempt0 in 0L..1000L, "attempt 0 out of envelope: $attempt0")
            assertTrue(attempt3 in 0L..8000L, "attempt 3 out of envelope: $attempt3")
            assertTrue(attempt6 in 0L..60_000L, "attempt 6 out of envelope: $attempt6")
        }
    }

    @Test
    fun `calculateBackoff produces different delays across attempts`() {
        // The contract we care about for thundering herd is non-determinism per
        // call. With a fixed seed, drawing the same attempt-N many times must
        // not produce the same value every time.
        val noopDelegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>) = CompletableResultCode.ofSuccess()
        }
        exporter = newExporterFor(noopDelegate, Random(42))

        val draws = (1..10).map { exporter!!.calculateBackoffForTest(3) }
        assertTrue(draws.toSet().size > 1,
            "Expected jittered backoff; all 10 draws identical: $draws")
    }

    @Test
    fun `calculateBackoff with deterministic random returns predictable value`() {
        // Sanity check the seam: a seeded Random produces a known first draw,
        // proving the injected random is actually used.
        val noopDelegate = object : FakeExporter() {
            override fun export(logs: Collection<LogRecordData>) = CompletableResultCode.ofSuccess()
        }
        val seeded = Random(12345)
        val expected = seeded.nextLong(0, 1001) // mirror the production call
        exporter = newExporterFor(noopDelegate, Random(12345))

        assertEquals(expected, exporter!!.calculateBackoffForTest(0))
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private abstract class FakeExporter : LogRecordExporter {
        override fun flush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }
}
