// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.scenarios

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.demo.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 of the real crash test: generates a rich pre-crash event sequence
 * then triggers a real RuntimeException crash via the debug toolbar.
 *
 * This test is designed to be invoked via `am instrument` from the crash
 * demo shell script — NOT via Gradle's connectedAndroidTest with orchestrator.
 *
 * The app process will die when the crash fires. The shell script detects
 * process death, dismisses the crash dialog, and launches Phase 2.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealCrashPhase1Test : DemoScenarioBase() {

    companion object {
        private const val TAG = "RealCrashPhase1"
    }

    @Test
    fun generateEventsAndCrash() {
        Log.i(TAG, "=== Phase 1: Generating pre-crash events ===")

        // Navigate through screens to build breadcrumb trail
        pace.step("realCrash", "app_launched")

        pace.step("realCrash", "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step("realCrash", "navigate_to_book")
        navigateTo(R.id.nav_book)

        pace.step("realCrash", "navigate_to_directions")
        navigateTo(R.id.nav_directions)

        // Navigate back to booking flow for the crash context
        pace.step("realCrash", "navigate_to_book_for_crash")
        navigateTo(R.id.nav_book)

        // Wait for crash-safety mirror to persist RAM events to disk (runs every 2s)
        pace.step("realCrash", "waiting_for_disk_mirror")
        Thread.sleep(3000)

        // Capture buffer state before crash
        emitBufferStats("pre_crash")

        // Trigger REAL crash — btnTriggerCrash throws RuntimeException on main thread
        // via Handler.postDelayed(500ms). The app process will die.
        Log.i(TAG, "=== Triggering real crash via debug toolbar ===")
        pace.step("realCrash", "triggering_crash")
        clickDebugButton(R.id.btnTriggerCrash)

        // The 500ms postDelayed means we need to wait for the crash to fire.
        // This sleep will be interrupted by process death — that's expected.
        Thread.sleep(5000)
    }
}
