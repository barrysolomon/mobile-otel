/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.tailing

import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.logs.Severity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SR-010: proves user trigger predicates are evaluated WITHOUT holding the
 * buffer lock.
 *
 * A user predicate must never run inside the buffer's read/write lock: doing so
 * risks a latent deadlock (a predicate that re-enters a buffer method needing
 * the lock cannot upgrade a held read lock to a write lock on a
 * [java.util.concurrent.locks.ReentrantReadWriteLock]) and priority inversion
 * (a slow predicate would block all concurrent writers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogTailBufferLockFreeTest {

    // ── 1. A predicate must not hold the lock against another thread ────────
    //
    // From inside the predicate (running on the addLog thread) we require a
    // SECOND thread to take the buffer's WRITE lock and finish. Under the OLD
    // behavior the predicate ran while the addLog thread held the read lock, so
    // the second thread blocks forever on the write lock → the join inside the
    // predicate times out and the write lock is never acquired within the
    // predicate's lifetime. (This cross-thread deadlock is NOT rescued by
    // Kotlin's same-thread read→write upgrade in `write {}`.) Under the fix the
    // predicate runs lock-free, so the second thread acquires the write lock
    // immediately.
    @Test
    fun `predicate does not hold the buffer lock against another thread`() {
        lateinit var buffer: LogTailBuffer
        val otherThreadAcquiredLock = AtomicBoolean(false)
        val acquiredWithinPredicate = AtomicBoolean(false)

        val trigger = LogTailTrigger(
            id = "cross-thread",
            name = "Cross-thread predicate",
            pattern = TailPattern.CustomPredicate {
                val locker = Thread {
                    buffer.clear() // needs the WRITE lock
                    otherThreadAcquiredLock.set(true)
                }
                locker.isDaemon = true
                locker.start()
                locker.join(2000)
                // True only if the other thread grabbed the write lock WHILE the
                // predicate was executing — impossible if the read lock is held.
                acquiredWithinPredicate.set(!locker.isAlive && otherThreadAcquiredLock.get())
                false
            },
            lookbackCount = 5
        )
        buffer = LogTailBuffer(LogTailingConfig(tailSize = 10), listOf(trigger))

        val worker = Thread {
            buffer.addLog(TestUtils.createTestLogRecord("event.1", severity = Severity.INFO))
        }
        worker.isDaemon = true
        worker.start()
        worker.join(5000)

        assertTrue(
            "another thread could not acquire the write lock while the predicate ran — " +
                "the predicate executed while holding the buffer lock",
            acquiredWithinPredicate.get()
        )
    }

    // ── 2. A slow predicate must not block a concurrent writer ──────────────
    //
    // While a slow predicate runs on thread A, thread B's clear() (which needs
    // the WRITE lock) must not be blocked. Under the old behavior the read lock
    // was held for the whole predicate loop, so a concurrent write-lock request
    // blocked for the predicate's entire duration.
    @Test
    fun `slow predicate does not block a concurrent writer`() {
        lateinit var buffer: LogTailBuffer
        val predicateEntered = CountDownLatch(1)
        val releasePredicate = CountDownLatch(1)
        val concurrentWriteDone = AtomicBoolean(false)

        val trigger = LogTailTrigger(
            id = "slow",
            name = "Slow predicate",
            pattern = TailPattern.CustomPredicate {
                predicateEntered.countDown()
                // Block until the test signals — simulates an expensive predicate.
                releasePredicate.await(3, TimeUnit.SECONDS)
                false
            },
            lookbackCount = 5
        )
        buffer = LogTailBuffer(LogTailingConfig(tailSize = 10), listOf(trigger))

        // Thread A: triggers the slow predicate (add takes+releases the write
        // lock, then evaluateTriggers runs the predicate).
        val slow = Thread {
            buffer.addLog(TestUtils.createTestLogRecord("slow.1", severity = Severity.INFO))
        }
        slow.isDaemon = true
        slow.start()

        // Wait until the predicate is running.
        assertTrue("predicate should start", predicateEntered.await(3, TimeUnit.SECONDS))

        // Thread B: a concurrent write-lock operation while the predicate is
        // still mid-flight. It has no triggers to re-enter — clear() only takes
        // the write lock.
        val writer = Thread {
            buffer.clear()
            concurrentWriteDone.set(true)
        }
        writer.isDaemon = true
        writer.start()
        writer.join(2000)

        assertTrue(
            "concurrent writer was blocked by the in-flight predicate (lock held during evaluation)",
            concurrentWriteDone.get()
        )

        // Let the slow predicate finish.
        releasePredicate.countDown()
        slow.join(3000)
    }
}
