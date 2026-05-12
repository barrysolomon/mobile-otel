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
import io.opentelemetry.android.mobile.network.NetworkAvailabilityWatcher
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NF-003: When the network transitions LOST → AVAILABLE, the
 * [MobileLogRecordProcessor] must drain its buffered events.
 *
 * This closes the demo gap reported on 2026-05-12: booking failed in
 * airplane mode → airplane mode off → no telemetry arrived because nothing
 * woke the exporter.
 *
 * See: `docs/epics/NETWORK_RESTORED_FLUSH_EPIC.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkRestoredFlushTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var processor: MobileLogRecordProcessor
    private lateinit var watcher: NetworkAvailabilityWatcher

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        mockExporter = MockLogRecordExporter()

        val config = MobileConfig(
            serviceName = "network-restored-test",
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

        watcher = NetworkAvailabilityWatcher()
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
    fun `processor flushes buffered events on LOST then AVAILABLE transition`() {
        // Subscribe processor to the watcher
        processor.attachNetworkWatcher(watcher, windowMinutes = 60)

        // Phase 1: Buffer events while "offline" (exporter fails)
        mockExporter.shouldFail = true
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("airplane.$i")))
        }

        // Verify they didn't escape during offline
        processor.forceFlush()
        Thread.sleep(300)
        assertTrue(
            "Events must still be buffered while exporter is failing — got ${mockExporter.exportedLogs.size}",
            mockExporter.exportedLogs.size == 0
        )

        // Phase 2: Toggle network back on. Exporter recovers.
        mockExporter.shouldFail = false
        watcher.onLost()
        watcher.onAvailable() // <-- LOST → AVAILABLE: this must trigger flushWindow

        // Give the processor's executor time to drain
        Thread.sleep(800)

        assertTrue(
            "After network restored, buffered events must be exported — got ${mockExporter.exportedLogs.size}",
            mockExporter.exportedLogs.size >= 5
        )
    }

    @Test
    fun `processor does NOT flush on bare AVAILABLE without prior LOST`() {
        processor.attachNetworkWatcher(watcher, windowMinutes = 60)
        mockExporter.shouldFail = false

        // Emit some events
        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("warmup.$i")))
        }
        val baseline = mockExporter.exportedLogs.size

        // Spurious onAvailable (no prior onLost) — must NOT trigger flush
        watcher.onAvailable()
        Thread.sleep(300)

        assertTrue(
            "Spurious onAvailable must not trigger a flush — exported went from $baseline to ${mockExporter.exportedLogs.size}",
            mockExporter.exportedLogs.size == baseline
        )
    }
}
