/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral tests for [AutoCaptureOptions].
 *
 * Validates default values, validation constraints, and documents which fields
 * are actively wired into capture components versus reserved for future use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoCaptureOptionsBehaviorTest {

    // ========== 1. Default Values ==========

    @Test
    fun `default options have all boolean capture flags set to true`() {
        val opts = AutoCaptureOptions()

        assertTrue(opts.captureTaps, "captureTaps")
        assertTrue(opts.captureLongPress, "captureLongPress")
        assertTrue(opts.captureSwipe, "captureSwipe")
        assertTrue(opts.captureScroll, "captureScroll")
        assertTrue(opts.captureTextInput, "captureTextInput")
        assertTrue(opts.captureBackPress, "captureBackPress")
        assertTrue(opts.captureLifecycle, "captureLifecycle")
        assertTrue(opts.captureScreens, "captureScreens")
        assertTrue(opts.captureFragments, "captureFragments")
        assertTrue(opts.freezeDetectorEnabled, "freezeDetectorEnabled")
    }

    @Test
    fun `default swipeMinDistancePx is 50f`() {
        val opts = AutoCaptureOptions()
        assertEquals(50f, opts.swipeMinDistancePx)
    }

    @Test
    fun `default scrollThrottleMs is 500`() {
        val opts = AutoCaptureOptions()
        assertEquals(500L, opts.scrollThrottleMs)
    }

    @Test
    fun `default tapCoalesceWindowMs is 800`() {
        val opts = AutoCaptureOptions()
        assertEquals(800L, opts.tapCoalesceWindowMs)
    }

    @Test
    fun `default freezeThresholdMs is 2000`() {
        val opts = AutoCaptureOptions()
        assertEquals(2000L, opts.freezeThresholdMs)
    }

    @Test
    fun `default freezeCooldownMs is 30000`() {
        val opts = AutoCaptureOptions()
        assertEquals(30_000L, opts.freezeCooldownMs)
    }

    @Test
    fun `default anrThresholdMs is 5000`() {
        val opts = AutoCaptureOptions()
        assertEquals(5000L, opts.anrThresholdMs)
    }

    @Test
    fun `default bucketGridSize is 3`() {
        val opts = AutoCaptureOptions()
        assertEquals(3, opts.bucketGridSize)
    }

    @Test
    fun `default maxHitTestDepth is 12`() {
        val opts = AutoCaptureOptions()
        assertEquals(12, opts.maxHitTestDepth)
    }

    @Test
    fun `default sessionRenewalMs is 30 minutes`() {
        val opts = AutoCaptureOptions()
        assertEquals(1_800_000L, opts.sessionRenewalMs)
    }

    @Test
    fun `default privacyMode is STRICT`() {
        val opts = AutoCaptureOptions()
        assertEquals(PrivacyMode.STRICT, opts.privacyMode)
    }

    @Test
    fun `default hashSalt is null`() {
        val opts = AutoCaptureOptions()
        assertNull(opts.hashSalt)
    }

    @Test
    fun `all set and list fields default to empty`() {
        val opts = AutoCaptureOptions()
        assertTrue(opts.allowlistedResourceIds.isEmpty(), "allowlistedResourceIds")
        assertTrue(opts.denylistedResourceIds.isEmpty(), "denylistedResourceIds")
        assertTrue(opts.allowlistedViewClasses.isEmpty(), "allowlistedViewClasses")
        assertTrue(opts.denylistedViewClasses.isEmpty(), "denylistedViewClasses")
    }

    // ========== 2. Validation ==========

    @Test
    fun `freezeThresholdMs below 250 throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(freezeThresholdMs = 249)
        }
        assertTrue(ex.message!!.contains("freezeThresholdMs"), "Error message should mention freezeThresholdMs")
    }

    @Test
    fun `freezeThresholdMs at 250 is accepted`() {
        val opts = AutoCaptureOptions(freezeThresholdMs = 250)
        assertEquals(250L, opts.freezeThresholdMs)
    }

    @Test
    fun `bucketGridSize below 2 throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(bucketGridSize = 1)
        }
        assertTrue(ex.message!!.contains("bucketGridSize"), "Error message should mention bucketGridSize")
    }

    @Test
    fun `bucketGridSize at 2 is accepted`() {
        val opts = AutoCaptureOptions(bucketGridSize = 2)
        assertEquals(2, opts.bucketGridSize)
    }

    @Test
    fun `maxHitTestDepth below 1 throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(maxHitTestDepth = 0)
        }
        assertTrue(ex.message!!.contains("maxHitTestDepth"), "Error message should mention maxHitTestDepth")
    }

    @Test
    fun `maxHitTestDepth at 1 is accepted`() {
        val opts = AutoCaptureOptions(maxHitTestDepth = 1)
        assertEquals(1, opts.maxHitTestDepth)
    }

    @Test
    fun `negative freezeThresholdMs throws`() {
        assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(freezeThresholdMs = -1)
        }
    }

    @Test
    fun `zero bucketGridSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(bucketGridSize = 0)
        }
    }

    @Test
    fun `negative maxHitTestDepth throws`() {
        assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(maxHitTestDepth = -5)
        }
    }

    // ========== 3. captureScroll gates ScrollCapture ==========

    @Test
    fun `captureScroll defaults to true`() {
        val opts = AutoCaptureOptions()
        assertTrue(opts.captureScroll)
    }

    @Test
    fun `captureScroll can be set to false`() {
        val opts = AutoCaptureOptions(captureScroll = false)
        assertEquals(false, opts.captureScroll)
    }

    // ========== 4. freezeDetectorEnabled gates FreezeDetector ==========

    @Test
    fun `freezeDetectorEnabled defaults to true`() {
        val opts = AutoCaptureOptions()
        assertTrue(opts.freezeDetectorEnabled)
    }

    @Test
    fun `freezeDetectorEnabled can be set to false`() {
        val opts = AutoCaptureOptions(freezeDetectorEnabled = false)
        assertEquals(false, opts.freezeDetectorEnabled)
    }

    // ========== 5. PrivacyMode.STRICT is default ==========

    @Test
    fun `privacyMode STRICT is the default`() {
        assertEquals(PrivacyMode.STRICT, AutoCaptureOptions().privacyMode)
    }

    @Test
    fun `privacyMode can be overridden`() {
        val opts = AutoCaptureOptions(privacyMode = PrivacyMode.RELAXED)
        assertEquals(PrivacyMode.RELAXED, opts.privacyMode)
    }

    // ========== 6. SessionTracker uses sessionRenewalMs ==========

    @Test
    fun `SessionTracker does not renew within default 30 min window`() {
        val opts = AutoCaptureOptions() // sessionRenewalMs = 1_800_000
        val tracker = SessionTracker(opts)
        val originalSessionId = tracker.getSessionId()
        val now = System.currentTimeMillis()

        tracker.onAppBackground(now)
        // Foreground after 29 minutes -- should NOT renew
        val renewed = tracker.onAppForeground(now + 29 * 60 * 1000L)

        assertEquals(false, renewed)
        assertEquals(originalSessionId, tracker.getSessionId())
    }

    @Test
    fun `SessionTracker renews after sessionRenewalMs elapses`() {
        val opts = AutoCaptureOptions(sessionRenewalMs = 5_000L)
        val tracker = SessionTracker(opts)
        val originalSessionId = tracker.getSessionId()
        val now = System.currentTimeMillis()

        tracker.onAppBackground(now)
        val renewed = tracker.onAppForeground(now + 6_000L)

        assertTrue(renewed, "Session should have been renewed")
        assertTrue(tracker.getSessionId() != originalSessionId, "Session ID should have changed")
    }

    // ========== 7. Custom configuration ==========

    @Test
    fun `custom swipeMinDistancePx is retained`() {
        val opts = AutoCaptureOptions(swipeMinDistancePx = 100f)
        assertEquals(100f, opts.swipeMinDistancePx)
    }

    @Test
    fun `custom scrollThrottleMs is retained`() {
        val opts = AutoCaptureOptions(scrollThrottleMs = 1000L)
        assertEquals(1000L, opts.scrollThrottleMs)
    }

    @Test
    fun `custom tapCoalesceWindowMs is retained`() {
        val opts = AutoCaptureOptions(tapCoalesceWindowMs = 400L)
        assertEquals(400L, opts.tapCoalesceWindowMs)
    }

    @Test
    fun `custom hashSalt is retained`() {
        val opts = AutoCaptureOptions(hashSalt = "my-secret-salt")
        assertEquals("my-secret-salt", opts.hashSalt)
    }

    @Test
    fun `custom allowlist and denylist sets are retained`() {
        val opts = AutoCaptureOptions(
            allowlistedResourceIds = setOf("com.example:id/btn_ok"),
            denylistedResourceIds = setOf("com.example:id/password_field"),
            allowlistedViewClasses = setOf("android.widget.Button"),
            denylistedViewClasses = setOf("android.widget.EditText")
        )
        assertEquals(setOf("com.example:id/btn_ok"), opts.allowlistedResourceIds)
        assertEquals(setOf("com.example:id/password_field"), opts.denylistedResourceIds)
        assertEquals(setOf("android.widget.Button"), opts.allowlistedViewClasses)
        assertEquals(setOf("android.widget.EditText"), opts.denylistedViewClasses)
    }

    // ========== 8. Data class equality ==========

    @Test
    fun `two default instances are equal`() {
        assertEquals(AutoCaptureOptions(), AutoCaptureOptions())
    }

    @Test
    fun `copy with changed field is not equal`() {
        val a = AutoCaptureOptions()
        val b = a.copy(captureTaps = false)
        assertTrue(a != b)
    }

    // ========== 9. Reserved fields (not yet wired to capture components) ==========

    /**
     * The following boolean fields exist on [AutoCaptureOptions] but are NOT currently
     * checked by any capture component at runtime. They are reserved for future instrumentation.
     *
     * - captureLifecycle: No LifecycleCapture component reads this yet.
     * - captureScreens: Not checked by ScreenViewInstrumentation (which is unconditional).
     * - captureBackPress: Not checked by BackPressInstrumentation (which is unconditional).
     * - captureFragments: No fragment-specific capture component reads this yet.
     *
     * These tests document their existence and default values so that when wiring is added
     * the test suite will already be covering them.
     */
    @Test
    fun `reserved field captureLifecycle defaults to true`() {
        assertTrue(AutoCaptureOptions().captureLifecycle)
    }

    @Test
    fun `reserved field captureScreens defaults to true`() {
        assertTrue(AutoCaptureOptions().captureScreens)
    }

    @Test
    fun `reserved field captureBackPress defaults to true`() {
        assertTrue(AutoCaptureOptions().captureBackPress)
    }

    @Test
    fun `reserved field captureFragments defaults to true`() {
        assertTrue(AutoCaptureOptions().captureFragments)
    }
}
