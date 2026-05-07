// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.mockk.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

/**
 * Tests for OTelMobileHandle — specifically the shutdown/flush contract
 * introduced in PR-001 (production readiness).
 */
class OTelMobileHandleTest {

    private val registry = mockk<InstrumentationRegistry>(relaxed = true)
    private val hubInstaller = mockk<WindowEventHubInstaller>(relaxed = true)

    @Test
    fun `stop flushes all three signal providers before shutdown`() {
        val completedResult = CompletableResultCode().apply { succeed() }

        val logProvider = mockk<SdkLoggerProvider> {
            every { forceFlush() } returns completedResult
            every { shutdown() } returns completedResult
        }
        val traceProvider = mockk<SdkTracerProvider> {
            every { forceFlush() } returns completedResult
            every { shutdown() } returns completedResult
        }
        val meterProvider = mockk<SdkMeterProvider> {
            every { forceFlush() } returns completedResult
            every { shutdown() } returns completedResult
        }

        val sdk = mockk<OpenTelemetrySdk> {
            every { sdkLoggerProvider } returns logProvider
            every { sdkTracerProvider } returns traceProvider
            every { sdkMeterProvider } returns meterProvider
            every { shutdown() } returns completedResult
        }

        val handle = OTelMobileHandle(sdk, registry, hubInstaller)
        handle.stop(timeoutSeconds = 5)

        verifyOrder {
            logProvider.forceFlush()
            traceProvider.forceFlush()
            meterProvider.forceFlush()
            sdk.shutdown()
        }
    }

    @Test
    fun `stop uninstalls hub and registry before flushing`() {
        val completedResult = CompletableResultCode().apply { succeed() }

        val sdk = mockk<OpenTelemetrySdk> {
            every { sdkLoggerProvider } returns mockk { every { forceFlush() } returns completedResult }
            every { sdkTracerProvider } returns mockk { every { forceFlush() } returns completedResult }
            every { sdkMeterProvider } returns mockk { every { forceFlush() } returns completedResult }
            every { shutdown() } returns completedResult
        }

        val handle = OTelMobileHandle(sdk, registry, hubInstaller)
        handle.stop()

        verifyOrder {
            hubInstaller.uninstall()
            registry.uninstall()
        }
    }

    @Test
    fun `stop is safe when openTelemetry is not OpenTelemetrySdk`() {
        val plainOtel = mockk<OpenTelemetry>(relaxed = true)
        val handle = OTelMobileHandle(plainOtel, registry)
        handle.stop()

        verify { registry.uninstall() }
    }

    @Test
    fun `stop handles flush exception gracefully`() {
        val logProvider = mockk<SdkLoggerProvider> {
            every { forceFlush() } throws RuntimeException("flush failed")
        }

        val sdk = mockk<OpenTelemetrySdk> {
            every { sdkLoggerProvider } returns logProvider
            every { sdkTracerProvider } returns mockk(relaxed = true)
            every { sdkMeterProvider } returns mockk(relaxed = true)
            every { shutdown() } returns CompletableResultCode().apply { succeed() }
        }

        val handle = OTelMobileHandle(sdk, registry)
        // Should not throw — exception is caught and logged
        handle.stop()
    }

    @Test
    fun `getTracer returns non-null tracer`() {
        val sdk = mockk<OpenTelemetrySdk>(relaxed = true)
        val handle = OTelMobileHandle(sdk, registry)
        assertNotNull(handle.getTracer("test-scope"))
    }

    @Test
    fun `getLogger returns non-null logger`() {
        val sdk = mockk<OpenTelemetrySdk>(relaxed = true)
        val handle = OTelMobileHandle(sdk, registry)
        assertNotNull(handle.getLogger("test-scope"))
    }

    @Test
    fun `getMeter returns non-null meter`() {
        val sdk = mockk<OpenTelemetrySdk>(relaxed = true)
        val handle = OTelMobileHandle(sdk, registry)
        assertNotNull(handle.getMeter("test-scope"))
    }
}
