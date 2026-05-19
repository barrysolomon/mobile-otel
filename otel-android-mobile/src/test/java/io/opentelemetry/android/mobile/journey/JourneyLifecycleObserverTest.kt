// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.journey

import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Adapter-level tests. The adapter has no logic beyond forwarding lifecycle
 * events; [JourneyTrackerTest] covers the actual tracker behavior.
 */
class JourneyLifecycleObserverTest {

    @Test fun `onStart forwards to tracker onForeground`() {
        val tracker = mockk<JourneyTracker>(relaxed = true)
        val observer = JourneyLifecycleObserver(tracker)
        val owner = mockk<LifecycleOwner>(relaxed = true)

        observer.onStart(owner)

        verify(exactly = 1) { tracker.onForeground() }
    }

    @Test fun `onStop forwards to tracker onBackground`() {
        val tracker = mockk<JourneyTracker>(relaxed = true)
        val observer = JourneyLifecycleObserver(tracker)
        val owner = mockk<LifecycleOwner>(relaxed = true)

        observer.onStop(owner)

        verify(exactly = 1) { tracker.onBackground() }
    }
}
