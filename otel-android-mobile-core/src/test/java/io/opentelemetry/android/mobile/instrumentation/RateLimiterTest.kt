// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    // ========== 1. Allows events up to limit ==========

    @Test
    fun `allows events up to limit`() {
        val limiter = RateLimiter(maxPerWindow = 10)
        repeat(10) { i ->
            assertTrue(limiter.tryAcquire(), "Event $i should be allowed")
        }
    }

    // ========== 2. Rejects at limit ==========

    @Test
    fun `rejects at limit`() {
        val limiter = RateLimiter(maxPerWindow = 10)
        repeat(10) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire(), "11th event should be rejected")
    }

    // ========== 3. Window expiry ==========

    @Test
    fun `window expiry allows new events after window passes`() {
        var now = 1_000L
        val limiter = RateLimiter(maxPerWindow = 3, windowMs = 50, clock = { now })
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire(), "Should be at limit")

        now += 80 // advance past the 50ms window (deterministic; no Thread.sleep)

        assertTrue(limiter.tryAcquire(), "Should allow after window expires")
    }

    // ========== 4. Rolling window ==========

    @Test
    fun `rolling window does not expire all events at once`() {
        var now = 1_000L
        val limiter = RateLimiter(maxPerWindow = 3, windowMs = 100, clock = { now })

        // Acquire first event at t=1000
        assertTrue(limiter.tryAcquire())
        now += 60 // t=1060

        // Acquire second and third events at t=1060
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire(), "At limit")

        // Advance so the first event expires (age 120 > 100ms window) but the
        // second/third (age 60) do not.
        now += 60 // t=1120

        // First event should have expired, freeing one slot
        assertTrue(limiter.tryAcquire(), "First event expired, one slot free")
        assertFalse(limiter.tryAcquire(), "Still at limit from events 2 and 3")
    }

    // ========== 5. Reset clears all ==========

    @Test
    fun `reset clears all and allows immediate tryAcquire`() {
        val limiter = RateLimiter(maxPerWindow = 2)
        repeat(2) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire(), "Should be at limit")

        limiter.reset()

        assertEquals(0, limiter.currentCount)
        assertTrue(limiter.tryAcquire(), "Should allow after reset")
    }

    // ========== 6. currentCount accuracy ==========

    @Test
    fun `currentCount matches number of successful acquires`() {
        val limiter = RateLimiter(maxPerWindow = 5)
        assertEquals(0, limiter.currentCount)

        assertTrue(limiter.tryAcquire())
        assertEquals(1, limiter.currentCount)

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertEquals(3, limiter.currentCount)

        // Fill to limit
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertEquals(5, limiter.currentCount)

        // Rejected events should not increment
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertEquals(5, limiter.currentCount, "Rejected events must not increment count")
    }

    // ========== 7. currentCount after expiry ==========

    @Test
    fun `currentCount returns 0 after window passes`() {
        var now = 1_000L
        val limiter = RateLimiter(maxPerWindow = 5, windowMs = 50, clock = { now })
        repeat(5) { limiter.tryAcquire() }
        assertEquals(5, limiter.currentCount)

        now += 80 // advance past the 50ms window

        assertEquals(0, limiter.currentCount, "All events should have expired")
    }

    // ========== 8. Custom window ==========

    @Test
    fun `custom windowMs of 1000 works correctly`() {
        val limiter = RateLimiter(maxPerWindow = 2, windowMs = 1000)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire(), "At limit within 1-second window")

        // Events should still be within the 1-second window
        assertEquals(2, limiter.currentCount)
    }

    @Test
    fun `custom windowMs of 1ms expires almost immediately`() {
        var now = 1_000L
        // All 100 acquires happen at the SAME instant (now), so the limiter is
        // genuinely at its cap — previously the real-clock version flaked on
        // loaded CI runners where the repeat(100) loop itself took >1ms, so the
        // earliest entries expired mid-loop and the limiter never reached 100.
        val limiter = RateLimiter(maxPerWindow = 100, windowMs = 1, clock = { now })
        repeat(100) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire(), "At limit within the 1ms window")

        now += 5 // advance past the 1ms window
        assertTrue(limiter.tryAcquire(), "All events should have expired with 1ms window")
    }

    // ========== 9. Single permit ==========

    @Test
    fun `single permit allows one rejects second`() {
        val limiter = RateLimiter(maxPerWindow = 1)
        assertTrue(limiter.tryAcquire(), "First event should be allowed")
        assertFalse(limiter.tryAcquire(), "Second event should be rejected")
        assertFalse(limiter.tryAcquire(), "Third event should be rejected")
        assertEquals(1, limiter.currentCount)
    }

    // ========== 10. Concurrent safety ==========

    @Test
    fun `concurrent threads do not exceed limit`() {
        val maxPerWindow = 50
        val limiter = RateLimiter(maxPerWindow = maxPerWindow, windowMs = 60_000)
        val threadCount = 20
        val attemptsPerThread = 10 // 200 total attempts, only 50 should succeed

        val barrier = CyclicBarrier(threadCount)
        val successCount = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                try {
                    barrier.await() // all threads start at the same time
                    repeat(attemptsPerThread) {
                        if (limiter.tryAcquire()) {
                            successCount.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        latch.await()

        // tryAcquire is atomic (synchronized), so the limit is EXACT even under
        // contention. This used to tolerate 2x overshoot because the old
        // CopyOnWriteArrayList implementation had a check-then-act race — which
        // CI then exceeded (>2x), proving the limiter failed at its one job
        // precisely when an event storm (max contention) hit it.
        assertEquals(
            maxPerWindow,
            successCount.get(),
            "Exactly $maxPerWindow of the 200 concurrent attempts must succeed"
        )
    }
}
