/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Semconv session-id convergence (docs/SEMCONV_AUDIT.md): the processor's
 * onEmit choke point mirrors the legacy `mobile.session.id` attribute onto
 * the semconv `session.id` for EVERY log record, regardless of which
 * instrumentation emitted it. Found necessary by the 0.4.0 receipt
 * validation: a SessionManager-only dual-emit left the dozens of
 * per-instrumentation emit sites (taps, screens, crashes …) legacy-only in
 * live Dash0 data.
 */
@RunWith(RobolectricTestRunner::class)
class SessionIdConvergenceTest {

    private val legacy = AttributeKey.stringKey("mobile.session.id")
    private val semconv = AttributeKey.stringKey("session.id")

    private val captured = mutableListOf<LogRecordData>()
    private lateinit var provider: SdkLoggerProvider
    private lateinit var processor: MobileLogRecordProcessor

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        val exporter = object : LogRecordExporter {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                captured.addAll(logs); return CompletableResultCode.ofSuccess()
            }
            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }
        processor = MobileLogRecordProcessor.builder(ApplicationProvider.getApplicationContext())
            .setExporter(exporter)
            .setMeter(io.opentelemetry.api.metrics.MeterProvider.noop().get("test"))
            .setConfig(
                MobileConfig(
                    serviceName = "convergence-test",
                    serviceVersion = "1.0.0",
                    collectorEndpoint = "http://localhost:4318",
                    remoteConfigEnabled = false,
                    encryptDiskBufferAtRest = false,
                ),
            )
            .build()
        provider = SdkLoggerProvider.builder().addLogRecordProcessor(processor).build()
    }

    @After
    fun tearDown() {
        processor.shutdown()
        DiskLogBuffer.resetForTesting()
    }

    private fun emitted(): LogRecordData {
        processor.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS)
        return captured.last()
    }

    @Test
    fun `legacy session id is mirrored onto the semconv name`() {
        provider.get("t").logRecordBuilder()
            .setBody("ui.tap")
            .setAttribute(legacy, "session-abc")
            .emit()
        val rec = emitted()
        assertEquals("session-abc", rec.attributes.get(semconv))
        assertEquals("session-abc", rec.attributes.get(legacy))
    }

    @Test
    fun `an existing semconv session id is never overwritten`() {
        provider.get("t").logRecordBuilder()
            .setBody("custom")
            .setAttribute(legacy, "legacy-id")
            .setAttribute(semconv, "explicit-id")
            .emit()
        assertEquals("explicit-id", emitted().attributes.get(semconv))
    }

    @Test
    fun `records without a session id are untouched`() {
        provider.get("t").logRecordBuilder().setBody("plain").emit()
        val rec = emitted()
        assertNull(rec.attributes.get(semconv))
        assertNull(rec.attributes.get(legacy))
    }
}
