/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.errors

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral tests for [ErrorConfig] fields.
 *
 * Each test proves a config toggle actually changes runtime behavior —
 * not just that it's accepted without error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorConfigBehaviorTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val logger get() = otelRule.openTelemetry.logsBridge.get("error-test")

    @Before
    fun setup() {
        // Reset singleton between tests via reflection
        resetErrorInstrumentationSingleton()
    }

    @After
    fun tearDown() {
        resetErrorInstrumentationSingleton()
    }

    // ── enabled ─────────────────────────────────────────────────────────────

    @Test
    fun `enabled=true captures exception`() {
        val inst = createInstrumentation(ErrorConfig(enabled = true))
        inst.captureException(RuntimeException("boom"), "manual")

        assertEquals(1, otelRule.logRecords.size,
            "enabled=true should capture the exception")
    }

    @Test
    fun `enabled=false suppresses all capture`() {
        val inst = createInstrumentation(ErrorConfig(enabled = false))
        inst.captureException(RuntimeException("boom"), "manual")

        assertEquals(0, otelRule.logRecords.size,
            "enabled=false should suppress all exception capture")
    }

    // ── captureExceptionMessages ────────────────────────────────────────────

    @Test
    fun `captureExceptionMessages=true includes exception message attribute`() {
        val inst = createInstrumentation(ErrorConfig(captureExceptionMessages = true, scrubStackTraces = false))
        inst.captureException(RuntimeException("detail message"), "manual")

        val log = otelRule.logRecords.first()
        val message = log.attributes.get(AttributeKey.stringKey("exception.message"))
        assertEquals("detail message", message,
            "captureExceptionMessages=true should include exception.message")
    }

    @Test
    fun `captureExceptionMessages=false omits exception message attribute`() {
        val inst = createInstrumentation(ErrorConfig(captureExceptionMessages = false))
        inst.captureException(RuntimeException("detail message"), "manual")

        val log = otelRule.logRecords.first()
        val message = log.attributes.get(AttributeKey.stringKey("exception.message"))
        assertNull(message,
            "captureExceptionMessages=false should omit exception.message attribute")
    }

    // ── captureCauses ──────────────────────────────────────────────────────

    @Test
    fun `captureCauses=true records cause chain`() {
        val inst = createInstrumentation(ErrorConfig(captureCauses = true, scrubStackTraces = false))
        val root = RuntimeException("root", IllegalStateException("cause1", ArithmeticException("cause2")))
        inst.captureException(root, "manual")

        val log = otelRule.logRecords.first()
        val cause0Type = log.attributes.get(AttributeKey.stringKey("exception.cause.0.type"))
        val cause1Type = log.attributes.get(AttributeKey.stringKey("exception.cause.1.type"))
        assertEquals("java.lang.IllegalStateException", cause0Type)
        assertEquals("java.lang.ArithmeticException", cause1Type)
    }

    @Test
    fun `captureCauses=false omits cause chain`() {
        val inst = createInstrumentation(ErrorConfig(captureCauses = false))
        val root = RuntimeException("root", IllegalStateException("cause"))
        inst.captureException(root, "manual")

        val log = otelRule.logRecords.first()
        val cause0Type = log.attributes.get(AttributeKey.stringKey("exception.cause.0.type"))
        assertNull(cause0Type,
            "captureCauses=false should omit cause chain attributes")
    }

    // ── scrubStackTraces ───────────────────────────────────────────────────

    @Test
    fun `scrubStackTraces=true redacts PII in exception message`() {
        val inst = createInstrumentation(ErrorConfig(
            scrubStackTraces = true,
            captureExceptionMessages = true
        ))
        inst.captureException(RuntimeException("Error for user@example.com"), "manual")

        val log = otelRule.logRecords.first()
        val message = log.attributes.get(AttributeKey.stringKey("exception.message"))!!
        assertFalse(message.contains("user@example.com"),
            "scrubStackTraces=true should redact email from exception message")
        assertTrue(message.contains("[EMAIL]"),
            "scrubStackTraces=true should replace email with [EMAIL] placeholder")
    }

    @Test
    fun `scrubStackTraces=false preserves raw exception message`() {
        val inst = createInstrumentation(ErrorConfig(
            scrubStackTraces = false,
            captureExceptionMessages = true
        ))
        inst.captureException(RuntimeException("Error for user@example.com"), "manual")

        val log = otelRule.logRecords.first()
        val message = log.attributes.get(AttributeKey.stringKey("exception.message"))!!
        assertTrue(message.contains("user@example.com"),
            "scrubStackTraces=false should preserve raw PII in message")
    }

    // ── maxStackTraceDepth ─────────────────────────────────────────────────

    @Test
    fun `maxStackTraceDepth limits captured frames`() {
        val inst = createInstrumentation(ErrorConfig(maxStackTraceDepth = 3, scrubStackTraces = false))
        // Create exception with a deep stack
        val exception = createDeepException(depth = 20)
        inst.captureException(exception, "manual")

        val log = otelRule.logRecords.first()
        val stackTrace = log.attributes.get(AttributeKey.stringKey("exception.stacktrace"))!!
        val frameCount = stackTrace.split("\n").filter { it.isNotBlank() }.size
        assertEquals(3, frameCount,
            "maxStackTraceDepth=3 should limit stack trace to 3 frames, got $frameCount")
    }

    @Test
    fun `maxStackTraceDepth=50 captures more frames`() {
        val inst = createInstrumentation(ErrorConfig(maxStackTraceDepth = 50, scrubStackTraces = false))
        val exception = createDeepException(depth = 20)
        inst.captureException(exception, "manual")

        val log = otelRule.logRecords.first()
        val stackTrace = log.attributes.get(AttributeKey.stringKey("exception.stacktrace"))!!
        val frameCount = stackTrace.split("\n").filter { it.isNotBlank() }.size
        assertTrue(frameCount > 3,
            "maxStackTraceDepth=50 should capture more than 3 frames")
    }

    // ── filterExceptions ───────────────────────────────────────────────────

    @Test
    fun `filterExceptions drops matching exception class`() {
        val inst = createInstrumentation(ErrorConfig(
            filterExceptions = listOf("java.util.concurrent.CancellationException")
        ))
        inst.captureException(java.util.concurrent.CancellationException("cancelled"), "manual")

        assertEquals(0, otelRule.logRecords.size,
            "Filtered exception should be dropped")
    }

    @Test
    fun `filterExceptions allows non-matching exception`() {
        val inst = createInstrumentation(ErrorConfig(
            filterExceptions = listOf("java.util.concurrent.CancellationException")
        ))
        inst.captureException(RuntimeException("not filtered"), "manual")

        assertEquals(1, otelRule.logRecords.size,
            "Non-matching exception should be captured")
    }

    @Test
    fun `filterExceptions matches prefix for nested classes`() {
        // Use the actual runtime class name for the filter prefix
        val exception = kotlinx.coroutines.CancellationException("cancelled")
        val className = exception.javaClass.name
        // Extract package prefix (e.g., "java.util.concurrent" from "java.util.concurrent.CancellationException")
        val packagePrefix = className.substringBeforeLast(".")

        val inst = createInstrumentation(ErrorConfig(
            filterExceptions = listOf(packagePrefix)
        ))
        inst.captureException(exception, "manual")

        assertEquals(0, otelRule.logRecords.size,
            "Exception matching prefix '$packagePrefix' should be filtered (class=$className)")
    }

    // ── deduplicateWindowMs ────────────────────────────────────────────────

    @Test
    fun `deduplication drops same exception within window`() {
        val inst = createInstrumentation(ErrorConfig(
            deduplicateWindowMs = 60_000 // 1 minute window
        ))
        val exception = RuntimeException("same error")

        inst.captureException(exception, "manual")
        inst.captureException(exception, "manual")
        inst.captureException(exception, "manual")

        assertEquals(1, otelRule.logRecords.size,
            "Same exception within dedup window should be captured only once")
    }

    @Test
    fun `deduplication allows different exceptions`() {
        val inst = createInstrumentation(ErrorConfig(
            deduplicateWindowMs = 60_000
        ))

        inst.captureException(RuntimeException("error A"), "manual")
        inst.captureException(IllegalStateException("error B"), "manual")
        inst.captureException(ArithmeticException("error C"), "manual")

        assertEquals(3, otelRule.logRecords.size,
            "Different exceptions should each be captured")
    }

    // ── rateLimit ──────────────────────────────────────────────────────────

    @Test
    fun `rateLimit=3 drops errors beyond limit`() {
        val inst = createInstrumentation(ErrorConfig(rateLimit = 3))

        // Send 5 different exceptions (to avoid dedup)
        for (i in 1..5) {
            inst.captureException(RuntimeException("error $i"), "manual")
        }

        assertEquals(3, otelRule.logRecords.size,
            "rateLimit=3 should only capture 3 errors per minute")
    }

    @Test
    fun `rateLimit=10 allows more errors`() {
        val inst = createInstrumentation(ErrorConfig(rateLimit = 10))

        for (i in 1..8) {
            inst.captureException(RuntimeException("error $i"), "manual")
        }

        assertEquals(8, otelRule.logRecords.size,
            "rateLimit=10 should allow all 8 errors")
    }

    // ── flushOnError ───────────────────────────────────────────────────────

    @Test
    fun `flushOnError=true invokes flush callback`() {
        val flushCount = AtomicInteger(0)
        val inst = createInstrumentation(
            ErrorConfig(flushOnError = true),
            onFlush = { flushCount.incrementAndGet(); io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess() }
        )
        inst.captureException(RuntimeException("boom"), "manual")

        assertEquals(1, flushCount.get(),
            "flushOnError=true should invoke flush callback")
    }

    @Test
    fun `flushOnError=false does not invoke flush callback for manual errors`() {
        val flushCount = AtomicInteger(0)
        val inst = createInstrumentation(
            ErrorConfig(flushOnError = false),
            onFlush = { flushCount.incrementAndGet(); io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess() }
        )
        inst.captureException(RuntimeException("boom"), "manual")

        assertEquals(0, flushCount.get(),
            "flushOnError=false should not invoke flush callback")
    }

    // ── captureCoroutineExceptions ──────────────────────────────────────────

    @Test
    fun `captureCoroutineExceptions=true captures via coroutineExceptionHandler`() {
        val inst = createInstrumentation(ErrorConfig(captureCoroutineExceptions = true))
        // Simulate coroutine exception handler invocation
        val handler = inst.coroutineExceptionHandler
        handler.handleException(kotlin.coroutines.EmptyCoroutineContext, RuntimeException("coroutine boom"))

        assertEquals(1, otelRule.logRecords.size,
            "captureCoroutineExceptions=true should capture coroutine exceptions")
    }

    @Test
    fun `captureCoroutineExceptions=false ignores coroutine exceptions`() {
        val inst = createInstrumentation(ErrorConfig(captureCoroutineExceptions = false))
        val handler = inst.coroutineExceptionHandler
        handler.handleException(kotlin.coroutines.EmptyCoroutineContext, RuntimeException("coroutine boom"))

        assertEquals(0, otelRule.logRecords.size,
            "captureCoroutineExceptions=false should ignore coroutine exceptions")
    }

    // ── Interaction: scrubStackTraces + captureCauses ──────────────────────

    @Test
    fun `scrubStackTraces applies to cause messages too`() {
        val inst = createInstrumentation(ErrorConfig(
            captureCauses = true,
            scrubStackTraces = true,
            captureExceptionMessages = true
        ))
        val cause = IllegalStateException("Contact admin@corp.com for help")
        val root = RuntimeException("Wrapper", cause)
        inst.captureException(root, "manual")

        val log = otelRule.logRecords.first()
        val causeMessage = log.attributes.get(AttributeKey.stringKey("exception.cause.0.message"))
        assertNotNull(causeMessage)
        assertFalse(causeMessage.contains("admin@corp.com"),
            "scrubStackTraces should redact PII in cause messages too")
        assertTrue(causeMessage.contains("[EMAIL]"))
    }

    // ── shouldFilterException on ErrorConfig ──────────────────────────────

    @Test
    fun `shouldFilterException returns true for disabled config`() {
        val config = ErrorConfig(enabled = false)
        assertTrue(config.shouldFilterException(RuntimeException("any")),
            "disabled config should filter all exceptions")
    }

    @Test
    fun `shouldFilterException returns false for non-matching exception`() {
        val config = ErrorConfig(filterExceptions = listOf("java.io.IOException"))
        assertFalse(config.shouldFilterException(RuntimeException("not io")),
            "Non-matching exception should not be filtered")
    }

    // ── getExceptionFingerprint ────────────────────────────────────────────

    @Test
    fun `same exception type and stack produces same fingerprint`() {
        val config = ErrorConfig()
        // Use custom stack traces so they're identical
        val frames = arrayOf(StackTraceElement("com.test.Foo", "bar", "Foo.kt", 42))
        val e1 = RuntimeException("same").also { it.stackTrace = frames }
        val e2 = RuntimeException("same").also { it.stackTrace = frames }
        assertEquals(config.getExceptionFingerprint(e1), config.getExceptionFingerprint(e2),
            "Same exception type+message+top frame should produce same fingerprint")
    }

    @Test
    fun `different exception types produce different fingerprints`() {
        val config = ErrorConfig()
        val e1 = RuntimeException("error")
        val e2 = IllegalStateException("error")
        assertTrue(config.getExceptionFingerprint(e1) != config.getExceptionFingerprint(e2),
            "Different exception types should produce different fingerprints")
    }

    // ── Preset configs have expected field values ─────────────────────────

    @Test
    fun `production preset filters CancellationException`() {
        val config = ErrorConfig.production()
        assertTrue(config.shouldFilterException(java.util.concurrent.CancellationException("cancelled")),
            "Production config should filter CancellationException")
    }

    @Test
    fun `minimal preset disables coroutine capture`() {
        assertFalse(ErrorConfig.minimal().captureCoroutineExceptions,
            "Minimal config should disable coroutine exception capture")
    }

    @Test
    fun `debug preset has higher rate limit than default`() {
        assertTrue(ErrorConfig.debug().rateLimit > ErrorConfig.default().rateLimit,
            "Debug config should have higher rate limit than default")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun createInstrumentation(
        config: ErrorConfig,
        onFlush: (() -> io.opentelemetry.sdk.common.CompletableResultCode)? = null
    ): ErrorInstrumentation {
        return ErrorInstrumentation.initialize(
            config = config,
            logger = logger,
            onFlush = onFlush
        )
    }

    private fun createDeepException(depth: Int): RuntimeException {
        val exception = RuntimeException("deep error")
        // The actual stack depth is determined by call depth; we can't easily control it.
        // Instead, set a custom stack trace with exactly `depth` frames.
        val frames = Array(depth) { i ->
            StackTraceElement("com.test.Class$i", "method$i", "Class$i.kt", i + 1)
        }
        exception.stackTrace = frames
        return exception
    }

    private fun resetErrorInstrumentationSingleton() {
        try {
            val field = ErrorInstrumentation::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // Ignore if field doesn't exist
        }
    }
}
