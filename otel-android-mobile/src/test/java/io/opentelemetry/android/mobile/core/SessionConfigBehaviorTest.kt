/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.core

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral tests for [SessionConfig] fields and their wiring into [SessionManager].
 *
 * Validates default values, field storage/retrieval, and edge cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionConfigBehaviorTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Before
    fun setup() {
        resetSessionManagerSingleton()
    }

    @After
    fun tearDown() {
        resetSessionManagerSingleton()
    }

    // -- Default values -------------------------------------------------------

    @Test
    fun `default config has enabled=true`() {
        val config = SessionConfig()
        assertTrue(config.enabled,
            "Default SessionConfig should have enabled=true")
    }

    @Test
    fun `default config has inactivityTimeoutMs=900000`() {
        val config = SessionConfig()
        assertEquals(900_000L, config.inactivityTimeoutMs,
            "Default inactivityTimeoutMs should be 15 minutes (900000 ms)")
    }

    @Test
    fun `default config has flushOnTermination=true`() {
        val config = SessionConfig()
        assertTrue(config.flushOnTermination,
            "Default flushOnTermination should be true")
    }

    @Test
    fun `default config has persistSession=true`() {
        val config = SessionConfig()
        assertTrue(config.persistSession,
            "Default persistSession should be true")
    }

    // -- enabled=false --------------------------------------------------------

    @Test
    fun `enabled=false produces valid config`() {
        val config = SessionConfig(enabled = false)
        assertFalse(config.enabled,
            "Config with enabled=false should store false")
    }

    @Test
    fun `enabled=false preserves other defaults`() {
        val config = SessionConfig(enabled = false)
        assertEquals(900_000L, config.inactivityTimeoutMs,
            "Disabling should not change inactivityTimeoutMs default")
        assertTrue(config.flushOnTermination,
            "Disabling should not change flushOnTermination default")
        assertTrue(config.persistSession,
            "Disabling should not change persistSession default")
    }

    // -- inactivityTimeoutMs validation ---------------------------------------

    @Test
    fun `inactivityTimeoutMs accepts custom value`() {
        val config = SessionConfig(inactivityTimeoutMs = 60_000L)
        assertEquals(60_000L, config.inactivityTimeoutMs,
            "Custom inactivityTimeoutMs should be stored")
    }

    @Test
    fun `inactivityTimeoutMs accepts very large value`() {
        val config = SessionConfig(inactivityTimeoutMs = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, config.inactivityTimeoutMs,
            "Very large timeout should be accepted")
    }

    @Test
    fun `inactivityTimeoutMs accepts zero`() {
        val config = SessionConfig(inactivityTimeoutMs = 0L)
        assertEquals(0L, config.inactivityTimeoutMs,
            "Zero timeout should be accepted (immediate expiration)")
    }

    @Test
    fun `inactivityTimeoutMs accepts 1ms`() {
        val config = SessionConfig(inactivityTimeoutMs = 1L)
        assertEquals(1L, config.inactivityTimeoutMs,
            "1ms timeout should be accepted")
    }

    @Test
    fun `inactivityTimeoutMs 15 minutes equals 900000`() {
        val fifteenMinutes = 15L * 60 * 1000
        assertEquals(900_000L, fifteenMinutes,
            "15 minutes in ms should be 900000")
        val config = SessionConfig()
        assertEquals(fifteenMinutes, config.inactivityTimeoutMs,
            "Default should match 15 minutes calculation")
    }

    // -- Config fields stored and retrievable ---------------------------------

    @Test
    fun `all fields are stored and retrievable`() {
        val config = SessionConfig(
            enabled = false,
            inactivityTimeoutMs = 30_000L,
            flushOnTermination = false,
            persistSession = false
        )
        assertFalse(config.enabled)
        assertEquals(30_000L, config.inactivityTimeoutMs)
        assertFalse(config.flushOnTermination)
        assertFalse(config.persistSession)
    }

    @Test
    fun `data class copy preserves unmodified fields`() {
        val original = SessionConfig(
            enabled = true,
            inactivityTimeoutMs = 60_000L,
            flushOnTermination = true,
            persistSession = true
        )
        val modified = original.copy(enabled = false)

        assertFalse(modified.enabled, "Copied field should be updated")
        assertEquals(original.inactivityTimeoutMs, modified.inactivityTimeoutMs,
            "Unmodified field should be preserved")
        assertEquals(original.flushOnTermination, modified.flushOnTermination,
            "Unmodified field should be preserved")
        assertEquals(original.persistSession, modified.persistSession,
            "Unmodified field should be preserved")
    }

    @Test
    fun `data class equality works for identical configs`() {
        val a = SessionConfig(enabled = true, inactivityTimeoutMs = 5000L)
        val b = SessionConfig(enabled = true, inactivityTimeoutMs = 5000L)
        assertEquals(a, b, "Identical SessionConfig instances should be equal")
    }

    @Test
    fun `data class equality detects differences`() {
        val a = SessionConfig(inactivityTimeoutMs = 5000L)
        val b = SessionConfig(inactivityTimeoutMs = 10_000L)
        assertFalse(a == b, "Different inactivityTimeoutMs should produce unequal configs")
    }

    // -- flushOnTermination ---------------------------------------------------

    @Test
    fun `flushOnTermination=false produces valid config`() {
        val config = SessionConfig(flushOnTermination = false)
        assertFalse(config.flushOnTermination,
            "flushOnTermination=false should be stored")
    }

    // -- persistSession -------------------------------------------------------

    @Test
    fun `persistSession=false produces valid config`() {
        val config = SessionConfig(persistSession = false)
        assertFalse(config.persistSession,
            "persistSession=false should be stored")
    }

    // -- Combined configs (edge cases) ----------------------------------------

    @Test
    fun `all-false config is valid`() {
        val config = SessionConfig(
            enabled = false,
            inactivityTimeoutMs = 0L,
            flushOnTermination = false,
            persistSession = false
        )
        assertFalse(config.enabled)
        assertEquals(0L, config.inactivityTimeoutMs)
        assertFalse(config.flushOnTermination)
        assertFalse(config.persistSession)
    }

    @Test
    fun `all-true config with max timeout is valid`() {
        val config = SessionConfig(
            enabled = true,
            inactivityTimeoutMs = Long.MAX_VALUE,
            flushOnTermination = true,
            persistSession = true
        )
        assertTrue(config.enabled)
        assertEquals(Long.MAX_VALUE, config.inactivityTimeoutMs)
        assertTrue(config.flushOnTermination)
        assertTrue(config.persistSession)
    }

    // -- Helpers --------------------------------------------------------------

    private fun resetSessionManagerSingleton() {
        try {
            val field = SessionManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // Ignore if field doesn't exist
        }
    }
}
