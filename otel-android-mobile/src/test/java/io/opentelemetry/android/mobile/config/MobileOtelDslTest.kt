// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MobileOtelDslTest {

    @Test fun `buildConfig maps service fields`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "test-app"; version = "2.0.0" }
            export { endpoint = "https://example.com:4317" }
        }
        val config = dsl.buildConfig()
        assertEquals("test-app", config.serviceName)
        assertEquals("2.0.0", config.serviceVersion)
    }

    @Test fun `buildConfig maps export fields`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export {
                endpoint = "https://example.com:4317"
                mode = ExportMode.HYBRID
                headers = mapOf("key" to "val")
                timeoutSeconds = 60
                maxRetries = 5
                traceIntervalSeconds = 15
                metricIntervalSeconds = 45
            }
        }
        val config = dsl.buildConfig()
        assertEquals("https://example.com:4317", config.collectorEndpoint)
        assertEquals(ExportMode.HYBRID, config.exportMode)
        assertEquals(mapOf("key" to "val"), config.headers)
        assertEquals(60, config.exportTimeoutSeconds)
        assertEquals(5, config.maxExportRetries)
        assertEquals(15, config.traceExportIntervalSeconds)
        assertEquals(45, config.metricExportIntervalSeconds)
    }

    @Test fun `buildConfig maps buffering fields`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export { endpoint = "https://example.com:4317" }
            buffering { ramSize = 3000; diskMb = 25; ttlHours = 12 }
        }
        val config = dsl.buildConfig()
        assertEquals(3000, config.ramBufferSize)
        assertEquals(25, config.diskBufferMb)
        assertEquals(12, config.diskBufferTtlHours)
    }

    @Test fun `buildConfig uses defaults when blocks not called`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export { endpoint = "https://example.com:4317" }
        }
        val config = dsl.buildConfig()
        assertEquals(5000, config.ramBufferSize)
        assertEquals(50, config.diskBufferMb)
        assertEquals(24, config.diskBufferTtlHours)
        assertEquals(ExportMode.CONDITIONAL, config.exportMode)
    }

    @Test fun `buildConfig errors when service name missing`() {
        val dsl = MobileOtelDsl().apply {
            service { version = "1.0" }
            export { endpoint = "https://example.com:4317" }
        }
        assertFailsWith<IllegalStateException> { dsl.buildConfig() }
    }

    @Test fun `buildConfig errors when service version missing`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app" }
            export { endpoint = "https://example.com:4317" }
        }
        assertFailsWith<IllegalStateException> { dsl.buildConfig() }
    }

    @Test fun `buildConfig errors when export endpoint missing`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
        }
        assertFailsWith<IllegalStateException> { dsl.buildConfig() }
    }

    @Test fun `buildCustomizers returns customizers from DSL`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export { endpoint = "https://example.com:4317" }
            exportCustomizers {
                log { it }
                span { it }
            }
        }
        val customizers = dsl.buildCustomizers()
        assertEquals(1, customizers.log.size)
        assertEquals(1, customizers.span.size)
        assertTrue(customizers.metric.isEmpty())
    }

    @Test fun `dsl exposes extraResourceAttributes setter`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "test"; version = "0.1.0" }
            export { endpoint = "http://localhost:4317" }
            extraResourceAttributes = mapOf(
                "dash0.test.cell_id" to "abc-123",
                "dash0.test.export_mode" to "cont",
            )
        }
        val config = dsl.buildConfig()
        assertEquals("abc-123", config.extraResourceAttributes?.get("dash0.test.cell_id"))
        assertEquals("cont", config.extraResourceAttributes?.get("dash0.test.export_mode"))
    }

    @Test fun `extraResourceAttributes defaults to null when unset`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "test"; version = "0.1.0" }
            export { endpoint = "http://localhost:4317" }
        }
        val config = dsl.buildConfig()
        assertEquals(null, config.extraResourceAttributes)
    }

    @Test fun `uiTelemetryMode is configurable`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export { endpoint = "https://example.com:4317" }
            uiTelemetryMode = UiTelemetryMode.SPANS
        }
        val config = dsl.buildConfig()
        assertEquals(UiTelemetryMode.SPANS, config.uiTelemetryMode)
    }
}
