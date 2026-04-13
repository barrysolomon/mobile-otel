// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.mobile.buffering.DiskLogBuffer
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Severity
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
 * Task 6: Hybrid timing test -- verifies that HYBRID mode exports heartbeat
 * events immediately (continuous path) and buffered events after a policy
 * trigger fires (conditional path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HybridTimingTest {

    private lateinit var context: Context
    private lateinit var exporter: MockLogRecordExporter

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        exporter = MockLogRecordExporter()
    }

    @After
    fun teardown() {
        DiskLogBuffer.resetForTesting()
    }

    private fun buildProcessor(): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName = "hybrid-timing-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            exportMode = ExportMode.HYBRID,
            traceExportIntervalSeconds = 30,
            ramBufferSize = 100,
            diskBufferMb = 10,
            diskBufferTtlHours = 1
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        return MobileLogRecordProcessor.builder(context)
            .setExporter(exporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    private fun wrap(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    private fun emit(processor: MobileLogRecordProcessor, record: LogRecordData) {
        processor.onEmit(OtelContext.root(), wrap(record))
    }

    // ── 1. Heartbeats export before crash trigger; crash trigger exports more ─

    @Test
    fun `hybrid mode exports heartbeats immediately and buffered events after trigger`() {
        val processor = buildProcessor()
        try {
            // Step 1: Emit 3 heartbeat events (immediate export path in HYBRID)
            emit(processor, TestUtils.createTestLogRecord(
                body = "device.heartbeat",
                severity = Severity.INFO,
                attributes = mapOf("device.battery" to 85)
            ))
            emit(processor, TestUtils.createTestLogRecord(
                body = "prediction.cycle",
                severity = Severity.INFO,
                attributes = mapOf("cycle.id" to 1)
            ))
            emit(processor, TestUtils.createTestLogRecord(
                body = "prediction.high_risk_alert",
                severity = Severity.WARN,
                attributes = mapOf("risk.level" to "high")
            ))

            // Step 2: Let immediate export path fire
            Thread.sleep(500)

            // Step 3: Record heartbeat batch count
            val heartbeatBatchCount = exporter.exportBatches.size

            // Step 4: Emit buffered events + crash trigger
            emit(processor, TestUtils.createNavigationEvent("ScreenA"))
            emit(processor, TestUtils.createNavigationEvent("ScreenB"))
            emit(processor, TestUtils.createCrashLog())

            // Step 5: Let policy flush fire
            Thread.sleep(1000)

            // Step 6: Assert heartbeats exported before trigger
            assertTrue(
                "Heartbeats should have been exported before crash trigger " +
                "(heartbeatBatchCount=$heartbeatBatchCount)",
                heartbeatBatchCount > 0
            )

            // Step 7: Assert policy flush added more batches
            assertTrue(
                "Policy flush should add batches beyond heartbeat batches " +
                "(total=${exporter.exportBatches.size}, heartbeat=$heartbeatBatchCount)",
                exporter.exportBatches.size > heartbeatBatchCount
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `hybrid heartbeat export contains correct event bodies`() {
        DiskLogBuffer.resetForTesting()
        val exporter = MockLogRecordExporter()
        val config = MobileConfig(
            serviceName = "hybrid-content",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            exportMode = ExportMode.HYBRID,
            ramBufferSize = 100,
            traceExportIntervalSeconds = 30
        )
        val processor = MobileLogRecordProcessor.builder(context)
            .setExporter(exporter)
            .setConfig(config)
            .setMeter(OpenTelemetry.noop().meterProvider.get("test"))
            .setRamBufferSize(100)
            .setDiskBufferMb(10)
            .setDiskBufferTtlHours(1)
            .build()

        try {
            TestUtils.emitAll(processor, listOf(
                TestUtils.createTestLogRecord("device.heartbeat"),
                TestUtils.createTestLogRecord("prediction.cycle")
            ))
            Thread.sleep(500)

            val exportedBodies = exporter.exportedLogs.map { it.body.asString() }
            assertTrue(
                "Heartbeat should be in exported logs",
                exportedBodies.contains("device.heartbeat")
            )
            assertTrue(
                "Prediction should be in exported logs",
                exportedBodies.contains("prediction.cycle")
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS with short interval actually exports periodically`() {
        DiskLogBuffer.resetForTesting()
        val exporter = MockLogRecordExporter()
        val config = MobileConfig(
            serviceName = "continuous-periodic",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            exportMode = ExportMode.CONTINUOUS,
            ramBufferSize = 100,
            traceExportIntervalSeconds = 1  // 1 second interval
        )
        val processor = MobileLogRecordProcessor.builder(context)
            .setExporter(exporter)
            .setConfig(config)
            .setMeter(OpenTelemetry.noop().meterProvider.get("test"))
            .setRamBufferSize(100)
            .setDiskBufferMb(10)
            .setDiskBufferTtlHours(1)
            .build()

        try {
            TestUtils.emitAll(processor, listOf(
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createNavigationEvent("Screen2")
            ))

            // Wait for periodic flush to fire (1s interval + margin)
            val exported = exporter.waitForLogs(2, 3000)
            assertTrue(
                "CONTINUOUS with 1s interval should auto-export within 3s",
                exported
            )
            assertTrue(
                "Should export both events periodically",
                exporter.getExportedCount() >= 2
            )
        } finally {
            processor.shutdown()
        }
    }
}
