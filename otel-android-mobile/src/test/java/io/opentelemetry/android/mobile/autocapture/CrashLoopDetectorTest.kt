/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crash-loop self-disable (SDK_SAFETY): the SDK counts consecutive
 * crash-marker launches and refuses to initialize once the count reaches
 * [io.opentelemetry.android.mobile.config.MobileConfig.crashLoopThreshold].
 * A clean launch (no crash marker) resets the counter, so the guard
 * self-clears without any external intervention.
 *
 * Mirrors iOS `CrashLoopDetectorTests` — zero platform drift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrashLoopDetectorTest {

    private lateinit var application: Application
    private val prefs
        get() = application.getSharedPreferences("otel_recovery", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
    }

    private fun setCrashMarker() {
        prefs.edit().putBoolean("crash_marker", true).commit()
    }

    @Test
    fun `clean launch proceeds with zero count`() {
        val verdict = CrashLoopDetector.evaluateOnLaunch(application, threshold = 3)
        assertEquals(CrashLoopDetector.Verdict.PROCEED, verdict)
        assertEquals(0, CrashLoopDetector.consecutiveCrashCount(application))
    }

    @Test
    fun `crash below threshold increments count and proceeds`() {
        setCrashMarker()
        val verdict = CrashLoopDetector.evaluateOnLaunch(application, threshold = 3)
        assertEquals(CrashLoopDetector.Verdict.PROCEED, verdict)
        assertEquals(1, CrashLoopDetector.consecutiveCrashCount(application))
    }

    @Test
    fun `proceeding leaves the crash marker for RecoveryTracker to consume`() {
        // The detector must NOT eat the marker on the proceed path —
        // RecoveryTracker still needs it to emit app.recovery.
        setCrashMarker()
        CrashLoopDetector.evaluateOnLaunch(application, threshold = 3)
        assertTrue(prefs.getBoolean("crash_marker", false))
    }

    @Test
    fun `reaching threshold disables and clears the marker`() {
        setCrashMarker()
        CrashLoopDetector.evaluateOnLaunch(application, threshold = 3) // count 1
        setCrashMarker()
        CrashLoopDetector.evaluateOnLaunch(application, threshold = 3) // count 2
        setCrashMarker()
        val verdict = CrashLoopDetector.evaluateOnLaunch(application, threshold = 3)
        assertEquals(CrashLoopDetector.Verdict.DISABLED, verdict)
        assertEquals(3, CrashLoopDetector.consecutiveCrashCount(application))
        // Marker is cleared on the disable path: with the SDK inert next
        // launch, nothing would ever consume it, and a stale marker would
        // keep the SDK disabled forever. Clearing it makes the next launch
        // count as clean, which resets the counter — self-clearing.
        assertFalse(prefs.getBoolean("crash_marker", false))
    }

    @Test
    fun `clean launch after crashes resets the counter`() {
        setCrashMarker()
        CrashLoopDetector.evaluateOnLaunch(application, threshold = 3)
        setCrashMarker()
        CrashLoopDetector.evaluateOnLaunch(application, threshold = 3)
        assertEquals(2, CrashLoopDetector.consecutiveCrashCount(application))

        // No marker this time: previous session ended cleanly (in production
        // RecoveryTracker consumes the marker after each proceed verdict).
        prefs.edit().remove("crash_marker").commit()
        val verdict = CrashLoopDetector.evaluateOnLaunch(application, threshold = 3)
        assertEquals(CrashLoopDetector.Verdict.PROCEED, verdict)
        assertEquals(0, CrashLoopDetector.consecutiveCrashCount(application))
    }

    @Test
    fun `threshold zero disables the guard entirely`() {
        repeat(5) {
            setCrashMarker()
            val verdict = CrashLoopDetector.evaluateOnLaunch(application, threshold = 0)
            assertEquals(CrashLoopDetector.Verdict.PROCEED, verdict)
        }
        // Guard off → no counting side effects either.
        assertEquals(0, CrashLoopDetector.consecutiveCrashCount(application))
    }

    @Test
    fun `count survives across evaluations via SharedPreferences`() {
        setCrashMarker()
        CrashLoopDetector.evaluateOnLaunch(application, threshold = 10)
        setCrashMarker()
        CrashLoopDetector.evaluateOnLaunch(application, threshold = 10)
        setCrashMarker()
        CrashLoopDetector.evaluateOnLaunch(application, threshold = 10)
        assertEquals(3, CrashLoopDetector.consecutiveCrashCount(application))
    }
}
