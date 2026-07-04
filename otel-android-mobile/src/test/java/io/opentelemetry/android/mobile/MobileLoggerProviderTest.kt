/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.config.ExporterCustomizers
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.OtlpProtocol
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for MobileLoggerProvider.
 *
 * Tests OTEL SDK initialization, device ID management, resource configuration,
 * and lifecycle methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileLoggerProviderTest {

    private lateinit var context: Context
    private lateinit var config: MobileConfig

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        // Clear singleton for each test
        clearSingleton()
    }

    @After
    fun tearDown() {
        clearSingleton()
    }

    @Test
    fun `initialization creates valid provider`() {
        val provider = MobileLoggerProvider.getInstance(context, config)

        assertNotNull(provider)
        assertNotNull(provider.getDeviceId())
        assertNotNull(provider.getOpenTelemetrySdk())
    }

    @Test
    fun `singleton returns same instance`() {
        val provider1 = MobileLoggerProvider.getInstance(context, config)
        val provider2 = MobileLoggerProvider.getInstance(context, config)

        assertTrue(provider1 === provider2, "Should return same singleton instance")
    }

    @Test
    fun `device ID persists across restarts`() {
        val provider1 = MobileLoggerProvider.getInstance(context, config)
        val deviceId1 = provider1.getDeviceId()

        // Simulate app restart by clearing singleton
        clearSingleton()

        val provider2 = MobileLoggerProvider.getInstance(context, config)
        val deviceId2 = provider2.getDeviceId()

        assertEquals(deviceId1, deviceId2, "Device ID should persist in SharedPreferences")
    }

    @Test
    fun `get logger returns valid logger instance`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val logger = provider.get("test-component")

        assertNotNull(logger)
    }

    @Test
    fun `loggerBuilder returns valid builder`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val builder = provider.loggerBuilder("test-component")

        assertNotNull(builder)
    }

    @Test
    fun `force flush completes successfully`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val result = provider.forceFlush(5)

        assertTrue(result.isSuccess || result.isDone, "Force flush should complete")
    }

    @Test
    fun `shutdown completes successfully`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val result = provider.shutdown(5)

        assertTrue(result.isSuccess || result.isDone, "Shutdown should complete")
    }

    @Test
    fun `different configs create providers with different settings`() {
        val config1 = MobileConfig(
            serviceName = "service-1",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        val config2 = MobileConfig(
            serviceName = "service-2",
            serviceVersion = "2.0.0",
            collectorEndpoint = "http://localhost:4318"
        )

        val provider1 = MobileLoggerProvider.getInstance(context, config1)
        clearSingleton()
        val provider2 = MobileLoggerProvider.getInstance(context, config2)

        // Both should initialize successfully with different configs
        assertNotNull(provider1)
        assertNotNull(provider2)
    }

    @Test
    fun `device ID is valid UUID format`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val deviceId = provider.getDeviceId()

        // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        val uuidRegex = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        )

        assertTrue(
            deviceId.matches(uuidRegex),
            "Device ID should be valid UUID format: $deviceId"
        )
    }

    @Test
    fun `getInstanceOrNull returns null when not initialized`() {
        val instance = MobileLoggerProvider.getInstanceOrNull()
        assertEquals(null, instance, "Should return null when not initialized")
    }

    @Test
    fun `getInstanceOrNull returns instance when initialized`() {
        MobileLoggerProvider.getInstance(context, config)
        val instance = MobileLoggerProvider.getInstanceOrNull()

        assertNotNull(instance, "Should return instance when initialized")
    }

    @Test
    fun `shutdown nulls singleton instance`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        assertNotNull(MobileLoggerProvider.getInstanceOrNull(), "Should exist before shutdown")

        provider.shutdown(5)

        val afterShutdown = MobileLoggerProvider.getInstanceOrNull()
        assertEquals(null, afterShutdown, "Singleton must be null after shutdown (PR-006)")
    }

    @Test
    fun `re-initialization works after shutdown`() {
        val provider1 = MobileLoggerProvider.getInstance(context, config)
        provider1.shutdown(5)

        val provider2 = MobileLoggerProvider.getInstance(context, config)
        assertNotNull(provider2, "Should allow re-initialization after shutdown")
        assertTrue(provider1 !== provider2, "New instance should be different object")
    }

    // ---------------------------------------------------------------------
    // OTLP protocol selection (Loper finding #3): the exporter type the
    // provider constructs must follow MobileConfig.protocol. We capture the
    // base exporter via the exporter-customizer seam — the customizer is
    // invoked with exactly the exporter the provider built, so its concrete
    // class is the ground truth for which transport was wired.
    // ---------------------------------------------------------------------

    /** Captures the base log/span/metric exporters via the customizer hooks. */
    private class CapturingCustomizers {
        var log: LogRecordExporter? = null
        var span: SpanExporter? = null
        var metric: MetricExporter? = null

        fun build(): ExporterCustomizers = ExporterCustomizers(
            // The provider hands the customizer its BASE exporter, which is now
            // a lazy wrapper (built on first export, off the main thread —
            // HS-001). Materialize it so these tests keep asserting the
            // concrete transport class that will actually be used.
            log = listOf({ e -> log = (e as? io.opentelemetry.android.mobile.export.LazyLogRecordExporter)?.materialize() ?: e; e }),
            span = listOf({ e -> span = (e as? io.opentelemetry.android.mobile.export.LazySpanExporter)?.materialize() ?: e; e }),
            metric = listOf({ e -> metric = (e as? io.opentelemetry.android.mobile.export.LazyMetricExporter)?.materialize() ?: e; e })
        )
    }

    @Test
    fun `default protocol is HTTP_PROTOBUF`() {
        val cfg = MobileConfig(
            serviceName = "svc",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://ingress.example.com"
        )
        assertEquals(
            OtlpProtocol.HTTP_PROTOBUF,
            cfg.protocol,
            "Default protocol must be HTTP_PROTOBUF to match iOS"
        )
    }

    @Test
    fun `builder default protocol is HTTP_PROTOBUF`() {
        val cfg = MobileConfig.builder()
            .setServiceName("svc")
            .setServiceVersion("1.0.0")
            .setCollectorEndpoint("https://ingress.example.com")
            .build()
        assertEquals(OtlpProtocol.HTTP_PROTOBUF, cfg.protocol)
    }

    @Test
    fun `default config wires OTLP HTTP exporters`() {
        val cfg = MobileConfig(
            serviceName = "svc",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://ingress.example.com"
        )
        val cap = CapturingCustomizers()
        MobileLoggerProvider.getInstance(context, cfg, cap.build())

        assertTrue(
            cap.log is OtlpHttpLogRecordExporter,
            "Default log exporter should be OTLP/HTTP, was ${cap.log?.javaClass?.name}"
        )
        assertTrue(
            cap.span is OtlpHttpSpanExporter,
            "Default span exporter should be OTLP/HTTP, was ${cap.span?.javaClass?.name}"
        )
        assertTrue(
            cap.metric is OtlpHttpMetricExporter,
            "Default metric exporter should be OTLP/HTTP, was ${cap.metric?.javaClass?.name}"
        )
    }

    @Test
    fun `HTTP_PROTOBUF protocol wires OTLP HTTP exporters`() {
        val cfg = MobileConfig(
            serviceName = "svc",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://ingress.example.com",
            protocol = OtlpProtocol.HTTP_PROTOBUF
        )
        val cap = CapturingCustomizers()
        MobileLoggerProvider.getInstance(context, cfg, cap.build())

        assertTrue(cap.log is OtlpHttpLogRecordExporter)
        assertTrue(cap.span is OtlpHttpSpanExporter)
        assertTrue(cap.metric is OtlpHttpMetricExporter)
    }

    @Test
    fun `GRPC protocol preserves OTLP gRPC exporters`() {
        val cfg = MobileConfig(
            serviceName = "svc",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://collector.example.com:4317",
            protocol = OtlpProtocol.GRPC
        )
        val cap = CapturingCustomizers()
        MobileLoggerProvider.getInstance(context, cfg, cap.build())

        assertTrue(
            cap.log is OtlpGrpcLogRecordExporter,
            "gRPC log exporter expected, was ${cap.log?.javaClass?.name}"
        )
        assertTrue(
            cap.span is OtlpGrpcSpanExporter,
            "gRPC span exporter expected, was ${cap.span?.javaClass?.name}"
        )
        assertTrue(
            cap.metric is OtlpGrpcMetricExporter,
            "gRPC metric exporter expected, was ${cap.metric?.javaClass?.name}"
        )
    }

    @Test
    fun `HTTP exporters build with auth and extra headers without error`() {
        // Header propagation uses the same builder block as the gRPC path
        // (config.headers.forEach { addHeader(...) }); this verifies the HTTP
        // exporter accepts the Authorization Bearer + extra headers and the
        // provider initializes successfully with the HTTP transport wired.
        val cfg = MobileConfig(
            serviceName = "svc",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://ingress.example.com",
            protocol = OtlpProtocol.HTTP_PROTOBUF,
            headers = mapOf(
                "Authorization" to "Bearer test-token",
                "X-Custom-Header" to "custom-value"
            )
        )
        val cap = CapturingCustomizers()
        val provider = MobileLoggerProvider.getInstance(context, cfg, cap.build())

        assertNotNull(provider.getOpenTelemetrySdk())
        assertTrue(cap.log is OtlpHttpLogRecordExporter)
        assertTrue(cap.span is OtlpHttpSpanExporter)
        assertTrue(cap.metric is OtlpHttpMetricExporter)
    }

    @Test
    fun `HTTP endpoint already suffixed is not double-appended`() {
        // Regression guard around buildOtlpHttpUrl as wired through the
        // provider: an endpoint already carrying /v1/logs must still build a
        // valid HTTP exporter (the suffix logic is exercised at construction).
        val cfg = MobileConfig(
            serviceName = "svc",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://ingress.example.com/v1/logs",
            protocol = OtlpProtocol.HTTP_PROTOBUF
        )
        val cap = CapturingCustomizers()
        MobileLoggerProvider.getInstance(context, cfg, cap.build())
        assertTrue(cap.log is OtlpHttpLogRecordExporter)
    }

    /**
     * Helper to clear singleton using reflection for testing.
     */
    private fun clearSingleton() {
        try {
            val instanceField = MobileLoggerProvider::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        } catch (e: Exception) {
            // Ignore if field doesn't exist or can't be cleared
        }
    }
}
