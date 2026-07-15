/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.EvictionStrategy
import io.opentelemetry.android.mobile.testing.TestUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Robustness tests for [DiskLogBuffer]:
 *
 * - SR-007: the expensive VACUUM/defrag must NOT run on the hot insert path;
 *   it belongs on the periodic cleanup() path.
 * - SR-015: size-budget enforcement must key on a LOGICAL measure (summed
 *   payload bytes) rather than the filesystem file length, which is inflated
 *   by SQLite free pages / page granularity / WAL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiskLogBufferRobustnessTest {

    private lateinit var context: Context
    private lateinit var diskBuffer: DiskLogBuffer

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        diskBuffer = DiskLogBuffer.getInstance(context = context, maxSizeMb = 10, ttlHours = 1)
    }

    @After
    fun teardown() {
        diskBuffer.close()
        DiskLogBuffer.resetForTesting()
    }

    private suspend fun waitUntil(timeoutMs: Long = 8000, cond: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(50)
        }
        return cond()
    }

    private suspend fun waitForCount(buffer: DiskLogBuffer, expected: Int, timeoutMs: Long = 4000) {
        waitUntil(timeoutMs) { buffer.getAllEvents().size >= expected }
    }

    // ── SR-007: inserts enforce size by DELETE but never VACUUM ─────────────
    @Test
    fun `insert path enforces size by deleting rows but does NOT run VACUUM`() = runBlocking {
        // Fresh buffer with a tiny 1 MB logical budget so a modest insert trips it.
        diskBuffer.close()
        DiskLogBuffer.resetForTesting()
        diskBuffer = DiskLogBuffer.getInstance(context = context, maxSizeMb = 1, ttlHours = 1)

        val vacuums = AtomicInteger(0)
        diskBuffer.onVacuum = { vacuums.incrementAndGet() }

        // 200 rows x ~8 KB body = ~1.6 MB of logical payload, over the 1 MB budget.
        val big = "x".repeat(8 * 1024)
        val records = (0 until 200).map { TestUtils.createTestLogRecord("big.$it.$big") }
        diskBuffer.persistEvents(records)

        // enforceSizeLimit must DELETE rows to get under budget (count drops below 200).
        val evicted = waitUntil(10_000) {
            val n = diskBuffer.getAllEvents().size
            n in 1 until 200
        }
        assertTrue("size enforcement should have deleted rows on the insert path", evicted)

        // Give any (erroneous) VACUUM a chance to fire.
        delay(400)
        assertEquals("insert / enforceSizeLimit path must NOT run VACUUM (SR-007)", 0, vacuums.get())
    }

    // ── SR-007: periodic cleanup DOES defrag via VACUUM ─────────────────────
    @Test
    fun `periodic cleanup runs VACUUM`() = runBlocking {
        val vacuums = AtomicInteger(0)
        diskBuffer.onVacuum = { vacuums.incrementAndGet() }

        diskBuffer.persistEvents(listOf(TestUtils.createTestLogRecord("keep.1")))
        waitForCount(diskBuffer, 1)

        diskBuffer.cleanup()

        val ran = waitUntil(5000) { vacuums.get() > 0 }
        assertTrue("periodic cleanup() must run VACUUM to reclaim free pages (SR-007)", ran)
    }

    // ── SR-015: enforcement keys on logical size, not filesystem slack ──────
    @Test
    fun `enforceOfflineBudget keys on logical size not filesystem slack`() = runBlocking {
        // Create filesystem slack: insert a chunk, delete it all WITHOUT vacuum
        // (clearAll = DELETE, no VACUUM), then reinsert a few small rows. The db
        // file retains the free pages so dbFile.length() stays large while the
        // logical payload size is tiny.
        val filler = "y".repeat(2 * 1024)
        diskBuffer.persistEvents((0 until 80).map { TestUtils.createTestLogRecord("slack.$it.$filler") })
        waitForCount(diskBuffer, 80)
        diskBuffer.clearAll()
        diskBuffer.persistEvents((0 until 5).map { TestUtils.createTestLogRecord("keep.$it") })
        waitForCount(diskBuffer, 5)

        val dbFile = context.getDatabasePath(DiskLogBuffer.DB_NAME)
        val fileBytes = dbFile.length()
        val logicalBytes = diskBuffer.logicalSizeBytes()

        // Precondition: filesystem length exceeds the logical payload size.
        assertTrue(
            "expected filesystem slack (fileBytes=$fileBytes logicalBytes=$logicalBytes)",
            fileBytes > logicalBytes
        )

        // Budget strictly between logical size and filesystem length:
        //   logical  <= budget  → logical-size enforcement evicts NOTHING.
        //   fileBytes >  budget  → old filesystem-length enforcement WOULD evict.
        val budget = (logicalBytes + fileBytes) / 2

        val evicted = diskBuffer.enforceOfflineBudget(budget, EvictionStrategy.OLDEST_FIRST)

        assertEquals("logical size is under budget; nothing should be evicted (SR-015)", 0, evicted)
        assertEquals("all kept rows must remain", 5, diskBuffer.getAllEvents().size)
    }
}
