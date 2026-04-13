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
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
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
        Thread.sleep(200) // Wait for async post-export cleanup on executor

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
        // With monotonic timestamps, all events buffered in the same test run are
        // "recent" (monotonicMs ≈ elapsedRealtime at creation). flushWindow(2)
        // includes all same-boot RAM events created within the last 2 monotonic minutes.
        val now = System.currentTimeMillis()

        val oldLog = TestUtils.createTestLogRecordWithTimestamp("old", now - (5 * 60 * 1000))
        processor.onEmit(OtelContext.root(), wrap(oldLog))

        val recentLog = TestUtils.createTestLogRecordWithTimestamp("recent", now - (1 * 60 * 1000))
        processor.onEmit(OtelContext.root(), wrap(recentLog))

        processor.flushWindow(2)
        Thread.sleep(200)

        // Both events have monotonic timestamps within the last 2 minutes
        // (they were both just buffered in this test), so both are exported.
        assertEquals(2, mockExporter.exportedLogs.size)
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
        Thread.sleep(200) // Wait for async post-export cleanup on executor

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

    // ==================== Tail Sampling Improvement Tests ====================

    /**
     * Creates a LogRecordData with a valid SpanContext for use in trace-flush tests.
     */
    private fun createLogRecordWithTrace(
        body: String,
        traceId: String,
        spanId: String,
        timestampMs: Long = System.currentTimeMillis()
    ): LogRecordData {
        val spanCtx = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())
        return object : LogRecordData {
            override fun getResource(): Resource = Resource.empty()
            override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = InstrumentationScopeInfo.empty()
            override fun getTimestampEpochNanos(): Long = timestampMs * 1_000_000
            override fun getObservedTimestampEpochNanos(): Long = timestampMs * 1_000_000
            override fun getSpanContext(): SpanContext = spanCtx
            override fun getSeverity(): Severity = Severity.INFO
            override fun getSeverityText(): String = "INFO"
            override fun getBody(): Body = object : Body {
                override fun asString() = body
                override fun getType() = Body.Type.STRING
            }
            override fun getAttributes(): Attributes = Attributes.empty()
            override fun getTotalAttributeCount(): Int = 0
        }
    }

    @Test
    fun `flushByTraceId exports only events with matching traceId`() {
        val traceId1 = "aaaabbbbccccdddd1111222233334444"
        val traceId2 = "eeeeffffaaaabbbb5555666677778888"
        val span1 = "1111222233334444"
        val span2 = "5555666677778888"

        // Emit 3 events for trace1 and 2 events for trace2
        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(createLogRecordWithTrace("trace1.event.$i", traceId1, span1)))
        }
        repeat(2) { i ->
            processor.onEmit(OtelContext.root(), wrap(createLogRecordWithTrace("trace2.event.$i", traceId2, span2)))
        }

        val result = processor.flushByTraceId(traceId1)
        assertTrue(result.isSuccess)
        Thread.sleep(200)

        // Should have exported exactly the 3 trace1 events
        assertEquals(3, mockExporter.exportedLogs.size)
        assertTrue(mockExporter.exportedLogs.all { it.spanContext.traceId == traceId1 })
    }

    @Test
    fun `flushByTraceId falls back to flushWindow when trace has at most 1 event`() {
        val now = System.currentTimeMillis()
        val traceId = "aaaabbbbccccdddd1111222233334444"
        val span = "1111222233334444"

        // Emit several events WITHOUT a trace context (they should be in the window)
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(
                TestUtils.createTestLogRecordWithTimestamp("normal.event.$i", now - (30 * 1000))
            ))
        }
        // Emit only 1 event with the target traceId (below fallback threshold)
        processor.onEmit(OtelContext.root(), wrap(
            createLogRecordWithTrace("single.trace.event", traceId, span, now - (10 * 1000))
        ))

        // flushByTraceId should fall back to flushWindow because only 1 event matches the trace
        val result = processor.flushByTraceId(traceId, fallbackWindowMinutes = 2)
        assertTrue(result.isSuccess)
        Thread.sleep(200)

        // All 6 events are within last 2 minutes, so flushWindow(2) exports them all
        assertEquals(6, mockExporter.exportedLogs.size)
    }

    @Test
    fun `flush deduplication suppresses second flushWindow within cooldown on overlapping range`() {
        val now = System.currentTimeMillis()

        // Emit events within the last 2 minutes
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(
                TestUtils.createTestLogRecordWithTimestamp("dedup.event.$i", now - (60 * 1000))
            ))
        }

        // First flush — should succeed and export 5 events
        val first = processor.flushWindow(2)
        assertTrue(first.isSuccess)
        Thread.sleep(100)
        val firstCount = mockExporter.exportedLogs.size

        // Second flush within 10s cooldown on the same overlapping range — should be suppressed
        val second = processor.flushWindow(2)
        assertTrue("Suppressed flush should succeed (no-op)", second.isSuccess)
        Thread.sleep(100)

        // Exported count should not have grown
        assertEquals("Second flush within cooldown should be suppressed", firstCount, mockExporter.exportedLogs.size)
    }

    @Test
    fun `flushWindow extends backward to include screen start when screen started before window`() {
        val now = System.currentTimeMillis()

        // Emit a screen.view event 4 minutes ago
        val screenViewTime = now - (4 * 60 * 1000)
        val screenViewLog = object : LogRecordData {
            override fun getResource(): Resource = Resource.empty()
            override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = InstrumentationScopeInfo.empty()
            override fun getTimestampEpochNanos(): Long = screenViewTime * 1_000_000
            override fun getObservedTimestampEpochNanos(): Long = screenViewTime * 1_000_000
            override fun getSpanContext(): SpanContext = SpanContext.getInvalid()
            override fun getSeverity(): Severity = Severity.INFO
            override fun getSeverityText(): String = "INFO"
            override fun getBody(): Body = object : Body {
                override fun asString() = "screen.view"
                override fun getType() = Body.Type.STRING
            }
            override fun getAttributes(): Attributes = Attributes.empty()
            override fun getTotalAttributeCount(): Int = 0
        }
        processor.onEmit(OtelContext.root(), wrap(screenViewLog))

        // Emit a regular event 1 minute ago (within a 2-minute window)
        processor.onEmit(OtelContext.root(), wrap(
            TestUtils.createTestLogRecordWithTimestamp("in.window.event", now - (60 * 1000))
        ))

        // flushWindow(2) with no screen extension would only get the 1-minute-ago event.
        // With screen-start extension it should also capture the screen.view from 4 min ago.
        val result = processor.flushWindow(2)
        assertTrue(result.isSuccess)
        Thread.sleep(200)

        // Both the screen.view (4 min ago) and the in-window event should have been exported
        assertEquals(
            "Screen start extension should include screen.view event from 4 minutes ago",
            2,
            mockExporter.exportedLogs.size
        )
        assertTrue(mockExporter.exportedLogs.any { it.bodyValue?.asString() == "screen.view" })
    }
}
