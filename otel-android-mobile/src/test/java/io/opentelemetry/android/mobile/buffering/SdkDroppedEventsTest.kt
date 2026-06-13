/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SDK self-observability (pulled forward from P2 for 1.0): a broken or
 * suppressed SDK must be distinguishable from a quiet app. `sdk.events.dropped`
 * counts every event the SDK discards before export, dimensioned by `reason` —
 * and, like the `sdk.enabled` gauge, it reports even while the remote gate is
 * dropping everything else.
 */
@RunWith(RobolectricTestRunner::class)
class SdkDroppedEventsTest {

    private val reasonKey = AttributeKey.stringKey("reason")
    private lateinit var metricReader: InMemoryMetricReader
    private lateinit var meterProvider: SdkMeterProvider
    private lateinit var processor: MobileLogRecordProcessor
    private lateinit var gate: io.opentelemetry.android.mobile.policy.RemoteGate
    private lateinit var loggerProvider: SdkLoggerProvider

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        metricReader = InMemoryMetricReader.create()
        meterProvider = SdkMeterProvider.builder().registerMetricReader(metricReader).build()
        val noopExporter = object : LogRecordExporter {
            override fun export(logs: Collection<LogRecordData>) = CompletableResultCode.ofSuccess()
            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }
        gate = io.opentelemetry.android.mobile.policy.RemoteGate()
        processor = MobileLogRecordProcessor.builder(ApplicationProvider.getApplicationContext())
            .setExporter(noopExporter)
            .setRemoteGate(gate)
            .setMeter(meterProvider.get("test"))
            // The cap is a builder field (the provider mirrors it from config);
            // tiny so an oversize drop is easy to provoke.
            .setRamBufferMaxEventBytes(64)
            .setConfig(
                MobileConfig(
                    serviceName = "dropped-events-test",
                    serviceVersion = "1.0.0",
                    collectorEndpoint = "http://localhost:4318",
                    remoteConfigEnabled = false,
                    encryptDiskBufferAtRest = false,
                ),
            )
            .build()
        loggerProvider = SdkLoggerProvider.builder().addLogRecordProcessor(processor).build()
    }

    @After
    fun tearDown() {
        processor.shutdown()
        meterProvider.shutdown()
        DiskLogBuffer.resetForTesting()
    }

    private fun droppedBy(reason: String): Long {
        val metric = metricReader.collectAllMetrics().firstOrNull { it.name == "sdk.events.dropped" }
            ?: return 0
        return metric.longSumData.points
            .filter { it.attributes.get(reasonKey) == reason }
            .sumOf { it.value }
    }

    @Test
    fun `oversize events are counted with reason oversize`() {
        loggerProvider.get("t").logRecordBuilder()
            .setBody("x".repeat(10_000)) // far over the 64-byte cap
            .emit()
        assertTrue("oversize drop must be counted, got ${droppedBy("oversize")}", droppedBy("oversize") >= 1)
    }

    @Test
    fun `gate-suppressed events are counted with reason remote_gate`() {
        gate.apply(io.opentelemetry.android.mobile.policy.SdkConfig(enabled = false, sampleRate = 1.0))
        repeat(5) { loggerProvider.get("t").logRecordBuilder().setBody("suppressed-$it").emit() }
        assertEquals("all 5 gated events must be counted", 5L, droppedBy("remote_gate"))
        // And the SDK still reports while gated — that is the whole point.
        gate.apply(io.opentelemetry.android.mobile.policy.SdkConfig(enabled = true, sampleRate = 1.0))
    }

    @Test
    fun `normal events are not counted as dropped`() {
        loggerProvider.get("t").logRecordBuilder().setBody("fine").emit()
        assertEquals(0L, droppedBy("remote_gate"))
        assertEquals(0L, droppedBy("oversize"))
    }
}
