// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.Window
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScreenViewInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application = mockk(relaxed = true)): InstrumentationContext =
        InstrumentationContext(
            otelRule.openTelemetry,
            DefaultMobileSessionProvider(),
            WindowEventHub(),
            app
        )

    @Test fun `instrumentationName is correct`() {
        assertEquals(
            "io.opentelemetry.android.mobile.screen",
            ScreenViewInstrumentation().instrumentationName
        )
    }

    @Test fun `install registers ActivityLifecycleCallbacks`() {
        val app = mockk<Application>(relaxed = true)
        ScreenViewInstrumentation().install(app, makeContext(app))
        verify { app.registerActivityLifecycleCallbacks(any()) }
    }

    @Test fun `ui_screen_view log emitted on activity resumed`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val activity = mockk<Activity>(relaxed = true)

        val inst = ScreenViewInstrumentation()
        inst.install(app, makeContext(app))
        callbackSlot.captured.onActivityResumed(activity)

        val logs = otelRule.logRecords
        assertTrue(
            logs.any { it.body.asString() == MobileSemconv.UI_SCREEN_VIEW },
            "Expected ui.screen_view log, got: ${logs.map { it.body.asString() }}"
        )
    }

    @Test fun `session provider screen name updated on activity resumed`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val sessionProvider = DefaultMobileSessionProvider()
        val ctx = InstrumentationContext(otelRule.openTelemetry, sessionProvider, WindowEventHub(), app)
        val activity = mockk<Activity>(relaxed = true)

        val inst = ScreenViewInstrumentation()
        inst.install(app, ctx)
        callbackSlot.captured.onActivityResumed(activity)

        // Screen name is set to activity.javaClass.simpleName (the mockk proxy class name);
        // we verify it was updated (non-null) rather than checking for a specific value, since
        // javaClass.simpleName cannot be stubbed on a MockK proxy (final native method).
        assertNotNull(sessionProvider.getCurrentScreenName(),
            "Expected getCurrentScreenName() to be non-null after onActivityResumed")
    }

    @Test fun `uninstall unregisters callbacks`() {
        val app = mockk<Application>(relaxed = true)
        val inst = ScreenViewInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
        verify { app.unregisterActivityLifecycleCallbacks(any()) }
    }
}
