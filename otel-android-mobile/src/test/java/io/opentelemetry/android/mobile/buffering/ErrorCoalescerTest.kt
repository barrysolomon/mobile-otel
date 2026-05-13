/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.logs.Severity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD tests for ErrorCoalescer (Phase 3 of Offline Flush Budget epic).
 *
 * Validates that identical errors within a time window are coalesced
 * into a single entry with a count, reducing noise in offline scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorCoalescerTest {

    private lateinit var coalescer: ErrorCoalescer

    @Before
    fun setup() {
        coalescer = ErrorCoalescer(windowMs = 60_000L)
    }

    // ==================== Basic Coalescing ====================

    @Test
    fun `first error is not coalesced`() {
        val record = TestUtils.createTestLogRecord(
            body = "app.crash",
            attributes = mapOf(
                "exception.type" to "NullPointerException",
                "exception.message" to "null reference"
            ),
            severity = Severity.ERROR
        )

        val suppressed = coalescer.tryCoalesce(record)
        assertFalse("First occurrence should not be suppressed", suppressed)
    }

    @Test
    fun `duplicate error within window is coalesced`() {
        val record1 = TestUtils.createTestLogRecord(
            body = "app.crash",
            attributes = mapOf(
                "exception.type" to "NullPointerException",
                "exception.message" to "null reference"
            ),
            severity = Severity.ERROR
        )
        val record2 = TestUtils.createTestLogRecord(
            body = "app.crash",
            attributes = mapOf(
                "exception.type" to "NullPointerException",
                "exception.message" to "null reference"
            ),
            severity = Severity.ERROR
        )

        assertFalse(coalescer.tryCoalesce(record1))
        assertTrue("Duplicate should be suppressed", coalescer.tryCoalesce(record2))
    }

    @Test
    fun `different exception types are not coalesced`() {
        val record1 = TestUtils.createTestLogRecord(
            body = "crash1",
            attributes = mapOf(
                "exception.type" to "NullPointerException",
                "exception.message" to "null"
            ),
            severity = Severity.ERROR
        )
        val record2 = TestUtils.createTestLogRecord(
            body = "crash2",
            attributes = mapOf(
                "exception.type" to "IllegalStateException",
                "exception.message" to "bad state"
            ),
            severity = Severity.ERROR
        )

        assertFalse(coalescer.tryCoalesce(record1))
        assertFalse("Different exception should not coalesce", coalescer.tryCoalesce(record2))
        assertEquals(2, coalescer.activeGroupCount())
    }

    @Test
    fun `same exception type with different message are not coalesced`() {
        val record1 = TestUtils.createTestLogRecord(
            body = "crash",
            attributes = mapOf(
                "exception.type" to "NullPointerException",
                "exception.message" to "on field A"
            ),
            severity = Severity.ERROR
        )
        val record2 = TestUtils.createTestLogRecord(
            body = "crash",
            attributes = mapOf(
                "exception.type" to "NullPointerException",
                "exception.message" to "on field B"
            ),
            severity = Severity.ERROR
        )

        assertFalse(coalescer.tryCoalesce(record1))
        assertFalse("Different message should not coalesce", coalescer.tryCoalesce(record2))
    }

    // ==================== Severity Filtering ====================

    @Test
    fun `INFO severity is not eligible for coalescing`() {
        val record = TestUtils.createTestLogRecord(
            body = "ui.tap",
            severity = Severity.INFO
        )

        assertFalse(coalescer.tryCoalesce(record))
        assertEquals(0, coalescer.activeGroupCount())
    }

    @Test
    fun `WARN severity is not eligible by default`() {
        val record = TestUtils.createTestLogRecord(
            body = "slow.operation",
            severity = Severity.WARN
        )

        assertFalse(coalescer.tryCoalesce(record))
        assertEquals(0, coalescer.activeGroupCount())
    }

    @Test
    fun `custom minSeverity allows WARN coalescing`() {
        val warnCoalescer = ErrorCoalescer(windowMs = 60_000L, minSeverity = Severity.WARN)

        val record1 = TestUtils.createTestLogRecord(
            body = "slow.op",
            severity = Severity.WARN
        )
        val record2 = TestUtils.createTestLogRecord(
            body = "slow.op",
            severity = Severity.WARN
        )

        assertFalse(warnCoalescer.tryCoalesce(record1))
        assertTrue("WARN should coalesce with lower minSeverity", warnCoalescer.tryCoalesce(record2))
    }

    // ==================== Count Tracking ====================

    @Test
    fun `count increments with each duplicate`() {
        val makeRecord = {
            TestUtils.createTestLogRecord(
                body = "network.timeout",
                attributes = mapOf(
                    "exception.type" to "SocketTimeoutException",
                    "exception.message" to "connect timed out"
                ),
                severity = Severity.ERROR
            )
        }

        coalescer.tryCoalesce(makeRecord())
        coalescer.tryCoalesce(makeRecord())
        coalescer.tryCoalesce(makeRecord())
        coalescer.tryCoalesce(makeRecord())
        coalescer.tryCoalesce(makeRecord())

        assertEquals(5, coalescer.getCount(makeRecord()))
    }

    @Test
    fun `drainCoalesced returns entries with count gt 1`() {
        val makeRecord = {
            TestUtils.createTestLogRecord(
                body = "crash",
                attributes = mapOf(
                    "exception.type" to "OOMError",
                    "exception.message" to "out of memory"
                ),
                severity = Severity.ERROR
            )
        }

        coalescer.tryCoalesce(makeRecord())
        coalescer.tryCoalesce(makeRecord())
        coalescer.tryCoalesce(makeRecord())

        val drained = coalescer.drainCoalesced()
        assertEquals(1, drained.size)
        assertEquals(3, drained[0].count)
        assertEquals("OOMError", drained[0].firstRecord.attributes.get(
            io.opentelemetry.api.common.AttributeKey.stringKey("exception.type")
        ))
    }

    @Test
    fun `drainCoalesced clears drained entries`() {
        val makeRecord = {
            TestUtils.createTestLogRecord(
                body = "crash",
                attributes = mapOf("exception.type" to "Error", "exception.message" to "x"),
                severity = Severity.ERROR
            )
        }

        coalescer.tryCoalesce(makeRecord())
        coalescer.tryCoalesce(makeRecord())

        coalescer.drainCoalesced()
        val secondDrain = coalescer.drainCoalesced()
        assertTrue("Drained entries should be cleared", secondDrain.isEmpty())
    }

    // ==================== Body-Based Coalescing ====================

    @Test
    fun `errors without exception attrs coalesce on body`() {
        val record1 = TestUtils.createTestLogRecord(
            body = "http.error",
            attributes = mapOf("http.status_code" to 503),
            severity = Severity.ERROR
        )
        val record2 = TestUtils.createTestLogRecord(
            body = "http.error",
            attributes = mapOf("http.status_code" to 503),
            severity = Severity.ERROR
        )

        assertFalse(coalescer.tryCoalesce(record1))
        assertTrue("Same body should coalesce", coalescer.tryCoalesce(record2))
    }

    // ==================== Tuple keying (architecture-hardening epic) ====================
    //
    // Locked in after the 2026-05-12 misdiagnosis. The previous body|<body>
    // fallback collapsed two http.error records to different URLs as if they
    // were duplicates. The new key shape distinguishes signal class +
    // identifying attributes. See docs/contracts/error-coalescer.md.

    @Test
    fun `http_error to DIFFERENT status codes are NOT coalesced`() {
        val r503 = TestUtils.createTestLogRecord(
            body = "http.error",
            attributes = mapOf(
                "event.name" to "http.error",
                "http.response.status_code" to 503L,
                "url.full" to "http://a/api"
            ),
            severity = Severity.ERROR
        )
        val r500 = TestUtils.createTestLogRecord(
            body = "http.error",
            attributes = mapOf(
                "event.name" to "http.error",
                "http.response.status_code" to 500L,
                "url.full" to "http://a/api"
            ),
            severity = Severity.ERROR
        )
        assertFalse(coalescer.tryCoalesce(r503))
        assertFalse(
            "different status on same URL must not collapse",
            coalescer.tryCoalesce(r500)
        )
    }

    @Test
    fun `http_error to DIFFERENT URLs are NOT coalesced`() {
        val rA = TestUtils.createTestLogRecord(
            body = "http.error",
            attributes = mapOf(
                "event.name" to "http.error",
                "http.response.status_code" to 503L,
                "url.full" to "http://a/api"
            ),
            severity = Severity.ERROR
        )
        val rB = TestUtils.createTestLogRecord(
            body = "http.error",
            attributes = mapOf(
                "event.name" to "http.error",
                "http.response.status_code" to 503L,
                "url.full" to "http://b/api"
            ),
            severity = Severity.ERROR
        )
        assertFalse(coalescer.tryCoalesce(rA))
        assertFalse(
            "different URL on same status must not collapse",
            coalescer.tryCoalesce(rB)
        )
    }

    @Test
    fun `identical http_error tuple IS coalesced`() {
        val attrs = mapOf(
            "event.name" to "http.error",
            "http.response.status_code" to 503L,
            "url.full" to "http://a/api"
        )
        val r1 = TestUtils.createTestLogRecord(body = "http.error", attributes = attrs, severity = Severity.ERROR)
        val r2 = TestUtils.createTestLogRecord(body = "http.error", attributes = attrs, severity = Severity.ERROR)
        assertFalse(coalescer.tryCoalesce(r1))
        assertTrue(
            "genuine duplicate (same status, same URL) should still collapse",
            coalescer.tryCoalesce(r2)
        )
    }

    @Test
    fun `structured event with event_name but no exception bypasses coalescer`() {
        val r1 = TestUtils.createTestLogRecord(
            body = "ui.tap",
            attributes = mapOf("event.name" to "ui.tap", "x" to "10"),
            severity = Severity.ERROR
        )
        val r2 = TestUtils.createTestLogRecord(
            body = "ui.tap",
            attributes = mapOf("event.name" to "ui.tap", "x" to "200"),
            severity = Severity.ERROR
        )
        assertFalse(coalescer.tryCoalesce(r1))
        assertFalse(
            "structured signals must not collapse on body alone",
            coalescer.tryCoalesce(r2)
        )
    }

    // ==================== Clear ====================

    @Test
    fun `clear resets all tracked entries`() {
        val record = TestUtils.createTestLogRecord(
            body = "crash",
            attributes = mapOf("exception.type" to "Error", "exception.message" to "x"),
            severity = Severity.ERROR
        )

        coalescer.tryCoalesce(record)
        coalescer.tryCoalesce(record)
        assertEquals(1, coalescer.activeGroupCount())

        coalescer.clear()
        assertEquals(0, coalescer.activeGroupCount())
    }
}
