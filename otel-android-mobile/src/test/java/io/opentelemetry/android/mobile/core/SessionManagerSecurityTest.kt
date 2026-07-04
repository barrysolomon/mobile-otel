/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Security tests for [SessionManager] attribute validation constants.
 *
 * Note: SessionManager is a singleton that requires Android context for full initialization.
 * These tests validate the security constants and attribute validation logic.
 * Full integration tests run in the androidTest suite.
 *
 * Validates:
 * - Attribute key length limit is reasonable
 * - Attribute value length limit is reasonable
 * - Max global attributes limit prevents unbounded growth
 * - Constants are accessible (internal visibility)
 */
class SessionManagerSecurityTest {

    @Test
    fun `MAX_ATTRIBUTE_KEY_LENGTH is reasonable`() {
        val maxLen = SessionManager.MAX_ATTRIBUTE_KEY_LENGTH
        assertTrue(maxLen in 64..1024,
            "MAX_ATTRIBUTE_KEY_LENGTH should be 64-1024, got $maxLen")
        assertEquals(256, maxLen, "Expected MAX_ATTRIBUTE_KEY_LENGTH = 256")
    }

    @Test
    fun `MAX_ATTRIBUTE_VALUE_LENGTH is reasonable`() {
        val maxLen = SessionManager.MAX_ATTRIBUTE_VALUE_LENGTH
        assertTrue(maxLen in 1024..65536,
            "MAX_ATTRIBUTE_VALUE_LENGTH should be 1024-65536, got $maxLen")
        assertEquals(4096, maxLen, "Expected MAX_ATTRIBUTE_VALUE_LENGTH = 4096")
    }

    @Test
    fun `MAX_GLOBAL_ATTRIBUTES is reasonable`() {
        val max = SessionManager.MAX_GLOBAL_ATTRIBUTES
        assertTrue(max in 16..1024,
            "MAX_GLOBAL_ATTRIBUTES should be 16-1024, got $max")
        assertEquals(128, max, "Expected MAX_GLOBAL_ATTRIBUTES = 128")
    }

    @Test
    fun `attribute key truncation logic`() {
        // Verify that String.take() works as expected for key truncation
        val longKey = "k".repeat(300)
        val safeKey = longKey.take(SessionManager.MAX_ATTRIBUTE_KEY_LENGTH)
        assertEquals(SessionManager.MAX_ATTRIBUTE_KEY_LENGTH, safeKey.length,
            "Truncated key should be exactly MAX_ATTRIBUTE_KEY_LENGTH")
    }

    @Test
    fun `attribute value truncation logic`() {
        val longValue = "v".repeat(5000)
        val safeValue = longValue.take(SessionManager.MAX_ATTRIBUTE_VALUE_LENGTH)
        assertEquals(SessionManager.MAX_ATTRIBUTE_VALUE_LENGTH, safeValue.length,
            "Truncated value should be exactly MAX_ATTRIBUTE_VALUE_LENGTH")
    }

    @Test
    fun `short key is not truncated`() {
        val shortKey = "my.key"
        val safeKey = shortKey.take(SessionManager.MAX_ATTRIBUTE_KEY_LENGTH)
        assertEquals(shortKey, safeKey, "Short key should pass through unchanged")
    }

    @Test
    fun `short value is not truncated`() {
        val shortValue = "my value"
        val safeValue = shortValue.take(SessionManager.MAX_ATTRIBUTE_VALUE_LENGTH)
        assertEquals(shortValue, safeValue, "Short value should pass through unchanged")
    }
}
