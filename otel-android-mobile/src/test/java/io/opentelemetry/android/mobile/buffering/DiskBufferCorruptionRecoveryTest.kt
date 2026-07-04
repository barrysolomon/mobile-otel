/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.testing.TestUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Corruption-recovery proof (TEST_HARDENING_PLAN P1): an unreadable or
 * garbage disk-buffer file must NEVER crash the host at SDK init — the
 * prime directive — and the buffer must come back functional (recreated,
 * accepting writes). Buffered telemetry is best-effort and TTL-bounded;
 * losing the corrupt file's contents is acceptable, dying is not.
 *
 * Exercises the [DiskLogBuffer] `openDatabaseCrashSafe()` path with the
 * corruption shapes a real device produces: a file that is not SQLite at
 * all (partial write / disk corruption), a truncated database, and a valid
 * SQLite file whose schema Room cannot validate.
 */
@RunWith(RobolectricTestRunner::class)
class DiskBufferCorruptionRecoveryTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(DiskLogBuffer.DB_NAME)
        dbFile.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        DiskLogBuffer.resetForTesting()
    }

    /** Opens the buffer and proves it is functional by writing + counting. */
    private fun assertBufferRecovers() {
        val buffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 24,
            encryptAtRest = false,
        )
        buffer.persistBufferedEventsBlocking(
            listOf(BufferedEvent(TestUtils.createTestLogRecord("post-recovery-write"))),
        )
        assertEquals(
            "Recovered buffer must accept and count new writes",
            1,
            buffer.getEventCount(),
        )
    }

    @Test
    fun `garbage file that is not SQLite recovers without crashing`() {
        dbFile.writeBytes(ByteArray(4096) { 0x42 }) // "BBBB..." — no SQLite header
        File(dbFile.absolutePath + "-wal").writeBytes(ByteArray(512) { 0x42 })
        assertBufferRecovers()
    }

    @Test
    fun `truncated database file recovers without crashing`() {
        // A real database cut off mid-write: valid header, missing pages.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use {
            it.execSQL("CREATE TABLE t (x INTEGER)")
            it.execSQL("INSERT INTO t VALUES (1)")
        }
        val full = dbFile.readBytes()
        dbFile.writeBytes(full.copyOf(full.size / 3))
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
        assertBufferRecovers()
    }

    @Test
    fun `valid SQLite file with foreign schema recovers without crashing`() {
        // Claims the current schema version but contains someone else's tables —
        // Room's identity validation rejects it; the SDK must recreate, not die.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use {
            it.execSQL("CREATE TABLE not_log_records (y TEXT)")
            it.version = 4
        }
        assertBufferRecovers()
    }
}
