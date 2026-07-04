// Copyright 2025 Barry Solomon
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

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
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
        assertTrue(logs.any { it.bodyValue?.asString() == MobileSemconv.APP_START },
            "Expected app.start log, got: ${logs.map { it.bodyValue?.asString() }}")
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

        val startLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.APP_START }
        assertEquals(1, startLogs.size, "app.start should only be emitted once")
    }

    @Test fun `process foreground emits app foreground`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        // Start in CREATED so addObserver doesn't replay onStart.
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        // Drain any logs emitted by install (none expected — process is CREATED, not STARTED).
        otelRule.logRecords.clear()

        // Drive the foreground transition.
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val fgLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.APP_FOREGROUND }
        assertEquals(1, fgLogs.size, "Expected exactly 1 app.foreground")
    }

    @Test fun `process background emits app background`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        otelRule.logRecords.clear()

        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val bgLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.APP_BACKGROUND }
        assertEquals(1, bgLogs.size, "Expected exactly 1 app.background")
    }

    @Test fun `uninstall unregisters callbacks`() {
        val app = mockk<Application>(relaxed = true)
        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
        verify { app.unregisterActivityLifecycleCallbacks(any()) }
    }

    @Test fun `install when process already started emits app start late and foreground`() {
        // Bring ProcessLifecycleOwner to STARTED before install — simulates the
        // RN useEffect / deferred-init scenario where SDK initialization runs
        // after the host Activity is already foregrounded.
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))

        // Drain any pending main-thread work so observer at-attach replays land.
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val bodies = otelRule.logRecords.map { it.bodyValue?.asString() }
        assertEquals(
            listOf(MobileSemconv.APP_START, MobileSemconv.APP_FOREGROUND),
            bodies.filter { it == MobileSemconv.APP_START || it == MobileSemconv.APP_FOREGROUND },
            "Expected app.start (late) followed by app.foreground (at-attach replay), got: $bodies"
        )

        val startLog = otelRule.logRecords.first { it.bodyValue?.asString() == MobileSemconv.APP_START }
        val typeAttr = startLog.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("app.start.type"))
        assertEquals("instrumentation_late", typeAttr)

        val durationAttr = startLog.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("app.start.duration_ms"))
        assertTrue(durationAttr != null && durationAttr >= 0L,
            "Expected non-negative app.start.duration_ms, got $durationAttr")
    }

    @Test fun `uninstall removes ProcessLifecycleOwner observer`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        inst.uninstall()
        otelRule.logRecords.clear()

        // Driving lifecycle after uninstall should produce no logs.
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val fgLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.APP_FOREGROUND }
        assertEquals(0, fgLogs.size, "uninstall() should remove the observer; got $fgLogs")
    }
}
