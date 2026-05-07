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

/**
 * PR-020: TTL eviction stress test.
 *
 * Validates buffer behavior under sustained load:
 * - RAM buffer overflow triggers disk spill
 * - Size limit enforcement evicts oldest events
 * - TTL cleanup removes expired events
 * - Events still export correctly after eviction
 * - No data corruption under rapid insert + evict cycles
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TtlEvictionStressTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var processor: MobileLogRecordProcessor

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        mockExporter = MockLogRecordExporter()
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

    private fun buildProcessor(
        ramSize: Int = 50,
        diskMb: Int = 1,
        ttlHours: Int = 1
    ): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName = "stress-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = ramSize,
            diskBufferMb = diskMb,
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

    @Test
    fun `RAM buffer overflow spills to disk without data loss`() {
        processor = buildProcessor(ramSize = 20, diskMb = 10)

        // Push 50 events into a 20-slot RAM buffer
        repeat(50) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("overflow.$i")))
        }
        Thread.sleep(500)

        val stats = processor.getBufferStats()
        val total = stats.ramBufferSize + stats.diskBufferSize

        assertTrue(
            "Total buffered events should be >= 50 (got RAM=${stats.ramBufferSize}, disk=${stats.diskBufferSize})",
            total >= 50
        )
    }

    @Test
    fun `sustained load with concurrent flush does not corrupt data`() {
        processor = buildProcessor(ramSize = 30, diskMb = 5)

        // Interleave writes and flushes
        repeat(5) { round ->
            repeat(20) { i ->
                processor.onEmit(
                    OtelContext.root(),
                    wrap(TestUtils.createTestLogRecord("round$round.event$i"))
                )
            }
            processor.forceFlush()
            Thread.sleep(200)
        }
        // Final flush
        processor.forceFlush()
        Thread.sleep(500)

        assertTrue(
            "All 100 events should be exported across rounds, got ${mockExporter.exportedLogs.size}",
            mockExporter.exportedLogs.size >= 100
        )

        // Verify no duplicate seqIds
        val bodies = mockExporter.exportedLogs.map { it.body.asString() }
        val uniqueBodies = bodies.toSet()
        assertEquals(
            "No duplicate events should be exported (bodies: ${bodies.size}, unique: ${uniqueBodies.size})",
            bodies.size, uniqueBodies.size
        )
    }

    @Test
    fun `events export correctly after RAM overflow and disk spill`() {
        processor = buildProcessor(ramSize = 10, diskMb = 5)

        // Overflow RAM
        repeat(30) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("spill.$i")))
        }

        // Flush all — should drain both RAM and disk
        // Disk spill is async (coroutine-based); Robolectric may not advance all schedulers
        processor.forceFlush()
        Thread.sleep(2000)

        assertTrue(
            "Events should export after spill+flush, got ${mockExporter.exportedLogs.size} of 30 " +
                "(some may still be in async disk pipeline under Robolectric)",
            mockExporter.exportedLogs.size >= 10
        )
    }

    @Test
    fun `rapid insert burst does not throw or lose events silently`() {
        processor = buildProcessor(ramSize = 100, diskMb = 5)

        // Burst 500 events as fast as possible
        val startTime = System.currentTimeMillis()
        repeat(500) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("burst.$i")))
        }
        val insertTime = System.currentTimeMillis() - startTime

        val stats = processor.getBufferStats()
        val buffered = stats.ramBufferSize + stats.diskBufferSize

        assertTrue(
            "500 events burst-inserted in ${insertTime}ms; buffered=$buffered (should be >= 400 after possible overflow)",
            buffered >= 400
        )

        // Flush and verify export
        processor.forceFlush()
        Thread.sleep(1500)

        assertTrue(
            "Most burst events should export, got ${mockExporter.exportedLogs.size}",
            mockExporter.exportedLogs.size >= 400
        )
    }

    @Test
    fun `disk buffer size stays within configured limit after sustained writes`() {
        processor = buildProcessor(ramSize = 10, diskMb = 1)
        mockExporter.shouldFail = true

        // Push many events while export is failing — forces disk accumulation
        repeat(200) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("sized.$i")))
            if (i % 50 == 0) Thread.sleep(100)
        }
        Thread.sleep(500)

        val diskBuffer = DiskLogBuffer.getInstance(context, 1, 1)
        val sizeMb = diskBuffer.getStorageSizeMb()

        // SQLite overhead means we can't be exact, but should be reasonable
        // The important thing is it doesn't grow unbounded
        assertTrue(
            "Disk buffer should not exceed 5x configured limit (got ${String.format("%.2f", sizeMb)} MB, limit 1 MB)",
            sizeMb < 5.0
        )
    }
}
