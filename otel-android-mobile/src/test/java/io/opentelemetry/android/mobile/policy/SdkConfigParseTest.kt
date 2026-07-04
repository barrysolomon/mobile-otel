/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for parsing the remote-config root `sdk` block (remote kill switch +
 * global head-sampling override) added by the remote-kill-switch feature.
 *
 * Covers presence, absence, malformed types, and `sample_rate` clamping across
 * both the v2 (FSM) and v1-compiler parse paths, plus the [SdkConfig] value
 * object's own clamp invariant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SdkConfigParseTest {

    // ── v2: presence ─────────────────────────────────────────────────────

    @Test
    fun `parseConfigV2 parses sdk block with enabled false and rate`() {
        val json = """
            {
              "version": 2,
              "sdk": { "enabled": false, "sample_rate": 0.25 },
              "workflows": []
            }
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        val sdk = config!!.sdk
        assertNotNull("sdk block should be parsed", sdk)
        assertFalse(sdk!!.enabled)
        assertEquals(0.25, sdk.clampedSampleRate, 0.0001)
    }

    @Test
    fun `parseConfigV2 parses sdk alongside workflows`() {
        val json = """
            {
              "version": 2,
              "sdk": { "enabled": true, "sample_rate": 0.5 },
              "workflows": [{
                "id": "crash-handler", "enabled": true, "priority": 1, "initial_state": "default",
                "states": [{
                  "id": "default",
                  "matchers": [{"type": "crash", "config": {}}],
                  "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 5}}]}
                }]
              }]
            }
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        assertEquals(1, config!!.policies.size)
        assertNotNull(config.sdk)
        assertTrue(config.sdk!!.enabled)
        assertEquals(0.5, config.sdk.clampedSampleRate, 0.0001)
    }

    // ── v2: absence (fail-open) ──────────────────────────────────────────

    @Test
    fun `parseConfigV2 yields null sdk when block absent`() {
        val json = """{"version": 2, "workflows": []}"""
        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        assertNull("absent sdk block must parse to null (no restriction)", config!!.sdk)
    }

    // ── v2: defaults for partial blocks ──────────────────────────────────

    @Test
    fun `parseConfigV2 defaults enabled true when only sample_rate present`() {
        val json = """{"version": 2, "sdk": {"sample_rate": 0.3}, "workflows": []}"""
        val sdk = PolicyEvaluator.parseConfigV2(json)!!.sdk
        assertNotNull(sdk)
        assertTrue("absent enabled defaults to true", sdk!!.enabled)
        assertEquals(0.3, sdk.clampedSampleRate, 0.0001)
    }

    @Test
    fun `parseConfigV2 defaults rate 1_0 when only enabled present`() {
        val json = """{"version": 2, "sdk": {"enabled": false}, "workflows": []}"""
        val sdk = PolicyEvaluator.parseConfigV2(json)!!.sdk
        assertNotNull(sdk)
        assertFalse(sdk!!.enabled)
        assertEquals("absent sample_rate defaults to 1.0", 1.0, sdk.clampedSampleRate, 0.0001)
    }

    // ── v2: malformed types degrade to defaults, never crash ─────────────

    @Test
    fun `parseConfigV2 malformed sdk field types fall back to defaults`() {
        // enabled is a string, sample_rate is a string — org.json opt* coerces
        // what it can and falls back otherwise; either way no throw.
        val json = """{"version": 2, "sdk": {"enabled": "nope", "sample_rate": "fast"}, "workflows": []}"""
        val sdk = PolicyEvaluator.parseConfigV2(json)!!.sdk
        assertNotNull(sdk)
        // "nope" is not a recognized boolean → optBoolean returns the fallback (true).
        assertTrue(sdk!!.enabled)
        // "fast" is not numeric → optDouble returns the fallback (1.0).
        assertEquals(1.0, sdk.clampedSampleRate, 0.0001)
    }

    @Test
    fun `parseConfigV2 sdk as wrong json type is treated as absent`() {
        // "sdk" is an array, not an object → optJSONObject returns null → absent.
        val json = """{"version": 2, "sdk": [1,2,3], "workflows": []}"""
        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        assertNull(config!!.sdk)
    }

    // ── v2: clamping ──────────────────────────────────────────────────────

    @Test
    fun `parseConfigV2 clamps sample_rate above 1_0 to 1_0`() {
        val json = """{"version": 2, "sdk": {"sample_rate": 4.2}, "workflows": []}"""
        val sdk = PolicyEvaluator.parseConfigV2(json)!!.sdk
        assertEquals(1.0, sdk!!.clampedSampleRate, 0.0001)
    }

    @Test
    fun `parseConfigV2 clamps negative sample_rate to 0_0`() {
        val json = """{"version": 2, "sdk": {"sample_rate": -0.5}, "workflows": []}"""
        val sdk = PolicyEvaluator.parseConfigV2(json)!!.sdk
        assertEquals(0.0, sdk!!.clampedSampleRate, 0.0001)
    }

    // ── v1-compiler path also parses sdk ─────────────────────────────────

    @Test
    fun `parseConfigV1Compiler parses sdk block`() {
        val json = """
            {
              "version": 1,
              "sdk": { "enabled": false, "sample_rate": 0.1 },
              "workflows": [{
                "id": "wf", "enabled": true,
                "trigger": {"any": [{"event": "app.crash"}]},
                "actions": [{"type": "flush_window", "minutes": 5}]
              }]
            }
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV1Compiler(json)
        assertNotNull(config)
        assertNotNull(config!!.sdk)
        assertFalse(config.sdk!!.enabled)
        assertEquals(0.1, config.sdk.clampedSampleRate, 0.0001)
    }

    @Test
    fun `parseConfigAny routes sdk for both versions`() {
        val v2 = PolicyEvaluator.parseConfigAny("""{"version": 2, "sdk": {"enabled": false}, "workflows": []}""")
        assertNotNull(v2!!.sdk)
        assertFalse(v2.sdk!!.enabled)

        val v1 = PolicyEvaluator.parseConfigAny(
            """{"version": 1, "sdk": {"sample_rate": 0.2}, "workflows": []}"""
        )
        assertNotNull(v1!!.sdk)
        assertEquals(0.2, v1.sdk!!.clampedSampleRate, 0.0001)
    }

    // ── SdkConfig value object clamps even on direct construction ────────

    @Test
    fun `SdkConfig clamps rate on direct construction`() {
        assertEquals(1.0, SdkConfig(sampleRate = 2.0).clampedSampleRate, 0.0001)
        assertEquals(0.0, SdkConfig(sampleRate = -1.0).clampedSampleRate, 0.0001)
        assertEquals(0.5, SdkConfig(sampleRate = 0.5).clampedSampleRate, 0.0001)
    }

    @Test
    fun `SdkConfig NaN rate fails open to 1_0`() {
        assertEquals(1.0, SdkConfig(sampleRate = Double.NaN).clampedSampleRate, 0.0001)
    }

    @Test
    fun `SdkConfig defaults are enabled and full rate`() {
        val sdk = SdkConfig()
        assertTrue(sdk.enabled)
        assertEquals(1.0, sdk.clampedSampleRate, 0.0001)
    }
}
