// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.mockk
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.android.session.SessionProvider
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InterfaceConvergenceTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    class TestModule : MobileInstrumentation {
        override val instrumentationName = "test.module"
        var installCalled = false
        var uninstallCalled = false
        var lastApp: Application? = null
        var lastCtx: InstrumentationContext? = null

        override fun install(application: Application, context: InstrumentationContext) {
            installCalled = true
            lastApp = application
            lastCtx = context
        }

        override fun uninstall() {
            uninstallCalled = true
        }
    }

    @Test fun `MobileInstrumentation is AndroidInstrumentation`() {
        val module = TestModule()
        assertIs<AndroidInstrumentation>(module)
    }

    @Test fun `name delegates to instrumentationName`() {
        val module = TestModule()
        assertEquals("test.module", module.name)
        assertEquals(module.instrumentationName, module.name)
    }

    @Test fun `install(InstallationContext) bridges to install(Application, InstrumentationContext)`() {
        val module = TestModule()
        val app = RuntimeEnvironment.getApplication()
        val sessionProvider = mockk<SessionProvider>(relaxed = true)
        val clock = Clock.getDefault()
        val installCtx = InstallationContext(app, otelRule.openTelemetry, sessionProvider, clock)

        module.install(installCtx)

        assertTrue(module.installCalled, "install(Application, InstrumentationContext) should be called")
        assertEquals(app, module.lastApp)
        assertEquals(otelRule.openTelemetry, module.lastCtx?.openTelemetry)
        assertEquals(clock, module.lastCtx?.clock)
    }

    @Test fun `uninstall(InstallationContext) bridges to uninstall()`() {
        val module = TestModule()
        val app = RuntimeEnvironment.getApplication()
        val installCtx = InstallationContext(app, otelRule.openTelemetry, mockk(relaxed = true), Clock.getDefault())

        module.uninstall(installCtx)

        assertTrue(module.uninstallCalled, "uninstall() should be called")
    }

    @Test fun `instrumentationVersion has default value`() {
        val module = TestModule()
        assertEquals("1.0.0", module.instrumentationVersion)
    }
}
