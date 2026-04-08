/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.util.Log
import androidx.room.*
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
    private val ttlHours: Int
) {
    private val TAG = "DiskLogBuffer"

    private val database: LogDatabase = Room.databaseBuilder(
        context.applicationContext,
        LogDatabase::class.java,
        "otel_log_buffer.db"
    )
        .fallbackToDestructiveMigration()
        .build()
        .also { db ->
            // Pre-warm: open the database connection immediately so the first insert
            // does not incur schema-creation delay (critical for test reliability).
            runBlocking(Dispatchers.IO) { db.openHelper.writableDatabase }
        }

    private val logDao = database.logDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Persists log records to disk.
     *
     * Converts LogRecordData to entities and stores in database.
     * Triggers size check after insertion.
     *
     * @param records List of log records to persist
     */
    fun persistEvents(records: List<LogRecordData>) {
        scope.launch {
            try {
                val entities = records.map { it.toEntity() }
                logDao.insertAll(entities)
                Log.d(TAG, "Persisted ${entities.size} events to disk")

                // Check and enforce size limit
                enforceSizeLimit()
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting events", e)
            }
        }
    }

    /**
     * Persists [BufferedEvent]s to disk, including their monotonic timestamps
     * and boot IDs for clock-skew-safe window queries.
     */
    internal fun persistBufferedEvents(events: List<BufferedEvent>) {
        scope.launch {
            try {
                val entities = events.map { it.toEntity() }
                logDao.insertAll(entities)
                Log.d(TAG, "Persisted ${entities.size} buffered events to disk (with monotonicMs)")
                enforceSizeLimit()
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting buffered events", e)
            }
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
            logDao.deleteEventsByTraceId(traceId)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting events by traceId", e)
            0
        }
    }

    /**
     * Gets the current number of events in disk buffer.
     */
    fun getEventCount(): Int {
        return runBlocking {
            try {
                logDao.getCount()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting event count", e)
                0
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
        val dbFile = context.getDatabasePath("otel_log_buffer.db")
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
     * Enforces the maximum size limit by removing oldest events.
     */
    private suspend fun enforceSizeLimit() {
        try {
            val dbFile = context.getDatabasePath("otel_log_buffer.db")
            if (!dbFile.exists()) return

            val currentSizeMb = dbFile.length() / (1024.0 * 1024.0)

            if (currentSizeMb > maxSizeMb) {
                // Calculate how many events to delete (approximately)
                val excessRatio = (currentSizeMb - maxSizeMb) / currentSizeMb
                val totalCount = logDao.getCount()
                val deleteCount = (totalCount * excessRatio).toInt() + 100 // Add buffer

                logDao.deleteOldest(deleteCount)
                Log.i(TAG, "Size limit enforcement: deleted $deleteCount oldest events (was ${currentSizeMb}MB, limit ${maxSizeMb}MB)")

                // Vacuum to reclaim space
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
        @Volatile
        private var instance: DiskLogBuffer? = null

        fun getInstance(context: Context, maxSizeMb: Int, ttlHours: Int): DiskLogBuffer {
            return instance ?: synchronized(this) {
                instance ?: DiskLogBuffer(context.applicationContext, maxSizeMb, ttlHours).also {
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
                    val dbPath = current.context.getDatabasePath("otel_log_buffer.db")
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
    val bootId: String? = null          // Kernel boot_id; null = pre-migration or cross-boot
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

    @Query("DELETE FROM log_records WHERE id IN (SELECT id FROM log_records ORDER BY timestampMs ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int

    @Query("DELETE FROM log_records")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM log_records WHERE traceId = :traceId ORDER BY timestampMs ASC")
    suspend fun getEventsByTraceId(traceId: String): List<LogRecordEntity>

    @Query("DELETE FROM log_records WHERE traceId = :traceId")
    suspend fun deleteEventsByTraceId(traceId: String): Int

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
@Database(entities = [LogRecordEntity::class], version = 3, exportSchema = false)
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
        bootId = BootTracker.currentBootId
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
