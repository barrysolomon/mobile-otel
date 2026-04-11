// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.scenarios

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.mobile.OTelMobile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 of the real crash test: verifies recovery after a real crash.
 *
 * The app starts fresh — RecoveryTracker reads the crash marker from
 * SharedPreferences, sets lastRecoveryType="crash", emits app.recovery,
 * and triggers forceFlush(30) to export disk-buffered events.
 *
 * This test is designed to be invoked via `am instrument` from the crash
 * demo shell script AFTER Phase 1 has crashed the app and the script has
 * dismissed the crash dialog.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealCrashPhase2Test : DemoScenarioBase() {

    companion object {
        private const val TAG = "RealCrashPhase2"
    }

    @Test
    fun verifyRecoveryAfterCrash() {
        Log.i(TAG, "=== Phase 2: Verifying recovery after crash ===")

        // The app has already started (DemoScenarioBase.setUp launches SchedulingActivity).
        // RecoveryTracker has already run in DemoApp.onCreate().
        // Give the recovery flush time to complete.
        pace.step("realCrashRecovery", "waiting_for_recovery_flush")
        Thread.sleep(10000)

        // Verify RecoveryTracker detected the crash
        val recoveryType = OTelMobile.getLastRecoveryType()
        Log.i(TAG, "Recovery type: $recoveryType")
        assertNotNull("RecoveryTracker should have detected crash", recoveryType)
        assertEquals(
            "RecoveryTracker should report crash recovery",
            "crash",
            recoveryType
        )

        // Emit a post-recovery buffer snapshot to confirm flush happened
        emitBufferStats("post_recovery")

        pace.step("realCrashRecovery", "recovery_verified")
        Log.i(TAG, "=== Real crash recovery verified successfully ===")
    }
}
