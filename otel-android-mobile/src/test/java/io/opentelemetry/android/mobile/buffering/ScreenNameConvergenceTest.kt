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
 * Semconv screen-name convergence (docs/SEMCONV_AUDIT.md): the processor's
 * onEmit choke point mirrors the legacy `mobile.screen.name` attribute onto
 * the upstream-aligned `app.screen.name` (renamed from `screen.name` in
 * opentelemetry-android 1.5.0) for EVERY log record, regardless of which
 * instrumentation emitted it. Mirrors the session-id convergence pattern —
 * one choke point instead of ~20 per-instrumentation emit sites, so no
 * instrumentation can drift. The legacy alias drops at 1.0.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenNameConvergenceTest {

    private val legacy = AttributeKey.stringKey("mobile.screen.name")
    private val semconv = AttributeKey.stringKey("app.screen.name")

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

    private val legacyAlt = AttributeKey.stringKey("screen.name")

    @Test
    fun `legacy mobile screen name is mirrored onto the semconv name`() {
        provider.get("t").logRecordBuilder()
            .setBody("ui.tap")
            .setAttribute(legacy, "BookScreen")
            .emit()
        val rec = emitted()
        assertEquals("BookScreen", rec.attributes.get(semconv))
        assertEquals("BookScreen", rec.attributes.get(legacy))
    }

    @Test
    fun `bare screen_name (iOS-style, RN bridge, app-authored) is also mirrored`() {
        provider.get("t").logRecordBuilder()
            .setBody("ui.screen_view")
            .setAttribute(legacyAlt, "ProfileScreen")
            .emit()
        val rec = emitted()
        assertEquals("ProfileScreen", rec.attributes.get(semconv))
        assertEquals("ProfileScreen", rec.attributes.get(legacyAlt))
    }

    @Test
    fun `an existing semconv screen name is never overwritten`() {
        provider.get("t").logRecordBuilder()
            .setBody("custom")
            .setAttribute(legacy, "legacy-screen")
            .setAttribute(semconv, "explicit-screen")
            .emit()
        assertEquals("explicit-screen", emitted().attributes.get(semconv))
    }

    @Test
    fun `records without a screen name are untouched`() {
        provider.get("t").logRecordBuilder().setBody("plain").emit()
        val rec = emitted()
        assertNull(rec.attributes.get(semconv))
        assertNull(rec.attributes.get(legacy))
    }
}
