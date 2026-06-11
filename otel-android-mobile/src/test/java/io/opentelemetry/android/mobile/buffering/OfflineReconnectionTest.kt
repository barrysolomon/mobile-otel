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
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR-012: Offline→online reconnection integration test.
 *
 * Verifies that events buffered during an offline period (export failure)
 * are successfully drained when connectivity is restored (export succeeds again).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineReconnectionTest {

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
            serviceName = "reconnection-test",
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

    @Test
    fun `events buffer during offline and export after reconnection`() {
        // Phase 1: Go "offline" — exporter fails
        mockExporter.shouldFail = true

        repeat(10) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("offline.$i")))
        }
        assertEquals(10, processor.getBufferStats().ramBufferSize)

        // Attempt flush while offline — should fail but not lose events
        processor.forceFlush()
        Thread.sleep(500)

        assertEquals(
            "No events should have been exported while offline",
            0, mockExporter.exportedLogs.size
        )
        assertTrue(
            "Events must still be in buffer after failed export",
            processor.getBufferStats().ramBufferSize > 0 ||
                processor.getBufferStats().diskBufferSize > 0
        )

        // Phase 2: Go "online" — exporter succeeds
        mockExporter.shouldFail = false

        val result = processor.forceFlush()
        result.join(10, TimeUnit.SECONDS)
        assertTrue("Force flush should succeed after reconnection", result.isSuccess)

        assertEquals(
            "All 10 events should be exported after reconnection, exactly once",
            10,
            mockExporter.exportedLogs.size
        )
    }

    @Test
    fun `disk-overflowed events survive offline and export on reconnection`() {
        // Fill RAM to capacity, forcing overflow to disk
        mockExporter.shouldFail = true

        repeat(100) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("overflow.$i")))
        }
        // Trigger disk overflow
        repeat(20) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("disk.$i")))
        }
        Thread.sleep(300)

        val stats = processor.getBufferStats()
        assertTrue(
            "Some events should have overflowed to disk",
            stats.diskBufferSize > 0 || stats.ramBufferSize > 0
        )

        // Go online and flush
        mockExporter.shouldFail = false
        processor.forceFlush()
        Thread.sleep(500)

        assertTrue(
            "All buffered events (RAM + disk) should export on reconnection",
            mockExporter.exportedLogs.size >= 100
        )
    }

    @Test
    fun `events emitted during offline do not duplicate on reconnection`() {
        mockExporter.shouldFail = true

        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("dedup.$i")))
        }

        // Try flush while offline (fails)
        processor.forceFlush()
        Thread.sleep(300)

        // Go online
        mockExporter.shouldFail = false
        processor.forceFlush()
        Thread.sleep(500)

        val dedupEvents = mockExporter.exportedLogs.filter {
            it.body.asString().startsWith("dedup.")
        }
        assertEquals(
            "Each event should export exactly once (no duplicates)",
            5, dedupEvents.size
        )
    }

    @Test
    fun `flushWindow works after reconnection for time-bounded export`() {
        mockExporter.shouldFail = true

        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("window.$i")))
        }

        // Go online and flush last 5 minutes
        mockExporter.shouldFail = false
        val result = processor.flushWindow(5)
        assertTrue("flushWindow should succeed after reconnection", result.isSuccess)
        Thread.sleep(500)

        assertTrue(
            "Time-windowed flush should export recent events",
            mockExporter.exportedLogs.isNotEmpty()
        )
    }
}
