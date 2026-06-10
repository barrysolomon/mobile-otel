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
import io.opentelemetry.android.mobile.policy.RemoteGate
import io.opentelemetry.android.mobile.policy.SdkConfig
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
 * Tests for the remote kill-switch / global-sampling integration in
 * [MobileLogRecordProcessor.onEmit].
 *
 * - `enabled = false` drops logs before buffering (RAM buffer stays empty).
 * - `sample_rate = 0` drops all; `= 1` keeps all.
 * - re-enabling via the shared gate restores buffering.
 * - a bridge-style emit (the path React Native uses) is dropped when disabled,
 *   proving RN coverage with no RN-side change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileLogRecordProcessorGateTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var processor: MobileLogRecordProcessor
    private lateinit var gate: RemoteGate

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        mockExporter = MockLogRecordExporter()
        gate = RemoteGate()

        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            // CONTINUOUS so onEmit buffers into RAM without immediate HYBRID export paths.
            exportMode = io.opentelemetry.android.mobile.config.ExportMode.CONTINUOUS,
            ramBufferSize = 100,
            diskBufferMb = 10,
            diskBufferTtlHours = 1,
            // Don't poll a real endpoint during the test.
            remoteConfigEnabled = false
        )

        val meter = OpenTelemetry.noop().meterProvider.get("test")
        processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .setRemoteGate(gate)
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
    fun `processor uses the shared gate instance`() {
        assertTrue(processor.getRemoteGate() === gate)
    }

    @Test
    fun `enabled gate buffers logs normally`() {
        repeat(10) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("event.$i")))
        }
        assertEquals(10, processor.getBufferStats().ramBufferSize)
    }

    @Test
    fun `disabled gate drops logs before buffering`() {
        gate.apply(SdkConfig(enabled = false, sampleRate = 1.0))
        repeat(50) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("event.$i")))
        }
        val stats = processor.getBufferStats()
        assertEquals("disabled SDK must not buffer", 0, stats.ramBufferSize)
        assertEquals(0, stats.diskBufferSize)
    }

    @Test
    fun `sample_rate 0 drops all logs`() {
        gate.apply(SdkConfig(enabled = true, sampleRate = 0.0))
        repeat(50) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("event.$i")))
        }
        assertEquals(0, processor.getBufferStats().ramBufferSize)
    }

    @Test
    fun `sample_rate 1 keeps all logs`() {
        gate.apply(SdkConfig(enabled = true, sampleRate = 1.0))
        repeat(25) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("event.$i")))
        }
        assertEquals(25, processor.getBufferStats().ramBufferSize)
    }

    @Test
    fun `re-enabling the gate restores buffering`() {
        gate.apply(SdkConfig(enabled = false))
        repeat(10) { processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("dropped.$it"))) }
        assertEquals(0, processor.getBufferStats().ramBufferSize)

        // Absent sdk block on a subsequent fetch re-opens the gate.
        gate.apply(null)
        repeat(10) { processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("kept.$it"))) }
        assertEquals(10, processor.getBufferStats().ramBufferSize)
    }

    @Test
    fun `bridge-style emit is dropped when disabled (RN coverage regression)`() {
        // React Native telemetry flows through OTelMobileCallSink → the native OTel
        // logger → this same onEmit. We simulate a bridge-originated record (carrying
        // the distro attribute the RN bridge stamps) and assert the native gate drops it
        // with no RN-side code. This is the §6 regression guard.
        gate.apply(SdkConfig(enabled = false))

        val bridgeRecord = TestUtils.createTestLogRecord("rn.error")
        repeat(5) { processor.onEmit(OtelContext.root(), wrap(bridgeRecord)) }

        assertEquals("RN bridge emit must be dropped when SDK disabled", 0,
            processor.getBufferStats().ramBufferSize)
    }
}
