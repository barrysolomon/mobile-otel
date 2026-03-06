// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
import kotlin.test.assertTrue

class LifecycleInstrumentationTest {

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
            "io.opentelemetry.android.mobile.lifecycle",
            LifecycleInstrumentation().instrumentationName
        )
    }

    @Test fun `install registers ActivityLifecycleCallbacks`() {
        val app = mockk<Application>(relaxed = true)
        LifecycleInstrumentation().install(app, makeContext(app))
        verify { app.registerActivityLifecycleCallbacks(any()) }
    }

    @Test fun `app start log emitted on first activity created`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        callbackSlot.captured.onActivityCreated(mockk(relaxed = true), null)

        val logs = otelRule.logRecords
        assertTrue(logs.any { it.body.asString() == MobileSemconv.APP_START },
            "Expected app.start log, got: ${logs.map { it.body.asString() }}")
    }

    @Test fun `app start log emitted only once across multiple activities`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))

        val cb = callbackSlot.captured
        cb.onActivityCreated(mockk(relaxed = true), null)
        cb.onActivityCreated(mockk(relaxed = true), null)

        val startLogs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.APP_START }
        assertEquals(1, startLogs.size, "app.start should only be emitted once")
    }

    @Test fun `app foreground log emitted when first activity starts`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        callbackSlot.captured.onActivityStarted(mockk(relaxed = true))

        val logs = otelRule.logRecords
        assertTrue(logs.any { it.body.asString() == MobileSemconv.APP_FOREGROUND },
            "Expected app.foreground log")
    }

    @Test fun `app background log emitted when last activity stops`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))

        val cb = callbackSlot.captured
        cb.onActivityStarted(mockk(relaxed = true))    // activeActivities = 1
        cb.onActivityStopped(mockk(relaxed = true))   // activeActivities = 0 → background

        val logs = otelRule.logRecords
        assertTrue(logs.any { it.body.asString() == MobileSemconv.APP_BACKGROUND },
            "Expected app.background log")
    }

    @Test fun `uninstall unregisters callbacks`() {
        val app = mockk<Application>(relaxed = true)
        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
        verify { app.unregisterActivityLifecycleCallbacks(any()) }
    }
}
