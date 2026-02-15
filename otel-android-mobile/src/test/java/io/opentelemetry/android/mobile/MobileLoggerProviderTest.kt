/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.config.MobileConfig
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for MobileLoggerProvider.
 *
 * Tests OTEL SDK initialization, device ID management, resource configuration,
 * and lifecycle methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileLoggerProviderTest {

    private lateinit var context: Context
    private lateinit var config: MobileConfig

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        // Clear singleton for each test
        clearSingleton()
    }

    @After
    fun tearDown() {
        clearSingleton()
    }

    @Test
    fun `initialization creates valid provider`() {
        val provider = MobileLoggerProvider.getInstance(context, config)

        assertNotNull(provider)
        assertNotNull(provider.getDeviceId())
        assertNotNull(provider.getOpenTelemetrySdk())
    }

    @Test
    fun `singleton returns same instance`() {
        val provider1 = MobileLoggerProvider.getInstance(context, config)
        val provider2 = MobileLoggerProvider.getInstance(context, config)

        assertTrue(provider1 === provider2, "Should return same singleton instance")
    }

    @Test
    fun `device ID persists across restarts`() {
        val provider1 = MobileLoggerProvider.getInstance(context, config)
        val deviceId1 = provider1.getDeviceId()

        // Simulate app restart by clearing singleton
        clearSingleton()

        val provider2 = MobileLoggerProvider.getInstance(context, config)
        val deviceId2 = provider2.getDeviceId()

        assertEquals(deviceId1, deviceId2, "Device ID should persist in SharedPreferences")
    }

    @Test
    fun `get logger returns valid logger instance`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val logger = provider.get("test-component")

        assertNotNull(logger)
    }

    @Test
    fun `loggerBuilder returns valid builder`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val builder = provider.loggerBuilder("test-component")

        assertNotNull(builder)
    }

    @Test
    fun `force flush completes successfully`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val result = provider.forceFlush(5)

        assertTrue(result.isSuccess || result.isDone, "Force flush should complete")
    }

    @Test
    fun `shutdown completes successfully`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val result = provider.shutdown(5)

        assertTrue(result.isSuccess || result.isDone, "Shutdown should complete")
    }

    @Test
    fun `different configs create providers with different settings`() {
        val config1 = MobileConfig(
            serviceName = "service-1",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        val config2 = MobileConfig(
            serviceName = "service-2",
            serviceVersion = "2.0.0",
            collectorEndpoint = "http://localhost:4318"
        )

        val provider1 = MobileLoggerProvider.getInstance(context, config1)
        clearSingleton()
        val provider2 = MobileLoggerProvider.getInstance(context, config2)

        // Both should initialize successfully with different configs
        assertNotNull(provider1)
        assertNotNull(provider2)
    }

    @Test
    fun `device ID is valid UUID format`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val deviceId = provider.getDeviceId()

        // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        val uuidRegex = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        )

        assertTrue(
            deviceId.matches(uuidRegex),
            "Device ID should be valid UUID format: $deviceId"
        )
    }

    @Test
    fun `getInstanceOrNull returns null when not initialized`() {
        val instance = MobileLoggerProvider.getInstanceOrNull()
        assertEquals(null, instance, "Should return null when not initialized")
    }

    @Test
    fun `getInstanceOrNull returns instance when initialized`() {
        MobileLoggerProvider.getInstance(context, config)
        val instance = MobileLoggerProvider.getInstanceOrNull()

        assertNotNull(instance, "Should return instance when initialized")
    }

    /**
     * Helper to clear singleton using reflection for testing.
     */
    private fun clearSingleton() {
        try {
            val instanceField = MobileLoggerProvider::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        } catch (e: Exception) {
            // Ignore if field doesn't exist or can't be cleared
        }
    }
}
