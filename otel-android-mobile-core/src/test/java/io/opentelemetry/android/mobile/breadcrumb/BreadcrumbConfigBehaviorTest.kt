/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.breadcrumb

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BreadcrumbConfigBehaviorTest {

    // --- shouldCaptureScreen ---

    @Test
    fun `shouldCaptureScreen returns true for any screen when allowedScreens is empty`() {
        val config = BreadcrumbConfig(allowedScreens = emptySet())

        assertTrue(config.shouldCaptureScreen("HomeScreen"))
        assertTrue(config.shouldCaptureScreen("SettingsScreen"))
        assertTrue(config.shouldCaptureScreen(""))
    }

    @Test
    fun `shouldCaptureScreen returns true only for listed screens when allowedScreens is non-empty`() {
        val config = BreadcrumbConfig(allowedScreens = setOf("HomeScreen", "CheckoutScreen"))

        assertTrue(config.shouldCaptureScreen("HomeScreen"))
        assertTrue(config.shouldCaptureScreen("CheckoutScreen"))
        assertFalse(config.shouldCaptureScreen("SettingsScreen"))
        assertFalse(config.shouldCaptureScreen("ProfileScreen"))
    }

    @Test
    fun `shouldCaptureScreen is case-sensitive`() {
        val config = BreadcrumbConfig(allowedScreens = setOf("HomeScreen"))

        assertTrue(config.shouldCaptureScreen("HomeScreen"))
        assertFalse(config.shouldCaptureScreen("homescreen"))
        assertFalse(config.shouldCaptureScreen("HOMESCREEN"))
        assertFalse(config.shouldCaptureScreen("homeScreen"))
    }

    // --- maxSize validation ---

    @Test
    fun `maxSize must be positive - zero throws`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            BreadcrumbConfig(maxSize = 0)
        }
        assertEquals("maxSize must be positive", exception.message)
    }

    @Test
    fun `maxSize must be positive - negative throws`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            BreadcrumbConfig(maxSize = -1)
        }
        assertEquals("maxSize must be positive", exception.message)
    }

    @Test
    fun `maxSize of 1 is valid`() {
        val config = BreadcrumbConfig(maxSize = 1)
        assertEquals(1, config.maxSize)
    }

    // --- Preset configs ---

    @Test
    fun `default preset has all features enabled`() {
        val config = BreadcrumbConfig.default()

        assertTrue(config.enabled)
        assertEquals(50, config.maxSize)
        assertTrue(config.captureNavigation)
        assertTrue(config.captureUserInput)
        assertTrue(config.captureNetwork)
        assertTrue(config.captureErrors)
        assertTrue(config.scrubElementIds)
        assertTrue(config.scrubNetworkUrls)
        assertTrue(config.allowedScreens.isEmpty())
    }

    @Test
    fun `minimal preset disables user input and network`() {
        val config = BreadcrumbConfig.minimal()

        assertTrue(config.enabled)
        assertTrue(config.captureNavigation)
        assertFalse(config.captureUserInput)
        assertFalse(config.captureNetwork)
        assertTrue(config.captureErrors)
    }

    @Test
    fun `privacyFocused preset disables user input and enables scrubbing`() {
        val config = BreadcrumbConfig.privacyFocused()

        assertTrue(config.enabled)
        assertFalse(config.captureUserInput)
        assertTrue(config.scrubElementIds)
        assertTrue(config.scrubNetworkUrls)
        assertTrue(config.captureNavigation)
        assertTrue(config.captureNetwork)
        assertTrue(config.captureErrors)
    }

    @Test
    fun `disabled preset sets enabled to false`() {
        val config = BreadcrumbConfig.disabled()

        assertFalse(config.enabled)
    }

    // --- Default field values ---

    @Test
    fun `scrubNetworkUrls defaults to true`() {
        val config = BreadcrumbConfig()
        assertTrue(config.scrubNetworkUrls)
    }

    @Test
    fun `captureNavigation defaults to true`() {
        val config = BreadcrumbConfig()
        assertTrue(config.captureNavigation)
    }

    // --- Reserved fields store values correctly ---

    @Test
    fun `captureUserInput stores assigned value`() {
        val enabled = BreadcrumbConfig(captureUserInput = true)
        val disabled = BreadcrumbConfig(captureUserInput = false)

        assertTrue(enabled.captureUserInput)
        assertFalse(disabled.captureUserInput)
    }

    @Test
    fun `captureNetwork stores assigned value`() {
        val enabled = BreadcrumbConfig(captureNetwork = true)
        val disabled = BreadcrumbConfig(captureNetwork = false)

        assertTrue(enabled.captureNetwork)
        assertFalse(disabled.captureNetwork)
    }

    @Test
    fun `captureErrors stores assigned value`() {
        val enabled = BreadcrumbConfig(captureErrors = true)
        val disabled = BreadcrumbConfig(captureErrors = false)

        assertTrue(enabled.captureErrors)
        assertFalse(disabled.captureErrors)
    }

    @Test
    fun `scrubElementIds stores assigned value`() {
        val enabled = BreadcrumbConfig(scrubElementIds = true)
        val disabled = BreadcrumbConfig(scrubElementIds = false)

        assertTrue(enabled.scrubElementIds)
        assertFalse(disabled.scrubElementIds)
    }
}
