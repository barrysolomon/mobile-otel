package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.util.Log
import androidx.room.*
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
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
    private val context: Context,
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
     * Retrieves events within a time window.
     *
     * @param windowStartMs Start of window in epoch milliseconds
     * @return List of log records within the window
     */
    suspend fun getEventsInWindow(windowStartMs: Long): List<LogRecordData> = withContext(Dispatchers.IO) {
        try {
            val entities = logDao.getEventsAfter(windowStartMs)
            Log.d(TAG, "Retrieved ${entities.size} events from disk for window starting at $windowStartMs")
            entities.map { it.toLogRecordData() }
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
            entities.map { it.toLogRecordData() }
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
    }
}

/**
 * Room entity for persisting log records.
 */
@Entity(tableName = "log_records", indices = [Index("timestampMs")])
data class LogRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val severityText: String?,
    val severityNumber: Int,
    val body: String,
    val attributes: String, // JSON-encoded attributes
    val resource: String,   // JSON-encoded resource attributes
    val instrumentationScopeName: String?,
    val instrumentationScopeVersion: String?
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

    @Query("DELETE FROM log_records WHERE id IN (SELECT id FROM log_records ORDER BY timestampMs ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int

    @Query("DELETE FROM log_records")
    suspend fun deleteAll(): Int
}

/**
 * Room database definition.
 */
@Database(entities = [LogRecordEntity::class], version = 1, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}

/**
 * Extension to convert LogRecordData to LogRecordEntity.
 */
private fun LogRecordData.toEntity(): LogRecordEntity {
    // Serialize attributes to JSON
    val attributesJson = JSONObject().apply {
        attributes.forEach { key, value ->
            when (value.type) {
                io.opentelemetry.api.common.AttributeType.STRING -> put(key.key, value.asString())
                io.opentelemetry.api.common.AttributeType.LONG -> put(key.key, value.asLong())
                io.opentelemetry.api.common.AttributeType.DOUBLE -> put(key.key, value.asDouble())
                io.opentelemetry.api.common.AttributeType.BOOLEAN -> put(key.key, value.asBoolean())
                io.opentelemetry.api.common.AttributeType.STRING_ARRAY -> put(key.key, value.asStringArray()?.joinToString(","))
                io.opentelemetry.api.common.AttributeType.LONG_ARRAY -> put(key.key, value.asLongArray()?.joinToString(","))
                io.opentelemetry.api.common.AttributeType.DOUBLE_ARRAY -> put(key.key, value.asDoubleArray()?.joinToString(","))
                io.opentelemetry.api.common.AttributeType.BOOLEAN_ARRAY -> put(key.key, value.asBooleanArray()?.joinToString(","))
            }
        }
    }.toString()

    // Serialize resource attributes to JSON
    val resourceJson = JSONObject().apply {
        resource.attributes.forEach { key, value ->
            when (value.type) {
                io.opentelemetry.api.common.AttributeType.STRING -> put(key.key, value.asString())
                io.opentelemetry.api.common.AttributeType.LONG -> put(key.key, value.asLong())
                io.opentelemetry.api.common.AttributeType.DOUBLE -> put(key.key, value.asDouble())
                io.opentelemetry.api.common.AttributeType.BOOLEAN -> put(key.key, value.asBoolean())
                else -> put(key.key, value.asString())
            }
        }
    }.toString()

    return LogRecordEntity(
        timestampMs = timestampEpochNanos / 1_000_000,
        severityText = severity?.name,
        severityNumber = severityNumber,
        body = body.asString(),
        attributes = attributesJson,
        resource = resourceJson,
        instrumentationScopeName = instrumentationScopeInfo.name,
        instrumentationScopeVersion = instrumentationScopeInfo.version
    )
}

/**
 * Extension to convert LogRecordEntity to LogRecordData.
 *
 * Reconstructs LogRecordData from stored entity using JSON deserialization.
 */
private fun LogRecordEntity.toLogRecordData(): LogRecordData {
    // Parse attributes from JSON
    val attributesBuilder = Attributes.builder()
    try {
        val attributesObj = JSONObject(attributes)
        attributesObj.keys().forEach { key ->
            val value = attributesObj.get(key)
            when (value) {
                is String -> attributesBuilder.put(key, value)
                is Int -> attributesBuilder.put(key, value.toLong())
                is Long -> attributesBuilder.put(key, value)
                is Double -> attributesBuilder.put(key, value)
                is Boolean -> attributesBuilder.put(key, value)
                else -> attributesBuilder.put(key, value.toString())
            }
        }
    } catch (e: Exception) {
        Log.w("DiskLogBuffer", "Error parsing attributes JSON: ${e.message}")
    }

    // Parse resource from JSON
    val resourceBuilder = Resource.builder()
    try {
        val resourceObj = JSONObject(resource)
        resourceObj.keys().forEach { key ->
            val value = resourceObj.get(key)
            when (value) {
                is String -> resourceBuilder.put(key, value)
                is Int -> resourceBuilder.put(key, value.toLong())
                is Long -> resourceBuilder.put(key, value)
                is Double -> resourceBuilder.put(key, value)
                is Boolean -> resourceBuilder.put(key, value)
                else -> resourceBuilder.put(key, value.toString())
            }
        }
    } catch (e: Exception) {
        Log.w("DiskLogBuffer", "Error parsing resource JSON: ${e.message}")
    }

    // Create reconstructed LogRecordData
    // Note: We use a SimpleLogRecordData implementation since we can't directly instantiate LogRecordData
    return object : LogRecordData {
        override fun getResource() = resourceBuilder.build()
        override fun getInstrumentationScopeInfo() = InstrumentationScopeInfo.create(
            instrumentationScopeName ?: "unknown",
            instrumentationScopeVersion,
            null
        )
        override fun getTimestampEpochNanos() = timestampMs * 1_000_000
        override fun getObservedTimestampEpochNanos() = timestampMs * 1_000_000
        override fun getSpanContext() = io.opentelemetry.api.trace.SpanContext.getInvalid()
        override fun getSeverity() = severityText?.let {
            try { Severity.valueOf(it) } catch (e: Exception) { null }
        }
        override fun getSeverityText() = severityText
        override fun getBody() = io.opentelemetry.api.common.Value.of(body)
        override fun getAttributes() = attributesBuilder.build()
        override fun getTotalAttributeCount() = attributesBuilder.build().size()
    }
}
