/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.config.ExporterCustomizers
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.OtlpProtocol
import io.opentelemetry.android.mobile.config.TransportSecurity
import io.opentelemetry.android.mobile.export.NoopLogRecordExporter
import io.opentelemetry.android.mobile.export.NoopMetricExporter
import io.opentelemetry.android.mobile.export.NoopSpanExporter
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Transport-security behaviour at the OTLP-export construction site
 * (MobileLoggerProvider), iOS parity:
 *
 * - cleartext http:// to a non-loopback host with allowInsecureTransport=false
 *   ⇒ export DISABLED via no-op exporters (graceful degrade, no crash)
 * - allowInsecureTransport=true ⇒ real OTLP/HTTP exporters wired
 * - https:// ⇒ always wired
 * - localhost http:// ⇒ wired (loopback exempt)
 * - pinningConfig set on the HTTP path ⇒ provider builds without crashing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileLoggerProviderTransportTest {

    private lateinit var context: Context

    private class CapturingCustomizers {
        var log: LogRecordExporter? = null
        var span: SpanExporter? = null
        var metric: MetricExporter? = null
        fun build(): ExporterCustomizers = ExporterCustomizers(
            // Materialize the lazy wrappers (exporters now build on first
            // export, off main — HS-001) so these tests keep asserting the
            // concrete transport class that will actually be used.
            log = listOf({ e -> log = (e as? io.opentelemetry.android.mobile.export.LazyLogRecordExporter)?.materialize() ?: e; e }),
            span = listOf({ e -> span = (e as? io.opentelemetry.android.mobile.export.LazySpanExporter)?.materialize() ?: e; e }),
            metric = listOf({ e -> metric = (e as? io.opentelemetry.android.mobile.export.LazyMetricExporter)?.materialize() ?: e; e }),
        )
    }

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        clearSingleton()
    }

    @After
    fun tearDown() {
        clearSingleton()
    }

    private fun cfg(
        endpoint: String,
        allowInsecure: Boolean = false,
        pinning: TransportSecurity.PinningConfig? = null,
        protocol: OtlpProtocol = OtlpProtocol.HTTP_PROTOBUF,
    ) = MobileConfig(
        serviceName = "svc",
        serviceVersion = "1.0.0",
        collectorEndpoint = endpoint,
        protocol = protocol,
        allowInsecureTransport = allowInsecure,
        pinningConfig = pinning,
        remoteConfigEnabled = false, // isolate export wiring from the poller
    )

    @Test
    fun `cleartext non-loopback rejected by default installs no-op exporters`() {
        val cap = CapturingCustomizers()
        val provider = MobileLoggerProvider.getInstance(
            context, cfg("http://collector.example.com:4317"), cap.build(),
        )
        // No crash, and export disabled gracefully via no-op exporters.
        assertNotNull(provider.getOpenTelemetrySdk())
        assertTrue(cap.log === NoopLogRecordExporter, "log export must be disabled (no-op)")
        assertTrue(cap.span === NoopSpanExporter, "span export must be disabled (no-op)")
        assertTrue(cap.metric === NoopMetricExporter, "metric export must be disabled (no-op)")
    }

    @Test
    fun `cleartext non-loopback permitted when allowInsecureTransport true`() {
        val cap = CapturingCustomizers()
        MobileLoggerProvider.getInstance(
            context, cfg("http://collector.example.com:4317", allowInsecure = true), cap.build(),
        )
        assertTrue(cap.log is OtlpHttpLogRecordExporter)
        assertTrue(cap.span is OtlpHttpSpanExporter)
        assertTrue(cap.metric is OtlpHttpMetricExporter)
    }

    @Test
    fun `https always wires real exporters`() {
        val cap = CapturingCustomizers()
        MobileLoggerProvider.getInstance(context, cfg("https://ingress.example.com"), cap.build())
        assertTrue(cap.log is OtlpHttpLogRecordExporter)
        assertTrue(cap.span is OtlpHttpSpanExporter)
        assertTrue(cap.metric is OtlpHttpMetricExporter)
    }

    @Test
    fun `localhost cleartext is exempt and wires real exporters`() {
        val cap = CapturingCustomizers()
        MobileLoggerProvider.getInstance(context, cfg("http://localhost:4317"), cap.build())
        assertTrue(cap.log is OtlpHttpLogRecordExporter)
    }

    @Test
    fun `pinning on the HTTP path builds without crashing`() {
        val held = HeldCertificate.Builder().commonName("x").build()
        val pin = TransportSecurity.spkiSha256Base64(held.certificate.encoded)!!
        val cap = CapturingCustomizers()
        val provider = MobileLoggerProvider.getInstance(
            context,
            cfg("https://ingress.example.com", pinning = TransportSecurity.PinningConfig(spkiSha256Pins = setOf(pin))),
            cap.build(),
        )
        // The pinned SSLContext is wired into the OTLP/HTTP exporter; the host is
        // never crashed by pinning configuration.
        assertNotNull(provider.getOpenTelemetrySdk())
        assertTrue(cap.log is OtlpHttpLogRecordExporter)
    }

    private fun clearSingleton() {
        try {
            val f = MobileLoggerProvider::class.java.getDeclaredField("instance")
            f.isAccessible = true
            f.set(null, null)
        } catch (e: Exception) {
            // ignore
        }
    }
}
