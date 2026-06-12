/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.opentelemetry.android.mobile.core.BootTracker
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit Room migrations for the log_records table.
 *
 * Schema history:
 *   v1: Base schema (id, timestampMs, severityText, severityNumber, body, attributes,
 *       resource, instrumentationScopeName, instrumentationScopeVersion). Index: timestampMs.
 *   v2: Added traceId, spanId, attributeTypes. Added traceId index. Dropped severityNumber.
 *   v3: Added monotonicMs, bootId. Added monotonicMs index.
 *   v4: Added seqId for RAM/disk dedup.
 */
internal object LogDatabaseMigrations {

    /** v1 → v2: Add trace context columns, attributeTypes; drop severityNumber; add traceId index. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // SQLite < 3.35 (Android < 14) doesn't support DROP COLUMN.
            // Rebuild the table to remove severityNumber.
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS log_records_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    timestampMs INTEGER NOT NULL,
                    severityText TEXT,
                    body TEXT NOT NULL,
                    attributes TEXT NOT NULL,
                    resource TEXT NOT NULL,
                    instrumentationScopeName TEXT,
                    instrumentationScopeVersion TEXT,
                    traceId TEXT,
                    spanId TEXT,
                    attributeTypes TEXT
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO log_records_new (id, timestampMs, severityText, body, attributes,
                    resource, instrumentationScopeName, instrumentationScopeVersion)
                SELECT id, timestampMs, severityText, body, attributes,
                    resource, instrumentationScopeName, instrumentationScopeVersion
                FROM log_records
            """.trimIndent())
            db.execSQL("DROP TABLE log_records")
            db.execSQL("ALTER TABLE log_records_new RENAME TO log_records")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_log_records_timestampMs ON log_records(timestampMs)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_log_records_traceId ON log_records(traceId)")
        }
    }

    /** v2 → v3: Add monotonic clock columns for boot-safe time windows. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE log_records ADD COLUMN monotonicMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE log_records ADD COLUMN bootId TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_log_records_monotonicMs ON log_records(monotonicMs)")
        }
    }

    /** v3 → v4: Add seqId for RAM/disk deduplication during flush. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE log_records ADD COLUMN seqId INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}

/**
 * Persistent disk buffer for log records using Room database.
 *
 * This provides durable storage for log events with:
 * - Automatic size management (bounded by max MB)
 * - TTL-based expiration (old events auto-deleted)
 * - Crash recovery (events survive app restarts)
 * - Efficient time-window queries
 *
 * **Storage Format:**
 * - SQLite database via Room
 * - JSON serialization for LogRecordData
 * - Indexed by timestamp for fast window queries
 *
 * **Size Management:**
 * - Tracks total storage size in MB
 * - Removes oldest events when size limit exceeded
 * - Automatic cleanup on TTL expiration
 *
 * Thread Safety: All operations use coroutines and are thread-safe
 *
 * @property maxSizeMb Maximum disk space in megabytes
 * @property ttlHours Time-to-live for events in hours
 */
class DiskLogBuffer private constructor(
    internal val context: Context,
    private val maxSizeMb: Int,
    private val ttlHours: Int,
    private val encryptAtRest: Boolean
) {
    private val TAG = "DiskLogBuffer"

    /**
     * Whether the buffer is actually running encrypted. This may be `false`
     * even when [encryptAtRest] was requested, if the Keystore could not
     * provision a passphrase (graceful degradation — see [buildDatabase]).
     * Exposed for tests/diagnostics.
     */
    @Volatile
    internal var encryptionActive: Boolean = false
        private set

    private val database: LogDatabase = openDatabaseCrashSafe()

    private val logDao = database.logDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cached event count — avoids runBlocking COUNT(*) on metric gauge callbacks.
    // Seeded on first access, updated on insert/delete operations.
    private val cachedCount = AtomicInteger(-1) // -1 = not yet seeded

    /**
     * Builds the Room database, opens it, and forces a WAL checkpoint — all in
     * a way that NEVER crashes the host on a failed open.
     *
     * Failure modes handled:
     * - **SQLCipher native library unavailable** (`UnsatisfiedLinkError` — the
     *   `.so` is missing for this ABI, e.g. some emulators): encryption can never
     *   succeed, and any existing DB file is plain *cleartext* written by a prior
     *   fallback run — fully readable. We open it as cleartext WITHOUT deleting.
     *   Deleting here (the old behaviour) silently wiped crash-persisted and
     *   offline-buffered telemetry on EVERY launch, so the crash/offline durability
     *   guarantee was broken on any device lacking the native lib.
     * - **Wrong/invalidated SQLCipher key or corrupt encrypted file**: opening
     *   the database throws (SQLCipher reports `file is not a database`). We log,
     *   delete the on-disk files, clear the Keystore-wrapped passphrase, and
     *   rebuild from scratch. This mirrors the intent of
     *   `fallbackToDestructiveMigration` for the encryption dimension: an
     *   unreadable buffer is recreated, never fatal.
     * - **Cleartext→encrypted transition** (e.g. enabling encryption on an
     *   existing cleartext DB): the encrypted open fails the same way and the
     *   old cleartext DB is recreated encrypted. Buffered telemetry is
     *   best-effort and bounded by TTL, so dropping it on this one-time
     *   transition is acceptable and crash-free.
     * - **Encrypted→cleartext transition** (encryption disabled after being on):
     *   same recreate-on-failure path.
     */
    private fun openDatabaseCrashSafe(): LogDatabase {
        val first = buildDatabase(encrypt = encryptAtRest)
        return try {
            prewarm(first)
            first
        } catch (e: Throwable) {
            try {
                first.close()
            } catch (_: Throwable) {
                // Best-effort close of the half-open handle.
            }

            // WHY the open failed decides whether the existing file is salvageable.
            // UnsatisfiedLinkError == SQLCipher native lib missing on this ABI:
            // encryption can never work, and any existing file is cleartext and
            // readable. Deleting it would destroy prior crash/offline telemetry on
            // every launch — so DO NOT delete; open the existing file as cleartext.
            val nativeUnavailable = e.causedByUnsatisfiedLink()

            if (encryptAtRest && !nativeUnavailable) {
                // Genuine key/format mismatch on an encrypted file we cannot read —
                // recreate destructively rather than crash.
                Log.w(TAG, "Encrypted disk buffer open failed; recreating database", e)
                deleteDatabaseFiles()
                // The wrapped passphrase may be stale/invalidated; reset it so the
                // rebuild mints a fresh key + passphrase.
                runCatching { DiskBufferKeyManager.create(context).reset() }
                val rebuilt = buildDatabase(encrypt = true)
                try {
                    prewarm(rebuilt)
                    return rebuilt
                } catch (e2: Throwable) {
                    Log.e(TAG, "Encrypted disk buffer unusable on this device; falling back to cleartext", e2)
                    try {
                        rebuilt.close()
                    } catch (_: Throwable) {
                    }
                    // fall through to the cleartext path below (data preserved)
                }
            } else {
                Log.w(TAG, "SQLCipher native library unavailable; using cleartext disk buffer (existing data preserved)", e)
            }

            // Cleartext fallback. Preserve the existing file — it holds prior
            // crash/offline events. Only recreate if even a cleartext open fails
            // (genuine corruption), never merely because encryption was unavailable.
            val cleartext = buildDatabase(encrypt = false)
            try {
                prewarm(cleartext)
                cleartext
            } catch (e3: Throwable) {
                Log.e(TAG, "Cleartext disk buffer open failed; recreating empty buffer", e3)
                try {
                    cleartext.close()
                } catch (_: Throwable) {
                }
                deleteDatabaseFiles()
                val fresh = buildDatabase(encrypt = false)
                prewarm(fresh)
                fresh
            }
        }
    }

    /**
     * True if this throwable or any of its causes is an [UnsatisfiedLinkError],
     * i.e. SQLCipher's native library could not be loaded for this ABI. In that
     * case the on-disk file is cleartext and must be preserved, not deleted.
     */
    private fun Throwable.causedByUnsatisfiedLink(): Boolean =
        generateSequence(this as Throwable?) { it.cause }.any { it is UnsatisfiedLinkError }

    /**
     * Constructs the Room database, attaching the SQLCipher
     * [net.zetetic.database.sqlcipher.SupportOpenHelperFactory] when [encrypt]
     * is true AND a passphrase can be provisioned from the Android Keystore.
     *
     * If [encrypt] is requested but the Keystore cannot supply a passphrase,
     * this logs and builds an UNENCRYPTED database (graceful degradation) — the
     * host must never lose telemetry buffering just because encryption could
     * not be provisioned.
     */
    private fun buildDatabase(encrypt: Boolean): LogDatabase {
        val builder = Room.databaseBuilder(
            context.applicationContext,
            LogDatabase::class.java,
            DB_NAME
        )
            .addMigrations(*LogDatabaseMigrations.ALL)
            .fallbackToDestructiveMigration(true)  // Safety net: if migration fails, recreate rather than crash

        encryptionActive = false
        if (encrypt) {
            val factory = createCipherFactory()
            if (factory != null) {
                builder.openHelperFactory(factory)
                encryptionActive = true
                Log.i(TAG, "Disk buffer at-rest encryption ENABLED (SQLCipher + Android Keystore)")
            } else {
                Log.w(TAG, "Disk buffer encryption requested but unavailable; running cleartext")
            }
        }
        return builder.build()
    }

    /**
     * Builds the SQLCipher SupportFactory from a Keystore-wrapped passphrase, or
     * returns `null` (caller runs cleartext) if SQLCipher native libs cannot be
     * loaded or the passphrase cannot be provisioned. Never throws.
     */
    private fun createCipherFactory(): androidx.sqlite.db.SupportSQLiteOpenHelper.Factory? {
        return try {
            // net.zetetic:sqlcipher-android auto-loads its native library on first
            // database open (no explicit loadLibs() call needed). If the .so is
            // missing for this ABI the open throws UnsatisfiedLinkError, which is
            // caught by openDatabaseCrashSafe() and degrades to cleartext.
            val passphrase = DiskBufferKeyManager.create(context).getOrCreatePassphrase()
                ?: return null
            net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase)
        } catch (e: Throwable) {
            // Keystore failure, class-load failure, anything — degrade to cleartext.
            Log.e(TAG, "Failed to initialize SQLCipher factory; running cleartext", e)
            null
        }
    }

    /**
     * Opens the writable database and forces a WAL checkpoint so crash-mirrored
     * events from a dead process are visible to subsequent DAO queries. Throws
     * if the underlying file cannot be opened (wrong key / corrupt) — the caller
     * ([openDatabaseCrashSafe]) treats that as the recreate trigger.
     */
    private fun prewarm(db: LogDatabase) {
        runBlocking(Dispatchers.IO) {
            val sqliteDb = db.openHelper.writableDatabase
            // Force WAL checkpoint. Room's SupportSQLiteDatabase doesn't allow
            // PRAGMA via execSQL, so use query() (side-effect: checkpoint).
            sqliteDb.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        }
    }

    private fun deleteDatabaseFiles() {
        try {
            val dbPath = context.getDatabasePath(DB_NAME)
            dbPath.delete()
            File(dbPath.absolutePath + "-wal").delete()
            File(dbPath.absolutePath + "-shm").delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete database files during recreate (non-fatal)", e)
        }
    }

    /**
     * Persists log records to disk.
     *
     * Converts LogRecordData to entities and stores in database.
     * Triggers size check after insertion.
     *
     * @param records List of log records to persist
     */
    fun persistEvents(records: List<LogRecordData>) {
        adjustCachedCount(records.size)
        scope.launch {
            try {
                val entities = records.map { it.toEntity() }
                logDao.insertAll(entities)
                Log.d(TAG, "Persisted ${entities.size} events to disk")
                enforceSizeLimit()
            } catch (e: Exception) {
                adjustCachedCount(-records.size)
                Log.e(TAG, "Error persisting events", e)
            }
        }
    }

    /**
     * Persists [BufferedEvent]s to disk, including their monotonic timestamps
     * and boot IDs for clock-skew-safe window queries.
     */
    internal fun persistBufferedEvents(events: List<BufferedEvent>) {
        adjustCachedCount(events.size)
        scope.launch {
            try {
                val entities = events.map { it.toEntity() }
                logDao.insertAll(entities)
                Log.d(TAG, "Persisted ${entities.size} buffered events to disk (with monotonicMs)")
                enforceSizeLimit()
            } catch (e: Exception) {
                adjustCachedCount(-events.size)
                Log.e(TAG, "Error persisting buffered events", e)
            }
        }
    }

    /**
     * Synchronous variant of [persistBufferedEvents]. Used by the crash path
     * where the calling thread is about to die (SIGKILL after the chained
     * default UncaughtExceptionHandler runs). The async `scope.launch` form
     * cannot be trusted to complete before process death — its coroutine
     * may never get scheduled. Blocking the crash thread on a sqlite insert
     * costs a few milliseconds and guarantees the buffer survives.
     *
     * Skips `enforceSizeLimit()` to keep the blocking section as short as
     * possible; size enforcement runs on next normal launch via the existing
     * `prune*` paths.
     */
    internal fun persistBufferedEventsBlocking(events: List<BufferedEvent>, enforceSize: Boolean = false) {
        if (events.isEmpty()) return
        adjustCachedCount(events.size)
        try {
            val entities = events.map { it.toEntity() }
            runBlocking { logDao.insertAll(entities) }
            Log.d(TAG, "Persisted ${entities.size} buffered events to disk (blocking)")
            if (enforceSize) {
                // Size enforcement off the blocking path — callers that need the insert
                // to be synchronous (overflow under the buffer-move lock) still get cap
                // enforcement, just asynchronously like the non-blocking persist path.
                scope.launch { enforceSizeLimit() }
            }
        } catch (e: Exception) {
            adjustCachedCount(-events.size)
            Log.e(TAG, "Error persisting buffered events (blocking)", e)
        }
    }

    /**
     * One disk row with its Room primary key. The id is what makes exactly-once
     * cleanup possible: a flush deletes precisely the rows it exported, never rows
     * that were persisted after its snapshot.
     */
    internal data class DiskRow(val id: Long, val record: LogRecordData, val seqId: Long)

    /** All disk rows with ids and seqIds (for force flush snapshot + precise cleanup). */
    internal suspend fun getAllEventsWithIds(): List<DiskRow> = withContext(Dispatchers.IO) {
        try {
            logDao.getAllEvents().mapNotNull { entity ->
                entity.toLogRecordData()?.let { DiskRow(entity.id, it, entity.seqId) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving events with ids", e)
            emptyList()
        }
    }

    /** Windowed disk rows with ids and seqIds (for window flush snapshot + precise cleanup). */
    internal suspend fun getEventsInWindowWithIds(
        monoStartMs: Long,
        wallStartMs: Long,
        currentBootId: String
    ): List<DiskRow> = withContext(Dispatchers.IO) {
        try {
            logDao.getEventsInWindowDualClock(monoStartMs, wallStartMs, currentBootId)
                .mapNotNull { entity ->
                    entity.toLogRecordData()?.let { DiskRow(entity.id, it, entity.seqId) }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving windowed events with ids", e)
            emptyList()
        }
    }

    /**
     * Deletes exactly the given rows. Chunked to stay under SQLite's bind-variable
     * limit. Returns the number of rows actually deleted.
     */
    internal suspend fun deleteByIds(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        try {
            var deleted = 0
            ids.chunked(500).forEach { chunk -> deleted += logDao.deleteByIds(chunk) }
            adjustCachedCount(-deleted)
            Log.d(TAG, "Deleted $deleted exported events from disk (by id)")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting events by id", e)
            0
        }
    }

    /**
     * Deletes rows whose seqId matches an exported RAM event. Covers crash-safety
     * mirror rows written DURING an export: their RAM originals were exported and
     * removed, so without this the orphaned mirrors would re-export as duplicates
     * on the next flush. seqId==0 rows (pre-migration) are never matched.
     */
    internal suspend fun deleteBySeqIds(seqIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (seqIds.isEmpty()) return@withContext 0
        try {
            var deleted = 0
            seqIds.chunked(500).forEach { chunk -> deleted += logDao.deleteBySeqIds(chunk) }
            adjustCachedCount(-deleted)
            if (deleted > 0) Log.d(TAG, "Deleted $deleted mirror rows from disk (by seqId)")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting events by seqId", e)
            0
        }
    }

    /**
     * Dual-clock window query: uses monotonic time for same-boot events (immune
     * to wall-clock changes) and wall-clock for cross-boot crash recovery events.
     */
    suspend fun getEventsInWindowDualClock(
        monoStartMs: Long,
        wallStartMs: Long,
        currentBootId: String
    ): List<LogRecordData> {
        return logDao.getEventsInWindowDualClock(monoStartMs, wallStartMs, currentBootId)
            .mapNotNull { it.toLogRecordData() }
    }

    /**
     * Retrieves events within a time window.
     *
     * @param windowStartMs Start of window in epoch milliseconds
     * @return List of log records within the window
     */
    suspend fun getEventsInWindow(windowStartMs: Long): List<LogRecordData> = withContext(Dispatchers.IO) {
        try {
            val entities = logDao.getEventsAfter(windowStartMs)
            Log.d(TAG, "Retrieved ${entities.size} events from disk for window starting at $windowStartMs")
            entities.mapNotNull { it.toLogRecordData() }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving events from window", e)
            emptyList()
        }
    }

    /**
     * Retrieves all events from disk (for force flush).
     *
     * @return List of all persisted log records
     */
    suspend fun getAllEvents(): List<LogRecordData> = withContext(Dispatchers.IO) {
        try {
            val entities = logDao.getAllEvents()
            Log.d(TAG, "Retrieved ${entities.size} total events from disk")
            entities.mapNotNull { it.toLogRecordData() }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving all events", e)
            emptyList()
        }
    }

    /**
     * Clears all events from disk.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            val deletedCount = logDao.deleteAll()
            cachedCount.set(0)
            Log.i(TAG, "Cleared $deletedCount events from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing events", e)
        }
    }

    /**
     * Deletes events within a time window from disk.
     *
     * @param windowStartMs Start of window in epoch milliseconds
     * @return Number of deleted events
     */
    suspend fun deleteEventsInWindow(windowStartMs: Long): Int = withContext(Dispatchers.IO) {
        try {
            val deletedCount = logDao.deleteEventsAfter(windowStartMs)
            adjustCachedCount(-deletedCount)
            Log.d(TAG, "Deleted $deletedCount events from disk for window starting at $windowStartMs")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting events from window", e)
            0
        }
    }

    /**
     * Retrieves all events that share a specific OTel trace ID.
     *
     * @param traceId The OTel trace ID hex string to match (32 hex chars)
     * @return List of log records belonging to that trace
     */
    suspend fun getEventsByTraceId(traceId: String): List<LogRecordData> = withContext(Dispatchers.IO) {
        try {
            logDao.getEventsByTraceId(traceId).mapNotNull { it.toLogRecordData() }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving events by traceId", e)
            emptyList()
        }
    }

    /**
     * Deletes all events that share a specific OTel trace ID.
     *
     * @param traceId The OTel trace ID hex string to match (32 hex chars)
     * @return Number of deleted rows
     */
    suspend fun deleteEventsByTraceId(traceId: String): Int = withContext(Dispatchers.IO) {
        try {
            val deleted = logDao.deleteEventsByTraceId(traceId)
            adjustCachedCount(-deleted)
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting events by traceId", e)
            0
        }
    }

    /**
     * Gets the current number of events in disk buffer.
     */
    fun getEventCount(): Int {
        val cached = cachedCount.get()
        if (cached >= 0) return cached
        // First access: seed from DB (runs once at startup, on the calling thread).
        return runBlocking {
            try {
                val count = logDao.getCount()
                cachedCount.set(count)
                count
            } catch (e: Exception) {
                Log.e(TAG, "Error getting event count", e)
                0
            }
        }
    }

    private fun adjustCachedCount(delta: Int) {
        cachedCount.updateAndGet { current ->
            // Not yet seeded: STAY unseeded (-1) — the next getEventCount()
            // seeds from the DB, which already reflects this mutation.
            // Seeding from the delta alone would permanently undercount rows
            // already on disk (crash-mirrored events from a previous process,
            // or a migrated buffer) in the stats gauge and recovery probe.
            if (current < 0) -1 else maxOf(0, current + delta)
        }
    }

    /**
     * Returns the maximum seqId stored on disk, or 0 if the buffer is empty.
     * Used to seed the in-process seqId counter on startup so that new events
     * never collide with crash-mirrored events from a previous process.
     */
    fun getMaxSeqId(): Long {
        return runBlocking {
            try {
                logDao.getMaxSeqId() ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Error getting max seqId", e)
                0L
            }
        }
    }

    /**
     * Performs cleanup of expired events based on TTL.
     *
     * Called periodically by MobileLogRecordProcessor.
     */
    fun cleanup() {
        scope.launch {
            try {
                val expiryTimeMs = System.currentTimeMillis() - (ttlHours * 60 * 60 * 1000L)
                val deletedCount = logDao.deleteOlderThan(expiryTimeMs)
                if (deletedCount > 0) {
                    adjustCachedCount(-deletedCount)
                    Log.i(TAG, "Cleanup: deleted $deletedCount expired events (TTL: ${ttlHours}h)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    /**
     * Alias for cleanup(). Removes events that have exceeded the TTL.
     */
    fun cleanupExpired() = cleanup()

    /**
     * Returns the current database file size in megabytes.
     *
     * Returns 0.0 if the database file does not exist yet.
     */
    fun getStorageSizeMb(): Double {
        val dbFile = context.getDatabasePath(DB_NAME)
        return if (dbFile.exists()) dbFile.length() / (1024.0 * 1024.0) else 0.0
    }

    /**
     * Runs SQLite VACUUM to reclaim disk space after deletions.
     *
     * This compacts the database file. Runs asynchronously in the background.
     */
    fun vacuum() {
        scope.launch {
            try {
                database.openHelper.writableDatabase.execSQL("VACUUM")
                Log.i(TAG, "VACUUM completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during VACUUM", e)
            }
        }
    }

    /**
     * Enforces the offline budget by evicting events until disk usage is within budget.
     *
     * @param maxBytes Maximum disk bytes allowed during offline
     * @param strategy Eviction strategy when over budget
     * @return Number of events evicted
     */
    suspend fun enforceOfflineBudget(
        maxBytes: Long,
        strategy: io.opentelemetry.android.mobile.config.EvictionStrategy
    ): Int = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return@withContext 0

            val currentSizeBytes = dbFile.length()
            if (currentSizeBytes <= maxBytes) return@withContext 0

            val totalCount = logDao.getCount()
            if (totalCount == 0) return@withContext 0

            val excessRatio = (currentSizeBytes - maxBytes).toDouble() / currentSizeBytes
            val deleteCount = (totalCount * excessRatio).toInt() + 1

            val deleted = when (strategy) {
                io.opentelemetry.android.mobile.config.EvictionStrategy.OLDEST_FIRST ->
                    logDao.deleteOldest(deleteCount)
                io.opentelemetry.android.mobile.config.EvictionStrategy.LOWEST_SEVERITY_FIRST ->
                    logDao.deleteLowestSeverity(deleteCount)
            }

            adjustCachedCount(-deleted)
            Log.i(TAG, "Offline budget enforcement: evicted $deleted events " +
                "(was ${currentSizeBytes / 1024}KB, budget ${maxBytes / 1024}KB, strategy=$strategy)")

            try {
                database.openHelper.writableDatabase.execSQL("VACUUM")
            } catch (e: Exception) {
                Log.w(TAG, "VACUUM after budget enforcement failed (non-fatal)", e)
            }

            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing offline budget", e)
            0
        }
    }

    /**
     * Enforces the maximum size limit by removing oldest events.
     */
    private suspend fun enforceSizeLimit() {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            val currentSizeMb = dbFile.length() / (1024.0 * 1024.0)

            if (currentSizeMb > maxSizeMb) {
                // Calculate how many events to delete (approximately)
                val excessRatio = (currentSizeMb - maxSizeMb) / currentSizeMb
                val totalCount = logDao.getCount()
                val deleteCount = (totalCount * excessRatio).toInt() + 100 // Add buffer

                logDao.deleteOldest(deleteCount)
                adjustCachedCount(-deleteCount)
                Log.i(TAG, "Size limit enforcement: deleted $deleteCount oldest events (was ${currentSizeMb}MB, limit ${maxSizeMb}MB)")
                database.openHelper.writableDatabase.execSQL("VACUUM")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing size limit", e)
        }
    }

    /**
     * Closes the database and releases resources.
     */
    fun close() {
        scope.cancel()
        database.close()
    }

    companion object {
        /** Room/SQLite database file name for the on-disk telemetry buffer. */
        internal const val DB_NAME = "otel_log_buffer.db"

        @Volatile
        private var instance: DiskLogBuffer? = null

        /**
         * Returns the shared disk buffer, creating it on first call.
         *
         * @param encryptAtRest when true, the Room/SQLite database is encrypted
         *   at rest via SQLCipher with a passphrase wrapped by an Android
         *   Keystore key. Defaults to true for enterprise-grade safety; if
         *   encryption cannot be provisioned the buffer degrades to cleartext
         *   rather than failing (see [openDatabaseCrashSafe]).
         */
        fun getInstance(
            context: Context,
            maxSizeMb: Int,
            ttlHours: Int,
            encryptAtRest: Boolean = true
        ): DiskLogBuffer {
            return instance ?: synchronized(this) {
                instance ?: DiskLogBuffer(context.applicationContext, maxSizeMb, ttlHours, encryptAtRest).also {
                    instance = it
                }
            }
        }

        /**
         * Closes and clears the singleton instance for test isolation.
         *
         * IMPORTANT: For testing only. Allows each test to start with a fresh
         * DiskLogBuffer instance and clean database state.
         */
        @Suppress("VisibleForTests")
        internal fun resetForTesting() {
            synchronized(this) {
                val current = instance
                if (current != null) {
                    val dbPath = current.context.getDatabasePath(DB_NAME)
                    current.close()
                    instance = null
                    // Delete database files explicitly so the next test starts with a
                    // completely empty database (Room does not delete WAL/SHM on close).
                    dbPath.delete()
                    File(dbPath.absolutePath + "-wal").delete()
                    File(dbPath.absolutePath + "-shm").delete()
                }
            }
        }
    }
}

/**
 * Room entity for persisting log records.
 */
@Entity(tableName = "log_records", indices = [Index("timestampMs"), Index("traceId"), Index("monotonicMs")])
data class LogRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val severityText: String?,
    val body: String,
    val attributes: String, // JSON-encoded attributes
    val resource: String,   // JSON-encoded resource attributes
    val instrumentationScopeName: String?,
    val instrumentationScopeVersion: String?,
    val traceId: String? = null,        // OTel traceId hex string (32 chars), null if invalid
    val spanId: String? = null,         // OTel spanId hex string (16 chars), null if invalid
    val attributeTypes: String? = null, // JSON: {"http.duration_ms":"long","event.name":"string"}
    val monotonicMs: Long = 0,          // SystemClock.elapsedRealtime() at capture time; 0 = unknown (pre-migration)
    val bootId: String? = null,         // Kernel boot_id; null = pre-migration or cross-boot
    val seqId: Long = 0                 // Process-wide sequence number for RAM/disk dedup; 0 = pre-migration
)

/**
 * Room DAO for log record operations.
 */
@Dao
interface LogDao {
    @Insert
    suspend fun insertAll(logs: List<LogRecordEntity>)

    @Query("SELECT * FROM log_records WHERE timestampMs >= :startMs ORDER BY timestampMs ASC")
    suspend fun getEventsAfter(startMs: Long): List<LogRecordEntity>

    @Query("SELECT * FROM log_records ORDER BY timestampMs ASC")
    suspend fun getAllEvents(): List<LogRecordEntity>

    @Query("SELECT COUNT(*) FROM log_records")
    suspend fun getCount(): Int

    @Query("DELETE FROM log_records WHERE timestampMs < :expiryMs")
    suspend fun deleteOlderThan(expiryMs: Long): Int

    @Query("DELETE FROM log_records WHERE timestampMs >= :startMs")
    suspend fun deleteEventsAfter(startMs: Long): Int

    @Query("DELETE FROM log_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM log_records WHERE seqId IN (:seqIds) AND seqId > 0")
    suspend fun deleteBySeqIds(seqIds: List<Long>): Int

    @Query("DELETE FROM log_records WHERE id IN (SELECT id FROM log_records ORDER BY timestampMs ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int

    @Query("DELETE FROM log_records")
    suspend fun deleteAll(): Int

    @Query("SELECT MAX(seqId) FROM log_records")
    suspend fun getMaxSeqId(): Long?

    @Query("SELECT * FROM log_records WHERE traceId = :traceId ORDER BY timestampMs ASC")
    suspend fun getEventsByTraceId(traceId: String): List<LogRecordEntity>

    @Query("DELETE FROM log_records WHERE traceId = :traceId")
    suspend fun deleteEventsByTraceId(traceId: String): Int

    @Query("""
        DELETE FROM log_records WHERE id IN (
            SELECT id FROM log_records
            ORDER BY
                CASE severityText
                    WHEN 'TRACE' THEN 0
                    WHEN 'DEBUG' THEN 1
                    WHEN 'INFO' THEN 2
                    WHEN 'WARN' THEN 3
                    WHEN 'ERROR' THEN 4
                    WHEN 'FATAL' THEN 5
                    ELSE 1
                END ASC,
                timestampMs ASC
            LIMIT :count
        )
    """)
    suspend fun deleteLowestSeverity(count: Int): Int

    /**
     * Dual-clock query: uses monotonic time for same-boot events (clock-skew-safe)
     * and wall-clock for cross-boot events (crash recovery fallback).
     * NOTE: explicit parentheses required — SQL AND binds tighter than OR.
     */
    @Query("""
        SELECT * FROM log_records
        WHERE (bootId = :currentBootId AND monotonicMs >= :monoStartMs)
           OR ((bootId IS NULL OR bootId != :currentBootId) AND timestampMs >= :wallStartMs)
        ORDER BY timestampMs ASC
    """)
    suspend fun getEventsInWindowDualClock(
        monoStartMs: Long,
        wallStartMs: Long,
        currentBootId: String
    ): List<LogRecordEntity>
}

/**
 * Room database definition.
 */
@Database(entities = [LogRecordEntity::class], version = 4, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}

/**
 * Extension to convert LogRecordData to LogRecordEntity.
 */
private fun LogRecordData.toEntity(): LogRecordEntity {
    // Extract trace/span context
    val spanCtx = spanContext
    val traceId = if (spanCtx.isValid) spanCtx.traceId else null
    val spanId = if (spanCtx.isValid) spanCtx.spanId else null

    // Serialize attributes to JSON (values as strings) and record original types
    val attributesJson = JSONObject()
    val attributeTypesJson = JSONObject()
    attributes.forEach { key, value ->
        attributesJson.put(key.key, value.toString())
        attributeTypesJson.put(key.key, key.type.name.lowercase())
    }

    // Serialize resource attributes to JSON
    val resourceJson = JSONObject().apply {
        resource.attributes.forEach { key, value ->
            put(key.key, value.toString())
        }
    }.toString()

    val tsNs = timestampEpochNanos
    val effectiveTimestampMs = if (tsNs > 0) tsNs / 1_000_000 else observedTimestampEpochNanos / 1_000_000

    return LogRecordEntity(
        timestampMs = effectiveTimestampMs,
        severityText = severity?.name,
        body = body.asString(),
        attributes = attributesJson.toString(),
        resource = resourceJson,
        instrumentationScopeName = instrumentationScopeInfo.name,
        instrumentationScopeVersion = instrumentationScopeInfo.version,
        traceId = traceId,
        spanId = spanId,
        attributeTypes = attributeTypesJson.toString().takeIf { it != "{}" }
    )
}

/**
 * Extension to convert BufferedEvent to LogRecordEntity, including monotonic timestamp and boot ID.
 */
private fun BufferedEvent.toEntity(): LogRecordEntity {
    val base = logRecord.toEntity()
    return base.copy(
        monotonicMs = monotonicMs,
        bootId = BootTracker.currentBootId,
        seqId = seqId
    )
}

/**
 * Extension to convert LogRecordEntity back to LogRecordData.
 */
private fun LogRecordEntity.toLogRecordData(): LogRecordData? {
    val entityBody = body // Renamed to avoid shadowing
    val entitySeverityText = severityText // Renamed to avoid shadowing

    return try {
        // Parse attributes from JSON, reconstructing original typed AttributeKeys
        val attributesBuilder = Attributes.builder()
        val attributesJson = JSONObject(attributes)
        val typesJson = attributeTypes?.let { JSONObject(it) } ?: JSONObject()
        attributesJson.keys().forEach { key ->
            val valueStr = attributesJson.getString(key)
            when (typesJson.optString(key, "string")) {
                "long" -> attributesBuilder.put(AttributeKey.longKey(key), valueStr.toLongOrNull() ?: 0L)
                "double" -> attributesBuilder.put(AttributeKey.doubleKey(key), valueStr.toDoubleOrNull() ?: 0.0)
                "boolean" -> attributesBuilder.put(AttributeKey.booleanKey(key), valueStr.toBooleanStrictOrNull() ?: false)
                else -> attributesBuilder.put(AttributeKey.stringKey(key), valueStr)
            }
        }

        // Parse resource attributes from JSON
        val resourceAttributesBuilder = Attributes.builder()
        val resourceJson = JSONObject(resource)
        resourceJson.keys().forEach { key ->
            val value = resourceJson.getString(key)
            resourceAttributesBuilder.put(AttributeKey.stringKey(key), value)
        }

        // Parse severity
        val parsedSeverity = severityText?.let {
            try {
                Severity.valueOf(it)
            } catch (e: Exception) {
                null
            }
        }

        // Create InstrumentationScopeInfo
        val scopeInfo = InstrumentationScopeInfo.builder(instrumentationScopeName ?: "unknown")
            .apply {
                instrumentationScopeVersion?.let { setVersion(it) }
            }
            .build()

        // Create Resource
        val resourceData = Resource.create(resourceAttributesBuilder.build())

        // Reconstruct SpanContext from persisted traceId/spanId if available
        val spanCtx = if (traceId != null && spanId != null) {
            io.opentelemetry.api.trace.SpanContext.create(
                traceId,
                spanId,
                io.opentelemetry.api.trace.TraceFlags.getSampled(),
                io.opentelemetry.api.trace.TraceState.getDefault()
            )
        } else {
            io.opentelemetry.api.trace.SpanContext.getInvalid()
        }

        val builtAttributes = attributesBuilder.build()

        // Create LogRecordData using builder pattern
        object : LogRecordData {
            override fun getResource(): Resource = resourceData
            override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = scopeInfo
            override fun getTimestampEpochNanos(): Long = timestampMs * 1_000_000
            override fun getObservedTimestampEpochNanos(): Long = timestampMs * 1_000_000
            override fun getSpanContext(): io.opentelemetry.api.trace.SpanContext = spanCtx

            override fun getSeverity(): Severity? = parsedSeverity
            override fun getSeverityText(): String? = entitySeverityText
            override fun getBody(): Body {
                return object : Body {
                    override fun asString(): String {
                        return entityBody
                    }

                    override fun getType(): Body.Type {
                        return Body.Type.STRING
                    }
                }
            }

            override fun getAttributes(): Attributes = builtAttributes
            override fun getTotalAttributeCount(): Int = builtAttributes.size()
        }
    } catch (e: Exception) {
        Log.e("DiskLogBuffer", "Error reconstructing LogRecordData from entity", e)
        null
    }
}
