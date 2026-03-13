/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral tests for [FreezeConfig] thresholds and the [FreezeInstrumentation] event logic.
 *
 * Since the watchdog runs on background/main threads and timing is hard to control in unit tests,
 * we test:
 * 1. Config validation (thresholds, constraints)
 * 2. The emitFreeze method behavior via reflection (ANR vs freeze classification)
 * 3. Enabled/disabled gating
 * 4. Watchdog lifecycle
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FreezeConfigBehaviorTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeCtx(app: Application = mockk(relaxed = true)) =
        InstrumentationContext(
            otelRule.openTelemetry,
            DefaultMobileSessionProvider(),
            WindowEventHub(),
            app
        )

    // ── Config validation ──────────────────────────────────────────────────

    @Test
    fun `freezeThresholdMs below 250 throws`() {
        assertFailsWith<IllegalArgumentException> {
            FreezeConfig(freezeThresholdMs = 200)
        }
    }

    @Test
    fun `freezeThresholdMs at 250 is accepted`() {
        val config = FreezeConfig(freezeThresholdMs = 250)
        assertEquals(250L, config.freezeThresholdMs)
    }

    @Test
    fun `anrThresholdMs below freezeThresholdMs throws`() {
        assertFailsWith<IllegalArgumentException> {
            FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 500)
        }
    }

    @Test
    fun `anrThresholdMs equal to freezeThresholdMs is accepted`() {
        val config = FreezeConfig(freezeThresholdMs = 3000, anrThresholdMs = 3000)
        assertEquals(3000L, config.anrThresholdMs)
    }

    @Test
    fun `default thresholds are freeze=2000 anr=5000`() {
        val config = FreezeConfig()
        assertEquals(2000L, config.freezeThresholdMs)
        assertEquals(5000L, config.anrThresholdMs)
    }

    // ── enabled gating ─────────────────────────────────────────────────────

    @Test
    fun `enabled=false prevents watchdog start`() {
        val inst = FreezeInstrumentation(FreezeConfig(enabled = false))
        inst.install(mockk(relaxed = true), makeCtx())
        assertFalse(inst.isRunning, "enabled=false should prevent watchdog from starting")
    }

    @Test
    fun `enabled=true starts watchdog`() {
        val inst = FreezeInstrumentation(FreezeConfig(enabled = true))
        inst.install(mockk(relaxed = true), makeCtx())
        assertTrue(inst.isRunning, "enabled=true should start watchdog")
        inst.uninstall()
    }

    // ── emitFreeze behavior (via reflection) ───────────────────────────────

    @Test
    fun `freeze below ANR threshold emits only ui_freeze`() {
        val config = FreezeConfig(freezeThresholdMs = 2000, anrThresholdMs = 5000)
        val inst = FreezeInstrumentation(config)
        inst.install(mockk(relaxed = true), makeCtx())

        // Call emitFreeze with duration below ANR threshold
        invokeEmitFreeze(inst, delayMs = 3000L, isAnr = false, screenName = "TestScreen")

        val bodies = otelRule.logRecords.map { it.body.asString() }
        assertTrue(bodies.contains("ui.freeze"),
            "Should emit ui.freeze for freeze below ANR threshold")
        assertFalse(bodies.contains("app.anr"),
            "Should NOT emit app.anr for freeze below ANR threshold")

        // Check duration attribute
        val freezeLog = otelRule.logRecords.first { it.body.asString() == "ui.freeze" }
        val duration = freezeLog.attributes.get(AttributeKey.longKey("mobile.freeze.duration_ms"))
        assertEquals(3000L, duration, "Duration should be 3000ms")

        inst.uninstall()
    }

    @Test
    fun `freeze at or above ANR threshold emits both ui_freeze and app_anr`() {
        val config = FreezeConfig(freezeThresholdMs = 2000, anrThresholdMs = 5000)
        val inst = FreezeInstrumentation(config)
        inst.install(mockk(relaxed = true), makeCtx())

        // Call emitFreeze with duration at ANR threshold
        invokeEmitFreeze(inst, delayMs = 5000L, isAnr = true, screenName = "HomeScreen")

        val bodies = otelRule.logRecords.map { it.body.asString() }
        assertTrue(bodies.contains("ui.freeze"),
            "Should emit ui.freeze for ANR-level freeze")
        assertTrue(bodies.contains("app.anr"),
            "Should emit app.anr when duration >= anrThresholdMs")

        inst.uninstall()
    }

    @Test
    fun `emitFreeze includes screen name when available`() {
        val inst = FreezeInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())

        invokeEmitFreeze(inst, delayMs = 3000L, isAnr = false, screenName = "SettingsScreen")

        val freezeLog = otelRule.logRecords.first { it.body.asString() == "ui.freeze" }
        val screenName = freezeLog.attributes.get(MobileSemconv.SCREEN_NAME)
        assertEquals("SettingsScreen", screenName,
            "Freeze event should include screen name")

        inst.uninstall()
    }

    @Test
    fun `emitFreeze without screen name omits attribute`() {
        val inst = FreezeInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())

        invokeEmitFreeze(inst, delayMs = 3000L, isAnr = false, screenName = null)

        val freezeLog = otelRule.logRecords.first { it.body.asString() == "ui.freeze" }
        val screenName = freezeLog.attributes.get(MobileSemconv.SCREEN_NAME)
        assertEquals(null, screenName,
            "Freeze event should omit screen name when null")

        inst.uninstall()
    }

    @Test
    fun `custom thresholds change ANR classification`() {
        // Set ANR threshold very low (same as freeze)
        val config = FreezeConfig(freezeThresholdMs = 500, anrThresholdMs = 500)
        val inst = FreezeInstrumentation(config)
        inst.install(mockk(relaxed = true), makeCtx())

        // Even a 500ms freeze should be classified as ANR
        invokeEmitFreeze(inst, delayMs = 500L, isAnr = true, screenName = null)

        val bodies = otelRule.logRecords.map { it.body.asString() }
        assertTrue(bodies.contains("app.anr"),
            "With anrThresholdMs=500, a 500ms freeze should emit app.anr")

        inst.uninstall()
    }

    @Test
    fun `high ANR threshold means long freezes are not ANR`() {
        val config = FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 30_000)
        val inst = FreezeInstrumentation(config)
        inst.install(mockk(relaxed = true), makeCtx())

        // 10 second freeze, but ANR threshold is 30s
        invokeEmitFreeze(inst, delayMs = 10_000L, isAnr = false, screenName = null)

        val bodies = otelRule.logRecords.map { it.body.asString() }
        assertTrue(bodies.contains("ui.freeze"))
        assertFalse(bodies.contains("app.anr"),
            "With anrThresholdMs=30000, a 10s freeze should NOT emit app.anr")

        inst.uninstall()
    }

    @Test
    fun `freeze events have ERROR severity`() {
        val inst = FreezeInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())

        invokeEmitFreeze(inst, delayMs = 3000L, isAnr = false, screenName = null)

        val freezeLog = otelRule.logRecords.first { it.body.asString() == "ui.freeze" }
        assertEquals(io.opentelemetry.api.logs.Severity.ERROR, freezeLog.severity,
            "Freeze events should have ERROR severity")

        inst.uninstall()
    }

    @Test
    fun `anr events have ERROR severity`() {
        val inst = FreezeInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())

        invokeEmitFreeze(inst, delayMs = 6000L, isAnr = true, screenName = null)

        val anrLog = otelRule.logRecords.first { it.body.asString() == "app.anr" }
        assertEquals(io.opentelemetry.api.logs.Severity.ERROR, anrLog.severity,
            "ANR events should have ERROR severity")

        inst.uninstall()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Test
    fun `uninstall stops watchdog cleanly`() {
        val inst = FreezeInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())
        assertTrue(inst.isRunning)

        inst.uninstall()
        assertFalse(inst.isRunning, "Watchdog should stop after uninstall")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun invokeEmitFreeze(
        inst: FreezeInstrumentation,
        delayMs: Long,
        isAnr: Boolean,
        screenName: String?
    ) {
        val method = FreezeInstrumentation::class.java.getDeclaredMethod(
            "emitFreeze",
            Long::class.java,
            Boolean::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(inst, delayMs, isAnr, screenName)
    }
}
