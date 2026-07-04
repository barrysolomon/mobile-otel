/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * PR-017: RetryableExporter end-to-end test.
 *
 * Verifies the full pipeline: events → MobileLogRecordProcessor → RetryableExporter
 * → flaky collector (fails then recovers) → events successfully exported.
 *
 * This is NOT a unit test of RetryableExporter (those exist in RetryableExporterTest).
 * This tests the integration: retry logic + buffer management + no data loss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetryableExporterE2ETest {

    private lateinit var context: Context
    private lateinit var flakyExporter: FlakyExporter
    private lateinit var retryableExporter: RetryableExporter
    private lateinit var processor: MobileLogRecordProcessor

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        flakyExporter = FlakyExporter()

        // SR-009: production now uses full-jitter backoff. For E2E timing
        // assertions we pin a deterministic Random so the envelope contract
        // (each ceiling roughly doubles up to maxDelayMs) is measurable.
        // Real jitter is covered in RetryableExporterTest.
        retryableExporter = RetryableExporter(
            delegate = flakyExporter,
            maxRetries = 3,
            initialDelayMs = 50,
            maxDelayMs = 200,
            random = MaxJitterRandom,
        )

        val config = MobileConfig(
            serviceName = "retry-e2e-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = 100,
            diskBufferMb = 10,
            diskBufferTtlHours = 1
        )

        val meter = OpenTelemetry.noop().meterProvider.get("test")
        processor = MobileLogRecordProcessor.builder(context)
            .setExporter(retryableExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    @After
    fun teardown() {
        processor.shutdown()
        DiskLogBuffer.resetForTesting()
    }

    private fun wrap(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    @Test
    fun `events export successfully after collector recovers from transient failure`() {
        // Collector fails first 2 attempts, succeeds on 3rd
        flakyExporter.failCount = 2

        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("retry-e2e.$i")))
        }

        // Force flush triggers export → fails → retries asynchronously → eventually succeeds
        processor.forceFlush()
        // Wait for async retry pipeline (initial + 50ms backoff + 100ms backoff + margin)
        Thread.sleep(2000)

        assertTrue(
            "All 5 events should be exported after retry recovers, got ${flakyExporter.exportedLogs.size}",
            flakyExporter.exportedLogs.size >= 5
        )
        assertTrue(
            "Exporter should have been called at least 2 times (initial + retries)",
            flakyExporter.attemptCount.get() >= 2
        )
    }

    @Test
    fun `events remain in buffer when all retries exhausted`() {
        // Collector fails all attempts (failCount > maxRetries)
        flakyExporter.failCount = 100

        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("exhaust.$i")))
        }

        processor.forceFlush()
        Thread.sleep(2000)

        assertEquals(
            "No events should be exported when all retries exhausted",
            0, flakyExporter.exportedLogs.size
        )
        assertTrue(
            "Events must remain in buffer after exhausted retries",
            processor.getBufferStats().ramBufferSize > 0 ||
                processor.getBufferStats().diskBufferSize > 0
        )
    }

    @Test
    fun `exponential backoff delays increase between retries`() {
        flakyExporter.failCount = 3
        flakyExporter.trackTimestamps = true

        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("backoff.$i")))
        }

        processor.forceFlush()
        Thread.sleep(2000)

        val timestamps = flakyExporter.attemptTimestamps
        assertTrue(
            "Should have at least 3 attempts for backoff measurement, got ${timestamps.size}",
            timestamps.size >= 3
        )

        if (timestamps.size >= 3) {
            val delay1 = timestamps[1] - timestamps[0]
            val delay2 = timestamps[2] - timestamps[1]
            assertTrue(
                "Second delay ($delay2 ms) should be >= first delay ($delay1 ms) due to exponential backoff",
                delay2 >= delay1 * 0.8 // allow 20% jitter tolerance
            )
        }
    }

    @Test
    fun `no duplicate events after retry success`() {
        flakyExporter.failCount = 1

        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("nodup.$i")))
        }

        processor.forceFlush()
        Thread.sleep(1500)

        val nodupEvents = flakyExporter.exportedLogs.filter {
            it.body.asString().startsWith("nodup.")
        }
        assertEquals(
            "Each event should export exactly once after retry (no duplicates)",
            3, nodupEvents.size
        )
    }

    @Test
    fun `IOException triggers retry but events eventually export`() {
        flakyExporter.failWithException = true
        flakyExporter.failCount = 1

        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("ioerr.$i")))
        }

        processor.forceFlush()
        Thread.sleep(1500)

        assertTrue(
            "Events should export after IOException retry, got ${flakyExporter.exportedLogs.size}",
            flakyExporter.exportedLogs.size >= 3
        )
    }

    // ── Flaky exporter that simulates transient collector failures ────────────

    class FlakyExporter : LogRecordExporter {
        val attemptCount = AtomicInteger(0)
        var failCount = 0
        var failWithException = false
        var trackTimestamps = false

        val exportedLogs: MutableList<LogRecordData> = Collections.synchronizedList(mutableListOf())
        val attemptTimestamps: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
            val attempt = attemptCount.incrementAndGet()
            if (trackTimestamps) {
                attemptTimestamps.add(System.currentTimeMillis())
            }

            return if (attempt <= failCount) {
                if (failWithException) {
                    throw java.io.IOException("Simulated connection refused (attempt $attempt)")
                }
                CompletableResultCode.ofFailure()
            } else {
                exportedLogs.addAll(logs)
                CompletableResultCode.ofSuccess()
            }
        }

        override fun flush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }

    /**
     * Identity-jitter Random for deterministic E2E backoff assertions:
     * `nextLong(0, ceiling + 1)` always returns `ceiling`. Makes the realized
     * delay equal the envelope ceiling, so the envelope-doubling contract is
     * directly testable.
     */
    private object MaxJitterRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(from: Long, until: Long): Long = until - 1
    }
}
