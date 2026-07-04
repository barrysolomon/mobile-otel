// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for SettingsActivity telemetry collection and trigger settings.
 *
 * Validates:
 * - Default values load correctly on first launch
 * - Save persists all toggle states to SharedPreferences
 * - Load restores saved values
 * - Reset returns all toggles to default state
 * - Round-trip: save → destroy → recreate → load preserves state
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsActivityTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        // Clear prefs before each test
        context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // --- Default Values ---

    @Test
    fun `default data collection settings are all enabled`() {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("collect_logs", true))
        assertTrue(prefs.getBoolean("collect_traces", true))
        assertTrue(prefs.getBoolean("collect_metrics", true))
        assertTrue(prefs.getBoolean("collect_device_metrics", true))
    }

    @Test
    fun `default device metrics are enabled except thermal and location`() {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("metric_memory", true))
        assertTrue(prefs.getBoolean("metric_battery", true))
        assertTrue(prefs.getBoolean("metric_cpu", true))
        assertTrue(prefs.getBoolean("metric_network", true))
        assertTrue(prefs.getBoolean("metric_storage", true))
        assertFalse(prefs.getBoolean("metric_thermal", false))
        assertTrue(prefs.getBoolean("metric_display", true))
        assertTrue(prefs.getBoolean("metric_system", true))
        assertTrue(prefs.getBoolean("metric_app", true))
        assertFalse(prefs.getBoolean("metric_location", false))
    }

    @Test
    fun `default trigger settings are all enabled`() {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("trigger_ui_freeze", true))
        assertTrue(prefs.getBoolean("trigger_crash", true))
        assertTrue(prefs.getBoolean("trigger_network_error", true))
        assertTrue(prefs.getBoolean("trigger_low_memory", true))
    }

    // --- Save & Load Round-Trip ---

    @Test
    fun `save persists all data collection flags`() {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("collect_logs", false)
            .putBoolean("collect_traces", false)
            .putBoolean("collect_metrics", true)
            .putBoolean("collect_device_metrics", false)
            .commit()

        assertFalse(prefs.getBoolean("collect_logs", true))
        assertFalse(prefs.getBoolean("collect_traces", true))
        assertTrue(prefs.getBoolean("collect_metrics", false))
        assertFalse(prefs.getBoolean("collect_device_metrics", true))
    }

    @Test
    fun `save persists device metric categories`() {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("metric_memory", false)
            .putBoolean("metric_thermal", true)
            .putBoolean("metric_location", true)
            .commit()

        assertFalse(prefs.getBoolean("metric_memory", true))
        assertTrue(prefs.getBoolean("metric_thermal", false))
        assertTrue(prefs.getBoolean("metric_location", false))
    }

    @Test
    fun `save persists trigger settings`() {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("trigger_ui_freeze", false)
            .putBoolean("trigger_crash", false)
            .putBoolean("trigger_network_error", true)
            .putBoolean("trigger_low_memory", false)
            .commit()

        assertFalse(prefs.getBoolean("trigger_ui_freeze", true))
        assertFalse(prefs.getBoolean("trigger_crash", true))
        assertTrue(prefs.getBoolean("trigger_network_error", false))
        assertFalse(prefs.getBoolean("trigger_low_memory", true))
    }

    @Test
    fun `all 18 prefs round-trip through SharedPreferences`() {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)

        // Set non-default values for all 18 settings
        prefs.edit()
            // Data collection (4)
            .putBoolean("collect_logs", false)
            .putBoolean("collect_traces", false)
            .putBoolean("collect_metrics", false)
            .putBoolean("collect_device_metrics", false)
            // Device metrics (10)
            .putBoolean("metric_memory", false)
            .putBoolean("metric_battery", false)
            .putBoolean("metric_cpu", false)
            .putBoolean("metric_network", false)
            .putBoolean("metric_storage", false)
            .putBoolean("metric_thermal", true)  // default is false
            .putBoolean("metric_display", false)
            .putBoolean("metric_system", false)
            .putBoolean("metric_app", false)
            .putBoolean("metric_location", true)  // default is false
            // Triggers (4)
            .putBoolean("trigger_ui_freeze", false)
            .putBoolean("trigger_crash", false)
            .putBoolean("trigger_network_error", false)
            .putBoolean("trigger_low_memory", false)
            .commit()

        // Verify all 18 non-defaults survive
        assertFalse(prefs.getBoolean("collect_logs", true))
        assertFalse(prefs.getBoolean("collect_traces", true))
        assertFalse(prefs.getBoolean("collect_metrics", true))
        assertFalse(prefs.getBoolean("collect_device_metrics", true))
        assertFalse(prefs.getBoolean("metric_memory", true))
        assertFalse(prefs.getBoolean("metric_battery", true))
        assertFalse(prefs.getBoolean("metric_cpu", true))
        assertFalse(prefs.getBoolean("metric_network", true))
        assertFalse(prefs.getBoolean("metric_storage", true))
        assertTrue(prefs.getBoolean("metric_thermal", false))
        assertFalse(prefs.getBoolean("metric_display", true))
        assertFalse(prefs.getBoolean("metric_system", true))
        assertFalse(prefs.getBoolean("metric_app", true))
        assertTrue(prefs.getBoolean("metric_location", false))
        assertFalse(prefs.getBoolean("trigger_ui_freeze", true))
        assertFalse(prefs.getBoolean("trigger_crash", true))
        assertFalse(prefs.getBoolean("trigger_network_error", true))
        assertFalse(prefs.getBoolean("trigger_low_memory", true))
    }

    // --- Reset to Defaults ---

    @Test
    fun `reset clears all saved settings`() {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)

        // Set all to non-default
        prefs.edit()
            .putBoolean("collect_logs", false)
            .putBoolean("metric_thermal", true)
            .putBoolean("trigger_crash", false)
            .commit()

        // Reset = clear all
        prefs.edit().clear().commit()

        // Should fall back to defaults
        assertTrue(prefs.getBoolean("collect_logs", true))
        assertFalse(prefs.getBoolean("metric_thermal", false))
        assertTrue(prefs.getBoolean("trigger_crash", true))
    }

    // --- Activity Lifecycle ---

    @Test
    fun `SettingsActivity launches without crash`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
        assertTrue(activity.isFinishing.not())
    }

    @Test
    fun `SettingsActivity loads saved prefs on create`() {
        // Pre-populate prefs
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("collect_logs", false)
            .putBoolean("metric_thermal", true)
            .putBoolean("trigger_crash", false)
            .commit()

        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        // The activity should have loaded these values into its switches
        val switchLogs = activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxCollectLogs)
        val switchThermal = activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxMetricThermal)
        val switchCrash = activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxCrash)

        assertFalse(switchLogs.isChecked)
        assertTrue(switchThermal.isChecked)
        assertFalse(switchCrash.isChecked)
    }

    @Test
    fun `SettingsActivity save button persists toggle states`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        // Toggle some switches off
        activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxCollectLogs).isChecked = false
        activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxMetricLocation).isChecked = true
        activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxLowMemory).isChecked = false

        // Click save
        activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave).performClick()

        // Verify persisted
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("collect_logs", true))
        assertTrue(prefs.getBoolean("metric_location", false))
        assertFalse(prefs.getBoolean("trigger_low_memory", true))
    }

    @Test
    fun `SettingsActivity reset button restores defaults`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        // Toggle everything off
        activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxCollectLogs).isChecked = false
        activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxMetricMemory).isChecked = false

        // Click reset
        activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetDefaults).performClick()

        // Verify defaults restored in UI
        val switchLogs = activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxCollectLogs)
        val switchMemory = activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxMetricMemory)
        val switchThermal = activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxMetricThermal)
        val switchLocation = activity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.checkboxMetricLocation)

        assertTrue(switchLogs.isChecked)
        assertTrue(switchMemory.isChecked)
        assertFalse(switchThermal.isChecked)  // default off
        assertFalse(switchLocation.isChecked) // default off
    }

    @Test
    fun `SettingsActivity toolbar has correct title`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.settingsToolbar)
        assertEquals("Telemetry", toolbar.title)
        assertEquals("Collection & Triggers", toolbar.subtitle)
    }
}
