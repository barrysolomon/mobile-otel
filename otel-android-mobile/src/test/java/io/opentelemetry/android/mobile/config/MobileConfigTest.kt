/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import io.opentelemetry.sdk.logs.export.LogRecordExporter
import org.junit.Test
import io.opentelemetry.android.mobile.config.ExportMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Unit tests for MobileConfig.
 *
 * Tests configuration validation, builder pattern, and default values.
 */
class MobileConfigTest {

    @Test
    fun `valid config with required fields`() {
        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        assertEquals("test-service", config.serviceName)
        assertEquals("1.0.0", config.serviceVersion)
        assertEquals("http://localhost:4317", config.collectorEndpoint)
    }

    @Test
    fun `default values are applied`() {
        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        assertEquals(5000, config.ramBufferSize)
        assertEquals(50, config.diskBufferMb)
        assertEquals(24, config.diskBufferTtlHours)
        assertEquals(30L, config.exportTimeoutSeconds)
        assertEquals(true, config.remoteConfigEnabled)
        assertEquals(false, config.appManagedScreens)
        assertEquals(300L, config.configPollIntervalSeconds)
        assertEquals(null, config.headers)
    }

    @Test
    fun `default export mode is HYBRID`() {
        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )
        assertEquals(ExportMode.HYBRID, config.exportMode, "PR-007: default must be HYBRID, not CONDITIONAL")
    }

    @Test
    fun `builder default export mode is HYBRID`() {
        val config = MobileConfig.builder()
            .setServiceName("test-service")
            .setServiceVersion("1.0.0")
            .setCollectorEndpoint("http://localhost:4317")
            .build()
        assertEquals(ExportMode.HYBRID, config.exportMode, "PR-007: builder default must be HYBRID")
    }

    @Test
    fun `custom values override defaults`() {
        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = 10000,
            diskBufferMb = 100,
            diskBufferTtlHours = 48,
            exportTimeoutSeconds = 60L,
            remoteConfigEnabled = false,
            configPollIntervalSeconds = 600L,
            headers = mapOf("Authorization" to "Bearer token")
        )

        assertEquals(10000, config.ramBufferSize)
        assertEquals(100, config.diskBufferMb)
        assertEquals(48, config.diskBufferTtlHours)
        assertEquals(60L, config.exportTimeoutSeconds)
        assertEquals(false, config.remoteConfigEnabled)
        assertEquals(600L, config.configPollIntervalSeconds)
        assertEquals(mapOf("Authorization" to "Bearer token"), config.headers)
    }

    @Test
    fun `blank serviceName throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig(
                serviceName = "",
                serviceVersion = "1.0.0",
                collectorEndpoint = "http://localhost:4317"
            )
        }
    }

    @Test
    fun `blank serviceVersion throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig(
                serviceName = "test-service",
                serviceVersion = "",
                collectorEndpoint = "http://localhost:4317"
            )
        }
    }

    @Test
    fun `blank collectorEndpoint throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig(
                serviceName = "test-service",
                serviceVersion = "1.0.0",
                collectorEndpoint = ""
            )
        }
    }

    @Test
    fun `negative ramBufferSize throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig(
                serviceName = "test-service",
                serviceVersion = "1.0.0",
                collectorEndpoint = "http://localhost:4317",
                ramBufferSize = -1
            )
        }
    }

    @Test
    fun `zero ramBufferSize throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig(
                serviceName = "test-service",
                serviceVersion = "1.0.0",
                collectorEndpoint = "http://localhost:4317",
                ramBufferSize = 0
            )
        }
    }

    @Test
    fun `negative diskBufferMb throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig(
                serviceName = "test-service",
                serviceVersion = "1.0.0",
                collectorEndpoint = "http://localhost:4317",
                diskBufferMb = -1
            )
        }
    }

    @Test
    fun `builder pattern creates valid config`() {
        val config = MobileConfig.builder()
            .setServiceName("test-service")
            .setServiceVersion("1.0.0")
            .setCollectorEndpoint("http://localhost:4317")
            .build()

        assertNotNull(config)
        assertEquals("test-service", config.serviceName)
        assertEquals("1.0.0", config.serviceVersion)
        assertEquals("http://localhost:4317", config.collectorEndpoint)
    }

    @Test
    fun `builder with custom values`() {
        val config = MobileConfig.builder()
            .setServiceName("test-service")
            .setServiceVersion("1.0.0")
            .setCollectorEndpoint("http://localhost:4317")
            .setRamBufferSize(10000)
            .setDiskBufferMb(100)
            .setDiskBufferTtlHours(48)
            .setExportTimeoutSeconds(60L)
            .setConfigPollIntervalSeconds(600L)
            .setHeaders(mapOf("Authorization" to "Bearer token"))
            .build()

        assertEquals(10000, config.ramBufferSize)
        assertEquals(100, config.diskBufferMb)
        assertEquals(48, config.diskBufferTtlHours)
        assertEquals(60L, config.exportTimeoutSeconds)
        assertEquals(600L, config.configPollIntervalSeconds)
        assertNotNull(config.headers)
    }

    @Test
    fun `builder without serviceName throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig.builder()
                .setServiceVersion("1.0.0")
                .setCollectorEndpoint("http://localhost:4317")
                .build()
        }
    }

    @Test
    fun `builder without serviceVersion throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig.builder()
                .setServiceName("test-service")
                .setCollectorEndpoint("http://localhost:4317")
                .build()
        }
    }

    @Test
    fun `builder without collectorEndpoint throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            MobileConfig.builder()
                .setServiceName("test-service")
                .setServiceVersion("1.0.0")
                .build()
        }
    }

    @Test
    fun `data class copy works correctly`() {
        val original = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        val modified = original.copy(
            serviceName = "modified-service"
        )

        assertEquals("modified-service", modified.serviceName)
        assertEquals("1.0.0", modified.serviceVersion)
        assertEquals("http://localhost:4317", modified.collectorEndpoint)
    }

    @Test
    fun `data class equals works correctly`() {
        val config1 = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        val config2 = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        assertEquals(config1, config2)
    }

    @Test
    fun `data class hashCode works correctly`() {
        val config1 = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        val config2 = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `setAppManagedScreens round-trips through builder`() {
        val config = MobileConfig.builder()
            .setServiceName("test")
            .setServiceVersion("1.0")
            .setCollectorEndpoint("https://example.com:4317")
            .setAppManagedScreens(true)
            .build()
        assertEquals(true, config.appManagedScreens)
    }

    @Test
    fun `buildWithCustomizers returns config and customizers`() {
        val customizer: (LogRecordExporter) -> LogRecordExporter = { it }
        val (config, customizers) = MobileConfig.builder()
            .setServiceName("test")
            .setServiceVersion("1.0")
            .setCollectorEndpoint("https://example.com:4317")
            .addLogExporterCustomizer(customizer)
            .buildWithCustomizers()

        assertEquals("test", config.serviceName)
        assertEquals(1, customizers.log.size)
        assertSame(customizer, customizers.log[0])
    }
}
