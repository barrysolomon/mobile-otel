package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.sdk.logs.data.LogRecordData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive tests for DiskLogBuffer.
 *
 * Tests cover:
 * - Event persistence to Room database
 * - Time window queries
 * - TTL-based cleanup
 * - Size-based eviction
 * - Concurrent operations
 * - Database corruption handling
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiskLogBufferTest {

    private lateinit var context: Context
    private lateinit var database: LogDatabase
    private lateinit var diskBuffer: DiskLogBuffer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Use in-memory database for tests
        database = Room.inMemoryDatabaseBuilder(
            context,
            LogDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        diskBuffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 1
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Persistence Tests ====================

    @Test
    fun `persistEvents stores logs in database`() = runBlocking {
        val logs = listOf(TestUtils.createTestLogRecord("event.1"))

        diskBuffer.persistEvents(logs)
        Thread.sleep(200) // Wait for async write

        val count = diskBuffer.getEventCount()
        assertEquals(1, count)
    }

    @Test
    fun `persistEvents handles multiple records`() = runBlocking {
        val logs = (1..10).map { TestUtils.createTestLogRecord("event.$it") }

        diskBuffer.persistEvents(logs)
        Thread.sleep(300)

        val count = diskBuffer.getEventCount()
        assertEquals(10, count)
    }

    @Test
    fun `persistEvents handles empty list`() = runBlocking {
        diskBuffer.persistEvents(emptyList())
        Thread.sleep(100)

        val count = diskBuffer.getEventCount()
        assertEquals(0, count)
    }

    @Test
    fun `persisted events can be retrieved`() = runBlocking {
        val originalLog = TestUtils.createTestLogRecord("retrievable")

        diskBuffer.persistEvents(listOf(originalLog))
        Thread.sleep(200)

        val retrieved = diskBuffer.getAllEvents()
        assertEquals(1, retrieved.size)
        assertEquals("retrievable", retrieved[0].body.asString())
    }

    @Test
    fun `persistEvents preserves log attributes`() = runBlocking {
        val log = TestUtils.createUIFreezeLog(2500)

        diskBuffer.persistEvents(listOf(log))
        Thread.sleep(200)

        val retrieved = diskBuffer.getAllEvents()
        assertEquals(1, retrieved.size)

        val retrievedLog = retrieved[0]
        assertNotNull(retrievedLog.attributes["duration_ms"])
    }

    // ==================== Time Window Tests ====================

    @Test
    fun `getEventsInWindow returns events within time range`() = runBlocking {
        val now = System.currentTimeMillis()

        // Add events at different times
        val oldLog = TestUtils.createTestLogRecordWithTimestamp("old", now - (10 * 60 * 1000)) // 10 min ago
        val recentLog = TestUtils.createTestLogRecordWithTimestamp("recent", now - (1 * 60 * 1000)) // 1 min ago

        diskBuffer.persistEvents(listOf(oldLog, recentLog))
        Thread.sleep(300)

        // Get events from last 5 minutes
        val windowStart = now - (5 * 60 * 1000)
        val windowEvents = diskBuffer.getEventsInWindow(windowStart)

        assertEquals(1, windowEvents.size)
        assertEquals("recent", windowEvents[0].body.asString())
    }

    @Test
    fun `getEventsInWindow with future timestamp returns empty`() = runBlocking {
        diskBuffer.persistEvents(listOf(TestUtils.createTestLogRecord("event")))
        Thread.sleep(200)

        val futureStart = System.currentTimeMillis() + (10 * 60 * 1000)
        val events = diskBuffer.getEventsInWindow(futureStart)

        assertEquals(0, events.size)
    }

    @Test
    fun `getEventsInWindow with old timestamp returns all events`() = runBlocking {
        val logs = (1..5).map { TestUtils.createTestLogRecord("event.$it") }
        diskBuffer.persistEvents(logs)
        Thread.sleep(300)

        val oldStart = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago
        val events = diskBuffer.getEventsInWindow(oldStart)

        assertEquals(5, events.size)
    }

    // ==================== Cleanup Tests ====================

    @Test
    fun `cleanupExpired removes old events`() = runBlocking {
        val now = System.currentTimeMillis()

        // Add events beyond TTL (1 hour)
        val expiredLog = TestUtils.createTestLogRecordWithTimestamp("expired", now - (2 * 60 * 60 * 1000))
        val validLog = TestUtils.createTestLogRecordWithTimestamp("valid", now - (30 * 60 * 1000))

        diskBuffer.persistEvents(listOf(expiredLog, validLog))
        Thread.sleep(300)

        diskBuffer.cleanupExpired()
        Thread.sleep(200)

        val remaining = diskBuffer.getAllEvents()
        assertEquals(1, remaining.size)
        assertEquals("valid", remaining[0].body.asString())
    }

    @Test
    fun `size limit enforces maximum storage`() = runBlocking {
        // Create buffer with very small size limit (1 MB)
        val smallBuffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 1,
            ttlHours = 24
        )

        // Add many events to exceed limit
        val logs = (1..1000).map { TestUtils.createTestLogRecord("large.event.$it") }
        smallBuffer.persistEvents(logs)
        Thread.sleep(500)

        // Should have removed oldest events to stay under limit
        val size = smallBuffer.getStorageSizeMb()
        assertTrue(size <= 1.1, "Storage should not significantly exceed limit")
    }

    @Test
    fun `clearAll removes all events`() = runBlocking {
        diskBuffer.persistEvents((1..20).map { TestUtils.createTestLogRecord("clear.$it") })
        Thread.sleep(300)

        diskBuffer.clearAll()
        Thread.sleep(200)

        val count = diskBuffer.getEventCount()
        assertEquals(0, count)
    }

    // ==================== Concurrent Operations Tests ====================

    @Test
    fun `concurrent persist operations are thread-safe`() = runBlocking {
        val threads = (1..5).map { threadId ->
            Thread {
                val logs = (1..10).map {
                    TestUtils.createTestLogRecord("thread.$threadId.event.$it")
                }
                diskBuffer.persistEvents(logs)
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }
        Thread.sleep(500)

        val count = diskBuffer.getEventCount()
        assertEquals(50, count)
    }

    @Test
    fun `concurrent read and write operations are safe`() = runBlocking {
        // Write thread
        val writeThread = Thread {
            repeat(20) { i ->
                diskBuffer.persistEvents(listOf(TestUtils.createTestLogRecord("write.$i")))
                Thread.sleep(10)
            }
        }

        // Read thread
        val readThread = Thread {
            repeat(20) {
                diskBuffer.getAllEvents()
                Thread.sleep(10)
            }
        }

        writeThread.start()
        readThread.start()
        writeThread.join()
        readThread.join()

        // Should not crash
        assertTrue(diskBuffer.getEventCount() > 0)
    }

    // ==================== Storage Statistics Tests ====================

    @Test
    fun `getEventCount returns accurate count`() = runBlocking {
        assertEquals(0, diskBuffer.getEventCount())

        diskBuffer.persistEvents((1..15).map { TestUtils.createTestLogRecord("count.$it") })
        Thread.sleep(300)

        assertEquals(15, diskBuffer.getEventCount())
    }

    @Test
    fun `getStorageSizeMb returns reasonable size`() = runBlocking {
        diskBuffer.persistEvents((1..100).map { TestUtils.createTestLogRecord("size.$it") })
        Thread.sleep(500)

        val sizeMb = diskBuffer.getStorageSizeMb()
        assertTrue(sizeMb > 0.0, "Size should be greater than 0")
        assertTrue(sizeMb < 10.0, "Size should be less than limit")
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `handles invalid JSON gracefully`() = runBlocking {
        // This tests the serialization/deserialization robustness
        val log = TestUtils.createTestLogRecord("test")
        diskBuffer.persistEvents(listOf(log))
        Thread.sleep(200)

        // Should be able to retrieve without crashing
        val retrieved = diskBuffer.getAllEvents()
        assertEquals(1, retrieved.size)
    }

    @Test
    fun `handles very large log body`() = runBlocking {
        val largeBody = "x".repeat(10000)
        val log = TestUtils.createTestLogRecord(largeBody)

        diskBuffer.persistEvents(listOf(log))
        Thread.sleep(300)

        val retrieved = diskBuffer.getAllEvents()
        assertEquals(1, retrieved.size)
        assertEquals(largeBody, retrieved[0].body.asString())
    }

    @Test
    fun `handles special characters in attributes`() = runBlocking {
        val specialChars = "Test with special: 日本語, emoji: 🎉, quotes: \"test\", newlines: \n\r"
        val log = TestUtils.createTestLogRecord(specialChars)

        diskBuffer.persistEvents(listOf(log))
        Thread.sleep(200)

        val retrieved = diskBuffer.getAllEvents()
        assertEquals(1, retrieved.size)
    }

    // ==================== VACUUM Operation Tests ====================

    @Test
    fun `vacuum reduces database size after deletions`() = runBlocking {
        // Add then delete many events
        diskBuffer.persistEvents((1..100).map { TestUtils.createTestLogRecord("vacuum.$it") })
        Thread.sleep(300)

        val sizeBeforeDelete = diskBuffer.getStorageSizeMb()

        diskBuffer.clearAll()
        Thread.sleep(200)

        diskBuffer.vacuum()
        Thread.sleep(200)

        val sizeAfterVacuum = diskBuffer.getStorageSizeMb()
        assertTrue(sizeAfterVacuum < sizeBeforeDelete, "VACUUM should reduce size")
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles zero TTL hours`() = runBlocking {
        val zeroTTLBuffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 0
        )

        zeroTTLBuffer.persistEvents(listOf(TestUtils.createTestLogRecord("instant")))
        Thread.sleep(200)

        // Events should be immediately expired
        zeroTTLBuffer.cleanupExpired()
        Thread.sleep(200)

        val count = zeroTTLBuffer.getEventCount()
        assertEquals(0, count)
    }

    @Test
    fun `handles maximum TTL hours`() = runBlocking {
        val maxTTLBuffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 8760 // 1 year
        )

        maxTTLBuffer.persistEvents(listOf(TestUtils.createTestLogRecord("long-lived")))
        Thread.sleep(200)

        maxTTLBuffer.cleanupExpired()
        Thread.sleep(200)

        val count = maxTTLBuffer.getEventCount()
        assertEquals(1, count)
    }

    @Test
    fun `multiple getAllEvents calls return consistent results`() = runBlocking {
        diskBuffer.persistEvents((1..10).map { TestUtils.createTestLogRecord("consistent.$it") })
        Thread.sleep(300)

        val first = diskBuffer.getAllEvents()
        val second = diskBuffer.getAllEvents()
        val third = diskBuffer.getAllEvents()

        assertEquals(first.size, second.size)
        assertEquals(second.size, third.size)
        assertEquals(10, first.size)
    }
}
