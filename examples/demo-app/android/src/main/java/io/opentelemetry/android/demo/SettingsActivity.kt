// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Settings screen for configuring telemetry collection and trigger behavior.
 *
 * Allows users to configure:
 * - Data collection types (logs, traces, metrics, device metrics)
 * - Device metric categories (memory, battery, CPU, network, etc.)
 * - Automatic export triggers (UI freeze, crash, network error, low memory)
 *
 * These settings can be:
 * 1. Bundled in assets/otel-config.json (shipped with app)
 * 2. Modified via this UI (stored in SharedPreferences)
 * 3. Updated remotely via Control Plane push (future feature)
 */
class SettingsActivity : AppCompatActivity() {

    // Data Collection Settings
    private lateinit var checkboxCollectLogs: SwitchMaterial
    private lateinit var checkboxCollectTraces: SwitchMaterial
    private lateinit var checkboxCollectMetrics: SwitchMaterial
    private lateinit var checkboxCollectDeviceMetrics: SwitchMaterial

    // Device Metric Categories
    private lateinit var checkboxMetricMemory: SwitchMaterial
    private lateinit var checkboxMetricBattery: SwitchMaterial
    private lateinit var checkboxMetricCpu: SwitchMaterial
    private lateinit var checkboxMetricNetwork: SwitchMaterial
    private lateinit var checkboxMetricStorage: SwitchMaterial
    private lateinit var checkboxMetricThermal: SwitchMaterial
    private lateinit var checkboxMetricDisplay: SwitchMaterial
    private lateinit var checkboxMetricSystem: SwitchMaterial
    private lateinit var checkboxMetricApp: SwitchMaterial
    private lateinit var checkboxMetricLocation: SwitchMaterial

    // Trigger Settings
    private lateinit var checkboxUiFreeze: SwitchMaterial
    private lateinit var checkboxCrash: SwitchMaterial
    private lateinit var checkboxNetworkError: SwitchMaterial
    private lateinit var checkboxLowMemory: SwitchMaterial

    // Buttons
    private lateinit var btnSave: MaterialButton
    private lateinit var btnResetDefaults: MaterialButton
    private lateinit var btnClose: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Set up MaterialToolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize data collection checkboxes
        checkboxCollectLogs = findViewById(R.id.checkboxCollectLogs)
        checkboxCollectTraces = findViewById(R.id.checkboxCollectTraces)
        checkboxCollectMetrics = findViewById(R.id.checkboxCollectMetrics)
        checkboxCollectDeviceMetrics = findViewById(R.id.checkboxCollectDeviceMetrics)

        // Initialize device metric category checkboxes
        checkboxMetricMemory = findViewById(R.id.checkboxMetricMemory)
        checkboxMetricBattery = findViewById(R.id.checkboxMetricBattery)
        checkboxMetricCpu = findViewById(R.id.checkboxMetricCpu)
        checkboxMetricNetwork = findViewById(R.id.checkboxMetricNetwork)
        checkboxMetricStorage = findViewById(R.id.checkboxMetricStorage)
        checkboxMetricThermal = findViewById(R.id.checkboxMetricThermal)
        checkboxMetricDisplay = findViewById(R.id.checkboxMetricDisplay)
        checkboxMetricSystem = findViewById(R.id.checkboxMetricSystem)
        checkboxMetricApp = findViewById(R.id.checkboxMetricApp)
        checkboxMetricLocation = findViewById(R.id.checkboxMetricLocation)

        // Initialize trigger checkboxes
        checkboxUiFreeze = findViewById(R.id.checkboxUiFreeze)
        checkboxCrash = findViewById(R.id.checkboxCrash)
        checkboxNetworkError = findViewById(R.id.checkboxNetworkError)
        checkboxLowMemory = findViewById(R.id.checkboxLowMemory)

        // Initialize buttons
        btnSave = findViewById(R.id.btnSave)
        btnResetDefaults = findViewById(R.id.btnResetDefaults)
        btnClose = findViewById(R.id.btnClose)

        // Load current settings
        loadSettings()

        // Set up button listeners
        btnSave.setOnClickListener {
            saveSettings()
        }

        btnResetDefaults.setOnClickListener {
            resetToDefaults()
        }

        btnClose.setOnClickListener {
            finish()
        }
    }

    /**
     * Loads all telemetry settings from SharedPreferences.
     */
    private fun loadSettings() {
        val prefs = getSharedPreferences("telemetry_settings", MODE_PRIVATE)

        // Load data collection settings
        checkboxCollectLogs.isChecked = prefs.getBoolean("collect_logs", true)
        checkboxCollectTraces.isChecked = prefs.getBoolean("collect_traces", true)
        checkboxCollectMetrics.isChecked = prefs.getBoolean("collect_metrics", true)
        checkboxCollectDeviceMetrics.isChecked = prefs.getBoolean("collect_device_metrics", true)

        // Load device metric categories
        checkboxMetricMemory.isChecked = prefs.getBoolean("metric_memory", true)
        checkboxMetricBattery.isChecked = prefs.getBoolean("metric_battery", true)
        checkboxMetricCpu.isChecked = prefs.getBoolean("metric_cpu", true)
        checkboxMetricNetwork.isChecked = prefs.getBoolean("metric_network", true)
        checkboxMetricStorage.isChecked = prefs.getBoolean("metric_storage", true)
        checkboxMetricThermal.isChecked = prefs.getBoolean("metric_thermal", false)
        checkboxMetricDisplay.isChecked = prefs.getBoolean("metric_display", true)
        checkboxMetricSystem.isChecked = prefs.getBoolean("metric_system", true)
        checkboxMetricApp.isChecked = prefs.getBoolean("metric_app", true)
        checkboxMetricLocation.isChecked = prefs.getBoolean("metric_location", false)

        // Load trigger settings
        checkboxUiFreeze.isChecked = prefs.getBoolean("trigger_ui_freeze", true)
        checkboxCrash.isChecked = prefs.getBoolean("trigger_crash", true)
        checkboxNetworkError.isChecked = prefs.getBoolean("trigger_network_error", true)
        checkboxLowMemory.isChecked = prefs.getBoolean("trigger_low_memory", true)
    }

    /**
     * Saves all telemetry settings to SharedPreferences.
     */
    private fun saveSettings() {
        val prefs = getSharedPreferences("telemetry_settings", MODE_PRIVATE)
        prefs.edit()
            // Data collection settings
            .putBoolean("collect_logs", checkboxCollectLogs.isChecked)
            .putBoolean("collect_traces", checkboxCollectTraces.isChecked)
            .putBoolean("collect_metrics", checkboxCollectMetrics.isChecked)
            .putBoolean("collect_device_metrics", checkboxCollectDeviceMetrics.isChecked)

            // Device metric categories
            .putBoolean("metric_memory", checkboxMetricMemory.isChecked)
            .putBoolean("metric_battery", checkboxMetricBattery.isChecked)
            .putBoolean("metric_cpu", checkboxMetricCpu.isChecked)
            .putBoolean("metric_network", checkboxMetricNetwork.isChecked)
            .putBoolean("metric_storage", checkboxMetricStorage.isChecked)
            .putBoolean("metric_thermal", checkboxMetricThermal.isChecked)
            .putBoolean("metric_display", checkboxMetricDisplay.isChecked)
            .putBoolean("metric_system", checkboxMetricSystem.isChecked)
            .putBoolean("metric_app", checkboxMetricApp.isChecked)
            .putBoolean("metric_location", checkboxMetricLocation.isChecked)

            // Trigger settings
            .putBoolean("trigger_ui_freeze", checkboxUiFreeze.isChecked)
            .putBoolean("trigger_crash", checkboxCrash.isChecked)
            .putBoolean("trigger_network_error", checkboxNetworkError.isChecked)
            .putBoolean("trigger_low_memory", checkboxLowMemory.isChecked)

            .apply()

        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Resets all settings to default values.
     */
    private fun resetToDefaults() {
        // Data collection defaults (all enabled)
        checkboxCollectLogs.isChecked = true
        checkboxCollectTraces.isChecked = true
        checkboxCollectMetrics.isChecked = true
        checkboxCollectDeviceMetrics.isChecked = true

        // Device metric category defaults
        checkboxMetricMemory.isChecked = true
        checkboxMetricBattery.isChecked = true
        checkboxMetricCpu.isChecked = true
        checkboxMetricNetwork.isChecked = true
        checkboxMetricStorage.isChecked = true
        checkboxMetricThermal.isChecked = false // Privacy/performance
        checkboxMetricDisplay.isChecked = true
        checkboxMetricSystem.isChecked = true
        checkboxMetricApp.isChecked = true
        checkboxMetricLocation.isChecked = false // Privacy

        // Trigger defaults (all enabled)
        checkboxUiFreeze.isChecked = true
        checkboxCrash.isChecked = true
        checkboxNetworkError.isChecked = true
        checkboxLowMemory.isChecked = true

        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
