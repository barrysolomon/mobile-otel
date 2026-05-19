// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.journey

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Bridges `androidx.lifecycle.ProcessLifecycleOwner` events to
 * [JourneyTracker] so open journeys close cleanly on background and resume
 * via cross-trace links on foreground.
 *
 * Registered by `OTelMobile.start` against `ProcessLifecycleOwner.get()`.
 * Kept separate from [JourneyTracker] so the tracker remains a pure unit-
 * testable class with no framework dependencies — see [JourneyTrackerTest].
 *
 * `onStart` fires when the first activity goes from CREATED→STARTED (app
 * coming to foreground). `onStop` fires when the last activity goes from
 * STARTED→CREATED (app going to background, including swipe-up to home or
 * lock screen). Both run on the main thread.
 */
class JourneyLifecycleObserver(
    private val tracker: JourneyTracker
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        tracker.onForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        tracker.onBackground()
    }
}
