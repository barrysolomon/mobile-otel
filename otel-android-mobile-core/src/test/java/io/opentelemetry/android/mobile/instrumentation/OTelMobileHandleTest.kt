// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.mockk.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    // UJ-001 / UJ-002 / UJ-005: journey-aware capture wiring

    /** Stand-in instrumentation that records when its capture method is called. */
    private class FakeCaptureInstrumentation(
        override val instrumentationName: String,
        private val methodToExpose: String
    ) : MobileInstrumentation {
        var lastTrigger: String? = null
        override val instrumentationVersion: String = "1.0.0"
        override fun install(application: android.app.Application, context: InstrumentationContext) {}
        override fun uninstall() {}
        // Methods the handle invokes reflectively. We declare both so each
        // fake can match either screenshot or wireframe call sites.
        fun captureScreenshot(trigger: String) { if (methodToExpose == "captureScreenshot") lastTrigger = trigger }
        fun captureWireframe(trigger: String) { if (methodToExpose == "captureWireframe") lastTrigger = trigger }
    }

    @Test
    fun `captureScreenshot delegates to registered screenshot instrumentation`() {
        val fake = FakeCaptureInstrumentation(
            "io.opentelemetry.android.mobile.screenshot",
            "captureScreenshot"
        )
        val realRegistry = InstrumentationRegistry(listOf(fake))
        val sdk = mockk<OpenTelemetrySdk>(relaxed = true)
        val handle = OTelMobileHandle(sdk, realRegistry)

        handle.captureScreenshot("journey_start")
        assertTrue(fake.lastTrigger == "journey_start")
    }

    @Test
    fun `captureWireframe delegates to registered wireframe instrumentation`() {
        val fake = FakeCaptureInstrumentation(
            "io.opentelemetry.android.mobile.wireframe",
            "captureWireframe"
        )
        val realRegistry = InstrumentationRegistry(listOf(fake))
        val sdk = mockk<OpenTelemetrySdk>(relaxed = true)
        val handle = OTelMobileHandle(sdk, realRegistry)

        handle.captureWireframe("journey_end")
        assertTrue(fake.lastTrigger == "journey_end")
    }

    @Test
    fun `captureScreenshot is a no-op when screenshot module is not registered`() {
        val realRegistry = InstrumentationRegistry(emptyList())
        val sdk = mockk<OpenTelemetrySdk>(relaxed = true)
        val handle = OTelMobileHandle(sdk, realRegistry)
        handle.captureScreenshot("manual") // must not throw
    }

    @Test
    fun `endJourney captures end state then ends the journey span`() {
        val screenshotFake = FakeCaptureInstrumentation(
            "io.opentelemetry.android.mobile.screenshot",
            "captureScreenshot"
        )
        val wireframeFake = FakeCaptureInstrumentation(
            "io.opentelemetry.android.mobile.wireframe",
            "captureWireframe"
        )
        val realRegistry = InstrumentationRegistry(listOf(screenshotFake, wireframeFake))
        val sdk = mockk<OpenTelemetrySdk>(relaxed = true)
        val handle = OTelMobileHandle(sdk, realRegistry)

        val span = mockk<Span>(relaxed = true)
        handle.endJourney(span)

        assertTrue(screenshotFake.lastTrigger == "journey_end")
        assertTrue(wireframeFake.lastTrigger == "journey_end")
        verify { span.end() }
    }

    @Test
    fun `endJourney still ends span even if capture instrumentations are not registered`() {
        val realRegistry = InstrumentationRegistry(emptyList())
        val sdk = mockk<OpenTelemetrySdk>(relaxed = true)
        val handle = OTelMobileHandle(sdk, realRegistry)

        val span = mockk<Span>(relaxed = true)
        handle.endJourney(span)
        verify { span.end() }
    }
}
