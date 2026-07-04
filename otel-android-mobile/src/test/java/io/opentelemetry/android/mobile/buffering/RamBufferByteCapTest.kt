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
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the RAM ring buffer byte caps (SDK_SAFETY non-negotiable #3,
 * iOS parity with `RAMEventBuffer.swift`):
 *
 *  - per-event byte cap ([MobileConfig.ramBufferMaxEventBytes]) — oversize
 *    events are dropped from RAM and counted ([BufferStats.droppedOversizeCount]);
 *  - total-byte budget ([MobileConfig.ramBufferMaxTotalBytes]) — enforced
 *    independently of the count cap by overflowing oldest events to disk;
 *  - byte accounting stays correct across add / evict.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RamBufferByteCapTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        mockExporter = MockLogRecordExporter()
    }

    @After
    fun teardown() {
        DiskLogBuffer.resetForTesting()
    }

    private fun wrap(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    /** Build a record whose estimated size is roughly [approxBytes] via a body string. */
    private fun sizedRecord(body: String, approxBytes: Int): LogRecordData {
        // estimateSize charges ~2 bytes/char for the body, so half as many chars.
        val padChars = (approxBytes / 2).coerceAtLeast(1)
        return TestUtils.createTestLogRecord("$body-" + "x".repeat(padChars))
    }

    private fun buildProcessor(
        ramBufferSize: Int = 100,
        maxTotalBytes: Long = 10L * 1024 * 1024,
        maxEventBytes: Int = 256 * 1024
    ): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = ramBufferSize,
            ramBufferMaxTotalBytes = maxTotalBytes,
            ramBufferMaxEventBytes = maxEventBytes,
            diskBufferMb = 10,
            diskBufferTtlHours = 1
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        return MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setRamBufferMaxTotalBytes(config.ramBufferMaxTotalBytes)
            .setRamBufferMaxEventBytes(config.ramBufferMaxEventBytes)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    /**
     * Poll [getBufferStats] to a deadline instead of sleeping. The disk
     * writes these tests observe are async (executor.submit / overflow /
     * post-flush cleanup); a fixed Thread.sleep races them and flakes on a
     * loaded CI runner (this class's sleeps DID flake — TEST_HARDENING_PLAN
     * bans them). Polls are linear-time and fail with the last-seen value.
     */
    private fun awaitStat(
        processor: MobileLogRecordProcessor,
        timeoutMs: Long = 10_000,
        desc: String,
        predicate: (BufferStats) -> Boolean,
    ): BufferStats {
        val deadline = System.currentTimeMillis() + timeoutMs
        var stats = processor.getBufferStats()
        while (System.currentTimeMillis() < deadline) {
            if (predicate(stats)) return stats
            Thread.sleep(25)
            stats = processor.getBufferStats()
        }
        assertTrue("timed out waiting for: $desc (last stats: $stats)", predicate(stats))
        return stats
    }

    @Test
    fun `oversize event is dropped from RAM and counted`() {
        val processor = buildProcessor(maxEventBytes = 4096)
        try {
            // ~10 KB event, over the 4 KB per-event cap.
            processor.onEmit(OtelContext.root(), wrap(sizedRecord("big", 10_000)))

            val stats = processor.getBufferStats()
            assertEquals("oversize event must not enter RAM", 0, stats.ramBufferSize)
            assertEquals("drop must be counted", 1L, stats.droppedOversizeCount)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `oversize event is still persisted to disk so the signal is not lost`() {
        val processor = buildProcessor(maxEventBytes = 4096)
        try {
            processor.onEmit(OtelContext.root(), wrap(sizedRecord("big", 10_000)))
            val stats = awaitStat(processor, desc = "oversize event persisted to disk") {
                it.diskBufferSize > 0
            }
            assertEquals(0, stats.ramBufferSize)
            assertTrue("oversize event should be persisted to disk", stats.diskBufferSize > 0)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `normal events under the per-event cap are buffered unaffected`() {
        val processor = buildProcessor(maxEventBytes = 256 * 1024)
        try {
            repeat(5) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("ok.$i")))
            }
            val stats = processor.getBufferStats()
            assertEquals(5, stats.ramBufferSize)
            assertEquals(0L, stats.droppedOversizeCount)
            assertTrue("byte accounting should be > 0 for buffered events", stats.ramBufferBytes > 0)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `total-byte budget triggers overflow independent of the count cap`() {
        // Count cap is high (1000) so it cannot be what triggers overflow.
        // Byte budget is 50 KB; each event ~12 KB → ~5 events fit, the rest overflow.
        val processor = buildProcessor(
            ramBufferSize = 1000,
            maxTotalBytes = 50_000L,
            maxEventBytes = 40_000 // per-event cap below the total budget; each event ~12 KB
        )
        try {
            repeat(20) { i ->
                processor.onEmit(OtelContext.root(), wrap(sizedRecord("evt.$i", 12_000)))
            }
            val stats = awaitStat(processor, desc = "events overflowed to disk under byte budget") {
                it.diskBufferSize > 0 && it.ramBufferBytes <= 50_000L
            }
            assertTrue(
                "RAM bytes (${stats.ramBufferBytes}) must stay within budget despite count cap not being hit",
                stats.ramBufferBytes <= 50_000L
            )
            assertTrue("count cap (1000) was never reached, so RAM count stays small", stats.ramBufferSize < 20)
            assertTrue("overflowed events should land on disk", stats.diskBufferSize > 0)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `byte accounting returns to zero after force flush clears RAM`() {
        val processor = buildProcessor()
        try {
            repeat(10) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("flush.$i")))
            }
            assertTrue(processor.getBufferStats().ramBufferBytes > 0)

            processor.forceFlush()
            val stats = awaitStat(processor, desc = "RAM cleared after force flush") {
                it.ramBufferSize == 0 && it.ramBufferBytes == 0L
            }
            assertEquals("RAM count cleared after flush", 0, stats.ramBufferSize)
            assertEquals("RAM byte accounting cleared after flush", 0L, stats.ramBufferBytes)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `config rejects total-byte budget smaller than per-event cap`() {
        try {
            MobileConfig(
                serviceName = "s",
                serviceVersion = "1.0",
                collectorEndpoint = "http://localhost:4317",
                ramBufferMaxTotalBytes = 1024,
                ramBufferMaxEventBytes = 4096
            )
            org.junit.Assert.fail("expected IllegalArgumentException for total < per-event")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ramBufferMaxTotalBytes"))
        }
    }
}
