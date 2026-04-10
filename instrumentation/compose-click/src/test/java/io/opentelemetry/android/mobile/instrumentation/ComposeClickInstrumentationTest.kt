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
}
