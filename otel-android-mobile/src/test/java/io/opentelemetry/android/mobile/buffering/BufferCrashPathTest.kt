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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for MobileLogRecordProcessor under crash, overflow, and edge-case scenarios.
 *
 * These tests complement [MobileLogRecordProcessorTest] by focusing on:
 * - RAM buffer eviction and overflow under pressure
 * - Burst ingestion and data integrity
 * - Concurrent flush-during-ingest race conditions
 * - Export failure retention semantics
 * - seqId monotonicity and uniqueness guarantees
 * - Flush cooldown / dedup guard behavior
 * - Empty-buffer and shutdown edge cases
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BufferCrashPathTest {

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
            serviceName = "crash-path-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = 100,
            diskBufferMb = 10,
            diskBufferTtlHours = 1
        )
    }

    @After
    fun teardown() {
        if (::processor.isInitialized) {
            processor.shutdown()
        }
        DiskLogBuffer.resetForTesting()
    }

    private fun buildProcessor(ramBufferSize: Int = config.ramBufferSize): MobileLogRecordProcessor {
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        return MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    private fun wrap(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    // ==================== 1. RAM Buffer at Capacity ====================

    @Test
    fun `RAM buffer at capacity overflows oldest events to disk`() {
        // Use a small RAM buffer to make overflow easy to trigger
        processor = buildProcessor(ramBufferSize = 50)

        // Fill past capacity
        repeat(80) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("overflow.$i")))
        }

        // Allow async disk overflow to complete
        Thread.sleep(500)

        val stats = processor.getBufferStats()
        // RAM should be at or below capacity
        assertTrue("RAM buffer should not exceed capacity (was ${stats.ramBufferSize})",
            stats.ramBufferSize <= 50)
        // Overflow events should be on disk
        assertTrue("Overflow events should have moved to disk (disk=${stats.diskBufferSize})",
            stats.diskBufferSize > 0)
        // Total events across both tiers should account for all 80
        assertTrue("Total events should be >= 80 (ram=${stats.ramBufferSize}, disk=${stats.diskBufferSize})",
            stats.ramBufferSize + stats.diskBufferSize >= 80)
    }

    // ==================== 2. Rapid Burst Ingestion ====================

    @Test
    fun `rapid burst of 1000 events loses no data`() {
        processor = buildProcessor(ramBufferSize = 5000)

        val count = 1000
        repeat(count) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("burst.$i")))
        }

        // All events should be buffered (RAM is large enough)
        val stats = processor.getBufferStats()
        assertEquals("All $count events should be in RAM", count, stats.ramBufferSize)

        // Force flush and verify all exported
        val result = processor.forceFlush()
        assertTrue("Force flush should succeed", result.isSuccess)
        assertEquals("All $count events should be exported", count, mockExporter.exportedLogs.size)
    }

    @Test
    fun `rapid burst exceeding RAM capacity preserves all events across tiers`() {
        processor = buildProcessor(ramBufferSize = 100)

        val count = 300
        repeat(count) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("bigburst.$i")))
        }

        // Allow disk overflow
        Thread.sleep(500)

        val stats = processor.getBufferStats()
        val total = stats.ramBufferSize + stats.diskBufferSize
        assertTrue("Total events across both tiers should be >= $count (was $total)", total >= count)
    }

    // ==================== 3. Concurrent Flush During Ingest ====================

    @Test
    fun `flush during active ingestion does not lose events`() {
        processor = buildProcessor(ramBufferSize = 5000)

        val ingestCount = AtomicInteger(0)
        val flushCount = AtomicInteger(0)
        val latch = CountDownLatch(2)

        // Thread 1: continuously ingest events
        Thread {
            repeat(200) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("concurrent.$i")))
                ingestCount.incrementAndGet()
            }
            latch.countDown()
        }.start()

        // Thread 2: flush partway through ingestion
        Thread {
            // Wait a bit for some events to accumulate
            Thread.sleep(50)
            processor.forceFlush()
            flushCount.incrementAndGet()
            latch.countDown()
        }.start()

        assertTrue("Threads should complete within 10s", latch.await(10, TimeUnit.SECONDS))

        // Final flush to get any remaining events
        processor.forceFlush()

        // All ingested events should either have been exported or still be in buffer
        val totalIngested = ingestCount.get()
        val totalExported = mockExporter.exportedLogs.size
        val stats = processor.getBufferStats()
        val stillBuffered = stats.ramBufferSize + stats.diskBufferSize

        assertTrue("No events should be lost: ingested=$totalIngested, exported=$totalExported, buffered=$stillBuffered",
            totalExported + stillBuffered >= totalIngested)
    }

    // ==================== 4. Export Failure with Retry ====================

    @Test
    fun `export failure retains events in buffer for retry`() {
        processor = buildProcessor()

        repeat(20) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("retry.$i")))
        }

        // First flush fails
        mockExporter.shouldFail = true
        val failResult = processor.forceFlush()
        assertFalse("Flush should fail when exporter fails", failResult.isSuccess)

        // Events should still be in buffer
        val statsAfterFail = processor.getBufferStats()
        assertTrue("Events should remain in buffer after failed export (ram=${statsAfterFail.ramBufferSize})",
            statsAfterFail.ramBufferSize > 0 || statsAfterFail.diskBufferSize > 0)

        // Now fix the exporter and retry
        mockExporter.shouldFail = false
        val retryResult = processor.forceFlush()
        assertTrue("Retry flush should succeed", retryResult.isSuccess)
        assertTrue("Events should be exported on retry", mockExporter.exportedLogs.size >= 20)
    }

    // ==================== 5. Export Partial Failure ====================

    @Test
    fun `events survive failed export and can be re-exported`() {
        processor = buildProcessor()

        // Add events
        repeat(10) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("partial.$i")))
        }

        // Fail the export
        mockExporter.shouldFail = true
        processor.forceFlush()

        val statsAfterFail = processor.getBufferStats()
        val totalAfterFail = statsAfterFail.ramBufferSize + statsAfterFail.diskBufferSize
        assertTrue("Events should remain after failed export (total=$totalAfterFail)", totalAfterFail > 0)
        assertEquals("No events should have been exported", 0, mockExporter.exportedLogs.size)

        // Succeed on second attempt
        mockExporter.shouldFail = false
        mockExporter.clear()
        val result = processor.forceFlush()
        assertTrue("Second flush should succeed", result.isSuccess)
        assertTrue("Events should now be exported (count=${mockExporter.exportedLogs.size})",
            mockExporter.exportedLogs.size >= 10)
    }

    // ==================== 6. seqId Monotonicity ====================

    @Test
    fun `seqIds are strictly increasing across sequential events`() {
        // Create events and verify their seqIds are monotonically increasing
        val events = mutableListOf<BufferedEvent>()
        repeat(100) { i ->
            val event = BufferedEvent(TestUtils.createTestLogRecord("seq.$i"))
            events.add(event)
        }

        for (i in 1 until events.size) {
            assertTrue("seqId[${i}] (${events[i].seqId}) should be > seqId[${i - 1}] (${events[i - 1].seqId})",
                events[i].seqId > events[i - 1].seqId)
        }
    }

    @Test
    fun `seqIds have no gaps in single-threaded creation`() {
        val first = BufferedEvent(TestUtils.createTestLogRecord("gap.0"))
        val second = BufferedEvent(TestUtils.createTestLogRecord("gap.1"))
        // Each BufferedEvent increments the global counter by 1
        assertEquals("Consecutive seqIds should differ by 1", 1, second.seqId - first.seqId)
    }

    // ==================== 7. seqId Uniqueness Under Concurrency ====================

    @Test
    fun `seqIds are unique across multiple threads`() {
        val allSeqIds = ConcurrentHashMap.newKeySet<Long>()
        val duplicates = AtomicInteger(0)
        val threadCount = 10
        val eventsPerThread = 100
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                repeat(eventsPerThread) { i ->
                    val event = BufferedEvent(TestUtils.createTestLogRecord("thread.$it.event.$i"))
                    if (!allSeqIds.add(event.seqId)) {
                        duplicates.incrementAndGet()
                    }
                }
                latch.countDown()
            }.start()
        }

        assertTrue("All threads should complete within 10s", latch.await(10, TimeUnit.SECONDS))
        assertEquals("There should be zero duplicate seqIds", 0, duplicates.get())
        assertEquals("Total unique seqIds should equal total events",
            threadCount * eventsPerThread, allSeqIds.size)
    }

    @Test
    fun `seqIds remain monotonic under concurrent creation`() {
        // Collect seqIds from multiple threads, then verify global ordering is consistent
        val allEvents = Collections.synchronizedList(mutableListOf<BufferedEvent>())
        val threadCount = 5
        val eventsPerThread = 50
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { t ->
            Thread {
                repeat(eventsPerThread) { i ->
                    val event = BufferedEvent(TestUtils.createTestLogRecord("mono.$t.$i"))
                    allEvents.add(event)
                }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))

        // Sort by seqId and verify no duplicates
        val sorted = allEvents.sortedBy { it.seqId }
        for (i in 1 until sorted.size) {
            assertTrue("seqIds should be strictly increasing when sorted: ${sorted[i - 1].seqId} < ${sorted[i].seqId}",
                sorted[i].seqId > sorted[i - 1].seqId)
        }
    }

    // ==================== 8. Flush Cooldown ====================

    @Test
    fun `rapid flush calls within cooldown do not create duplicate exports`() {
        processor = buildProcessor()

        repeat(10) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("cooldown.$i")))
        }

        // First flushWindow should export
        val first = processor.flushWindow(2)
        assertTrue("First flush should succeed", first.isSuccess)
        Thread.sleep(100)
        val firstExportCount = mockExporter.exportedLogs.size

        // Second flushWindow immediately after should be suppressed by cooldown
        val second = processor.flushWindow(2)
        assertTrue("Suppressed flush should still return success (no-op)", second.isSuccess)
        Thread.sleep(100)

        assertEquals("Second flush within cooldown should not export additional events",
            firstExportCount, mockExporter.exportedLogs.size)
    }

    @Test
    fun `concurrent flushInProgress guard prevents overlapping flushWindow calls`() {
        processor = buildProcessor()

        // Add enough events to make flush non-trivial
        repeat(50) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("guard.$i")))
        }

        // Simulate slow export
        mockExporter.simulatedDelayMs = 200

        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val latch = CountDownLatch(3)

        // Launch 3 concurrent flushWindow calls
        repeat(3) {
            Thread {
                val result = processor.flushWindow(5)
                results.add(result.isSuccess)
                latch.countDown()
            }.start()
        }

        assertTrue("All flush threads should complete", latch.await(10, TimeUnit.SECONDS))
        // All should succeed (either by actually flushing or by being suppressed)
        assertTrue("All flush results should be success", results.all { it })

        // But total export calls should be limited (not 3x the events)
        // At most one flush should have actually exported
        assertTrue("Export call count should be limited (was ${mockExporter.exportCallCount})",
            mockExporter.exportCallCount <= 2)
    }

    // ==================== 9. forceFlush with Empty Buffer ====================

    @Test
    fun `forceFlush on empty buffer is a no-op and succeeds`() {
        processor = buildProcessor()

        val result = processor.forceFlush()
        assertTrue("forceFlush on empty buffer should succeed", result.isSuccess)
        assertEquals("No events should be exported from empty buffer", 0, mockExporter.exportedLogs.size)
        assertEquals("Exporter should not be called for empty buffer", 0, mockExporter.exportCallCount)
    }

    @Test
    fun `flushWindow on empty buffer is a no-op and succeeds`() {
        processor = buildProcessor()

        val result = processor.flushWindow(5)
        assertTrue("flushWindow on empty buffer should succeed", result.isSuccess)
        assertEquals("No events should be exported from empty buffer", 0, mockExporter.exportedLogs.size)
    }

    // ==================== 10. Shutdown Cleanup ====================

    @Test
    fun `shutdown exports all buffered events before stopping`() {
        processor = buildProcessor()

        repeat(25) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("shutdown.$i")))
        }

        processor.shutdown()

        assertEquals("All events should be exported during shutdown", 25, mockExporter.exportedLogs.size)
    }

    @Test
    fun `shutdown stops accepting new events`() {
        processor = buildProcessor()

        processor.shutdown()

        // Events emitted after shutdown should be dropped
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("post-shutdown.$i")))
        }

        val stats = processor.getBufferStats()
        assertEquals("RAM buffer should be empty after shutdown + dropped events", 0, stats.ramBufferSize)
        // Exported logs should only contain pre-shutdown events (none in this case)
        assertEquals("No events should be exported for post-shutdown emits", 0, mockExporter.exportedLogs.size)
    }

    @Test
    fun `shutdown completes pending flush then stops`() {
        processor = buildProcessor()

        repeat(50) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("pending.$i")))
        }

        // Slow exporter to simulate in-flight flush
        mockExporter.simulatedDelayMs = 100

        val startTime = System.currentTimeMillis()
        processor.shutdown()
        val duration = System.currentTimeMillis() - startTime

        // Should complete within reasonable time
        assertTrue("Shutdown should complete within 10s (took ${duration}ms)", duration < 10_000)
        // All events should have been exported
        assertTrue("All events should be exported during shutdown (got ${mockExporter.exportedLogs.size})",
            mockExporter.exportedLogs.size >= 50)
    }

    @Test
    fun `double shutdown does not crash or double-export`() {
        processor = buildProcessor()

        repeat(10) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("double.$i")))
        }

        processor.shutdown()
        val exportedAfterFirst = mockExporter.exportedLogs.size

        // Second shutdown should be idempotent
        processor.shutdown()
        assertEquals("Second shutdown should not export additional events",
            exportedAfterFirst, mockExporter.exportedLogs.size)
    }
}
