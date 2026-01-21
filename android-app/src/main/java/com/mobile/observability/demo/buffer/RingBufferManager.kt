package com.mobile.observability.demo.buffer

import android.util.Log
import com.google.gson.Gson
import com.mobile.observability.demo.data.EventDao
import com.mobile.observability.demo.data.EventEntity
import com.mobile.observability.demo.workflow.Event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

class RingBufferManager(
    private val eventDao: EventDao,
    private val sessionId: String,
    private val deviceId: String,
    private val configVersion: Int,
    private val maxRamEvents: Int = 5000,
    private val maxDiskMb: Int = 50,
    private val retentionHours: Int = 24
) {
    private val ramBuffer = ConcurrentLinkedQueue<EventEntity>()
    private val gson = Gson()
    private val mutex = Mutex()

    suspend fun addEvent(event: Event, triggerId: String? = null) {
        val entity = EventEntity(
            eventName = event.eventName,
            sessionId = sessionId,
            deviceId = deviceId,
            triggerId = triggerId,
            configVersion = configVersion,
            timestamp = event.timestamp,
            attributesJson = gson.toJson(event.attributes)
        )

        // Add to RAM buffer
        ramBuffer.offer(entity)

        // Check RAM buffer size and flush to disk if needed
        if (ramBuffer.size >= maxRamEvents) {
            flushToDisk()
        }
    }

    suspend fun flushToDisk() = mutex.withLock {
        if (ramBuffer.isEmpty()) return

        val eventsToFlush = mutableListOf<EventEntity>()
        while (ramBuffer.isNotEmpty()) {
            ramBuffer.poll()?.let { eventsToFlush.add(it) }
        }

        // Insert into database
        for (event in eventsToFlush) {
            eventDao.insert(event)
        }

        Log.d(TAG, "Flushed ${eventsToFlush.size} events from RAM to disk")

        // Enforce disk limits
        enforceDiskLimits()
    }

    private suspend fun enforceDiskLimits() {
        // Remove old events beyond retention period
        val cutoffTime = System.currentTimeMillis() - (retentionHours * 3600 * 1000L)
        eventDao.deleteOlderThan(cutoffTime)

        // Check disk size and evict oldest if needed
        val sizeBytes = eventDao.getApproximateSizeBytes() ?: 0
        val sizeMb = sizeBytes / (1024 * 1024)

        if (sizeMb > maxDiskMb) {
            val eventsToDelete = ((sizeMb - maxDiskMb) * 1024 * 1024 / 1000).toInt()
            eventDao.deleteOldest(min(eventsToDelete, 1000))
            Log.d(TAG, "Evicted ~$eventsToDelete oldest events to maintain disk limit")
        }
    }

    suspend fun getEventsForFlush(
        windowMinutes: Int,
        scope: String,
        targetSessionId: String = sessionId
    ): List<EventEntity> {
        // Flush RAM to disk first
        flushToDisk()

        val startTimestamp = System.currentTimeMillis() - (windowMinutes * 60 * 1000L)

        return when (scope) {
            "session" -> eventDao.getEventsInWindow(targetSessionId, startTimestamp)
            "device" -> eventDao.getEventsInWindowAllSessions(startTimestamp)
            else -> {
                Log.w(TAG, "Unknown scope: $scope, defaulting to session")
                eventDao.getEventsInWindow(targetSessionId, startTimestamp)
            }
        }
    }

    suspend fun markEventsFlushed(eventIds: List<Long>) {
        if (eventIds.isNotEmpty()) {
            eventDao.markAsFlushed(eventIds)
            Log.d(TAG, "Marked ${eventIds.size} events as flushed")
        }
    }

    suspend fun getBufferStats(): BufferStats {
        flushToDisk() // Ensure RAM is synced to disk first

        val totalCount = eventDao.getTotalCount()
        val unflushedCount = eventDao.getUnflushedCount()
        val sizeBytes = eventDao.getApproximateSizeBytes() ?: 0
        val sizeMb = sizeBytes.toDouble() / (1024 * 1024)

        return BufferStats(
            ramEvents = ramBuffer.size,
            diskEvents = totalCount,
            unflushedEvents = unflushedCount,
            diskSizeMb = sizeMb
        )
    }

    suspend fun cleanup() {
        // Clean up old flushed events
        val cutoffTime = System.currentTimeMillis() - (retentionHours * 3600 * 1000L)
        eventDao.deleteFlushedOlderThan(cutoffTime)
    }

    companion object {
        private const val TAG = "RingBufferManager"
    }
}

data class BufferStats(
    val ramEvents: Int,
    val diskEvents: Int,
    val unflushedEvents: Int,
    val diskSizeMb: Double
)
