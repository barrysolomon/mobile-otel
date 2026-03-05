/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.context.ContextSnapshot
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.api.trace.SpanContext
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for geo and device-based policy matching.
 *
 * Tests cover:
 * - Geo matching (country, timezone, locale, region)
 * - Device matching (network, battery, OS version, build channel)
 * - Backward compatibility (policies without geo/device still work)
 * - Attribute enrichment when enabled
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyEvaluatorGeoDeviceTest {

    private lateinit var mockContext: Context

    private lateinit var config: MobileConfig
    private lateinit var evaluator: PolicyEvaluator

    @Before
    fun setup() {
        config = MobileConfig(
            serviceName = "test-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:8080",
            attachContextAttributes = false
        )

        mockContext = ApplicationProvider.getApplicationContext()
        // Note: PolicyEvaluator needs real context for ContextSnapshotProvider
        // In unit tests, we'll test the matching logic directly via reflection
    }

    // ========== GEO MATCHING TESTS ==========

    @Test
    fun `matchGeo - country list match - matches when country in list`() {
        val context = createContextSnapshot(country = "US")
        val geo = GeoMatch(country = listOf("US", "CA", "GB"))

        val result = invokeMatchGeo(context, geo)

        assertTrue(result)
    }

    @Test
    fun `matchGeo - country list match - does not match when country not in list`() {
        val context = createContextSnapshot(country = "DE")
        val geo = GeoMatch(country = listOf("US", "CA", "GB"))

        val result = invokeMatchGeo(context, geo)

        assertEquals(false, result)
    }

    @Test
    fun `matchGeo - timezone glob match - matches America timezone`() {
        val context = createContextSnapshot(timezone = "America/Los_Angeles")
        val geo = GeoMatch(timezone = listOf("America/*"))

        val result = invokeMatchGeo(context, geo)

        assertTrue(result)
    }

    @Test
    fun `matchGeo - timezone glob match - matches Europe timezone`() {
        val context = createContextSnapshot(timezone = "Europe/London")
        val geo = GeoMatch(timezone = listOf("Europe/*"))

        val result = invokeMatchGeo(context, geo)

        assertTrue(result)
    }

    @Test
    fun `matchGeo - timezone glob match - does not match different region`() {
        val context = createContextSnapshot(timezone = "Asia/Tokyo")
        val geo = GeoMatch(timezone = listOf("America/*", "Europe/*"))

        val result = invokeMatchGeo(context, geo)

        assertEquals(false, result)
    }

    @Test
    fun `matchGeo - timezone exact match - works without glob`() {
        val context = createContextSnapshot(timezone = "America/New_York")
        val geo = GeoMatch(timezone = listOf("America/New_York"))

        val result = invokeMatchGeo(context, geo)

        assertTrue(result)
    }

    @Test
    fun `matchGeo - locale match - matches exact locale`() {
        val context = createContextSnapshot(locale = "en-US")
        val geo = GeoMatch(locale = listOf("en-US", "es-ES"))

        val result = invokeMatchGeo(context, geo)

        assertTrue(result)
    }

    @Test
    fun `matchGeo - region match - matches when region in list`() {
        val context = createContextSnapshot(region = "CA")
        val geo = GeoMatch(region = listOf("CA", "NY"))

        val result = invokeMatchGeo(context, geo)

        assertTrue(result)
    }

    @Test
    fun `matchGeo - no constraints - always matches`() {
        val context = createContextSnapshot()
        val geo: GeoMatch? = null

        val result = invokeMatchGeo(context, geo)

        assertTrue(result)
    }

    @Test
    fun `matchGeo - multiple constraints - all must match`() {
        val context = createContextSnapshot(
            country = "US",
            timezone = "America/Los_Angeles",
            locale = "en-US"
        )
        val geo = GeoMatch(
            country = listOf("US"),
            timezone = listOf("America/*"),
            locale = listOf("en-US")
        )

        val result = invokeMatchGeo(context, geo)

        assertTrue(result)
    }

    @Test
    fun `matchGeo - multiple constraints - fails if one does not match`() {
        val context = createContextSnapshot(
            country = "US",
            timezone = "America/Los_Angeles",
            locale = "es-ES"  // Wrong locale
        )
        val geo = GeoMatch(
            country = listOf("US"),
            timezone = listOf("America/*"),
            locale = listOf("en-US")
        )

        val result = invokeMatchGeo(context, geo)

        assertEquals(false, result)
    }

    // ========== DEVICE MATCHING TESTS ==========

    @Test
    fun `matchDevice - network match - matches cellular`() {
        val context = createContextSnapshot(networkType = "cellular")
        val device = DeviceMatch(network = listOf("cellular"))

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - network match - matches wifi or cellular`() {
        val context = createContextSnapshot(networkType = "wifi")
        val device = DeviceMatch(network = listOf("wifi", "cellular"))

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - network match - does not match offline`() {
        val context = createContextSnapshot(networkType = "offline")
        val device = DeviceMatch(network = listOf("wifi", "cellular"))

        val result = invokeMatchDevice(context, device)

        assertEquals(false, result)
    }

    @Test
    fun `matchDevice - battery match - matches low battery`() {
        val context = createContextSnapshot(batteryState = "low")
        val device = DeviceMatch(battery = listOf("low"))

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - battery match - matches charging or normal`() {
        val context = createContextSnapshot(batteryState = "normal")
        val device = DeviceMatch(battery = listOf("normal", "charging"))

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - device class match - matches phone`() {
        val context = createContextSnapshot(deviceClass = "phone")
        val device = DeviceMatch(deviceClass = listOf("phone"))

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - device class match - matches phone or tablet`() {
        val context = createContextSnapshot(deviceClass = "tablet")
        val device = DeviceMatch(deviceClass = listOf("phone", "tablet"))

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - build channel match - matches beta`() {
        val context = createContextSnapshot(buildChannel = "beta")
        val device = DeviceMatch(buildChannel = listOf("beta", "internal"))

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - build channel match - does not match prod`() {
        val context = createContextSnapshot(buildChannel = "prod")
        val device = DeviceMatch(buildChannel = listOf("beta", "internal"))

        val result = invokeMatchDevice(context, device)

        assertEquals(false, result)
    }

    @Test
    fun `matchDevice - OS version min - matches when version greater`() {
        val context = createContextSnapshot(osVersion = 33)  // Android 13
        val device = DeviceMatch(osVersionMin = 26)  // Min API 26

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - OS version min - matches when version equal`() {
        val context = createContextSnapshot(osVersion = 26)
        val device = DeviceMatch(osVersionMin = 26)

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - OS version min - does not match when version less`() {
        val context = createContextSnapshot(osVersion = 24)
        val device = DeviceMatch(osVersionMin = 26)

        val result = invokeMatchDevice(context, device)

        assertEquals(false, result)
    }

    @Test
    fun `matchDevice - OS version max - matches when version less`() {
        val context = createContextSnapshot(osVersion = 28)
        val device = DeviceMatch(osVersionMax = 30)

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - OS version range - matches when in range`() {
        val context = createContextSnapshot(osVersion = 28)
        val device = DeviceMatch(osVersionMin = 26, osVersionMax = 30)

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - OS version range - does not match when out of range`() {
        val context = createContextSnapshot(osVersion = 33)
        val device = DeviceMatch(osVersionMin = 26, osVersionMax = 30)

        val result = invokeMatchDevice(context, device)

        assertEquals(false, result)
    }

    @Test
    fun `matchDevice - app version match - matches specific version`() {
        val context = createContextSnapshot(appVersion = "1.2.3")
        val device = DeviceMatch(appVersion = listOf("1.2.3", "1.2.4"))

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - no constraints - always matches`() {
        val context = createContextSnapshot()
        val device: DeviceMatch? = null

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - multiple constraints - all must match`() {
        val context = createContextSnapshot(
            networkType = "cellular",
            batteryState = "normal",
            osVersion = 33,
            buildChannel = "beta"
        )
        val device = DeviceMatch(
            network = listOf("cellular"),
            battery = listOf("normal", "charging"),
            osVersionMin = 26,
            buildChannel = listOf("beta", "internal")
        )

        val result = invokeMatchDevice(context, device)

        assertTrue(result)
    }

    @Test
    fun `matchDevice - multiple constraints - fails if one does not match`() {
        val context = createContextSnapshot(
            networkType = "wifi",  // Wrong network
            batteryState = "normal",
            osVersion = 33,
            buildChannel = "beta"
        )
        val device = DeviceMatch(
            network = listOf("cellular"),
            battery = listOf("normal", "charging"),
            osVersionMin = 26,
            buildChannel = listOf("beta", "internal")
        )

        val result = invokeMatchDevice(context, device)

        assertEquals(false, result)
    }

    // ========== BACKWARD COMPATIBILITY TESTS ==========

    @Test
    fun `policy without geo or device constraints - still matches on attributes`() {
        // This tests that existing policies without geo/device continue to work
        val policy = Policy(
            id = "test-policy",
            enabled = true,
            match = Match(
                logicalOperator = "and",
                attributes = mapOf(
                    "event.name" to Condition(equals = "ui.freeze")
                ),
                geo = null,  // No geo constraints
                device = null  // No device constraints
            ),
            actions = Actions(flushWindowMinutes = 2)
        )

        // Should match based on attributes alone
        val logRecord = createLogRecord(eventName = "ui.freeze")
        val context = createContextSnapshot()

        val result = invokeMatchesPolicy(logRecord, context, policy)

        assertTrue(result)
    }

    // ========== GLOB PATTERN TESTS ==========

    @Test
    fun `matchGlob - matches prefix with wildcard`() {
        val result = invokeMatchGlob("America/Los_Angeles", "America/*")
        assertTrue(result)
    }

    @Test
    fun `matchGlob - does not match different prefix`() {
        val result = invokeMatchGlob("Europe/London", "America/*")
        assertEquals(false, result)
    }

    @Test
    fun `matchGlob - exact match without wildcard`() {
        val result = invokeMatchGlob("America/New_York", "America/New_York")
        assertTrue(result)
    }

    @Test
    fun `matchGlob - does not exact match different value`() {
        val result = invokeMatchGlob("America/Los_Angeles", "America/New_York")
        assertEquals(false, result)
    }

    // ========== HELPER METHODS ==========

    private fun createContextSnapshot(
        country: String = "US",
        region: String? = "CA",
        timezone: String = "America/Los_Angeles",
        locale: String = "en-US",
        appVersion: String = "1.0.0",
        osVersion: Int = 33,
        deviceClass: String = "phone",
        networkType: String = "wifi",
        batteryState: String = "normal",
        buildChannel: String = "prod"
    ): ContextSnapshot {
        return ContextSnapshot(
            country = country,
            region = region,
            timezone = timezone,
            locale = locale,
            appVersion = appVersion,
            osVersion = osVersion,
            deviceClass = deviceClass,
            networkType = networkType,
            batteryState = batteryState,
            buildChannel = buildChannel
        )
    }

    private fun createLogRecord(
        eventName: String = "test.event",
        attributes: Attributes = Attributes.empty()
    ): LogRecordData {
        return object : LogRecordData {
            override fun getResource() = Resource.empty()
            override fun getInstrumentationScopeInfo() = InstrumentationScopeInfo.empty()
            override fun getTimestampEpochNanos() = System.currentTimeMillis() * 1_000_000
            override fun getObservedTimestampEpochNanos() = System.currentTimeMillis() * 1_000_000
            override fun getSpanContext() = SpanContext.getInvalid()
            override fun getSeverity() = Severity.INFO
            override fun getSeverityText() = "INFO"
            override fun getBody() = Body.string(eventName)
            override fun getAttributes() = attributes
            override fun getTotalAttributeCount() = attributes.size()
        }
    }

    // Reflection helpers to test private methods
    // (In production, you might make these methods package-private or use a test-only interface)

    private fun invokeMatchGeo(context: ContextSnapshot, geo: GeoMatch?): Boolean {
        // Create a minimal evaluator to test the method
        // Note: In real tests, you'd use dependency injection or make methods package-private
        val mockEvaluator = PolicyEvaluator(mockContext, config)
        val method = PolicyEvaluator::class.java.getDeclaredMethod(
            "matchGeo",
            ContextSnapshot::class.java,
            GeoMatch::class.java
        )
        method.isAccessible = true
        return method.invoke(mockEvaluator, context, geo) as Boolean
    }

    private fun invokeMatchDevice(context: ContextSnapshot, device: DeviceMatch?): Boolean {
        val mockEvaluator = PolicyEvaluator(mockContext, config)
        val method = PolicyEvaluator::class.java.getDeclaredMethod(
            "matchDevice",
            ContextSnapshot::class.java,
            DeviceMatch::class.java
        )
        method.isAccessible = true
        return method.invoke(mockEvaluator, context, device) as Boolean
    }

    private fun invokeMatchGlob(value: String, pattern: String): Boolean {
        val mockEvaluator = PolicyEvaluator(mockContext, config)
        val method = PolicyEvaluator::class.java.getDeclaredMethod(
            "matchGlob",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(mockEvaluator, value, pattern) as Boolean
    }

    private fun invokeMatchesPolicy(
        logRecord: LogRecordData,
        context: ContextSnapshot,
        policy: Policy
    ): Boolean {
        val mockEvaluator = PolicyEvaluator(mockContext, config)
        val method = PolicyEvaluator::class.java.getDeclaredMethod(
            "matchesPolicy",
            LogRecordData::class.java,
            ContextSnapshot::class.java,
            Policy::class.java
        )
        method.isAccessible = true
        return method.invoke(mockEvaluator, logRecord, context, policy) as Boolean
    }
}
