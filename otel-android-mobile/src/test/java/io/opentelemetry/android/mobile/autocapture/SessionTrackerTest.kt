/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SessionTracker] - validates session lifecycle management.
 *
 * Sessions renew when the app returns to foreground after being backgrounded
 * for longer than [AutoCaptureOptions.sessionRenewalMs]. Each screen view
 * also rotates the view ID, which scopes telemetry to a specific screen visit.
 */
class SessionTrackerTest {

    private val defaultOptions = AutoCaptureOptions()
    private val shortRenewalOptions = AutoCaptureOptions(sessionRenewalMs = 1_000L) // 1 second

    // ========== Initial State ==========

    @Test
    fun `new tracker has non-null session ID`() {
        val tracker = SessionTracker(defaultOptions)
        assertNotNull(tracker.getSessionId())
    }

    @Test
    fun `new tracker has non-null view ID`() {
        val tracker = SessionTracker(defaultOptions)
        assertNotNull(tracker.getViewId())
    }

    @Test
    fun `new tracker has null screen name`() {
        val tracker = SessionTracker(defaultOptions)
        assertNull(tracker.getCurrentScreenName())
    }

    @Test
    fun `two new trackers have different session IDs`() {
        val t1 = SessionTracker(defaultOptions)
        val t2 = SessionTracker(defaultOptions)
        assertNotEquals(t1.getSessionId(), t2.getSessionId())
    }

    @Test
    fun `two new trackers have different view IDs`() {
        val t1 = SessionTracker(defaultOptions)
        val t2 = SessionTracker(defaultOptions)
        assertNotEquals(t1.getViewId(), t2.getViewId())
    }

    // ========== Session Renewal ==========

    @Test
    fun `foreground without prior background does not renew session`() {
        val tracker = SessionTracker(defaultOptions)
        val originalSessionId = tracker.getSessionId()

        val renewed = tracker.onAppForeground(System.currentTimeMillis())

        assertFalse(renewed)
        assertEquals(originalSessionId, tracker.getSessionId())
    }

    @Test
    fun `foreground immediately after background does not renew session`() {
        val tracker = SessionTracker(shortRenewalOptions)
        val originalSessionId = tracker.getSessionId()
        val now = System.currentTimeMillis()

        tracker.onAppBackground(now)
        // Foreground after only 100ms (less than 1s renewal threshold)
        val renewed = tracker.onAppForeground(now + 100)

        assertFalse(renewed)
        assertEquals(originalSessionId, tracker.getSessionId())
    }

    @Test
    fun `foreground after long background renews session`() {
        val tracker = SessionTracker(shortRenewalOptions)
        val now = System.currentTimeMillis()

        tracker.onAppBackground(now)
        // Foreground after 2s (exceeds 1s renewal threshold)
        val renewed = tracker.onAppForeground(now + 2_000)

        assertTrue(renewed, "Session should have renewed after long background")
    }

    @Test
    fun `session renewal generates new session ID`() {
        val tracker = SessionTracker(shortRenewalOptions)
        val originalSessionId = tracker.getSessionId()
        val now = System.currentTimeMillis()

        tracker.onAppBackground(now)
        tracker.onAppForeground(now + 2_000)

        assertNotEquals(originalSessionId, tracker.getSessionId(), "Session ID should change on renewal")
    }

    @Test
    fun `session renewal generates new view ID`() {
        val tracker = SessionTracker(shortRenewalOptions)
        val originalViewId = tracker.getViewId()
        val now = System.currentTimeMillis()

        tracker.onAppBackground(now)
        tracker.onAppForeground(now + 2_000)

        assertNotEquals(originalViewId, tracker.getViewId(), "View ID should change on session renewal")
    }

    @Test
    fun `session ID is stable across multiple short foreground-background cycles`() {
        val tracker = SessionTracker(shortRenewalOptions)
        val originalSessionId = tracker.getSessionId()
        var now = System.currentTimeMillis()

        // Rapid background/foreground cycles (each < 1s)
        repeat(5) {
            tracker.onAppBackground(now)
            now += 500
            tracker.onAppForeground(now)
            now += 500
        }

        assertEquals(originalSessionId, tracker.getSessionId(), "Short cycles should not renew session")
    }

    // ========== Screen View Tracking ==========

    @Test
    fun `onScreenView updates screen name`() {
        val tracker = SessionTracker(defaultOptions)

        tracker.onScreenView("ProfileScreen")

        assertEquals("ProfileScreen", tracker.getCurrentScreenName())
    }

    @Test
    fun `onScreenView rotates view ID`() {
        val tracker = SessionTracker(defaultOptions)
        val originalViewId = tracker.getViewId()

        tracker.onScreenView("ProfileScreen")

        assertNotEquals(originalViewId, tracker.getViewId(), "Screen navigation should rotate view ID")
    }

    @Test
    fun `successive screen views each rotate view ID`() {
        val tracker = SessionTracker(defaultOptions)

        tracker.onScreenView("ScreenA")
        val viewIdAfterA = tracker.getViewId()

        tracker.onScreenView("ScreenB")
        val viewIdAfterB = tracker.getViewId()

        assertNotEquals(viewIdAfterA, viewIdAfterB)
        assertEquals("ScreenB", tracker.getCurrentScreenName())
    }

    @Test
    fun `session ID does not change on screen view`() {
        val tracker = SessionTracker(defaultOptions)
        val originalSessionId = tracker.getSessionId()

        tracker.onScreenView("SomeScreen")

        assertEquals(originalSessionId, tracker.getSessionId(), "Session ID should not change on screen navigation")
    }

    // ========== AutoCaptureOptions defaults ==========

    @Test
    fun `default session renewal is 30 minutes`() {
        val options = AutoCaptureOptions()
        // 30 minutes = 1_800_000 ms
        assertEquals(1_800_000L, options.sessionRenewalMs)
    }

    // ========== Helper ==========

    private fun assertFalse(value: Boolean) {
        kotlin.test.assertFalse(value)
    }

    private fun assertTrue(value: Boolean, message: String = "") {
        kotlin.test.assertTrue(value, message)
    }
}
