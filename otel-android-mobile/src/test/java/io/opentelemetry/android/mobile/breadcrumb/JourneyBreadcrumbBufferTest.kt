/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.breadcrumb

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for [JourneyBreadcrumbBuffer] — FIFO circular buffer for user journey breadcrumbs.
 *
 * Covers: add/addAll, FIFO eviction, toList/takeLast, time-window queries,
 * JSON serialization, type/screen filters, thread safety, and the companion factory.
 */
class JourneyBreadcrumbBufferTest {

    // ─────────────────────────────────────────────────────────────
    // Initial state
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `new buffer is empty`() {
        val buffer = JourneyBreadcrumbBuffer()
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.size())
    }

    @Test
    fun `new buffer has no first or last`() {
        val buffer = JourneyBreadcrumbBuffer()
        assertNull(buffer.first())
        assertNull(buffer.last())
    }

    @Test
    fun `new buffer duration is zero`() {
        val buffer = JourneyBreadcrumbBuffer()
        assertEquals(0L, buffer.duration())
    }

    // ─────────────────────────────────────────────────────────────
    // add / addAll
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `add increases size`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.add(crumb("ScreenA", "tap"))
        assertEquals(1, buffer.size())
        assertFalse(buffer.isEmpty())
    }

    @Test
    fun `addAll adds multiple breadcrumbs`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.addAll(listOf(crumb("A", "tap"), crumb("B", "swipe"), crumb("C", "navigate")))
        assertEquals(3, buffer.size())
    }

    @Test
    fun `toList returns ordered oldest to newest`() {
        val buffer = JourneyBreadcrumbBuffer()
        val c1 = crumb("A", "first", ts = 100L)
        val c2 = crumb("A", "second", ts = 200L)
        val c3 = crumb("A", "third", ts = 300L)
        buffer.addAll(listOf(c1, c2, c3))

        val list = buffer.toList()
        assertEquals(3, list.size)
        assertEquals("first", list[0].action)
        assertEquals("second", list[1].action)
        assertEquals("third", list[2].action)
    }

    // ─────────────────────────────────────────────────────────────
    // FIFO eviction
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `FIFO eviction drops oldest when buffer full`() {
        val buffer = JourneyBreadcrumbBuffer(maxSize = 3)
        buffer.add(crumb("A", "first", ts = 100L))
        buffer.add(crumb("A", "second", ts = 200L))
        buffer.add(crumb("A", "third", ts = 300L))
        buffer.add(crumb("A", "fourth", ts = 400L)) // pushes out "first"

        assertEquals(3, buffer.size())
        assertEquals("second", buffer.first()!!.action)
        assertEquals("fourth", buffer.last()!!.action)
    }

    @Test
    fun `maxSize=1 keeps only the latest breadcrumb`() {
        val buffer = JourneyBreadcrumbBuffer(maxSize = 1)
        buffer.add(crumb("A", "old"))
        buffer.add(crumb("A", "new"))

        assertEquals(1, buffer.size())
        assertEquals("new", buffer.last()!!.action)
    }

    // ─────────────────────────────────────────────────────────────
    // takeLast
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `takeLast returns last N breadcrumbs`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.addAll((1..10).map { crumb("S", "action$it") })

        val last3 = buffer.takeLast(3)
        assertEquals(3, last3.size)
        assertEquals("action8", last3[0].action)
        assertEquals("action10", last3[2].action)
    }

    @Test
    fun `takeLast returns all when count exceeds size`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.addAll(listOf(crumb("S", "a"), crumb("S", "b")))

        val result = buffer.takeLast(100)
        assertEquals(2, result.size)
    }

    // ─────────────────────────────────────────────────────────────
    // Time-window queries
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `getWindow returns breadcrumbs within window`() {
        val buffer = JourneyBreadcrumbBuffer()
        val now = System.currentTimeMillis()

        // Add one breadcrumb 5 minutes ago (outside 2-min window) and one recent
        val old = JourneyBreadcrumb(
            timestamp = now - 300_000L,
            type = BreadcrumbType.NAVIGATION,
            screen = "OldScreen",
            action = "old"
        )
        val recent = JourneyBreadcrumb(
            timestamp = now - 30_000L,
            type = BreadcrumbType.NAVIGATION,
            screen = "NewScreen",
            action = "recent"
        )
        buffer.addAll(listOf(old, recent))

        val window = buffer.getWindow(120_000L) // last 2 minutes
        assertEquals(1, window.size)
        assertEquals("recent", window[0].action)
    }

    @Test
    fun `getWindow returns empty when all breadcrumbs are outside window`() {
        val buffer = JourneyBreadcrumbBuffer()
        val old = JourneyBreadcrumb(
            timestamp = System.currentTimeMillis() - 600_000L,
            type = BreadcrumbType.NAVIGATION,
            screen = "S",
            action = "old"
        )
        buffer.add(old)

        assertTrue(buffer.getWindow(60_000L).isEmpty())
    }

    // ─────────────────────────────────────────────────────────────
    // JSON serialization
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `toJson returns valid JSON array`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.add(JourneyBreadcrumb.navigation("HomeScreen", "screen_enter"))
        buffer.add(JourneyBreadcrumb.userInput("HomeScreen", "tap", "btn_book"))

        val json = buffer.toJson()
        assertTrue("JSON should start with [", json.startsWith("["))
        assertTrue("JSON should end with ]", json.endsWith("]"))
        assertTrue("JSON should contain screen name", json.contains("HomeScreen"))
    }

    @Test
    fun `toJson on empty buffer returns empty array`() {
        val buffer = JourneyBreadcrumbBuffer()
        assertEquals("[]", buffer.toJson())
    }

    // ─────────────────────────────────────────────────────────────
    // first / last / duration
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `first and last return correct breadcrumbs`() {
        val buffer = JourneyBreadcrumbBuffer()
        val c1 = crumb("S", "first", ts = 1000L)
        val c2 = crumb("S", "last", ts = 5000L)
        buffer.addAll(listOf(c1, c2))

        assertEquals("first", buffer.first()!!.action)
        assertEquals("last", buffer.last()!!.action)
    }

    @Test
    fun `duration returns zero for fewer than two breadcrumbs`() {
        val buffer = JourneyBreadcrumbBuffer()
        assertEquals(0L, buffer.duration())

        buffer.add(crumb("S", "only"))
        assertEquals(0L, buffer.duration())
    }

    @Test
    fun `duration returns time span between first and last`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.add(crumb("S", "start", ts = 1000L))
        buffer.add(crumb("S", "mid", ts = 3000L))
        buffer.add(crumb("S", "end", ts = 6000L))

        assertEquals(5000L, buffer.duration())
    }

    // ─────────────────────────────────────────────────────────────
    // clear
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `clear empties the buffer`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.addAll(listOf(crumb("A", "x"), crumb("B", "y")))
        assertFalse(buffer.isEmpty())

        buffer.clear()
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.size())
        assertNull(buffer.first())
        assertNull(buffer.last())
    }

    // ─────────────────────────────────────────────────────────────
    // filterByType / filterByScreen
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `filterByType returns only matching breadcrumbs`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.add(JourneyBreadcrumb.navigation("S", "enter"))
        buffer.add(JourneyBreadcrumb.userInput("S", "tap"))
        buffer.add(JourneyBreadcrumb.network("S", "GET", "https://api.example.com/data"))
        buffer.add(JourneyBreadcrumb.navigation("S", "exit"))

        val navCrumbs = buffer.filterByType(BreadcrumbType.NAVIGATION)
        assertEquals(2, navCrumbs.size)
        assertTrue(navCrumbs.all { it.type == BreadcrumbType.NAVIGATION })

        val inputCrumbs = buffer.filterByType(BreadcrumbType.USER_INPUT)
        assertEquals(1, inputCrumbs.size)
    }

    @Test
    fun `filterByScreen returns only breadcrumbs for that screen`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.add(crumb("HomeScreen", "tap"))
        buffer.add(crumb("DetailScreen", "swipe"))
        buffer.add(crumb("HomeScreen", "scroll"))

        val homeCrumbs = buffer.filterByScreen("HomeScreen")
        assertEquals(2, homeCrumbs.size)
        assertTrue(homeCrumbs.all { it.screen == "HomeScreen" })
    }

    // ─────────────────────────────────────────────────────────────
    // summary
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `summary on empty buffer returns no-breadcrumbs message`() {
        val buffer = JourneyBreadcrumbBuffer()
        assertEquals("No breadcrumbs", buffer.summary())
    }

    @Test
    fun `summary includes count and type breakdown`() {
        val buffer = JourneyBreadcrumbBuffer()
        buffer.add(JourneyBreadcrumb.navigation("S", "enter"))
        buffer.add(JourneyBreadcrumb.userInput("S", "tap"))

        val summary = buffer.summary()
        assertTrue(summary.contains("Breadcrumbs: 2"))
        assertTrue(summary.contains("NAVIGATION=1"))
        assertTrue(summary.contains("USER_INPUT=1"))
    }

    // ─────────────────────────────────────────────────────────────
    // Companion factory
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `create factory builds buffer with correct maxSize`() {
        val buffer = JourneyBreadcrumbBuffer.create(maxSize = 10)
        repeat(15) { buffer.add(crumb("S", "action$it")) }
        assertEquals(10, buffer.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create with zero maxSize throws`() {
        JourneyBreadcrumbBuffer.create(maxSize = 0)
    }

    // ─────────────────────────────────────────────────────────────
    // Thread safety
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `concurrent adds are thread-safe and do not exceed maxSize`() {
        val maxSize = 50
        val buffer = JourneyBreadcrumbBuffer(maxSize = maxSize)
        val threadCount = 10
        val addsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { t ->
            executor.submit {
                repeat(addsPerThread) { i ->
                    buffer.add(crumb("Thread$t", "action$i"))
                }
                latch.countDown()
            }
        }

        assertTrue("Threads did not finish in time", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue("Buffer should not exceed maxSize", buffer.size() <= maxSize)
    }

    @Test
    fun `concurrent reads and writes do not throw`() {
        val buffer = JourneyBreadcrumbBuffer(maxSize = 100)
        val latch = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        var readException: Exception? = null

        executor.submit {
            repeat(500) { buffer.add(crumb("S", "write$it")) }
            latch.countDown()
        }
        executor.submit {
            try {
                repeat(500) {
                    buffer.toList()
                    buffer.size()
                }
            } catch (e: Exception) {
                readException = e
            }
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertNull("Concurrent read/write should not throw", readException)
    }

    // ─────────────────────────────────────────────────────────────
    // JourneyBreadcrumb factory methods
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `navigation breadcrumb includes route in attributes`() {
        val crumb = JourneyBreadcrumb.navigation("HomeScreen", "navigate", route = "/home")
        assertEquals(BreadcrumbType.NAVIGATION, crumb.type)
        assertEquals("/home", crumb.attributes["route"])
    }

    @Test
    fun `network breadcrumb includes http attributes`() {
        val crumb = JourneyBreadcrumb.network("BookScreen", "POST", "https://api.example.com/book", 201)
        assertEquals(BreadcrumbType.NETWORK, crumb.type)
        assertEquals("POST", crumb.attributes["http.method"])
        assertEquals("https://api.example.com/book", crumb.attributes["http.url"])
        assertEquals("201", crumb.attributes["http.status_code"])
    }

    @Test
    fun `error breadcrumb includes error attributes`() {
        val crumb = JourneyBreadcrumb.error("CrashScreen", "NullPointerException", "msg")
        assertEquals(BreadcrumbType.ERROR, crumb.type)
        assertEquals("NullPointerException", crumb.attributes["exception.type"])
        assertEquals("msg", crumb.attributes["exception.message"])
    }

    @Test
    fun `durationTo returns time difference between breadcrumbs`() {
        val c1 = crumb("S", "a", ts = 1000L)
        val c2 = crumb("S", "b", ts = 3500L)
        assertEquals(2500L, c1.durationTo(c2))
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun crumb(
        screen: String,
        action: String,
        ts: Long = System.currentTimeMillis(),
        type: BreadcrumbType = BreadcrumbType.USER_INPUT
    ) = JourneyBreadcrumb(
        timestamp = ts,
        type = type,
        screen = screen,
        action = action
    )
}
