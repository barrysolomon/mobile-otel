/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivacyUtilsTest {

    private val strictOptions = AutoCaptureOptions(privacyMode = PrivacyMode.STRICT)
    private val relaxedOptions = AutoCaptureOptions(privacyMode = PrivacyMode.RELAXED)

    // ── STRICT mode: hashing ──────────────────────────────────────────────────

    @Test
    fun `STRICT mode hashes non-null text`() {
        val result = PrivacyUtils.maybeHash("Submit", strictOptions)
        // SHA-256 hex is 64 chars
        assertEquals(64, result?.length, "Hash should be 64-char hex SHA-256")
        assertTrue(result?.all { it.isLetterOrDigit() } == true)
    }

    @Test
    fun `STRICT mode produces deterministic hash for same input`() {
        val result1 = PrivacyUtils.maybeHash("Add to cart", strictOptions)
        val result2 = PrivacyUtils.maybeHash("Add to cart", strictOptions)
        assertEquals(result1, result2)
    }

    @Test
    fun `STRICT mode produces different hashes for different inputs`() {
        val hash1 = PrivacyUtils.maybeHash("Submit", strictOptions)
        val hash2 = PrivacyUtils.maybeHash("Cancel", strictOptions)
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `STRICT mode hash differs with salt`() {
        val saltedOptions = AutoCaptureOptions(
            privacyMode = PrivacyMode.STRICT,
            hashSalt = "mysalt"
        )
        val unsalted = PrivacyUtils.maybeHash("button_label", strictOptions)
        val salted = PrivacyUtils.maybeHash("button_label", saltedOptions)
        assertNotEquals(unsalted, salted, "Salt must change the hash output")
    }

    @Test
    fun `STRICT mode with same salt produces same hash`() {
        val options1 = AutoCaptureOptions(privacyMode = PrivacyMode.STRICT, hashSalt = "salt1")
        val options2 = AutoCaptureOptions(privacyMode = PrivacyMode.STRICT, hashSalt = "salt1")
        assertEquals(
            PrivacyUtils.maybeHash("label", options1),
            PrivacyUtils.maybeHash("label", options2)
        )
    }

    @Test
    fun `STRICT mode returns null for null input`() {
        assertNull(PrivacyUtils.maybeHash(null, strictOptions))
    }

    @Test
    fun `STRICT mode returns null for empty string`() {
        assertNull(PrivacyUtils.maybeHash("", strictOptions))
    }

    @Test
    fun `STRICT mode returns null for whitespace-only string`() {
        assertNull(PrivacyUtils.maybeHash("   ", strictOptions))
    }

    @Test
    fun `STRICT mode trims whitespace before hashing`() {
        val trimmed = PrivacyUtils.maybeHash("label", strictOptions)
        val padded = PrivacyUtils.maybeHash("  label  ", strictOptions)
        assertEquals(trimmed, padded, "Leading/trailing whitespace should be trimmed before hashing")
    }

    // ── RELAXED mode: plaintext passthrough ───────────────────────────────────

    @Test
    fun `RELAXED mode returns raw text`() {
        val result = PrivacyUtils.maybeHash("Add to cart", relaxedOptions)
        assertEquals("Add to cart", result)
    }

    @Test
    fun `RELAXED mode returns null for null input`() {
        assertNull(PrivacyUtils.maybeHash(null, relaxedOptions))
    }

    @Test
    fun `RELAXED mode returns null for empty string`() {
        assertNull(PrivacyUtils.maybeHash("", relaxedOptions))
    }

    @Test
    fun `RELAXED mode trims whitespace`() {
        val result = PrivacyUtils.maybeHash("  Submit  ", relaxedOptions)
        assertEquals("Submit", result)
    }

    // ── PrivacyMode enum ──────────────────────────────────────────────────────

    @Test
    fun `PrivacyMode has STRICT and RELAXED variants`() {
        val modes = PrivacyMode.values()
        assertTrue(modes.contains(PrivacyMode.STRICT))
        assertTrue(modes.contains(PrivacyMode.RELAXED))
        assertEquals(2, modes.size)
    }

    @Test
    fun `AutoCaptureOptions defaults to STRICT privacy mode`() {
        val options = AutoCaptureOptions()
        assertEquals(PrivacyMode.STRICT, options.privacyMode)
    }

    @Test
    fun `AutoCaptureOptions defaults to null salt`() {
        val options = AutoCaptureOptions()
        assertNull(options.hashSalt)
    }

    // ── AutoCaptureOptions validation ─────────────────────────────────────────

    @Test
    fun `AutoCaptureOptions bucketGridSize must be at least 2`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(bucketGridSize = 1)
        }
    }

    @Test
    fun `AutoCaptureOptions maxHitTestDepth must be at least 1`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(maxHitTestDepth = 0)
        }
    }

    @Test
    fun `AutoCaptureOptions freezeThresholdMs must be at least 250ms`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AutoCaptureOptions(freezeThresholdMs = 249)
        }
    }

    @Test
    fun `AutoCaptureOptions accepts valid values`() {
        val options = AutoCaptureOptions(
            bucketGridSize = 4,
            maxHitTestDepth = 5,
            freezeThresholdMs = 500,
            privacyMode = PrivacyMode.RELAXED,
            hashSalt = "test-salt"
        )
        assertEquals(4, options.bucketGridSize)
        assertEquals(PrivacyMode.RELAXED, options.privacyMode)
        assertEquals("test-salt", options.hashSalt)
    }
}
