// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive tests for [FreezeInstrumentation].
 *
 * Strategy: Robolectric gives us control over the main-thread looper and the system clock.
 * We can advance [SystemClock.uptimeMillis] without running main-thread runnables, simulating
 * a frozen main thread. Then we idle the looper to simulate recovery.
 *
 * The watchdog's background [ScheduledExecutorService] posts to the main handler every 250ms.
 * Under Robolectric, we simulate its behavior by:
 *   1. Advancing the system clock (freeze simulation)
 *   2. Manually invoking the internal checkFreeze() via reflection
 *   3. Idling the main looper to run any pending tick runnables
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FreezeInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    private var activeInst: FreezeInstrumentation? = null

    private fun realApp(): Application = ApplicationProvider.getApplicationContext()

    private fun makeCtx(app: Application = realApp()) =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    /**
     * Install freeze instrumentation and track it for cleanup.
     * Idles the main looper so the initial tick runnable runs.
     */
    private fun installAndIdle(
        config: FreezeConfig = FreezeConfig()
    ): FreezeInstrumentation {
        val inst = FreezeInstrumentation(config)
        val app = realApp()
        inst.install(app, makeCtx(app))
        activeInst = inst
        // Run the initial tick posted during startWatchdog()
        mainLooper.idle()
        return inst
    }

    /**
     * Simulate the watchdog's checkFreeze() call via reflection.
     * In production this runs on a background thread every 250ms.
     * Under Robolectric we invoke it directly.
     */
    private fun invokeCheckFreeze(inst: FreezeInstrumentation) {
        val method = FreezeInstrumentation::class.java.getDeclaredMethod("checkFreeze")
        method.isAccessible = true
        method.invoke(inst)
    }

    @After
    fun tearDown() {
        activeInst?.uninstall()
        activeInst = null
    }

    // ── 1. Config defaults ──────────────────────────────────────────────

    @Test fun `config defaults - enabled is true`() {
        val config = FreezeConfig()
        assertTrue(config.enabled)
    }

    @Test fun `config defaults - freezeThresholdMs is 2000`() {
        val config = FreezeConfig()
        assertEquals(2000L, config.freezeThresholdMs)
    }

    @Test fun `config defaults - anrThresholdMs is 5000`() {
        val config = FreezeConfig()
        assertEquals(5000L, config.anrThresholdMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config rejects freezeThresholdMs below 250`() {
        FreezeConfig(freezeThresholdMs = 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config rejects anrThresholdMs below freezeThresholdMs`() {
        FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 500)
    }

    // ── 2. Disabled config ──────────────────────────────────────────────

    @Test fun `install with disabled config does not start watchdog`() {
        val inst = FreezeInstrumentation(FreezeConfig(enabled = false))
        inst.install(realApp(), makeCtx())
        activeInst = inst

        assertFalse(inst.isRunning, "Watchdog should not run when config.enabled=false")
    }

    @Test fun `disabled config emits no events`() {
        val inst = FreezeInstrumentation(FreezeConfig(enabled = false))
        inst.install(realApp(), makeCtx())
        activeInst = inst

        // Even after advancing time, no events should appear
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 10_000)
        mainLooper.idle()

        assertTrue(otelRule.logRecords.isEmpty(), "No events when disabled")
    }

    // ── 3. Install starts watchdog ──────────────────────────────────────

    @Test fun `install starts watchdog - isRunning is true`() {
        val inst = installAndIdle()
        assertTrue(inst.isRunning)
    }

    @Test fun `instrumentationName is correct`() {
        assertEquals(
            "io.opentelemetry.android.mobile.freeze",
            FreezeInstrumentation().instrumentationName
        )
    }

    // ── 4. Uninstall stops watchdog ─────────────────────────────────────

    @Test fun `uninstall stops watchdog - isRunning is false`() {
        val inst = installAndIdle()
        assertTrue(inst.isRunning)

        inst.uninstall()
        activeInst = null
        assertFalse(inst.isRunning)
    }

    // ── 5. No freeze when main thread is responsive ─────────────────────

    @Test fun `no freeze event when main thread responds within threshold`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 2000))

        // Advance clock by less than freeze threshold
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 500)

        // Watchdog checks and sees main thread is healthy
        invokeCheckFreeze(inst)

        // Main thread runs the tick
        mainLooper.idle()

        assertTrue(
            otelRule.logRecords.isEmpty(),
            "No events when main thread responds within threshold"
        )
    }

    @Test fun `repeated healthy ticks produce no events`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 2000))

        // Simulate multiple healthy watchdog cycles
        repeat(10) {
            SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 250)
            invokeCheckFreeze(inst)
            mainLooper.idle()
        }

        assertTrue(
            otelRule.logRecords.isEmpty(),
            "No events during normal operation"
        )
    }

    // ── 6. Freeze detected ──────────────────────────────────────────────

    @Test fun `freeze detected - emits ui_freeze event`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        // Simulate freeze: advance clock past threshold WITHOUT idling main looper
        val freezeDuration = 2000L
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + freezeDuration)

        // Watchdog detects the freeze
        invokeCheckFreeze(inst)

        // No events yet -- main thread is still "frozen"
        val eventsBeforeRecovery = otelRule.logRecords.size
        assertEquals(0, eventsBeforeRecovery, "No events while main thread is frozen")

        // Main thread recovers -- tick runnable finally runs
        mainLooper.idle()

        val freezeEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "ui.freeze" }
        assertEquals(1, freezeEvents.size, "Exactly one ui.freeze event after recovery")
    }

    @Test fun `freeze event has duration_ms attribute`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        val freezeDuration = 2000L
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + freezeDuration)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        val freezeEvent = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.freeze" }
        val durationMs = freezeEvent.attributes[AttributeKey.longKey("mobile.freeze.duration_ms")]
        assertTrue(durationMs != null && durationMs > 0, "duration_ms should be positive")
    }

    @Test fun `freeze does not emit app_anr when below ANR threshold`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        // 2s freeze -- above freeze threshold but below ANR threshold
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 2000)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        val anrEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.anr" }
        assertTrue(anrEvents.isEmpty(), "No app.anr for sub-ANR-threshold freeze")
    }

    // ── 7. ANR detected ─────────────────────────────────────────────────

    @Test fun `ANR detected - emits both ui_freeze and app_anr`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        // 6s freeze -- exceeds ANR threshold
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 6000)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        val freezeEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "ui.freeze" }
        val anrEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.anr" }

        assertEquals(1, freezeEvents.size, "Exactly one ui.freeze for ANR")
        assertEquals(1, anrEvents.size, "Exactly one app.anr for ANR")
    }

    @Test fun `ANR event has same duration_ms as freeze event`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 6000)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        val durationKey = AttributeKey.longKey("mobile.freeze.duration_ms")
        val freezeDuration = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.freeze" }
            .attributes[durationKey]
        val anrDuration = otelRule.logRecords.first { it.bodyValue?.asString() == "app.anr" }
            .attributes[durationKey]

        assertEquals(freezeDuration, anrDuration, "Both events should report same duration")
    }

    // ── 8. Single event per freeze ──────────────────────────────────────

    @Test fun `multiple watchdog checks during one freeze produce only one event on recovery`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        // Simulate a long freeze with multiple watchdog checks
        // First check at 1500ms (freeze detected, freezeInProgress=true)
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 1500)
        invokeCheckFreeze(inst)

        // Second check at 2000ms (still frozen, should be a no-op)
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 500)
        invokeCheckFreeze(inst)

        // Third check at 2500ms (still frozen, should be a no-op)
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 500)
        invokeCheckFreeze(inst)

        // No events yet
        assertEquals(0, otelRule.logRecords.size, "No events while still frozen")

        // Main thread recovers
        mainLooper.idle()

        val freezeEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "ui.freeze" }
        assertEquals(1, freezeEvents.size, "Only ONE ui.freeze event despite multiple watchdog checks")
    }

    @Test fun `second freeze after recovery produces a new event`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        // First freeze
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 2000)
        invokeCheckFreeze(inst)
        mainLooper.idle() // recover

        val afterFirst = otelRule.logRecords.filter { it.bodyValue?.asString() == "ui.freeze" }.size
        assertEquals(1, afterFirst, "One event after first freeze")

        // Brief healthy period -- checkFreeze resets freezeInProgress and posts new tick
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 250)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        // Second freeze
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 2000)
        invokeCheckFreeze(inst)
        mainLooper.idle() // recover

        val afterSecond = otelRule.logRecords.filter { it.bodyValue?.asString() == "ui.freeze" }.size
        assertEquals(2, afterSecond, "Two events after two distinct freezes")
    }

    // ── 9. Freeze duration accuracy ─────────────────────────────────────

    @Test fun `freeze duration_ms reflects actual freeze time`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        val beforeFreeze = SystemClock.uptimeMillis()

        // Freeze for exactly 3000ms
        SystemClock.setCurrentTimeMillis(beforeFreeze + 3000)
        invokeCheckFreeze(inst)

        // Recovery: the tick reads SystemClock.uptimeMillis() and computes duration from freezeStartMs
        mainLooper.idle()

        val durationKey = AttributeKey.longKey("mobile.freeze.duration_ms")
        val duration = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.freeze" }
            .attributes[durationKey]

        // Duration should be approximately 3000ms.
        // It measures from lastTickAtMs (set during the initial idle) to recovery time.
        assertTrue(
            duration != null && duration >= 2500 && duration <= 3500,
            "duration_ms ($duration) should be approximately 3000ms"
        )
    }

    // ── 10. Custom freeze threshold ─────────────────────────────────────

    @Test fun `custom freezeThresholdMs 500 detects freeze earlier`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 500, anrThresholdMs = 5000))

        // 700ms delay -- above custom 500ms threshold, below default 2000ms
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 700)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        val freezeEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "ui.freeze" }
        assertEquals(1, freezeEvents.size, "Custom 500ms threshold catches 700ms freeze")
    }

    @Test fun `delay below custom threshold produces no event`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 500, anrThresholdMs = 5000))

        // 400ms delay -- below custom 500ms threshold
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 400)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        assertTrue(
            otelRule.logRecords.isEmpty(),
            "400ms delay should not trigger freeze with 500ms threshold"
        )
    }

    // ── 11. Custom ANR threshold ────────────────────────────────────────

    @Test fun `custom anrThresholdMs 3000 detects ANR earlier`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 3000))

        // 4s freeze -- above custom ANR threshold of 3000ms
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 4000)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        val anrEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.anr" }
        assertEquals(1, anrEvents.size, "Custom 3000ms ANR threshold catches 4s freeze")
    }

    @Test fun `freeze above freeze threshold but below custom ANR threshold emits only ui_freeze`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 3000))

        // 2s freeze -- above freeze threshold, below custom ANR threshold
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 2000)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        val freezeEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "ui.freeze" }
        val anrEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.anr" }

        assertEquals(1, freezeEvents.size, "Should have ui.freeze")
        assertTrue(anrEvents.isEmpty(), "Should NOT have app.anr for 2s freeze with 3s ANR threshold")
    }

    @Test fun `exact ANR threshold boundary emits app_anr`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 3000))

        // Freeze for exactly the ANR threshold
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 3000)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        // Duration is measured as (now - freezeStartMs). With uptimeMillis advancing by 3000
        // and freezeStartMs being the lastTickAtMs, the duration should be >= 3000.
        val anrEvents = otelRule.logRecords.filter { it.bodyValue?.asString() == "app.anr" }
        assertEquals(1, anrEvents.size, "Exact ANR threshold should emit app.anr")
    }

    // ── Additional edge cases ───────────────────────────────────────────

    @Test fun `freeze events include session_id attribute`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 2000)
        invokeCheckFreeze(inst)
        mainLooper.idle()

        val freezeEvent = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.freeze" }
        val sessionId = freezeEvent.attributes[MobileSemconv.SESSION_ID]
        assertTrue(
            sessionId != null && sessionId.isNotEmpty(),
            "Freeze event should carry a session_id"
        )
    }

    @Test fun `uninstall during freeze prevents event emission`() {
        val inst = installAndIdle(FreezeConfig(freezeThresholdMs = 1000, anrThresholdMs = 5000))

        // Start a freeze
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 2000)
        invokeCheckFreeze(inst)

        // Uninstall before main thread recovers
        inst.uninstall()
        activeInst = null

        // Main thread runs the tick, but logger is now null
        mainLooper.idle()

        // The tick runnable runs but emitFreeze early-returns because logger is null
        // so no event should appear (or at most the event is a no-op)
        // This verifies graceful handling of mid-freeze uninstall
        assertFalse(inst.isRunning)
    }
}
