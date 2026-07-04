/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Upgrade-path proof (TEST_HARDENING_PLAN P1): a disk buffer written by an
 * OLD SDK survives the Room migration chain to the current schema and its
 * events remain readable — i.e. an app update does not silently destroy
 * crash/offline telemetry buffered by the previous version.
 *
 * The v1 schema is reconstructed exactly as MIGRATION_1_2 expects to find it
 * (the columns its table-rebuild SELECT reads, including the later-dropped
 * `severityNumber`). If someone edits a migration in a way that breaks the
 * 1→2→3→4 chain, this test fails; without it the only signal is
 * `fallbackToDestructiveMigration` quietly wiping user data in production.
 */
@RunWith(RobolectricTestRunner::class)
class DiskBufferUpgradePathTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        DiskLogBuffer.resetForTesting()
    }

    /** Builds an on-disk v1-schema buffer with [rowCount] rows, as the old SDK left it. */
    private fun seedV1Database(rowCount: Int) {
        val dbFile = context.getDatabasePath(DiskLogBuffer.DB_NAME)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.use {
            it.execSQL(
                """
                CREATE TABLE log_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestampMs INTEGER NOT NULL,
                    severityText TEXT,
                    severityNumber INTEGER,
                    body TEXT NOT NULL,
                    attributes TEXT NOT NULL,
                    resource TEXT NOT NULL,
                    instrumentationScopeName TEXT,
                    instrumentationScopeVersion TEXT
                )
                """.trimIndent(),
            )
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS index_log_records_timestampMs ON log_records(timestampMs)",
            )
            val now = System.currentTimeMillis()
            for (i in 1..rowCount) {
                it.execSQL(
                    "INSERT INTO log_records (timestampMs, severityText, severityNumber, body, attributes, resource, instrumentationScopeName, instrumentationScopeVersion) " +
                        "VALUES (?, 'INFO', 9, ?, '{}', '{}', 'upgrade.test', '1.0')",
                    arrayOf(now - i * 1000L, "upgrade-event-$i"),
                )
            }
            it.version = 1
        }
    }

    @Test
    fun `v1 disk buffer survives migration to current schema with all events readable`() {
        seedV1Database(rowCount = 3)

        // Opening through the SDK runs MIGRATION_1_2 → 2_3 → 3_4.
        val buffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 24,
            encryptAtRest = false,
        )

        val count = buffer.getEventCount()
        assertEquals(
            "All 3 v1 events must survive the schema migration — a destructive " +
                "recreate here means an app update wipes buffered telemetry",
            3,
            count,
        )

        val events = runBlocking { buffer.getAllEventsWithIds() }
        val bodies = events.map { it.record.bodyValue?.asString().orEmpty() }
        for (i in 1..3) {
            assertTrue(
                "Event body 'upgrade-event-$i' must be readable post-migration (got: $bodies)",
                bodies.any { it.contains("upgrade-event-$i") },
            )
        }
    }

    @Test
    fun `migrated buffer accepts new writes alongside migrated rows`() {
        seedV1Database(rowCount = 2)

        val buffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 24,
            encryptAtRest = false,
        )

        val record = io.opentelemetry.android.mobile.testing.TestUtils.createTestLogRecord("post-upgrade-write")
        buffer.persistBufferedEventsBlocking(listOf(BufferedEvent(record)))

        assertEquals(
            "Migrated buffer must keep accepting writes (2 migrated + 1 new)",
            3,
            buffer.getEventCount(),
        )
    }
}
