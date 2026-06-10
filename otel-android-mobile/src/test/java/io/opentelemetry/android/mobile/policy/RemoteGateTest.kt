/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for [RemoteGate] — the thread-safe holder for the remote kill switch
 * and global head-sampling override.
 *
 * Covers default open state, enabled/disabled drops, rate boundaries (0 / 1),
 * statistical behaviour at 0.5, fail-open precedence (absent block re-opens),
 * and concurrent read/write safety.
 */
class RemoteGateTest {

    // ── Defaults (fail-open) ─────────────────────────────────────────────

    @Test
    fun `fresh gate is enabled at full rate`() {
        val gate = RemoteGate()
        assertTrue(gate.enabled)
        assertEquals(1.0, gate.sampleRate, 0.0001)
        assertTrue("fresh gate must allow events", gate.allowEvent())
    }

    // ── Kill switch ──────────────────────────────────────────────────────

    @Test
    fun `disabled gate drops every event`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = false, sampleRate = 1.0))
        assertFalse(gate.enabled)
        repeat(1000) { assertFalse(gate.allowEvent()) }
    }

    // ── Rate boundaries ──────────────────────────────────────────────────

    @Test
    fun `sample_rate 1_0 keeps all events`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = true, sampleRate = 1.0))
        repeat(1000) { assertTrue(gate.allowEvent()) }
    }

    @Test
    fun `sample_rate 0_0 drops all events`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = true, sampleRate = 0.0))
        repeat(1000) { assertFalse(gate.allowEvent()) }
    }

    @Test
    fun `sample_rate 0_5 keeps roughly half over large N`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = true, sampleRate = 0.5))
        val n = 100_000
        var kept = 0
        repeat(n) { if (gate.allowEvent()) kept++ }
        val fraction = kept.toDouble() / n
        // Non-biased RNG over 100k draws: expect 0.5 within a generous tolerance.
        assertTrue("Expected ~0.5 kept, got $fraction", fraction in 0.47..0.53)
    }

    @Test
    fun `disabled overrides any sample_rate`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = false, sampleRate = 1.0))
        repeat(1000) { assertFalse(gate.allowEvent()) }
    }

    // ── Clamping flows through apply ─────────────────────────────────────

    @Test
    fun `apply clamps out-of-range rate`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = true, sampleRate = 9.0))
        assertEquals(1.0, gate.sampleRate, 0.0001)
        gate.apply(SdkConfig(enabled = true, sampleRate = -3.0))
        assertEquals(0.0, gate.sampleRate, 0.0001)
    }

    // ── Fail-open precedence ─────────────────────────────────────────────

    @Test
    fun `null apply re-opens the gate (absent block = no restriction)`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = false, sampleRate = 0.0))
        assertFalse(gate.enabled)

        // A later config that OMITS the sdk block must re-enable the fleet.
        gate.apply(null)
        assertTrue(gate.enabled)
        assertEquals(1.0, gate.sampleRate, 0.0001)
    }

    @Test
    fun `not calling apply preserves last value (fetch-failure contract)`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = false, sampleRate = 0.2))
        // Simulate a transient fetch failure: the evaluator simply does NOT call apply.
        // State must persist untouched.
        assertFalse(gate.enabled)
        assertEquals(0.2, gate.sampleRate, 0.0001)
    }

    @Test
    fun `snapshot returns consistent pair`() {
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = false, sampleRate = 0.3))
        val snap = gate.snapshot()
        assertFalse(snap.enabled)
        assertEquals(0.3, snap.sampleRate, 0.0001)
    }

    // ── Thread safety ─────────────────────────────────────────────────────

    @Test
    fun `concurrent apply and allowEvent do not throw or corrupt state`() {
        val gate = RemoteGate()
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(200)
        val errors = mutableListOf<Throwable>()

        repeat(100) { i ->
            executor.submit {
                try {
                    gate.apply(SdkConfig(enabled = i % 2 == 0, sampleRate = (i % 11) / 10.0))
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                } finally {
                    latch.countDown()
                }
            }
            executor.submit {
                try {
                    gate.allowEvent()
                    gate.snapshot()
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timed out", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("Unexpected errors: $errors", errors.isEmpty())

        // Final state must be a valid, internally-consistent snapshot.
        val snap = gate.snapshot()
        assertTrue(snap.sampleRate in 0.0..1.0)
    }
}
