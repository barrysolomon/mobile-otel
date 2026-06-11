// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenViewInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(
        app: Application = mockk(relaxed = true),
        sessionProvider: MobileSessionProvider = DefaultMobileSessionProvider(),
        uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
    ): InstrumentationContext = InstrumentationContext(
        otelRule.openTelemetry,
        sessionProvider,
        WindowEventHub(),
        app,
        uiTelemetryMode
    )

    // ── Basic properties ──────────────────────────────────────────────────────

    @Test fun `instrumentationName is correct`() {
        assertEquals(
            "io.opentelemetry.android.mobile.screen",
            ScreenViewInstrumentation().instrumentationName
        )
    }

    // ── install / uninstall ───────────────────────────────────────────────────

    @Test fun `install registers ActivityLifecycleCallbacks`() {
        val app = mockk<Application>(relaxed = true)
        ScreenViewInstrumentation().install(app, makeContext(app))
        verify { app.registerActivityLifecycleCallbacks(any()) }
    }

    @Test fun `uninstall unregisters callbacks`() {
        val app = mockk<Application>(relaxed = true)
        val inst = ScreenViewInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
        verify { app.unregisterActivityLifecycleCallbacks(any()) }
    }

    @Test fun `uninstall without prior install does not throw`() {
        // Should be a safe no-op
        ScreenViewInstrumentation().uninstall()
    }

    // ── startPageSpan / endPageSpan ───────────────────────────────────────────

    @Test fun `startPageSpan creates span with name page dot ScreenName`() {
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext())
        inst.startPageSpan("MyScreen")
        inst.endPageSpan()

        val spans = otelRule.spans
        assertTrue(
            spans.any { it.name == "page.MyScreen" },
            "Expected a span named 'page.MyScreen', got: ${spans.map { it.name }}"
        )
    }

    @Test fun `startPageSpan sets SpanKind INTERNAL`() {
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext())
        inst.startPageSpan("MyScreen")
        inst.endPageSpan()

        val span = otelRule.spans.first { it.name == "page.MyScreen" }
        assertEquals(SpanKind.INTERNAL, span.kind)
    }

    @Test fun `startPageSpan sets mobile session id attribute`() {
        val sessionProvider = DefaultMobileSessionProvider()
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext(sessionProvider = sessionProvider))

        val expectedSessionId = sessionProvider.getSessionId()
        inst.startPageSpan("MyScreen")
        inst.endPageSpan()

        val span = otelRule.spans.first { it.name == "page.MyScreen" }
        assertEquals(
            expectedSessionId,
            span.attributes.get(MobileSemconv.SESSION_ID),
            "Expected mobile.session.id='$expectedSessionId'"
        )
    }

    @Test fun `startPageSpan sets mobile view id attribute`() {
        val sessionProvider = DefaultMobileSessionProvider()
        sessionProvider.onScreenView("PreviousScreen") // advance view counter once
        val expectedViewId = sessionProvider.getViewId()

        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext(sessionProvider = sessionProvider))
        inst.startPageSpan("MyScreen")
        inst.endPageSpan()

        val span = otelRule.spans.first { it.name == "page.MyScreen" }
        assertNotNull(
            span.attributes.get(MobileSemconv.VIEW_ID),
            "Expected mobile.view.id attribute on page span"
        )
    }

    @Test fun `startPageSpan sets mobile screen name attribute`() {
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext())
        inst.startPageSpan("CheckoutScreen")
        inst.endPageSpan()

        val span = otelRule.spans.first { it.name == "page.CheckoutScreen" }
        assertEquals("CheckoutScreen", span.attributes.get(MobileSemconv.SCREEN_NAME))
    }

    @Test fun `startPageSpan ends previous page span before starting new one`() {
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext())

        inst.startPageSpan("ScreenA")
        inst.startPageSpan("ScreenB") // should end ScreenA
        inst.endPageSpan()

        val spans = otelRule.spans
        val spanA = spans.find { it.name == "page.ScreenA" }
        val spanB = spans.find { it.name == "page.ScreenB" }

        assertNotNull(spanA, "page.ScreenA should have been recorded")
        assertNotNull(spanB, "page.ScreenB should have been recorded")
        // ScreenA must have ended before ScreenB started — its end time <= ScreenB start time
        assertTrue(
            spanA.endEpochNanos <= spanB.startEpochNanos,
            "ScreenA span must end before ScreenB span starts"
        )
    }

    @Test fun `endPageSpan without active span is a safe no-op`() {
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext())
        // No startPageSpan called — endPageSpan must not throw
        inst.endPageSpan()
    }

    @Test fun `endPageSpan completes the active span`() {
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext())
        inst.startPageSpan("HomeScreen")
        inst.endPageSpan()

        val spans = otelRule.spans
        val span = spans.find { it.name == "page.HomeScreen" }
        assertNotNull(span, "Expected page.HomeScreen to be recorded after endPageSpan")
        // A completed span will have a non-zero end time
        assertTrue(span.endEpochNanos > 0, "Span should have ended with a non-zero end time")
    }

    // ── Sequential page spans ─────────────────────────────────────────────────

    @Test fun `sequential page spans each have unique view IDs`() {
        val sessionProvider = DefaultMobileSessionProvider()
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext(sessionProvider = sessionProvider))

        sessionProvider.onScreenView("ScreenA")
        inst.startPageSpan("ScreenA")
        val viewIdA = sessionProvider.getViewId()
        inst.endPageSpan()

        sessionProvider.onScreenView("ScreenB")
        inst.startPageSpan("ScreenB")
        val viewIdB = sessionProvider.getViewId()
        inst.endPageSpan()

        assertNotEquals(viewIdA, viewIdB, "Sequential screens must get different view IDs")
    }

    @Test fun `sequential page spans each use the same session ID within a session`() {
        val sessionProvider = DefaultMobileSessionProvider()
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext(sessionProvider = sessionProvider))

        sessionProvider.onScreenView("ScreenA")
        inst.startPageSpan("ScreenA")
        inst.endPageSpan()

        sessionProvider.onScreenView("ScreenB")
        inst.startPageSpan("ScreenB")
        inst.endPageSpan()

        val spans = otelRule.spans
        val spanA = spans.first { it.name == "page.ScreenA" }
        val spanB = spans.first { it.name == "page.ScreenB" }

        // Both spans must share the same session ID as long as no renewal occurred
        assertEquals(
            spanA.attributes.get(MobileSemconv.SESSION_ID),
            spanB.attributes.get(MobileSemconv.SESSION_ID),
            "Both page spans should share the same session ID within a session"
        )
    }

    @Test fun `three sequential screens each produce a distinct span`() {
        val inst = ScreenViewInstrumentation()
        inst.install(mockk(relaxed = true), makeContext())

        listOf("Home", "Profile", "Settings").forEach { name ->
            inst.startPageSpan(name)
            inst.endPageSpan()
        }

        val spanNames = otelRule.spans.map { it.name }
        assertTrue("page.Home" in spanNames)
        assertTrue("page.Profile" in spanNames)
        assertTrue("page.Settings" in spanNames)
    }

    // ── Screen view log records ───────────────────────────────────────────────

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
            logs.any { it.bodyValue?.asString() == MobileSemconv.UI_SCREEN_VIEW },
            "Expected ui.screen_view log, got: ${logs.map { it.bodyValue?.asString() }}"
        )
    }

    @Test fun `ui_screen_view log contains session id attribute`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val sessionProvider = DefaultMobileSessionProvider()
        val ctx = makeContext(app = app, sessionProvider = sessionProvider)
        val activity = mockk<Activity>(relaxed = true)

        val inst = ScreenViewInstrumentation()
        inst.install(app, ctx)
        callbackSlot.captured.onActivityResumed(activity)

        val screenViewLog = otelRule.logRecords
            .firstOrNull { it.bodyValue?.asString() == MobileSemconv.UI_SCREEN_VIEW }
        assertNotNull(screenViewLog, "Expected ui.screen_view log record")
        assertNotNull(
            screenViewLog.attributes.get(MobileSemconv.SESSION_ID),
            "Log record must carry mobile.session.id attribute"
        )
    }

    @Test fun `ui_screen_view log is NOT emitted in SPANS mode`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val activity = mockk<Activity>(relaxed = true)

        val inst = ScreenViewInstrumentation()
        inst.install(app, makeContext(app = app, uiTelemetryMode = UiTelemetryMode.SPANS))
        callbackSlot.captured.onActivityResumed(activity)

        val logs = otelRule.logRecords
        assertFalse(
            logs.any { it.bodyValue?.asString() == MobileSemconv.UI_SCREEN_VIEW },
            "In SPANS mode, ui.screen_view log must NOT be emitted"
        )
    }

    @Test fun `ui_screen_view log IS emitted in BOTH mode`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val activity = mockk<Activity>(relaxed = true)

        val inst = ScreenViewInstrumentation()
        inst.install(app, makeContext(app = app, uiTelemetryMode = UiTelemetryMode.BOTH))
        callbackSlot.captured.onActivityResumed(activity)

        val logs = otelRule.logRecords
        assertTrue(
            logs.any { it.bodyValue?.asString() == MobileSemconv.UI_SCREEN_VIEW },
            "In BOTH mode, ui.screen_view log must be emitted"
        )
    }

    // ── Render span (single-shot) ─────────────────────────────────────────────

    /**
     * Drives a live ViewTreeObserver and dispatches onPreDraw on many frames, the
     * way the framework does. Regression guard for the screen.render storm: before
     * the single-shot fix, the OnPreDrawListener unregistered itself via a stale
     * observer reference (silent no-op), so it fired on every frame and emitted one
     * screen.render span per frame — each with an ever-growing duration.
     */
    @Test fun `screen render span is emitted exactly once across many frames`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val activity = mockk<Activity>(relaxed = true)
        val window = mockk<Window>(relaxed = true)
        val decorView = mockk<View>(relaxed = true)
        val vto = mockk<ViewTreeObserver>(relaxed = true)
        every { activity.window } returns window
        every { window.decorView } returns decorView
        every { decorView.viewTreeObserver } returns vto
        every { vto.isAlive } returns true
        val preDrawSlot = slot<ViewTreeObserver.OnPreDrawListener>()
        every { vto.addOnPreDrawListener(capture(preDrawSlot)) } just runs
        every { vto.removeOnPreDrawListener(any()) } just runs

        val inst = ScreenViewInstrumentation()
        inst.install(app, makeContext(app))
        callbackSlot.captured.onActivityResumed(activity)

        // Framework dispatches onPreDraw once per frame; simulate 60 frames.
        repeat(60) { preDrawSlot.captured.onPreDraw() }

        val renderSpans = otelRule.spans.filter { it.name == MobileSemconv.SCREEN_RENDER }
        assertEquals(
            1, renderSpans.size,
            "Exactly one screen.render span per resume; got ${renderSpans.size} (storm regression)"
        )
        // The listener must unregister itself after the first frame.
        verify(atLeast = 1) { vto.removeOnPreDrawListener(any()) }
        // Sane duration: end no earlier than start (never a bogus ballooned span).
        val render = renderSpans.first()
        assertTrue(
            render.endEpochNanos >= render.startEpochNanos,
            "screen.render must not end before it starts"
        )
    }

    // ── Null-safety ───────────────────────────────────────────────────────────

    @Test fun `startPageSpan without install does not throw`() {
        // Tracer and sessionProvider are null — must be a no-op, not a crash
        val inst = ScreenViewInstrumentation()
        inst.startPageSpan("SomeScreen") // sessionProvider is null, must return early
    }

    // ── Session provider screen name tracking ─────────────────────────────────

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

    @Test fun `previous screen name tracked after two sequential activity resumes`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val sessionProvider = DefaultMobileSessionProvider()
        val ctx = InstrumentationContext(otelRule.openTelemetry, sessionProvider, WindowEventHub(), app)

        val activityA = mockk<Activity>(relaxed = true)
        val activityB = mockk<Activity>(relaxed = true)

        val inst = ScreenViewInstrumentation()
        inst.install(app, ctx)

        callbackSlot.captured.onActivityResumed(activityA)
        val firstScreenName = sessionProvider.getCurrentScreenName()

        callbackSlot.captured.onActivityResumed(activityB)

        assertEquals(
            firstScreenName,
            sessionProvider.getPreviousScreenName(),
            "After second resume, previousScreenName must equal the first screen's name"
        )
    }
}
