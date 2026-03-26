// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test fun `allows events under limit`() {
        val limiter = RateLimiter(maxPerWindow = 5)
        repeat(5) { assertTrue(limiter.tryAcquire(), "Event $it should be allowed") }
    }

    @Test fun `rejects events at limit`() {
        val limiter = RateLimiter(maxPerWindow = 3)
        repeat(3) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire(), "4th event should be rejected")
    }

    @Test fun `currentCount tracks events`() {
        val limiter = RateLimiter(maxPerWindow = 10)
        assertEquals(0, limiter.currentCount)
        limiter.tryAcquire()
        limiter.tryAcquire()
        assertEquals(2, limiter.currentCount)
    }

    @Test fun `reset clears all tracked events`() {
        val limiter = RateLimiter(maxPerWindow = 5)
        repeat(5) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())

        limiter.reset()
        assertEquals(0, limiter.currentCount)
        assertTrue(limiter.tryAcquire(), "Should allow after reset")
    }

    @Test fun `maxPerWindow of 1 allows exactly one event`() {
        val limiter = RateLimiter(maxPerWindow = 1)
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test fun `rejected events are not counted`() {
        val limiter = RateLimiter(maxPerWindow = 2)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire()) // rejected
        assertFalse(limiter.tryAcquire()) // rejected
        assertEquals(2, limiter.currentCount, "Rejected events should not increment count")
    }

    @Test fun `expired events are pruned`() {
        // Use a very short window so events expire immediately.
        val limiter = RateLimiter(maxPerWindow = 2, windowMs = 1)
        limiter.tryAcquire()
        limiter.tryAcquire()

        // Sleep just past the window.
        Thread.sleep(5)

        // Old events should have expired — new ones should be allowed.
        assertTrue(limiter.tryAcquire(), "Should allow after window expires")
        assertEquals(1, limiter.currentCount, "Only the new event should remain")
    }

    @Test fun `custom window length is respected`() {
        val limiter = RateLimiter(maxPerWindow = 100, windowMs = 1)
        repeat(100) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())

        Thread.sleep(5)
        assertTrue(limiter.tryAcquire(), "All events should have expired")
    }
}
