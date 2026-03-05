/*
 * Copyright The OpenTelemetry Authors
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Comprehensive tests for MobileLogRecordProcessor.
 *
 * Tests cover:
 * - RAM buffer operations
 * - Disk buffer overflow
 * - Policy evaluation and flush
 * - Time window flushing
 * - Force flush
 * - Thread safety
 * - Shutdown behavior
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileLogRecordProcessorTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var processor: MobileLogRecordProcessor
    private lateinit var config: MobileConfig

    @Before
    fun setup() {
        // Reset DiskLogBuffer singleton so each test starts with a clean disk state
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

    /**
     * Wraps a LogRecordData in a mock ReadWriteLogRecord suitable for passing to onEmit().
     *
     * MobileLogRecordProcessor.onEmit() expects ReadWriteLogRecord; the mock delegates
     * toLogRecordData() to the provided data so the processor sees the correct content.
     */
    private fun wrap(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    // ==================== RAM Buffer Tests ====================

    @Test
    fun `onEmit adds log to RAM buffer`() {
        val logRecord = TestUtils.createTestLogRecord("test.event")

        processor.onEmit(OtelContext.root(), wrap(logRecord))

        val stats = processor.getBufferStats()
        assertEquals(1, stats.ramBufferSize)
        assertEquals(0, stats.diskBufferSize)
    }

    @Test
    fun `multiple onEmit calls accumulate in RAM buffer`() {
        repeat(10) { i ->
            val logRecord = TestUtils.createTestLogRecord("event.$i")
            processor.onEmit(OtelContext.root(), wrap(logRecord))
        }

        val stats = processor.getBufferStats()
        assertEquals(10, stats.ramBufferSize)
    }

    @Test
    fun `RAM buffer respects size limit`() {
        // Fill RAM buffer to capacity (100)
        repeat(100) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("event.$i")))
        }

        val stats = processor.getBufferStats()
        assertEquals(100, stats.ramBufferSize)

        // Add one more - should trigger overflow to disk
        processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("overflow")))

        Thread.sleep(100) // Wait for async disk write
        val newStats = processor.getBufferStats()
        assertTrue(newStats.ramBufferSize <= 100)
    }

    @Test
    fun `RAM buffer handles rapid additions`() {
        val count = 50
        repeat(count) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("rapid.$i")))
        }

        val stats = processor.getBufferStats()
        assertEquals(count, stats.ramBufferSize)
    }

    // ==================== Disk Buffer Tests ====================

    @Test
    fun `RAM overflow moves events to disk`() {
        // Fill RAM buffer beyond capacity
        repeat(150) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("event.$i")))
        }

        Thread.sleep(500) // Wait for async disk writes

        val stats = processor.getBufferStats()
        assertTrue("Events should have overflowed to disk", stats.diskBufferSize > 0)
    }

    @Test
    fun `disk buffer persists across processor restarts`() {
        // Add events and let them overflow to disk
        repeat(150) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("persistent.$i")))
        }

        Thread.sleep(500) // Wait for disk writes
        processor.shutdown()

        // Create new processor with same context
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        val newProcessor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
        Thread.sleep(200) // Allow disk buffer to load

        val stats = newProcessor.getBufferStats()
        assertTrue("Disk events should persist", stats.diskBufferSize > 0)

        newProcessor.shutdown()
    }

    // ==================== Force Flush Tests ====================

    @Test
    fun `forceFlush exports all RAM buffer events`() {
        // Add events to RAM buffer
        repeat(10) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("flush.$i")))
        }

        // Force flush
        val result = processor.forceFlush()
        assertTrue(result.isSuccess)

        // Verify events were exported
        assertEquals(10, mockExporter.exportedLogs.size)
    }

    @Test
    fun `forceFlush exports disk buffer events`() {
        // Add events that will overflow to disk
        repeat(150) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("disk.$i")))
        }

        Thread.sleep(500) // Wait for disk writes

        // Force flush
        val result = processor.forceFlush()
        assertTrue(result.isSuccess)

        // Verify all events were exported
        assertTrue(mockExporter.exportedLogs.size >= 150)
    }

    @Test
    fun `forceFlush clears buffers after export`() {
        repeat(20) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("clear.$i")))
        }

        processor.forceFlush()

        val stats = processor.getBufferStats()
        assertEquals(0, stats.ramBufferSize)
    }

    @Test
    fun `forceFlush with failed exporter returns failure`() {
        // Configure exporter to fail
        mockExporter.shouldFail = true

        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("fail.$i")))
        }

        val result = processor.forceFlush()
        assertFalse(result.isSuccess)
    }

    @Test
    fun `forceFlush with empty buffer succeeds`() {
        val result = processor.forceFlush()
        assertTrue(result.isSuccess)
    }

    // ==================== Time Window Flush Tests ====================

    @Test
    fun `flushWindow exports events from last N minutes`() {
        val now = System.currentTimeMillis()

        // Add old event (5 minutes ago)
        val oldLog = TestUtils.createTestLogRecordWithTimestamp("old", now - (5 * 60 * 1000))
        processor.onEmit(OtelContext.root(), wrap(oldLog))

        // Add recent event (1 minute ago)
        val recentLog = TestUtils.createTestLogRecordWithTimestamp("recent", now - (1 * 60 * 1000))
        processor.onEmit(OtelContext.root(), wrap(recentLog))

        // Flush last 2 minutes
        processor.flushWindow(2)
        Thread.sleep(200)

        // Only recent event should be exported
        assertEquals(1, mockExporter.exportedLogs.size)
        assertEquals("recent", mockExporter.exportedLogs[0].body.asString())
    }

    @Test
    fun `flushWindow with zero minutes exports nothing`() {
        processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("event")))

        processor.flushWindow(0)
        Thread.sleep(100)

        assertEquals(0, mockExporter.exportedLogs.size)
    }

    // ==================== Policy Evaluation Tests ====================

    @Test
    fun `policy match triggers automatic flush`() {
        // Add normal events
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("normal.$i")))
        }

        // Add UI freeze event that matches policy
        val freezeLog = TestUtils.createUIFreezeLog(2500)
        processor.onEmit(OtelContext.root(), wrap(freezeLog))

        // Poll until exported (up to 3s) instead of fixed sleep to avoid flakiness
        mockExporter.waitForLogs(6, timeoutMs = 3000)

        // Events should have been flushed
        assertTrue("Policy should trigger flush (exported ${mockExporter.exportedLogs.size})", mockExporter.exportedLogs.size >= 6)
    }

    @Test
    fun `policy match includes window context`() {
        // Add historical events
        repeat(10) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("history.$i")))
        }

        Thread.sleep(100)

        // Trigger crash policy
        val crashLog = TestUtils.createCrashLog()
        processor.onEmit(OtelContext.root(), wrap(crashLog))

        // Poll until exported (up to 3s) instead of fixed sleep to avoid flakiness
        mockExporter.waitForLogs(11, timeoutMs = 3000)

        // Should export crash + historical context
        assertTrue("Policy should flush history context (exported ${mockExporter.exportedLogs.size})", mockExporter.exportedLogs.size >= 11)
    }

    // ==================== Thread Safety Tests ====================

    @Test
    fun `concurrent onEmit calls are thread-safe`() {
        val latch = CountDownLatch(10)
        val threads = mutableListOf<Thread>()

        repeat(10) { threadId ->
            val thread = Thread {
                repeat(10) { i ->
                    processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("thread.$threadId.event.$i")))
                }
                latch.countDown()
            }
            threads.add(thread)
            thread.start()
        }

        // Wait for all threads to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS))

        // Should have 100 events total
        val stats = processor.getBufferStats()
        assertEquals(100, stats.ramBufferSize)
    }

    @Test
    fun `concurrent forceFlush calls are safe`() {
        repeat(50) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("concurrent.$i")))
        }

        val latch = CountDownLatch(5)
        repeat(5) {
            Thread {
                processor.forceFlush()
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        // Should not crash
    }

    // ==================== Shutdown Tests ====================

    @Test
    fun `shutdown flushes remaining events`() {
        repeat(15) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("shutdown.$i")))
        }

        processor.shutdown()

        // Events should be exported during shutdown
        assertEquals(15, mockExporter.exportedLogs.size)
    }

    @Test
    fun `shutdown waits for pending operations`() {
        // Add events
        repeat(100) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("pending.$i")))
        }

        val startTime = System.currentTimeMillis()
        processor.shutdown()
        val duration = System.currentTimeMillis() - startTime

        // Should wait but not exceed timeout
        assertTrue(duration < 5000)
        assertTrue(mockExporter.exportedLogs.size >= 100)
    }

    @Test
    fun `shutdown is idempotent`() {
        processor.shutdown()
        processor.shutdown() // Should not crash
    }

    // ==================== Buffer Statistics Tests ====================

    @Test
    fun `getBufferStats returns accurate counts`() {
        repeat(25) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("stats.$i")))
        }

        val stats = processor.getBufferStats()
        assertEquals(25, stats.ramBufferSize)
        assertTrue(stats.ramBufferCapacity == 100)
    }

    @Test
    fun `buffer stats update after flush`() {
        repeat(10) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("update.$i")))
        }

        val beforeFlush = processor.getBufferStats()
        assertEquals(10, beforeFlush.ramBufferSize)

        processor.forceFlush()

        val afterFlush = processor.getBufferStats()
        assertEquals(0, afterFlush.ramBufferSize)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `processor handles exporter failure gracefully`() {
        mockExporter.shouldFail = true

        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("fail.$i")))
        }

        val result = processor.forceFlush()
        assertFalse(result.isSuccess)

        // Buffer should still contain events (not lost)
        val stats = processor.getBufferStats()
        assertTrue(stats.ramBufferSize > 0 || stats.diskBufferSize > 0)
    }

    @Test
    fun `processor handles empty log body`() {
        val logRecord = TestUtils.createTestLogRecord("")

        // Should not crash
        processor.onEmit(OtelContext.root(), wrap(logRecord))

        val stats = processor.getBufferStats()
        assertEquals(1, stats.ramBufferSize)
    }
}
