// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.content.res.Configuration
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun `orientation change emits device_orientation log record`() {
        val app = RuntimeEnvironment.getApplication()
        val i = inst()
        i.install(app, makeContext(app))

        // Simulate orientation change by calling onConfigurationChanged directly.
        // The instrumentation registers a ComponentCallbacks2 on the application.
        // We trigger it via the application's registered callbacks.
        val landscapeConfig = Configuration(app.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        // Dispatch configuration change to the application, which notifies all registered callbacks
        app.onConfigurationChanged(landscapeConfig)

        assertTrue(
            otelRule.logRecords.any { it.bodyValue?.asString() == "device.orientation" },
            "Expected device.orientation log record after orientation change"
        )
        val record = otelRule.logRecords.first { it.bodyValue?.asString() == "device.orientation" }
        assertEquals("landscape", record.attributes.get(
            io.opentelemetry.api.common.AttributeKey.stringKey("device.orientation")))
    }

    @Test fun `same orientation does not emit event`() {
        val app = RuntimeEnvironment.getApplication()
        val i = inst()
        i.install(app, makeContext(app))

        // Clear any records from install
        val countBefore = otelRule.logRecords.size

        // Dispatch the same orientation that is already active (portrait by default in Robolectric)
        val sameConfig = Configuration(app.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        app.onConfigurationChanged(sameConfig)

        assertEquals(
            countBefore,
            otelRule.logRecords.size,
            "Expected no new log records when orientation does not change"
        )
    }

    private fun inst() = ScreenOrientationInstrumentation()
}
