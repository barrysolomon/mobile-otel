// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstrumentationRegistryTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application = mockk(relaxed = true)): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `install calls install on every instrumentation`() {
        val i1 = mockk<MobileInstrumentation>(relaxed = true)
        val i2 = mockk<MobileInstrumentation>(relaxed = true)
        val app = mockk<Application>(relaxed = true)
        val ctx = makeContext(app)

        InstrumentationRegistry(listOf(i1, i2)).install(app, ctx)

        verify { i1.install(app, ctx) }
        verify { i2.install(app, ctx) }
    }

    @Test fun `uninstall calls uninstall on every instrumentation`() {
        val i1 = mockk<MobileInstrumentation>(relaxed = true)
        val i2 = mockk<MobileInstrumentation>(relaxed = true)
        val app = mockk<Application>(relaxed = true)
        val ctx = makeContext(app)

        val registry = InstrumentationRegistry(listOf(i1, i2))
        registry.install(app, ctx)
        registry.uninstall()

        verify { i1.uninstall() }
        verify { i2.uninstall() }
    }

    @Test fun `install with empty list succeeds without throwing`() {
        val app = mockk<Application>(relaxed = true)
        InstrumentationRegistry(emptyList()).install(app, makeContext(app))
        assertTrue(true)
    }

    @Test fun `uninstall before install does not throw`() {
        InstrumentationRegistry(emptyList()).uninstall()
        assertTrue(true)
    }

    @Test fun `sessionProvider is null before install`() {
        val registry = InstrumentationRegistry(emptyList())
        assertNull(registry.sessionProvider)
    }

    @Test fun `sessionProvider is set after install`() {
        val app = mockk<Application>(relaxed = true)
        val provider = DefaultMobileSessionProvider()
        val ctx = InstrumentationContext(otelRule.openTelemetry, provider, WindowEventHub(), app)
        val registry = InstrumentationRegistry(emptyList())
        registry.install(app, ctx)
        assertSame(provider, registry.sessionProvider)
    }

    // UJ-001 / UJ-002: lookup hooks for journey-aware capture wiring

    @Test fun `findByName returns the registered instrumentation`() {
        val i1 = mockk<MobileInstrumentation>(relaxed = true) {
            every { instrumentationName } returns "io.opentelemetry.android.mobile.screenshot"
        }
        val i2 = mockk<MobileInstrumentation>(relaxed = true) {
            every { instrumentationName } returns "io.opentelemetry.android.mobile.wireframe"
        }
        val registry = InstrumentationRegistry(listOf(i1, i2))
        assertSame(i1, registry.findByName("io.opentelemetry.android.mobile.screenshot"))
        assertSame(i2, registry.findByName("io.opentelemetry.android.mobile.wireframe"))
    }

    @Test fun `findByName returns null for unregistered names`() {
        val registry = InstrumentationRegistry(emptyList())
        assertNull(registry.findByName("io.opentelemetry.android.mobile.does_not_exist"))
    }
}
