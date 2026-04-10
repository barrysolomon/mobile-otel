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
class ScreenOrientationInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `instrumentationName is correct`() {
        val inst = ScreenOrientationInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.screen-orientation", inst.instrumentationName)
    }

    @Test fun `install succeeds without throwing`() {
        val app = RuntimeEnvironment.getApplication()
        inst().install(app, makeContext(app))
    }

    @Test fun `uninstall after install succeeds`() {
        val app = RuntimeEnvironment.getApplication()
        val i = inst()
        i.install(app, makeContext(app))
        i.uninstall()
    }

    @Test fun `uninstall before install does not throw`() {
        inst().uninstall()
    }

    private fun inst() = ScreenOrientationInstrumentation()
}
