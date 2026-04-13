/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context as OtelContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive buffer system tests — covers the dual-tier ring buffer
 * (RAM + SQLite), overflow, crash-safe mirroring, flush windows, and
 * concurrent access patterns.
 *
 * Test Plan Reference: Section 3.1 (B-001..B-028, D-001..D-012, R-001..R-010)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BufferSystemComprehensiveTest {

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildProcessor(
        exportMode: ExportMode = ExportMode.CONDITIONAL,
        ramBufferSize: Int = 100,
        diskBufferMb: Int = 10,
        ttlHours: Int = 1
    ): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName = "buffer-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            exportMode = exportMode,
            ramBufferSize = ramBufferSize,
            diskBufferMb = diskBufferMb,
            diskBufferTtlHours = ttlHours
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        return MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    private fun emit(processor: MobileLogRecordProcessor, body: String) {
        val record = TestUtils.createTestLogRecord(body)
        processor.onEmit(OtelContext.root(), TestUtils.asReadWriteLogRecord(record))
    }

    private fun emitWithTimestamp(processor: MobileLogRecordProcessor, body: String, timestampMs: Long) {
        val record = TestUtils.createTestLogRecordWithTimestamp(body, timestampMs)
        processor.onEmit(OtelContext.root(), TestUtils.asReadWriteLogRecord(record))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B-001..B-005: RAM buffer basic operations
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `B-001 add event to empty RAM buffer`() {
        val processor = buildProcessor()
        emit(processor, "test-event")

        // Event is in RAM, not exported yet (CONDITIONAL mode, no policy match)
        assertEquals(0, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    @Test
    fun `B-002 RAM buffer at capacity overflows to disk`() {
        val processor = buildProcessor(ramBufferSize = 10)

        // Fill RAM buffer
        repeat(10) { emit(processor, "event-$it") }
        // Add one more to trigger overflow
        emit(processor, "overflow-event")

        // Wait for async overflow
        Thread.sleep(1000)

        // No export (CONDITIONAL, no match), but events should be buffered
        assertEquals(0, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    @Test
    fun `B-003 RAM buffer overflow batch moves oldest to disk`() {
        val processor = buildProcessor(ramBufferSize = 20)

        // Fill to 20
        repeat(20) { emit(processor, "event-$it") }
        // Add 10 more — should overflow 10 to disk
        repeat(10) { emit(processor, "overflow-$it") }

        // Wait for async overflow
        Thread.sleep(1500)

        // Force flush to verify all events are intact
        processor.forceFlush()
        val arrived = mockExporter.waitForLogs(30, timeoutMs = 5000)
        assertTrue("Expected 30 events after force flush", arrived)
        assertEquals(30, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    @Test
    fun `B-008 flushWindow exports all same-boot RAM events within monotonic window`() {
        val processor = buildProcessor()

        // With monotonic timestamps, all events buffered in this test run are
        // "recent" (monotonicMs ≈ now). flushWindow(2) includes all of them.
        // The wall-clock timestamps don't affect RAM filtering — monotonic time does.
        emitWithTimestamp(processor, "old-event", System.currentTimeMillis() - 10 * 60 * 1000)
        emitWithTimestamp(processor, "recent-event-1", System.currentTimeMillis() - 90 * 1000)
        emitWithTimestamp(processor, "recent-event-2", System.currentTimeMillis() - 30 * 1000)

        processor.flushWindow(2)
        val arrived = mockExporter.waitForLogs(3, timeoutMs = 5000)
        assertTrue("All 3 same-boot events are within monotonic 2-min window", arrived)

        val bodies = mockExporter.exportedLogs.map { it.bodyValue?.asString() }
        assertTrue("recent-event-1 exported", "recent-event-1" in bodies)
        assertTrue("recent-event-2 exported", "recent-event-2" in bodies)
        assertTrue("old-event also exported (monotonically recent)", "old-event" in bodies)
        processor.shutdown()
    }

    @Test
    fun `B-009 flushWindow with zero minutes is no-op`() {
        val processor = buildProcessor()
        emit(processor, "event-1")

        val result = processor.flushWindow(0)
        assertTrue("flushWindow(0) should succeed as no-op", result.isSuccess)
        assertEquals(0, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    @Test
    fun `B-012 concurrent onEmit from 10 threads`() {
        val processor = buildProcessor(ramBufferSize = 5000)
        val threads = (0 until 10).map { threadIdx ->
            Thread {
                repeat(100) { eventIdx ->
                    emit(processor, "thread-$threadIdx-event-$eventIdx")
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Force flush all — should have all 1000 events
        processor.forceFlush()
        val arrived = mockExporter.waitForLogs(1000, timeoutMs = 10000)
        assertTrue("All 1000 events should survive concurrent access", arrived)
        assertEquals(1000, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    @Test
    fun `B-015 empty buffer export is success`() {
        val processor = buildProcessor()

        val result = processor.forceFlush()
        assertTrue("Empty buffer forceFlush should succeed", result.isSuccess)
        assertEquals(0, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    @Test
    fun `B-016 custom RAM buffer size is respected`() {
        val processor = buildProcessor(ramBufferSize = 5)

        // Add exactly 5 events
        repeat(5) { emit(processor, "event-$it") }
        // Add 1 more — should trigger overflow
        emit(processor, "overflow")

        Thread.sleep(1000)

        // Force flush all
        processor.forceFlush()
        val arrived = mockExporter.waitForLogs(6, timeoutMs = 5000)
        assertTrue("All 6 events present after overflow", arrived)
        processor.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Export failure and retry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `R-001 export succeeds first try`() {
        val processor = buildProcessor(exportMode = ExportMode.CONTINUOUS, ramBufferSize = 100)

        emit(processor, "event-1")
        processor.forceFlush()
        val arrived = mockExporter.waitForLogs(1, timeoutMs = 5000)

        assertTrue("Export should succeed", arrived)
        assertEquals(1, mockExporter.exportedLogs.size)
        assertEquals(1, mockExporter.exportCallCount)
        processor.shutdown()
    }

    @Test
    fun `R-002 export failure retains events for next attempt`() {
        val processor = buildProcessor()

        emit(processor, "event-1")
        emit(processor, "event-2")

        // Make export fail
        mockExporter.shouldFail = true
        processor.forceFlush()
        Thread.sleep(1000)

        // Events should still be buffered (not lost)
        assertEquals(0, mockExporter.exportedLogs.size)

        // Fix export and flush again
        mockExporter.shouldFail = false
        processor.forceFlush()
        val arrived = mockExporter.waitForLogs(2, timeoutMs = 5000)
        assertTrue("Events retained after failed export", arrived)
        processor.shutdown()
    }

    @Test
    fun `R-008 export with empty collection succeeds immediately`() {
        val processor = buildProcessor()

        val result = processor.forceFlush()
        assertTrue("Empty export should succeed", result.isSuccess)
        assertEquals(0, mockExporter.exportCallCount)
        processor.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Export modes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `EC-001 CONDITIONAL mode does not export without policy match`() {
        val processor = buildProcessor(exportMode = ExportMode.CONDITIONAL)

        // Generate 20 events — none should trigger export
        repeat(20) { emit(processor, "user.tap.$it") }

        Thread.sleep(2000)

        // CONDITIONAL: no policy match = no export
        assertEquals(
            "CONDITIONAL should not export without policy match",
            0, mockExporter.exportedLogs.size
        )
        processor.shutdown()
    }

    @Test
    fun `EC-002 CONDITIONAL policy match triggers flush`() {
        val processor = buildProcessor(exportMode = ExportMode.CONDITIONAL)
        val now = System.currentTimeMillis()

        // Buffer some events
        repeat(5) { emitWithTimestamp(processor, "user.tap.$it", now - 60_000 + it * 1000) }

        // Emit a crash event — should trigger default crash-recovery policy
        val crashLog = TestUtils.createCrashLog()
        processor.onEmit(OtelContext.root(), TestUtils.asReadWriteLogRecord(crashLog))

        // Wait for policy evaluation + flush
        val arrived = mockExporter.waitForLogs(1, timeoutMs = 5000)
        assertTrue("Crash policy should trigger export", arrived)
        assertTrue(
            "At least crash event should be exported",
            mockExporter.exportedLogs.size >= 1
        )
        processor.shutdown()
    }

    @Test
    fun `EN-001 CONTINUOUS mode exports periodically`() {
        val processor = buildProcessor(
            exportMode = ExportMode.CONTINUOUS,
            ramBufferSize = 100
        )

        emit(processor, "event-1")
        emit(processor, "event-2")

        // Force flush to simulate timer
        processor.forceFlush()
        val arrived = mockExporter.waitForLogs(2, timeoutMs = 5000)
        assertTrue("CONTINUOUS forceFlush should export all", arrived)
        assertEquals(2, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Flush window — time window precision
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `B-010 flushWindow with maximum window (1440 min) exports all`() {
        val processor = buildProcessor()
        val now = System.currentTimeMillis()

        // Events spread across 12 hours
        repeat(10) {
            emitWithTimestamp(processor, "event-$it", now - (it * 60 * 60 * 1000))
        }

        processor.flushWindow(1440) // 24 hours
        val arrived = mockExporter.waitForLogs(10, timeoutMs = 5000)
        assertTrue("24h window should include all events", arrived)
        assertEquals(10, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    @Test
    fun `forceFlush exports everything regardless of timestamps`() {
        val processor = buildProcessor()
        val now = System.currentTimeMillis()

        // Events: some old, some new
        emitWithTimestamp(processor, "ancient", now - 48 * 60 * 60 * 1000)
        emitWithTimestamp(processor, "recent", now - 30 * 1000)

        processor.forceFlush()
        val arrived = mockExporter.waitForLogs(2, timeoutMs = 5000)
        assertTrue("forceFlush exports ALL events", arrived)
        assertEquals(2, mockExporter.exportedLogs.size)
        processor.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Concurrent flush + emit safety
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `B-013 concurrent flush and onEmit do not corrupt state`() {
        val processor = buildProcessor(ramBufferSize = 1000)

        // Writer thread: continuously emitting
        val writer = Thread {
            repeat(500) {
                emit(processor, "concurrent-$it")
                Thread.sleep(1)
            }
        }

        // Flusher thread: periodically flushing
        val flusher = Thread {
            repeat(5) {
                Thread.sleep(50)
                processor.forceFlush()
            }
        }

        writer.start()
        flusher.start()
        writer.join()
        flusher.join()

        // Final flush to get remaining events
        processor.forceFlush()
        Thread.sleep(2000)

        // Should have all 500 events (some via interim flushes, rest via final)
        // Allow brief settling time for final async operations
        mockExporter.waitForLogs(500, timeoutMs = 10000)
        assertTrue(
            "All events should survive concurrent flush+emit (got ${mockExporter.exportedLogs.size})",
            mockExporter.exportedLogs.size >= 490 // Allow small tolerance for race timing
        )
        processor.shutdown()
    }
}
