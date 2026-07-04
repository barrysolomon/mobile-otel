/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.tailing

import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.logs.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [LogTailingConfig] and its effect on [LogTailBuffer].
 *
 * Each test proves that a config field actually changes runtime behavior,
 * not just that it compiles or is accepted without error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogTailingConfigBehaviorTest {

    // ── 1. enabled=false prevents all log buffering ─────────────────────────

    @Test
    fun `enabled=false prevents all log buffering`() {
        val config = LogTailingConfig(enabled = false, tailSize = 10)
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("event.1", severity = Severity.INFO))
        buffer.addLog(TestUtils.createTestLogRecord("event.2", severity = Severity.ERROR))
        buffer.addLog(TestUtils.createTestLogRecord("event.3", severity = Severity.FATAL))

        assertEquals("enabled=false should prevent all buffering", 0, buffer.size())
    }

    // ── 2. enabled=true allows log buffering ────────────────────────────────

    @Test
    fun `enabled=true allows log buffering`() {
        val config = LogTailingConfig(enabled = true, tailSize = 10)
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("event.1", severity = Severity.INFO))
        buffer.addLog(TestUtils.createTestLogRecord("event.2", severity = Severity.ERROR))

        assertEquals("enabled=true should buffer logs", 2, buffer.size())
    }

    // ── 3. includeDebugLogs=false excludes DEBUG severity ───────────────────

    @Test
    fun `includeDebugLogs=false excludes DEBUG severity`() {
        val config = LogTailingConfig(includeDebugLogs = false, tailSize = 10)
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("debug-event", severity = Severity.DEBUG))

        assertEquals("includeDebugLogs=false should exclude DEBUG logs", 0, buffer.size())
    }

    // ── 4. includeDebugLogs=true includes DEBUG severity ────────────────────

    @Test
    fun `includeDebugLogs=true includes DEBUG severity`() {
        val config = LogTailingConfig(includeDebugLogs = true, tailSize = 10)
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("debug-event", severity = Severity.DEBUG))

        assertEquals("includeDebugLogs=true should include DEBUG logs", 1, buffer.size())
    }

    // ── 5. includeInfoLogs=false excludes INFO severity ─────────────────────

    @Test
    fun `includeInfoLogs=false excludes INFO severity`() {
        val config = LogTailingConfig(includeInfoLogs = false, tailSize = 10)
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("info-event", severity = Severity.INFO))

        assertEquals("includeInfoLogs=false should exclude INFO logs", 0, buffer.size())
    }

    // ── 6. includeWarnLogs=false excludes WARN severity ─────────────────────

    @Test
    fun `includeWarnLogs=false excludes WARN severity`() {
        val config = LogTailingConfig(includeWarnLogs = false, tailSize = 10)
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("warn-event", severity = Severity.WARN))

        assertEquals("includeWarnLogs=false should exclude WARN logs", 0, buffer.size())
    }

    // ── 7. includeErrorLogs=false excludes ERROR severity ───────────────────

    @Test
    fun `includeErrorLogs=false excludes ERROR severity`() {
        val config = LogTailingConfig(includeErrorLogs = false, tailSize = 10)
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("error-event", severity = Severity.ERROR))

        assertEquals("includeErrorLogs=false should exclude ERROR logs", 0, buffer.size())
    }

    // ── 8. includeFatalLogs=false excludes FATAL severity ───────────────────

    @Test
    fun `includeFatalLogs=false excludes FATAL severity`() {
        val config = LogTailingConfig(includeFatalLogs = false, tailSize = 10)
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("fatal-event", severity = Severity.FATAL))

        assertEquals("includeFatalLogs=false should exclude FATAL logs", 0, buffer.size())
    }

    // ── 9. tailSize controls buffer capacity (FIFO eviction) ────────────────

    @Test
    fun `tailSize controls buffer capacity with FIFO eviction`() {
        val config = LogTailingConfig(tailSize = 3)
        val buffer = LogTailBuffer(config)

        // Add 5 logs to a buffer that holds 3
        buffer.addLog(TestUtils.createTestLogRecord("oldest", severity = Severity.INFO))
        buffer.addLog(TestUtils.createTestLogRecord("old", severity = Severity.INFO))
        buffer.addLog(TestUtils.createTestLogRecord("middle", severity = Severity.INFO))
        buffer.addLog(TestUtils.createTestLogRecord("recent", severity = Severity.INFO))
        buffer.addLog(TestUtils.createTestLogRecord("newest", severity = Severity.INFO))

        assertEquals("buffer size should not exceed tailSize", 3, buffer.size())

        // getTail returns newest first
        val tail = buffer.getTail()
        assertEquals("newest log should be first in tail", "newest", tail[0].bodyValue?.asString())
        assertEquals("recent log should be second in tail", "recent", tail[1].bodyValue?.asString())
        assertEquals("middle log should be third in tail", "middle", tail[2].bodyValue?.asString())
    }

    // ── 10. tailSize validation (must be 1-1000) ────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `tailSize=0 throws IllegalArgumentException`() {
        LogTailingConfig(tailSize = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tailSize=-1 throws IllegalArgumentException`() {
        LogTailingConfig(tailSize = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tailSize=1001 throws IllegalArgumentException`() {
        LogTailingConfig(tailSize = 1001)
    }

    @Test
    fun `tailSize=1 is valid minimum`() {
        val config = LogTailingConfig(tailSize = 1)
        assertEquals(1, config.tailSize)
    }

    @Test
    fun `tailSize=1000 is valid maximum`() {
        val config = LogTailingConfig(tailSize = 1000)
        assertEquals(1000, config.tailSize)
    }

    // ── 11. Severity filtering happens before trigger evaluation ────────────

    @Test
    fun `severity filtering prevents trigger evaluation`() {
        // Config excludes DEBUG logs
        val config = LogTailingConfig(includeDebugLogs = false, tailSize = 10)
        val trigger = LogTailTrigger.onAnyError()
        val buffer = LogTailBuffer(config, listOf(trigger))

        // Add a DEBUG log -- it should be filtered out before triggers run
        val triggered = buffer.addLog(
            TestUtils.createTestLogRecord("debug-event", severity = Severity.DEBUG)
        )

        assertTrue("no triggers should fire for filtered-out severity", triggered.isEmpty())
        assertEquals("filtered log should not be in buffer", 0, buffer.size())
    }

    @Test
    fun `severity filtering allows trigger evaluation for included severity`() {
        val config = LogTailingConfig(includeErrorLogs = true, tailSize = 10)
        val trigger = LogTailTrigger.onAnyError()
        val buffer = LogTailBuffer(config, listOf(trigger))

        val triggered = buffer.addLog(
            TestUtils.createTestLogRecord("error-event", severity = Severity.ERROR)
        )

        assertFalse("trigger should fire for included severity", triggered.isEmpty())
        assertEquals("log should be in buffer", 1, buffer.size())
    }

    // ── 12. Preset configs have correct values ──────────────────────────────

    @Test
    fun `default() preset has expected values`() {
        val config = LogTailingConfig.default()

        assertTrue("default should be enabled", config.enabled)
        assertEquals("default tailSize should be 100", 100, config.tailSize)
        assertFalse("default should exclude debug logs", config.includeDebugLogs)
        assertTrue("default should include info logs", config.includeInfoLogs)
        assertTrue("default should include warn logs", config.includeWarnLogs)
        assertTrue("default should include error logs", config.includeErrorLogs)
        assertTrue("default should include fatal logs", config.includeFatalLogs)
    }

    @Test
    fun `errorsOnly() preset has expected values`() {
        val config = LogTailingConfig.errorsOnly()

        assertTrue("errorsOnly should be enabled", config.enabled)
        assertEquals("errorsOnly tailSize should be 50", 50, config.tailSize)
        assertFalse("errorsOnly should exclude debug logs", config.includeDebugLogs)
        assertFalse("errorsOnly should exclude info logs", config.includeInfoLogs)
        assertFalse("errorsOnly should exclude warn logs", config.includeWarnLogs)
        assertTrue("errorsOnly should include error logs", config.includeErrorLogs)
        assertTrue("errorsOnly should include fatal logs", config.includeFatalLogs)
    }

    @Test
    fun `verbose() preset has expected values`() {
        val config = LogTailingConfig.verbose()

        assertTrue("verbose should be enabled", config.enabled)
        assertEquals("verbose tailSize should be 100", 100, config.tailSize)
        assertTrue("verbose should include debug logs", config.includeDebugLogs)
        assertTrue("verbose should include info logs", config.includeInfoLogs)
        assertTrue("verbose should include warn logs", config.includeWarnLogs)
        assertTrue("verbose should include error logs", config.includeErrorLogs)
        assertTrue("verbose should include fatal logs", config.includeFatalLogs)
    }

    @Test
    fun `disabled() preset throws because tailSize=0 violates validation`() {
        // Note: LogTailingConfig.disabled() uses tailSize=0, but init requires tailSize > 0.
        // This documents the current behavior -- disabled() cannot be constructed.
        var threw = false
        try {
            LogTailingConfig.disabled()
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(
                "exception message should mention tailSize",
                e.message!!.contains("tailSize")
            )
        }
        assertTrue("disabled() should throw IllegalArgumentException", threw)
    }

    // ── Bonus: errorsOnly preset actually filters non-error logs ────────────

    @Test
    fun `errorsOnly() preset filters INFO and WARN but keeps ERROR and FATAL`() {
        val config = LogTailingConfig.errorsOnly()
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("info-event", severity = Severity.INFO))
        buffer.addLog(TestUtils.createTestLogRecord("warn-event", severity = Severity.WARN))
        buffer.addLog(TestUtils.createTestLogRecord("error-event", severity = Severity.ERROR))
        buffer.addLog(TestUtils.createTestLogRecord("fatal-event", severity = Severity.FATAL))

        assertEquals("errorsOnly should only buffer ERROR and FATAL", 2, buffer.size())
    }

    @Test
    fun `verbose() preset includes DEBUG logs`() {
        val config = LogTailingConfig.verbose()
        val buffer = LogTailBuffer(config)

        buffer.addLog(TestUtils.createTestLogRecord("debug-event", severity = Severity.DEBUG))
        buffer.addLog(TestUtils.createTestLogRecord("info-event", severity = Severity.INFO))

        assertEquals("verbose should include both DEBUG and INFO", 2, buffer.size())
    }
}
