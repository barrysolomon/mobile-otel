// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ComposeClickInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `instrumentationName is correct`() {
        assertEquals(
            "io.opentelemetry.android.mobile.compose.click",
            ComposeClickInstrumentation().instrumentationName,
        )
    }

    @Test fun `install succeeds when Compose is on classpath`() {
        val app = RuntimeEnvironment.getApplication()
        ComposeClickInstrumentation().install(app, makeContext(app))
    }

    @Test fun `install with enabled=false is no-op`() {
        val app = RuntimeEnvironment.getApplication()
        ComposeClickInstrumentation(ComposeClickConfig(enabled = false)).install(app, makeContext(app))
    }

    @Test fun `uninstall after install succeeds`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeClickInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
    }

    @Test fun `uninstall before install does not throw`() {
        ComposeClickInstrumentation().uninstall()
    }

    @Test fun `install with enabled=false emits no telemetry`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeClickInstrumentation(ComposeClickConfig(enabled = false))
        inst.install(app, makeContext(app))

        // With enabled=false, no detector is created and no lifecycle callbacks are registered.
        // Verify no log records were emitted during install.
        assertTrue(otelRule.logRecords.isEmpty(),
            "Expected no log records when compose click is disabled")

        inst.uninstall()
    }

    @Test fun `install then uninstall then reinstall does not throw`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeClickInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
        inst.install(app, makeContext(app))  // re-install after uninstall
        inst.uninstall()
    }
}
