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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD tests for flush deduplication (Phase 1 of Offline Flush Budget epic).
 *
 * Validates that events exported in one flush cycle are not re-exported
 * in subsequent flush cycles, using a seqId high-water mark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FlushDedupTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var processor: MobileLogRecordProcessor
    private lateinit var config: MobileConfig

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        mockExporter = MockLogRecordExporter()

        config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = 100,
            diskBufferMb = 10,
            diskBufferTtlHours = 1
        )

        val meter = OpenTelemetry.noop().meterProvider.get("test")
        processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
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

    // ==================== Flush Dedup Tests ====================

    @Test
    fun `consecutive forceFlush does not re-export same events`() {
        // Emit 5 events
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("dedup.$i")))
        }

        // First flush — should export all 5
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals("First flush should export 5 events", 5, mockExporter.exportedLogs.size)

        // Second flush — should export 0 (all already exported)
        mockExporter.clear()
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals("Second flush should not re-export", 0, mockExporter.exportedLogs.size)
    }

    @Test
    fun `forceFlush after new events only exports new events`() {
        // Emit 3 events, flush
        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("batch1.$i")))
        }
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals(3, mockExporter.exportedLogs.size)

        // Emit 2 more events
        mockExporter.clear()
        repeat(2) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("batch2.$i")))
        }

        // Second flush — should only export the 2 new events
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals("Should only export new events", 2, mockExporter.exportedLogs.size)

        val bodies = mockExporter.exportedLogs.map { it.body.asString() }.toSet()
        assertTrue("Should contain batch2 events", bodies.contains("batch2.0"))
        assertTrue("Should contain batch2 events", bodies.contains("batch2.1"))
        assertFalse("Should NOT contain batch1 events", bodies.contains("batch1.0"))
    }

    @Test
    fun `flushWindow does not re-export events from previous flush`() {
        // Emit events with known timestamps
        val now = System.currentTimeMillis()
        repeat(5) { i ->
            processor.onEmit(
                OtelContext.root(),
                wrap(TestUtils.createTestLogRecord("window.$i", timestamp = now - 30_000 + (i * 1000)))
            )
        }

        // First flush with 5-minute window
        processor.flushWindow(5)
        Thread.sleep(200)
        assertEquals("First window flush should export 5", 5, mockExporter.exportedLogs.size)

        // Second flush with same window — should export 0
        mockExporter.clear()
        processor.flushWindow(5)
        Thread.sleep(200)
        assertEquals("Second window flush should not re-export", 0, mockExporter.exportedLogs.size)
    }

    @Test
    fun `forceFlush after flushWindow exports only remaining events`() {
        val now = System.currentTimeMillis()

        // Emit first batch
        repeat(3) { i ->
            processor.onEmit(
                OtelContext.root(),
                wrap(TestUtils.createTestLogRecord("first.$i", timestamp = now - 20_000 + (i * 1000)))
            )
        }
        processor.flushWindow(5)
        Thread.sleep(200)
        assertEquals(3, mockExporter.exportedLogs.size)

        // Emit second batch
        mockExporter.clear()
        repeat(2) { i ->
            processor.onEmit(
                OtelContext.root(),
                wrap(TestUtils.createTestLogRecord("second.$i", timestamp = now - 5_000 + (i * 1000)))
            )
        }

        // forceFlush bypasses cooldown — should only export the 2 new events
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals("Should only export 2 new events", 2, mockExporter.exportedLogs.size)
    }

    @Test
    fun `failed flush does not advance high-water mark`() {
        // Emit 3 events
        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("retry.$i")))
        }

        // Fail the flush
        mockExporter.shouldFail = true
        processor.forceFlush()
        Thread.sleep(200)

        // Retry — should export all 3 again since first attempt failed
        mockExporter.shouldFail = false
        mockExporter.clear()
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals("Failed flush should not skip events on retry", 3, mockExporter.exportedLogs.size)
    }

    @Test
    fun `high-water mark survives processor restart`() {
        // Emit and flush 5 events
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("persist.$i")))
        }
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals(5, mockExporter.exportedLogs.size)

        // Shut down processor
        processor.shutdown()

        // Create new processor (simulates process restart)
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        val newExporter = MockLogRecordExporter()
        val newProcessor = MobileLogRecordProcessor.builder(context)
            .setExporter(newExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
        Thread.sleep(200)

        // Force flush on new processor — should not re-export events already exported
        newProcessor.forceFlush()
        Thread.sleep(200)
        assertEquals(
            "Events exported before restart should not be re-exported",
            0,
            newExporter.exportedLogs.size
        )

        newProcessor.shutdown()
    }

    @Test
    fun `dedup works across mixed flushWindow and forceFlush`() {
        val now = System.currentTimeMillis()

        // Emit 3 events
        repeat(3) { i ->
            processor.onEmit(
                OtelContext.root(),
                wrap(TestUtils.createTestLogRecord("mixed.$i", timestamp = now - 10_000 + (i * 1000)))
            )
        }

        // Flush via window
        processor.flushWindow(5)
        Thread.sleep(200)
        assertEquals(3, mockExporter.exportedLogs.size)

        // Force flush — should not re-export
        mockExporter.clear()
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals("forceFlush should not re-export window-flushed events", 0, mockExporter.exportedLogs.size)
    }

    @Test
    fun `dedup handles interleaved emit and flush`() {
        // Emit 2, flush, emit 3, flush — total exported should be 5
        repeat(2) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("interleave.a.$i")))
        }
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals(2, mockExporter.exportedLogs.size)

        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("interleave.b.$i")))
        }
        processor.forceFlush()
        Thread.sleep(200)
        assertEquals("Total should be 2 + 3 = 5", 5, mockExporter.exportedLogs.size)

        // Verify no duplicates
        val bodies = mockExporter.exportedLogs.map { it.body.asString() }
        assertEquals("No duplicates", bodies.size, bodies.toSet().size)
    }
}
