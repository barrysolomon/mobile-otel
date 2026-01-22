package com.mobile.observability.demo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE flushed = 0 ORDER BY timestamp DESC")
    suspend fun getUnflushedEvents(): List<EventEntity>

    @Query("""
        SELECT * FROM events
        WHERE session_id = :sessionId
          AND timestamp >= :startTimestamp
          AND flushed = 0
        ORDER BY timestamp ASC
    """)
    suspend fun getEventsInWindow(sessionId: String, startTimestamp: Long): List<EventEntity>

    @Query("""
        SELECT * FROM events
        WHERE timestamp >= :startTimestamp
          AND flushed = 0
        ORDER BY timestamp ASC
    """)
    suspend fun getEventsInWindowAllSessions(startTimestamp: Long): List<EventEntity>

    @Query("UPDATE events SET flushed = 1 WHERE id IN (:eventIds)")
    suspend fun markAsFlushed(eventIds: List<Long>)

    @Query("SELECT COUNT(*) FROM events WHERE flushed = 0")
    suspend fun getUnflushedCount(): Int

    @Query("SELECT COUNT(*) FROM events")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM events WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("DELETE FROM events WHERE flushed = 1 AND timestamp < :timestamp")
    suspend fun deleteFlushedOlderThan(timestamp: Long)

    @Query("SELECT SUM(LENGTH(attributes_json)) FROM events")
    suspend fun getApproximateSizeBytes(): Long?
}
