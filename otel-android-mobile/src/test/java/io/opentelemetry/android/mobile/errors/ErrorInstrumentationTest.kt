/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.errors

import io.opentelemetry.android.mobile.instrumentation.MobileSessionProvider
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.resources.Resource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for [ErrorInstrumentation].
 *
 * Covers crash capture, deduplication, rate limiting, coroutine errors,
 * stack trace scrubbing, cause chains, filtering, flush behavior,
 * session error marking, and statistics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorInstrumentationTest {

    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var loggerProvider: SdkLoggerProvider
    private lateinit var logger: Logger
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        // Save original uncaught exception handler
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()

        // Reset singleton before each test
        resetSingleton()

        // Set up mock log exporter with synchronous processor
        mockExporter = MockLogRecordExporter()
        loggerProvider = SdkLoggerProvider.builder()
            .setResource(Resource.getDefault())
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(mockExporter))
            .build()
        logger = loggerProvider.get("error-instrumentation-test")
    }

    @After
    fun tearDown() {
        // Reset singleton
        resetSingleton()
        // Restore default exception handler
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    // ── 1. Basic crash capture ──────────────────────────────────────────────

    @Test
    fun `basic crash capture emits app_crash with exception attributes`() {
        val inst = createInstrumentation(ErrorConfig(scrubStackTraces = false))
        val exception = RuntimeException("Something went wrong")

        inst.captureException(exception, "manual")

        assertEquals(1, mockExporter.getExportedCount())
        val log = mockExporter.exportedLogs.first()
        assertEquals("app.crash", log.bodyValue?.asString())
        assertEquals(Severity.ERROR, log.severity)
        assertEquals(
            "java.lang.RuntimeException",
            log.attributes.get(AttributeKey.stringKey("exception.type"))
        )
        assertEquals(
            "Something went wrong",
            log.attributes.get(AttributeKey.stringKey("exception.message"))
        )
        val stackTrace = log.attributes.get(AttributeKey.stringKey("exception.stacktrace"))
        assertNotNull(stackTrace, "exception.stacktrace should be present")
        assertTrue(stackTrace.isNotEmpty(), "exception.stacktrace should not be empty")
    }

    // ── 2. Disabled config ──────────────────────────────────────────────────

    @Test
    fun `disabled config does not capture exceptions`() {
        val inst = createInstrumentation(ErrorConfig(enabled = false))

        inst.captureException(RuntimeException("boom"), "manual")

        assertEquals(0, mockExporter.getExportedCount(),
            "enabled=false should suppress all exception capture")
    }

    // ── 3. Deduplication ────────────────────────────────────────────────────

    @Test
    fun `deduplication suppresses same exception within window`() {
        val inst = createInstrumentation(ErrorConfig(deduplicateWindowMs = 300_000))
        val frames = arrayOf(StackTraceElement("com.test.Foo", "bar", "Foo.kt", 42))
        val e1 = RuntimeException("same error").also { it.stackTrace = frames }
        val e2 = RuntimeException("same error").also { it.stackTrace = frames }

        inst.captureException(e1, "manual")
        inst.captureException(e2, "manual")

        assertEquals(1, mockExporter.getExportedCount(),
            "Same exception within dedup window should be captured only once")
    }

    @Test
    fun `deduplication allows capture outside window`() {
        // Use a very short dedup window (1ms) so we can exceed it immediately
        val inst = createInstrumentation(ErrorConfig(deduplicateWindowMs = 1))
        val frames = arrayOf(StackTraceElement("com.test.Foo", "bar", "Foo.kt", 42))
        val e1 = RuntimeException("same error").also { it.stackTrace = frames }

        inst.captureException(e1, "manual")

        // Wait just enough to exceed the 1ms window
        Thread.sleep(10)

        val e2 = RuntimeException("same error").also { it.stackTrace = frames }
        inst.captureException(e2, "manual")

        assertEquals(2, mockExporter.getExportedCount(),
            "Same exception outside dedup window should be captured again")
    }

    // ── 4. Rate limiting ────────────────────────────────────────────────────

    @Test
    fun `rate limiting suppresses 11th error with rateLimit 10`() {
        val inst = createInstrumentation(ErrorConfig(rateLimit = 10))

        // Send 11 different exceptions (different messages to avoid dedup)
        for (i in 1..11) {
            inst.captureException(RuntimeException("error $i"), "manual")
        }

        assertEquals(10, mockExporter.getExportedCount(),
            "rateLimit=10 should suppress the 11th error")
    }

    // ── 5. Rate limiting boundary ───────────────────────────────────────────

    @Test
    fun `rate limiting allows exactly at limit but blocks one over`() {
        val inst = createInstrumentation(ErrorConfig(rateLimit = 5))

        // Send exactly 5 different exceptions
        for (i in 1..5) {
            inst.captureException(RuntimeException("error $i"), "manual")
        }
        assertEquals(5, mockExporter.getExportedCount(),
            "Exactly at rate limit should all be captured")

        // 6th should be blocked
        inst.captureException(RuntimeException("error 6"), "manual")
        assertEquals(5, mockExporter.getExportedCount(),
            "One over rate limit should be blocked")
    }

    // ── 6. Exception filtering ──────────────────────────────────────────────

    @Test
    fun `exception filtering drops CancellationException`() {
        val inst = createInstrumentation(ErrorConfig(
            filterExceptions = listOf("java.util.concurrent.CancellationException")
        ))

        inst.captureException(
            java.util.concurrent.CancellationException("cancelled"),
            "manual"
        )

        assertEquals(0, mockExporter.getExportedCount(),
            "Filtered exception should be dropped")
    }

    // ── 7. Exception filtering partial match ────────────────────────────────

    @Test
    fun `exception filtering matches subpackage prefix`() {
        // shouldFilterException checks className.startsWith("$filter.")
        val inst = createInstrumentation(ErrorConfig(
            filterExceptions = listOf("java.util.concurrent")
        ))

        inst.captureException(
            java.util.concurrent.CancellationException("cancelled"),
            "manual"
        )

        assertEquals(0, mockExporter.getExportedCount(),
            "Filter 'java.util.concurrent' should match subpackage classes")
    }

    @Test
    fun `exception filtering allows non-matching exception`() {
        val inst = createInstrumentation(ErrorConfig(
            filterExceptions = listOf("java.util.concurrent.CancellationException")
        ))

        inst.captureException(RuntimeException("not filtered"), "manual")

        assertEquals(1, mockExporter.getExportedCount(),
            "Non-matching exception should be captured")
    }

    // ── 8. Coroutine exception handler captures ─────────────────────────────

    @Test
    fun `coroutine exception handler captures when enabled`() {
        val inst = createInstrumentation(ErrorConfig(captureCoroutineExceptions = true))

        val handler = inst.coroutineExceptionHandler
        handler.handleException(
            kotlin.coroutines.EmptyCoroutineContext,
            RuntimeException("coroutine crash")
        )

        assertEquals(1, mockExporter.getExportedCount(),
            "captureCoroutineExceptions=true should capture coroutine exceptions")
        val log = mockExporter.exportedLogs.first()
        assertEquals("coroutine",
            log.attributes.get(AttributeKey.stringKey("mobile.exception.origin")))
    }

    // ── 9. Coroutine exception handler disabled ─────────────────────────────

    @Test
    fun `coroutine exception handler does not capture when disabled`() {
        val inst = createInstrumentation(ErrorConfig(captureCoroutineExceptions = false))

        val handler = inst.coroutineExceptionHandler
        handler.handleException(
            kotlin.coroutines.EmptyCoroutineContext,
            RuntimeException("coroutine crash")
        )

        assertEquals(0, mockExporter.getExportedCount(),
            "captureCoroutineExceptions=false should ignore coroutine exceptions")
    }

    // ── 10. Flush on error for manual source ────────────────────────────────

    @Test
    fun `flush on error invokes onFlush for manual source`() {
        val flushCount = AtomicInteger(0)
        val inst = createInstrumentation(
            ErrorConfig(flushOnError = true),
            onFlush = { flushCount.incrementAndGet(); CompletableResultCode.ofSuccess() }
        )

        inst.captureException(RuntimeException("boom"), "manual")

        assertEquals(1, flushCount.get(),
            "flushOnError=true should invoke flush callback for manual source")
    }

    // ── 11. Flush on uncaught ───────────────────────────────────────────────

    @Test
    fun `uncaught exception handler invokes onFlush`() {
        val flushCount = AtomicInteger(0)
        createInstrumentation(
            ErrorConfig(
                captureUncaughtExceptions = true,
                flushOnError = true
            ),
            onFlush = { flushCount.incrementAndGet(); CompletableResultCode.ofSuccess() }
        )

        // Get the installed uncaught exception handler
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler, "Uncaught exception handler should be installed")

        // Simulate an uncaught exception on a fake thread
        val fakeThread = Thread("test-thread")
        handler.uncaughtException(fakeThread, RuntimeException("uncaught boom"))

        // The uncaught handler path calls onFlush directly
        assertTrue(flushCount.get() >= 1,
            "Uncaught exception handler should invoke flush")
    }

    // ── 12. No double flush on uncaught ─────────────────────────────────────

    @Test
    fun `captureException does not flush again for uncaught source`() {
        val flushCount = AtomicInteger(0)
        val inst = createInstrumentation(
            ErrorConfig(
                captureUncaughtExceptions = false, // Don't install handler, test captureException directly
                flushOnError = true
            ),
            onFlush = { flushCount.incrementAndGet(); CompletableResultCode.ofSuccess() }
        )

        // Call captureException directly with source="uncaught"
        inst.captureException(RuntimeException("uncaught boom"), "uncaught")

        // captureException skips flush for source="uncaught" (line: source != "uncaught")
        assertEquals(0, flushCount.get(),
            "captureException should NOT flush for source='uncaught' to avoid double flush")
    }

    @Test
    fun `uncaught handler flushes exactly once`() {
        val flushCount = AtomicInteger(0)
        createInstrumentation(
            ErrorConfig(
                captureUncaughtExceptions = true,
                flushOnError = true
            ),
            onFlush = { flushCount.incrementAndGet(); CompletableResultCode.ofSuccess() }
        )

        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        val fakeThread = Thread("test-thread")
        handler.uncaughtException(fakeThread, RuntimeException("crash"))

        // The uncaught handler calls captureException(source="uncaught") which does NOT flush,
        // then the handler itself flushes. So exactly 1 flush total.
        assertEquals(1, flushCount.get(),
            "Uncaught path should produce exactly 1 flush (from handler, not from captureException)")
    }

    // ── 13. Stack trace scrubbing ───────────────────────────────────────────

    @Test
    fun `scrubStackTraces removes PII from exception message`() {
        val inst = createInstrumentation(ErrorConfig(
            scrubStackTraces = true,
            captureExceptionMessages = true
        ))

        inst.captureException(
            RuntimeException("Error for user@example.com at 555-123-4567"),
            "manual"
        )

        val log = mockExporter.exportedLogs.first()
        val message = log.attributes.get(AttributeKey.stringKey("exception.message"))!!
        assertFalse(message.contains("user@example.com"),
            "Scrubbing should remove email address")
        assertTrue(message.contains("[EMAIL]"),
            "Scrubbing should replace email with [EMAIL] placeholder")
    }

    @Test
    fun `scrubStackTraces=false preserves PII`() {
        val inst = createInstrumentation(ErrorConfig(
            scrubStackTraces = false,
            captureExceptionMessages = true
        ))

        inst.captureException(
            RuntimeException("Error for user@example.com"),
            "manual"
        )

        val log = mockExporter.exportedLogs.first()
        val message = log.attributes.get(AttributeKey.stringKey("exception.message"))!!
        assertTrue(message.contains("user@example.com"),
            "scrubStackTraces=false should preserve PII")
    }

    // ── 14. Cause chain capture ─────────────────────────────────────────────

    @Test
    fun `captureCauses=true captures up to 5 causes`() {
        val inst = createInstrumentation(ErrorConfig(
            captureCauses = true,
            scrubStackTraces = false
        ))

        // Build a 6-deep cause chain; only 5 should be captured
        val cause5 = RuntimeException("cause5")
        val cause4 = RuntimeException("cause4", cause5)
        val cause3 = RuntimeException("cause3", cause4)
        val cause2 = RuntimeException("cause2", cause3)
        val cause1 = RuntimeException("cause1", cause2)
        val root = RuntimeException("root", cause1)

        inst.captureException(root, "manual")

        val log = mockExporter.exportedLogs.first()
        // Causes 0..4 should be present
        for (i in 0..4) {
            val causeType = log.attributes.get(
                AttributeKey.stringKey("exception.cause.$i.type")
            )
            assertNotNull(causeType, "exception.cause.$i.type should be present")
            assertEquals("java.lang.RuntimeException", causeType)
        }
        // Cause 5 should NOT be present (limit is 5 causes)
        val cause5Type = log.attributes.get(
            AttributeKey.stringKey("exception.cause.5.type")
        )
        assertNull(cause5Type, "cause chain should be limited to 5")
    }

    @Test
    fun `captureCauses=true records cause messages`() {
        val inst = createInstrumentation(ErrorConfig(
            captureCauses = true,
            scrubStackTraces = false
        ))

        val cause = IllegalStateException("inner cause")
        val root = RuntimeException("outer", cause)

        inst.captureException(root, "manual")

        val log = mockExporter.exportedLogs.first()
        assertEquals(
            "java.lang.IllegalStateException",
            log.attributes.get(AttributeKey.stringKey("exception.cause.0.type"))
        )
        assertEquals(
            "inner cause",
            log.attributes.get(AttributeKey.stringKey("exception.cause.0.message"))
        )
    }

    // ── 15. Cause chain disabled ────────────────────────────────────────────

    @Test
    fun `captureCauses=false omits cause chain`() {
        val inst = createInstrumentation(ErrorConfig(captureCauses = false))

        val root = RuntimeException("root", IllegalStateException("cause"))
        inst.captureException(root, "manual")

        val log = mockExporter.exportedLogs.first()
        val cause0 = log.attributes.get(AttributeKey.stringKey("exception.cause.0.type"))
        assertNull(cause0, "captureCauses=false should omit cause chain")
    }

    // ── 16. Exception fingerprint same ──────────────────────────────────────

    @Test
    fun `same type, top frame, and message produce same fingerprint`() {
        val config = ErrorConfig()
        val frames = arrayOf(StackTraceElement("com.test.Foo", "bar", "Foo.kt", 42))
        val e1 = RuntimeException("same message").also { it.stackTrace = frames }
        val e2 = RuntimeException("same message").also { it.stackTrace = frames }

        assertEquals(
            config.getExceptionFingerprint(e1),
            config.getExceptionFingerprint(e2),
            "Same type+frame+message should produce identical fingerprint"
        )
    }

    // ── 17. Exception fingerprint different ─────────────────────────────────

    @Test
    fun `different message produces different fingerprint and both captured`() {
        val inst = createInstrumentation(ErrorConfig(scrubStackTraces = false))
        val frames = arrayOf(StackTraceElement("com.test.Foo", "bar", "Foo.kt", 42))
        val e1 = RuntimeException("error A").also { it.stackTrace = frames }
        val e2 = RuntimeException("error B").also { it.stackTrace = frames }

        // Verify fingerprints differ
        val config = ErrorConfig()
        assertNotEquals(
            config.getExceptionFingerprint(e1),
            config.getExceptionFingerprint(e2),
            "Different messages should produce different fingerprints"
        )

        // Both should be captured
        inst.captureException(e1, "manual")
        inst.captureException(e2, "manual")
        assertEquals(2, mockExporter.getExportedCount(),
            "Different fingerprints should both be captured")
    }

    @Test
    fun `different exception type produces different fingerprint`() {
        val config = ErrorConfig()
        val frames = arrayOf(StackTraceElement("com.test.Foo", "bar", "Foo.kt", 42))
        val e1 = RuntimeException("error").also { it.stackTrace = frames }
        val e2 = IllegalStateException("error").also { it.stackTrace = frames }

        assertNotEquals(
            config.getExceptionFingerprint(e1),
            config.getExceptionFingerprint(e2),
            "Different exception types should produce different fingerprints"
        )
    }

    // ── 18. Session error marking ───────────────────────────────────────────

    @Test
    fun `captureException calls markSessionError on sessionProvider`() {
        val sessionProvider = mockk<MobileSessionProvider>(relaxed = true)
        val inst = createInstrumentation(
            ErrorConfig(),
            sessionProvider = sessionProvider
        )

        inst.captureException(RuntimeException("boom"), "manual")

        verify(exactly = 1) { sessionProvider.markSessionError() }
    }

    @Test
    fun `markSessionError not called when exception is filtered`() {
        val sessionProvider = mockk<MobileSessionProvider>(relaxed = true)
        val inst = createInstrumentation(
            ErrorConfig(
                filterExceptions = listOf("java.lang.RuntimeException")
            ),
            sessionProvider = sessionProvider
        )

        inst.captureException(RuntimeException("filtered"), "manual")

        verify(exactly = 0) { sessionProvider.markSessionError() }
    }

    // ── 19. clearDeduplicationCache ─────────────────────────────────────────

    @Test
    fun `clearDeduplicationCache allows recapture of previously deduped error`() {
        val inst = createInstrumentation(ErrorConfig(deduplicateWindowMs = 300_000))
        val frames = arrayOf(StackTraceElement("com.test.Foo", "bar", "Foo.kt", 42))
        val e1 = RuntimeException("same error").also { it.stackTrace = frames }

        inst.captureException(e1, "manual")
        assertEquals(1, mockExporter.getExportedCount())

        // Second capture is deduped
        val e2 = RuntimeException("same error").also { it.stackTrace = frames }
        inst.captureException(e2, "manual")
        assertEquals(1, mockExporter.getExportedCount(),
            "Should still be 1 due to dedup")

        // Clear dedup cache
        inst.clearDeduplicationCache()

        // Now it should capture again
        val e3 = RuntimeException("same error").also { it.stackTrace = frames }
        inst.captureException(e3, "manual")
        assertEquals(2, mockExporter.getExportedCount(),
            "After clearing dedup cache, same error should be captured again")
    }

    // ── 20. getStatistics ───────────────────────────────────────────────────

    @Test
    fun `getStatistics returns correct unique errors count`() {
        val inst = createInstrumentation(ErrorConfig(rateLimit = 20))

        inst.captureException(RuntimeException("error A"), "manual")
        inst.captureException(IllegalStateException("error B"), "manual")
        inst.captureException(ArithmeticException("error C"), "manual")

        val stats = inst.getStatistics()
        assertEquals(3, stats.uniqueErrors,
            "uniqueErrors should reflect number of distinct fingerprints")
    }

    @Test
    fun `getStatistics returns correct errorsThisMinute count`() {
        val inst = createInstrumentation(ErrorConfig(rateLimit = 20))

        for (i in 1..5) {
            inst.captureException(RuntimeException("error $i"), "manual")
        }

        val stats = inst.getStatistics()
        assertEquals(5, stats.errorsThisMinute,
            "errorsThisMinute should reflect rate limiter count")
    }

    @Test
    fun `getStatistics rateLimitActive is true when at limit`() {
        val inst = createInstrumentation(ErrorConfig(rateLimit = 3))

        for (i in 1..3) {
            inst.captureException(RuntimeException("error $i"), "manual")
        }

        val stats = inst.getStatistics()
        assertTrue(stats.rateLimitActive,
            "rateLimitActive should be true when count >= rateLimit")
    }

    @Test
    fun `getStatistics rateLimitActive is false when under limit`() {
        val inst = createInstrumentation(ErrorConfig(rateLimit = 10))

        inst.captureException(RuntimeException("error 1"), "manual")

        val stats = inst.getStatistics()
        assertFalse(stats.rateLimitActive,
            "rateLimitActive should be false when under limit")
    }

    // ── Additional edge cases ───────────────────────────────────────────────

    @Test
    fun `captureException records source attribute`() {
        val inst = createInstrumentation(ErrorConfig(scrubStackTraces = false))

        inst.captureException(RuntimeException("test"), "manual")

        val log = mockExporter.exportedLogs.first()
        assertEquals("manual",
            log.attributes.get(AttributeKey.stringKey("mobile.exception.origin")))
    }

    @Test
    fun `captureException records context attribute when provided`() {
        val inst = createInstrumentation(ErrorConfig(scrubStackTraces = false))

        inst.captureException(RuntimeException("test"), "manual", "some-context")

        val log = mockExporter.exportedLogs.first()
        assertEquals("some-context",
            log.attributes.get(AttributeKey.stringKey("mobile.error.context")))
    }

    @Test
    fun `captureException records fingerprint attribute`() {
        val inst = createInstrumentation(ErrorConfig(scrubStackTraces = false))
        val frames = arrayOf(StackTraceElement("com.test.Foo", "bar", "Foo.kt", 42))
        val exception = RuntimeException("test").also { it.stackTrace = frames }

        inst.captureException(exception, "manual")

        val log = mockExporter.exportedLogs.first()
        val fingerprint = log.attributes.get(AttributeKey.stringKey("mobile.error.fingerprint"))
        assertNotNull(fingerprint, "Fingerprint attribute should be present")
        assertTrue(fingerprint.contains("java.lang.RuntimeException"),
            "Fingerprint should contain exception class name")
    }

    @Test
    fun `coroutine source triggers flush when flushOnError is true`() {
        val flushCount = AtomicInteger(0)
        val inst = createInstrumentation(
            ErrorConfig(
                captureCoroutineExceptions = true,
                flushOnError = true
            ),
            onFlush = { flushCount.incrementAndGet(); CompletableResultCode.ofSuccess() }
        )

        val handler = inst.coroutineExceptionHandler
        handler.handleException(
            kotlin.coroutines.EmptyCoroutineContext,
            RuntimeException("coroutine crash")
        )

        assertEquals(1, flushCount.get(),
            "Coroutine source should trigger flush when flushOnError=true")
    }

    @Test
    fun `flushOnError=false does not flush for any source`() {
        val flushCount = AtomicInteger(0)
        val inst = createInstrumentation(
            ErrorConfig(flushOnError = false),
            onFlush = { flushCount.incrementAndGet(); CompletableResultCode.ofSuccess() }
        )

        inst.captureException(RuntimeException("manual"), "manual")
        inst.captureException(RuntimeException("coroutine"), "coroutine")

        assertEquals(0, flushCount.get(),
            "flushOnError=false should never invoke flush callback")
    }

    @Test
    fun `exception summary attribute is set`() {
        val inst = createInstrumentation(ErrorConfig(scrubStackTraces = false))

        inst.captureException(IllegalArgumentException("bad arg"), "manual")

        val log = mockExporter.exportedLogs.first()
        val summary = log.attributes.get(AttributeKey.stringKey("mobile.exception.summary"))
        assertNotNull(summary)
        assertTrue(summary.contains("IllegalArgumentException"),
            "Summary should contain exception simple name")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun createInstrumentation(
        config: ErrorConfig,
        onFlush: (() -> CompletableResultCode)? = null,
        sessionProvider: MobileSessionProvider? = null
    ): ErrorInstrumentation {
        return ErrorInstrumentation.initialize(
            config = config,
            logger = logger,
            onFlush = onFlush,
            sessionProvider = sessionProvider
        )
    }

    private fun resetSingleton() {
        try {
            val field = ErrorInstrumentation::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // Ignore if field doesn't exist
        }
    }
}
