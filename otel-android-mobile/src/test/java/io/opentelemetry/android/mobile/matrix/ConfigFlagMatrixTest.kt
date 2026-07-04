// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import io.opentelemetry.android.mobile.errors.ErrorConfig
import io.opentelemetry.android.mobile.errors.ErrorInstrumentation
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 5: Config flag matrix -- each test toggles ONE ErrorConfig flag from default
 * and asserts the behavioural change via captureException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigFlagMatrixTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val logger get() = otelRule.openTelemetry.logsBridge.get("config-flag-matrix")

    @Before
    fun setup() {
        resetSingleton()
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    private fun init(config: ErrorConfig): ErrorInstrumentation =
        ErrorInstrumentation.initialize(config = config, logger = logger)

    private fun resetSingleton() {
        try {
            val field = ErrorInstrumentation::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {}
    }

    // ── 1. filterExceptions=empty → SocketTimeoutException produces 1 event ──

    @Test
    fun `filterExceptions empty allows SocketTimeoutException`() {
        val inst = init(ErrorConfig(
            filterExceptions = emptyList(),
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        inst.captureException(java.net.SocketTimeoutException("timed out"), "manual")
        assertEquals(1, otelRule.logRecords.size,
            "Empty filterExceptions should let SocketTimeoutException through")
    }

    // ── 2. deduplicateWindowMs=1 → same exception twice = 2 events ───────────

    @Test
    fun `deduplicateWindowMs near zero allows duplicate exceptions`() {
        val inst = init(ErrorConfig(
            deduplicateWindowMs = 1,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        val ex = RuntimeException("boom")
        inst.captureException(ex, "manual")
        Thread.sleep(5) // exceed the 1ms window
        inst.captureException(ex, "manual")
        assertEquals(2, otelRule.logRecords.size,
            "With 1ms dedup window and sleep, same exception should produce 2 events")
    }

    // ── 3. deduplicateWindowMs=300000 → same exception twice = 1 event ───────

    @Test
    fun `deduplicateWindowMs 5min deduplicates same exception`() {
        val inst = init(ErrorConfig(
            deduplicateWindowMs = 300_000,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        val ex = RuntimeException("boom")
        inst.captureException(ex, "manual")
        inst.captureException(ex, "manual")
        assertEquals(1, otelRule.logRecords.size,
            "5-minute dedup window should collapse identical exceptions to 1 event")
    }

    // ── 4. rateLimit=2 → 5 exceptions = only 2 captured ─────────────────────

    @Test
    fun `rateLimit 2 caps captured exceptions`() {
        val inst = init(ErrorConfig(
            rateLimit = 2,
            deduplicateWindowMs = 1, // disable dedup
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        repeat(5) { i ->
            Thread.sleep(5) // exceed dedup window
            inst.captureException(RuntimeException("boom-$i"), "manual")
        }
        assertEquals(2, otelRule.logRecords.size,
            "Rate limit of 2 should cap at 2 events out of 5")
    }

    // ── 5. attachBreadcrumbs=false → no mobile.user.journey attribute ────────

    @Test
    fun `attachBreadcrumbs false omits journey attribute`() {
        val inst = init(ErrorConfig(
            attachBreadcrumbs = false,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        inst.captureException(RuntimeException("boom"), "manual")
        assertEquals(1, otelRule.logRecords.size)
        val attrs = otelRule.logRecords[0].attributes
        val journey = attrs[AttributeKey.stringKey("mobile.user.journey")]
        assertNull(journey,
            "With attachBreadcrumbs=false, mobile.user.journey should be absent")
    }

    // ── 6. captureExceptionMessages=false → no exception.message attribute ───

    @Test
    fun `captureExceptionMessages false omits exception message`() {
        val inst = init(ErrorConfig(
            captureExceptionMessages = false,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        inst.captureException(RuntimeException("secret message"), "manual")
        assertEquals(1, otelRule.logRecords.size)
        val attrs = otelRule.logRecords[0].attributes
        val msg = attrs[AttributeKey.stringKey("exception.message")]
        assertNull(msg,
            "With captureExceptionMessages=false, exception.message should be absent")
    }

    // ── 7. scrubStackTraces=true → stack trace present but scrubbed ──────────

    @Test
    fun `scrubStackTraces true produces scrubbed stack trace`() {
        val inst = init(ErrorConfig(
            scrubStackTraces = true,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        inst.captureException(RuntimeException("boom"), "manual")
        assertEquals(1, otelRule.logRecords.size)
        val attrs = otelRule.logRecords[0].attributes
        val stackTrace = attrs[AttributeKey.stringKey("exception.stacktrace")]
        assertNotNull(stackTrace, "Stack trace should be present even when scrubbed")
        assertTrue(stackTrace.isNotEmpty(), "Scrubbed stack trace should not be empty")
    }

    // ── 8. captureUncaughtExceptions=false → captureException still works ────

    @Test
    fun `captureUncaughtExceptions false does not block direct captureException`() {
        val inst = init(ErrorConfig(
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        inst.captureException(RuntimeException("manual capture"), "manual")
        assertEquals(1, otelRule.logRecords.size,
            "captureUncaughtExceptions=false should only control handler install, not direct capture")
    }

    // ── Flag interactions ──────────────────────────────────────────────────

    private fun crashes() = otelRule.logRecords

    @Test
    fun `enabled=false suppresses all capture`() {
        init(ErrorConfig(enabled = false))
            .captureException(RuntimeException("crash"), "uncaught")
        assertEquals(0, crashes().size, "enabled=false should suppress everything")
    }

    @Test
    fun `rateLimit + deduplication interact correctly`() {
        // Dedup window is 5 min, rate limit is 3
        // 5 unique exceptions → dedup allows all 5 → rate limit caps at 3
        val inst = init(ErrorConfig(
            rateLimit = 3,
            deduplicateWindowMs = 300_000,
            captureUncaughtExceptions = false,
            flushOnError = false
        ))
        for (i in 1..5) {
            inst.captureException(RuntimeException("crash $i"), "uncaught")
        }
        assertTrue(crashes().size <= 3, "Rate limit should cap at 3 even when dedup allows all, got ${crashes().size}")
    }

    @Test
    fun `captureExceptionMessages=false + scrubStackTraces=true both active`() {
        init(ErrorConfig(
            captureExceptionMessages = false,
            scrubStackTraces = true,
            captureUncaughtExceptions = false,
            flushOnError = false
        )).captureException(RuntimeException("sensitive data here"), "uncaught")
        val event = crashes().first()
        val message = event.attributes[AttributeKey.stringKey("exception.message")]
        assertTrue(message == null || message.isEmpty(), "Message should be omitted when captureExceptionMessages=false")
        val stackTrace = event.attributes[AttributeKey.stringKey("exception.stacktrace")]
        assertTrue(stackTrace != null, "Stack trace should still be present (scrubbed)")
    }
}
