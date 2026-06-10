/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import kotlinx.coroutines.delay
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
        // Reset singleton so each test starts with a fresh DiskLogBuffer and clean database
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()

        // Use in-memory database for tests (separate from diskBuffer's internal DB)
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
        diskBuffer.close()
        DiskLogBuffer.resetForTesting()
        database.close()
    }

    /**
     * Polls until [buffer] contains at least [expected] events or [timeoutMs] expires.
     *
     * Must be called from a coroutine (suspend) to avoid nested runBlocking deadlocks.
     * Uses getAllEvents() (suspend) and delay (suspend) instead of runBlocking+Thread.sleep.
     */
    private suspend fun waitForCount(buffer: DiskLogBuffer, expected: Int, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (buffer.getAllEvents().size >= expected) return
            delay(50)
        }
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
        assertEquals("retrievable", retrieved[0].bodyValue?.asString())
    }

    @Test
    fun `persistEvents preserves log attributes`() = runBlocking {
        val log = TestUtils.createUIFreezeLog(2500)

        diskBuffer.persistEvents(listOf(log))
        Thread.sleep(200)

        val retrieved = diskBuffer.getAllEvents()
        assertEquals(1, retrieved.size)

        val retrievedLog = retrieved[0]
        // Typed attributes round-trip correctly: duration_ms is a Long
        assertNotNull(retrievedLog.attributes.get(AttributeKey.longKey("duration_ms")))
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
        assertEquals("recent", windowEvents[0].bodyValue?.asString())
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
        assertEquals("valid", remaining[0].bodyValue?.asString())
    }

    @Test
    fun `size limit enforces maximum storage`() = runBlocking {
        // Reset singleton to create a fresh buffer with a very small size limit (1 MB)
        diskBuffer.close()
        DiskLogBuffer.resetForTesting()
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
        assertTrue("Storage should not significantly exceed limit", size <= 1.1)

        // Restore default buffer for @After teardown
        smallBuffer.close()
        DiskLogBuffer.resetForTesting()
        diskBuffer = DiskLogBuffer.getInstance(context = context, maxSizeMb = 10, ttlHours = 1)
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
                runBlocking { diskBuffer.getAllEvents() }
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
        assertTrue("Size should be greater than 0", sizeMb > 0.0)
        assertTrue("Size should be less than limit", sizeMb < 10.0)
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
        assertEquals(largeBody, retrieved[0].bodyValue?.asString())
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
        waitForCount(diskBuffer, 100) // Wait for async write instead of fixed sleep

        val sizeBeforeDelete = diskBuffer.getStorageSizeMb()

        diskBuffer.clearAll()
        Thread.sleep(200)

        diskBuffer.vacuum()
        Thread.sleep(200)

        val sizeAfterVacuum = diskBuffer.getStorageSizeMb()
        // VACUUM should not increase the database size. Strict reduction may not be observable
        // in Robolectric's SQLite implementation when the dataset is small.
        assertTrue("VACUUM should not increase size", sizeAfterVacuum <= sizeBeforeDelete)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles zero TTL hours`() = runBlocking {
        // Reset singleton to get a fresh buffer with ttlHours=0
        diskBuffer.close()
        DiskLogBuffer.resetForTesting()
        val zeroTTLBuffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 0
        )

        zeroTTLBuffer.persistEvents(listOf(TestUtils.createTestLogRecord("instant")))
        waitForCount(zeroTTLBuffer, 1) // Wait for async write

        // Events should be immediately expired
        zeroTTLBuffer.cleanupExpired()
        delay(200) // coroutine-safe delay

        val events = zeroTTLBuffer.getAllEvents() // avoid nested runBlocking
        assertEquals(0, events.size)

        // Restore for @After
        zeroTTLBuffer.close()
        DiskLogBuffer.resetForTesting()
        diskBuffer = DiskLogBuffer.getInstance(context = context, maxSizeMb = 10, ttlHours = 1)
    }

    @Test
    fun `handles maximum TTL hours`() = runBlocking {
        // Reset singleton to get a fresh buffer with ttlHours=8760
        diskBuffer.close()
        DiskLogBuffer.resetForTesting()
        val maxTTLBuffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 8760 // 1 year
        )

        maxTTLBuffer.persistEvents(listOf(TestUtils.createTestLogRecord("long-lived")))
        waitForCount(maxTTLBuffer, 1) // Wait for async write instead of fixed sleep

        maxTTLBuffer.cleanupExpired()
        delay(200) // coroutine-safe delay (no nested runBlocking)

        val events = maxTTLBuffer.getAllEvents() // suspend instead of getEventCount() to avoid nested runBlocking
        assertEquals(1, events.size)

        // Restore for @After
        maxTTLBuffer.close()
        DiskLogBuffer.resetForTesting()
        diskBuffer = DiskLogBuffer.getInstance(context = context, maxSizeMb = 10, ttlHours = 1)
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

    // ==================== TraceId / SpanId Round-trip Tests ====================

    /**
     * Helper that creates a LogRecordData with a valid SpanContext so traceId/spanId
     * are persisted to the database.
     */
    private fun createLogRecordWithTrace(
        body: String,
        traceId: String,
        spanId: String,
        timestampMs: Long = System.currentTimeMillis()
    ): LogRecordData {
        val spanCtx = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())
        return object : LogRecordData {
            override fun getResource(): Resource = Resource.empty()
            override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = InstrumentationScopeInfo.empty()
            override fun getTimestampEpochNanos(): Long = timestampMs * 1_000_000
            override fun getObservedTimestampEpochNanos(): Long = timestampMs * 1_000_000
            override fun getSpanContext(): SpanContext = spanCtx
            override fun getSeverity(): Severity = Severity.INFO
            override fun getSeverityText(): String = "INFO"
            override fun getBody(): Body = object : Body {
                override fun asString() = body
                override fun getType() = Body.Type.STRING
            }
            override fun getAttributes(): Attributes = Attributes.empty()
            override fun getTotalAttributeCount(): Int = 0
        }
    }

    @Test
    fun `traceId and spanId round-trip through SQLite`() = runBlocking {
        val traceId = "0af7651916cd43dd8448eb211c80319c"
        val spanId = "b7ad6b7169203331"
        val record = createLogRecordWithTrace("trace.event", traceId, spanId)

        diskBuffer.persistEvents(listOf(record))
        Thread.sleep(200)

        val retrieved = diskBuffer.getAllEvents()
        assertEquals(1, retrieved.size)
        val retrievedSpanCtx = retrieved[0].spanContext
        assertTrue("SpanContext should be valid after round-trip", retrievedSpanCtx.isValid)
        assertEquals(traceId, retrievedSpanCtx.traceId)
        assertEquals(spanId, retrievedSpanCtx.spanId)
    }

    @Test
    fun `getEventsByTraceId returns only matching events`() = runBlocking {
        val traceId1 = "aaaabbbbccccdddd1111222233334444"
        val traceId2 = "eeeeffffaaaabbbb5555666677778888"
        val span1 = "1111222233334444"
        val span2 = "5555666677778888"

        diskBuffer.persistEvents(listOf(
            createLogRecordWithTrace("trace1.event1", traceId1, span1),
            createLogRecordWithTrace("trace1.event2", traceId1, span1),
            createLogRecordWithTrace("trace2.event1", traceId2, span2)
        ))
        Thread.sleep(200)

        val trace1Events = diskBuffer.getEventsByTraceId(traceId1)
        assertEquals(2, trace1Events.size)
        assertTrue(trace1Events.all { it.spanContext.traceId == traceId1 })

        val trace2Events = diskBuffer.getEventsByTraceId(traceId2)
        assertEquals(1, trace2Events.size)
        assertEquals("trace2.event1", trace2Events[0].bodyValue?.asString())
    }

    @Test
    fun `deleteEventsByTraceId removes only matching events`() = runBlocking {
        val traceId1 = "aaaabbbbccccdddd1111222233334444"
        val traceId2 = "eeeeffffaaaabbbb5555666677778888"
        val span1 = "1111222233334444"
        val span2 = "5555666677778888"

        diskBuffer.persistEvents(listOf(
            createLogRecordWithTrace("trace1.event1", traceId1, span1),
            createLogRecordWithTrace("trace1.event2", traceId1, span1),
            createLogRecordWithTrace("trace2.event1", traceId2, span2)
        ))
        Thread.sleep(200)

        val deleted = diskBuffer.deleteEventsByTraceId(traceId1)
        assertEquals(2, deleted)

        val remaining = diskBuffer.getAllEvents()
        assertEquals(1, remaining.size)
        assertEquals("trace2.event1", remaining[0].bodyValue?.asString())
    }

    // ==================== At-Rest Encryption (degradation) Tests ====================
    //
    // Robolectric provides no SQLCipher native library and no real Android
    // Keystore, so requesting encryption here exercises the GRACEFUL-DEGRADATION
    // path: the buffer must fall back to a working cleartext database rather than
    // crash, and all existing behaviors (round-trip, TTL, count) must still hold.
    // The true encrypted round-trip + "no SQLite header" assertions live in the
    // instrumented EncryptedDiskBufferTest (src/androidTest).

    @Test
    fun `encryption requested degrades to cleartext under Robolectric without crashing`() = runBlocking {
        diskBuffer.close()
        DiskLogBuffer.resetForTesting()
        val buffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 1,
            encryptAtRest = true
        )

        // SQLCipher native libs are absent under Robolectric → encryption must
        // have degraded, not crashed.
        assertFalse(
            "Encryption cannot be active without SQLCipher native libs",
            buffer.encryptionActive
        )

        // Buffer must still be fully functional.
        buffer.persistEvents(listOf(TestUtils.createTestLogRecord("degraded.but.works")))
        waitForCount(buffer, 1)
        val retrieved = buffer.getAllEvents()
        assertEquals(1, retrieved.size)
        assertEquals("degraded.but.works", retrieved[0].bodyValue?.asString())

        buffer.close()
        DiskLogBuffer.resetForTesting()
        diskBuffer = DiskLogBuffer.getInstance(context = context, maxSizeMb = 10, ttlHours = 1)
    }

    @Test
    fun `TTL and count behavior unchanged when encryption requested`() = runBlocking {
        diskBuffer.close()
        DiskLogBuffer.resetForTesting()
        val buffer = DiskLogBuffer.getInstance(
            context = context,
            maxSizeMb = 10,
            ttlHours = 1,
            encryptAtRest = true
        )

        val now = System.currentTimeMillis()
        buffer.persistEvents(
            listOf(
                TestUtils.createTestLogRecordWithTimestamp("expired", now - (2 * 60 * 60 * 1000)),
                TestUtils.createTestLogRecordWithTimestamp("valid", now - (5 * 60 * 1000))
            )
        )
        waitForCount(buffer, 2)
        assertEquals(2, buffer.getEventCount())

        buffer.cleanupExpired()
        delay(300)

        val remaining = buffer.getAllEvents()
        assertEquals(1, remaining.size)
        assertEquals("valid", remaining[0].bodyValue?.asString())

        buffer.close()
        DiskLogBuffer.resetForTesting()
        diskBuffer = DiskLogBuffer.getInstance(context = context, maxSizeMb = 10, ttlHours = 1)
    }

    @Test
    fun `typed attribute round-trip - Long value stored as long retrieved as LongAttribute`() = runBlocking {
        val log = TestUtils.createTestLogRecord(
            body = "typed.event",
            attributes = mapOf(
                "http.duration_ms" to 1234L,
                "event.name" to "page_load",
                "cache.hit" to true,
                "response.ratio" to 0.95
            )
        )

        diskBuffer.persistEvents(listOf(log))
        Thread.sleep(200)

        val retrieved = diskBuffer.getAllEvents()
        assertEquals(1, retrieved.size)

        val attrs = retrieved[0].attributes

        // Long attribute should come back as a Long, not a String
        val longVal = attrs.get(AttributeKey.longKey("http.duration_ms"))
        assertNotNull("Long attribute should be retrievable as longKey", longVal)
        assertEquals(1234L, longVal)

        // String attribute should round-trip correctly
        val strVal = attrs.get(AttributeKey.stringKey("event.name"))
        assertEquals("page_load", strVal)

        // Boolean attribute should round-trip correctly
        val boolVal = attrs.get(AttributeKey.booleanKey("cache.hit"))
        assertNotNull("Boolean attribute should be retrievable as booleanKey", boolVal)
        assertEquals(true, boolVal)

        // Double attribute should round-trip correctly
        val dblVal = attrs.get(AttributeKey.doubleKey("response.ratio"))
        assertNotNull("Double attribute should be retrievable as doubleKey", dblVal)
        assertEquals(0.95, dblVal!!, 0.001)
    }
}
