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
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComposeNavigationInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `instrumentationName is correct`() {
        assertEquals(
            "io.opentelemetry.android.mobile.compose.navigation",
            ComposeNavigationInstrumentation().instrumentationName,
        )
    }

    @Test fun `install succeeds when Compose Navigation is on classpath`() {
        val app = RuntimeEnvironment.getApplication()
        ComposeNavigationInstrumentation().install(app, makeContext(app))
    }

    @Test fun `install with enabled=false is no-op`() {
        val app = RuntimeEnvironment.getApplication()
        ComposeNavigationInstrumentation(ComposeNavigationConfig(enabled = false)).install(app, makeContext(app))
    }

    @Test fun `uninstall after install succeeds`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeNavigationInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
    }

    @Test fun `uninstall before install does not throw`() {
        ComposeNavigationInstrumentation().uninstall()
    }

    @Test fun `install with enabled=false emits no telemetry`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeNavigationInstrumentation(ComposeNavigationConfig(enabled = false))
        inst.install(app, makeContext(app))

        assertTrue(otelRule.logRecords.isEmpty(),
            "Expected no log records when compose navigation is disabled")
        assertTrue(otelRule.spans.isEmpty(),
            "Expected no spans when compose navigation is disabled")

        inst.uninstall()
    }

    @Test fun `onDestinationChanged emits screen view log`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeNavigationInstrumentation()
        inst.install(app, makeContext(app))

        inst.onDestinationChanged("HomeScreen")

        val screenViews = otelRule.logRecords.filter { it.body.asString() == "ui.screen_view" }
        assertEquals(1, screenViews.size, "Expected exactly one screen_view log")
        assertEquals("HomeScreen",
            screenViews[0].attributes.get(MobileSemconv.SCREEN_NAME))

        inst.uninstall()
    }

    @Test fun `onDestinationChanged starts page span`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeNavigationInstrumentation()
        inst.install(app, makeContext(app))

        inst.onDestinationChanged("SettingsScreen")
        // Uninstall ends the active page span, making it visible in otelRule.spans
        inst.uninstall()

        val pageSpans = otelRule.spans.filter { it.name.startsWith("page.") }
        assertTrue(pageSpans.isNotEmpty(), "Expected at least one page span")
        assertEquals("page.SettingsScreen", pageSpans.last().name)
    }

    @Test fun `consecutive destination changes end previous page span`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeNavigationInstrumentation()
        inst.install(app, makeContext(app))

        inst.onDestinationChanged("ScreenA")
        inst.onDestinationChanged("ScreenB")

        val pageSpans = otelRule.spans.filter { it.name.startsWith("page.") }
        assertTrue(pageSpans.any { it.name == "page.ScreenA" && it.hasEnded() },
            "Previous page span should have ended")

        inst.uninstall()
    }

    @Test fun `install then uninstall then reinstall does not throw`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeNavigationInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
        inst.install(app, makeContext(app))
        inst.uninstall()
    }
}
