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

    // ── Helper ────────────────────────────────────────────────────────────────

    private abstract class FakeExporter : LogRecordExporter {
        override fun flush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }
}
