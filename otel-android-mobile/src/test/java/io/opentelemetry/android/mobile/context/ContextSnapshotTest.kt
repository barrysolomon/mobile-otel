/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.context

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SR-011 + SR-024: ContextSnapshot must not bake the demo app's
 * SharedPreferences name into the SDK. Demographics are opt-in via
 * `MobileConfig.userContextPrefsName`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContextSnapshotTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `demographics are null when userContextPrefsName is null`() {
        // Even if the legacy demo_app_prefs file exists, the SDK must NOT
        // read it unless the app explicitly opts in via MobileConfig.
        context.getSharedPreferences("demo_app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("user_device_type", "smartphone")
            .putString("user_age_group", "25-34")
            .apply()

        val config = baseConfig(userContextPrefsName = null)
        val snapshot = ContextSnapshotProvider.getSnapshot(context, config)

        assertNull(snapshot.deviceType, "deviceType must be null when prefs name not configured")
        assertNull(snapshot.ageGroup, "ageGroup must be null when prefs name not configured")
        assertNull(snapshot.userRegion, "userRegion must be null when prefs name not configured")
        assertNull(snapshot.tier, "tier must be null when prefs name not configured")
    }

    @Test
    fun `demographics read from configured prefs name`() {
        context.getSharedPreferences("my_app_user_context", Context.MODE_PRIVATE)
            .edit()
            .putString("user_device_type", "phablet")
            .putString("user_region", "eu")
            .putString("user_age_group", "35-44")
            .putString("user_tier", "premium")
            .apply()

        val config = baseConfig(userContextPrefsName = "my_app_user_context")
        val snapshot = ContextSnapshotProvider.getSnapshot(context, config)

        assertEquals("phablet", snapshot.deviceType)
        assertEquals("eu", snapshot.userRegion)
        assertEquals("35-44", snapshot.ageGroup)
        assertEquals("premium", snapshot.tier)
    }

    @Test
    fun `SDK ignores demo_app_prefs when userContextPrefsName points elsewhere`() {
        // Plant misleading data under "demo_app_prefs" and the real data
        // under the configured name. The SDK must read from the configured
        // name only.
        context.getSharedPreferences("demo_app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("user_device_type", "DEMO_SHOULD_NOT_LEAK")
            .apply()
        context.getSharedPreferences("real_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("user_device_type", "smartphone")
            .apply()

        val config = baseConfig(userContextPrefsName = "real_prefs")
        val snapshot = ContextSnapshotProvider.getSnapshot(context, config)

        assertEquals("smartphone", snapshot.deviceType)
    }

    private fun baseConfig(userContextPrefsName: String?) = MobileConfig(
        serviceName = "test-app",
        serviceVersion = "1.0.0",
        collectorEndpoint = "https://collector.example.com:4317",
        userContextPrefsName = userContextPrefsName,
    )
}
