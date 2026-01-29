package io.opentelemetry.android.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.metrics.DeviceMetricsConfig
import org.json.JSONObject
import java.io.IOException

/**
 * Manages configuration persistence using SharedPreferences.
 *
 * Supports:
 * 1. Bundled configuration (shipped with app in assets/otel-config.json)
 * 2. Runtime configuration (stored in SharedPreferences)
 * 3. Default fallback values
 *
 * Load priority:
 * 1. Runtime config (if saved) - highest priority
 * 2. Bundled config (from assets) - fallback
 * 3. Default values - last resort
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val PREFS_NAME = "otel_config"
    private const val BUNDLED_CONFIG_FILE = "otel-config.json"
    private const val KEY_CONFIG_LOADED = "config_loaded_from_bundle"

    // Keys
    private const val KEY_SERVICE_NAME = "service_name"
    private const val KEY_SERVICE_VERSION = "service_version"
    private const val KEY_COLLECTOR_ENDPOINT = "collector_endpoint"
    private const val KEY_EXPORT_MODE = "export_mode"
    private const val KEY_TRACE_EXPORT_INTERVAL_SECONDS = "trace_export_interval_seconds"
    private const val KEY_METRIC_EXPORT_INTERVAL_SECONDS = "metric_export_interval_seconds"
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_DATASET = "dataset"
    private const val KEY_RAM_BUFFER_SIZE = "ram_buffer_size"
    private const val KEY_DISK_BUFFER_MB = "disk_buffer_mb"
    private const val KEY_DISK_BUFFER_TTL_HOURS = "disk_buffer_ttl_hours"
    private const val KEY_EXPORT_TIMEOUT_SECONDS = "export_timeout_seconds"
    private const val KEY_CONFIG_POLL_INTERVAL_SECONDS = "config_poll_interval_seconds"
    private const val KEY_MAX_EXPORT_RETRIES = "max_export_retries"
    private const val KEY_ATTACH_CONTEXT_ATTRIBUTES = "attach_context_attributes"
    private const val KEY_BUILD_CHANNEL = "build_channel"

    // Defaults
    private const val DEFAULT_SERVICE_NAME = "otel-mobile-demo"
    private const val DEFAULT_SERVICE_VERSION = "1.0.0"
    private const val DEFAULT_COLLECTOR_ENDPOINT = "http://10.0.2.2:4317"
    private const val DEFAULT_EXPORT_MODE = "CONDITIONAL"  // Battery-friendly by default
    private const val DEFAULT_TRACE_EXPORT_INTERVAL_SECONDS = 30L
    private const val DEFAULT_METRIC_EXPORT_INTERVAL_SECONDS = 60L
    private const val DEFAULT_PROTOCOL = "grpc"
    private const val DEFAULT_AUTH_TOKEN = ""
    private const val DEFAULT_DATASET = ""
    private const val DEFAULT_RAM_BUFFER_SIZE = 5000
    private const val DEFAULT_DISK_BUFFER_MB = 50
    private const val DEFAULT_DISK_BUFFER_TTL_HOURS = 24
    private const val DEFAULT_EXPORT_TIMEOUT_SECONDS = 30L
    private const val DEFAULT_CONFIG_POLL_INTERVAL_SECONDS = 300L
    private const val DEFAULT_MAX_EXPORT_RETRIES = 3
    private const val DEFAULT_ATTACH_CONTEXT_ATTRIBUTES = false
    private const val DEFAULT_BUILD_CHANNEL = "debug"

    /**
     * Gets the SharedPreferences instance.
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Loads DeviceMetricsConfig from SharedPreferences.
     */
    private fun loadDeviceMetricsConfig(context: Context): DeviceMetricsConfig {
        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        return DeviceMetricsConfig(
            captureMemory = prefs.getBoolean("metric_memory", true),
            captureBattery = prefs.getBoolean("metric_battery", true),
            captureCpu = prefs.getBoolean("metric_cpu", true),
            captureNetwork = prefs.getBoolean("metric_network", true),
            captureStorage = prefs.getBoolean("metric_storage", true),
            captureThermal = prefs.getBoolean("metric_thermal", false),
            captureDisplay = prefs.getBoolean("metric_display", true),
            captureSystem = prefs.getBoolean("metric_system", true),
            captureApp = prefs.getBoolean("metric_app", true),
            captureLocation = prefs.getBoolean("metric_location", false)
        )
    }

    /**
     * Loads the current MobileConfig with priority order:
     * 1. Runtime config (SharedPreferences) - if previously saved
     * 2. Bundled config (assets/otel-config.json) - if app hasn't loaded config yet
     * 3. Default values - fallback
     */
    fun loadConfig(context: Context): MobileConfig {
        val prefs = getPrefs(context)

        // Check if we've ever loaded config (either runtime or bundled)
        val hasLoadedConfig = prefs.getBoolean(KEY_CONFIG_LOADED, false)

        // If no runtime config saved, try loading bundled config
        if (!hasLoadedConfig) {
            val bundledConfig = loadBundledConfig(context)
            if (bundledConfig != null) {
                Log.i(TAG, "Loaded initial configuration from bundled assets")
                // Save bundled config as runtime config for future use
                saveConfig(context, bundledConfig)
                prefs.edit().putBoolean(KEY_CONFIG_LOADED, true).apply()
                return bundledConfig
            }
        }

        val authToken = prefs.getString(KEY_AUTH_TOKEN, DEFAULT_AUTH_TOKEN) ?: DEFAULT_AUTH_TOKEN
        val dataset = prefs.getString(KEY_DATASET, DEFAULT_DATASET) ?: DEFAULT_DATASET

        // Parse export mode
        val exportModeStr = prefs.getString(KEY_EXPORT_MODE, DEFAULT_EXPORT_MODE) ?: DEFAULT_EXPORT_MODE
        val exportMode = try {
            ExportMode.valueOf(exportModeStr)
        } catch (e: IllegalArgumentException) {
            ExportMode.CONDITIONAL
        }

        // Build headers map if auth token or dataset is provided
        val headers = mutableMapOf<String, String>()
        if (authToken.isNotBlank()) {
            headers["Authorization"] = "Bearer $authToken"
        }
        if (dataset.isNotBlank()) {
            headers["Dash0-Dataset"] = dataset
        }

        return MobileConfig(
            serviceName = prefs.getString(KEY_SERVICE_NAME, DEFAULT_SERVICE_NAME)!!,
            serviceVersion = prefs.getString(KEY_SERVICE_VERSION, DEFAULT_SERVICE_VERSION)!!,
            collectorEndpoint = prefs.getString(KEY_COLLECTOR_ENDPOINT, DEFAULT_COLLECTOR_ENDPOINT)!!,
            exportMode = exportMode,
            traceExportIntervalSeconds = prefs.getLong(KEY_TRACE_EXPORT_INTERVAL_SECONDS, DEFAULT_TRACE_EXPORT_INTERVAL_SECONDS),
            metricExportIntervalSeconds = prefs.getLong(KEY_METRIC_EXPORT_INTERVAL_SECONDS, DEFAULT_METRIC_EXPORT_INTERVAL_SECONDS),
            ramBufferSize = prefs.getInt(KEY_RAM_BUFFER_SIZE, DEFAULT_RAM_BUFFER_SIZE),
            diskBufferMb = prefs.getInt(KEY_DISK_BUFFER_MB, DEFAULT_DISK_BUFFER_MB),
            diskBufferTtlHours = prefs.getInt(KEY_DISK_BUFFER_TTL_HOURS, DEFAULT_DISK_BUFFER_TTL_HOURS),
            exportTimeoutSeconds = prefs.getLong(KEY_EXPORT_TIMEOUT_SECONDS, DEFAULT_EXPORT_TIMEOUT_SECONDS),
            configPollIntervalSeconds = prefs.getLong(KEY_CONFIG_POLL_INTERVAL_SECONDS, DEFAULT_CONFIG_POLL_INTERVAL_SECONDS),
            maxExportRetries = prefs.getInt(KEY_MAX_EXPORT_RETRIES, DEFAULT_MAX_EXPORT_RETRIES),
            headers = headers.ifEmpty { null },
            attachContextAttributes = prefs.getBoolean(KEY_ATTACH_CONTEXT_ATTRIBUTES, DEFAULT_ATTACH_CONTEXT_ATTRIBUTES),
            buildChannel = prefs.getString(KEY_BUILD_CHANNEL, DEFAULT_BUILD_CHANNEL),
            deviceMetricsConfig = loadDeviceMetricsConfig(context)
        )
    }

    /**
     * Saves auth token separately (for UI convenience).
     */
    fun saveAuthToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    /**
     * Gets auth token (for UI population).
     */
    fun getAuthToken(context: Context): String {
        return getPrefs(context).getString(KEY_AUTH_TOKEN, DEFAULT_AUTH_TOKEN) ?: DEFAULT_AUTH_TOKEN
    }

    /**
     * Saves dataset separately (for UI convenience).
     */
    fun saveDataset(context: Context, dataset: String) {
        getPrefs(context).edit().putString(KEY_DATASET, dataset).apply()
    }

    /**
     * Gets dataset (for UI population).
     */
    fun getDataset(context: Context): String {
        return getPrefs(context).getString(KEY_DATASET, DEFAULT_DATASET) ?: DEFAULT_DATASET
    }

    /**
     * Saves protocol preference (grpc or http).
     */
    fun saveProtocol(context: Context, protocol: String) {
        getPrefs(context).edit().putString(KEY_PROTOCOL, protocol).apply()
    }

    /**
     * Gets protocol preference (grpc or http).
     */
    fun getProtocol(context: Context): String {
        return getPrefs(context).getString(KEY_PROTOCOL, DEFAULT_PROTOCOL) ?: DEFAULT_PROTOCOL
    }

    /**
     * Saves a MobileConfig to SharedPreferences.
     * Extracts auth token and dataset from headers if present.
     */
    fun saveConfig(context: Context, config: MobileConfig) {
        getPrefs(context).edit().apply {
            putString(KEY_SERVICE_NAME, config.serviceName)
            putString(KEY_SERVICE_VERSION, config.serviceVersion)
            putString(KEY_COLLECTOR_ENDPOINT, config.collectorEndpoint)
            putString(KEY_EXPORT_MODE, config.exportMode.name)
            putLong(KEY_TRACE_EXPORT_INTERVAL_SECONDS, config.traceExportIntervalSeconds)
            putLong(KEY_METRIC_EXPORT_INTERVAL_SECONDS, config.metricExportIntervalSeconds)
            putInt(KEY_RAM_BUFFER_SIZE, config.ramBufferSize)
            putInt(KEY_DISK_BUFFER_MB, config.diskBufferMb)
            putInt(KEY_DISK_BUFFER_TTL_HOURS, config.diskBufferTtlHours)
            putLong(KEY_EXPORT_TIMEOUT_SECONDS, config.exportTimeoutSeconds)
            putLong(KEY_CONFIG_POLL_INTERVAL_SECONDS, config.configPollIntervalSeconds)
            putInt(KEY_MAX_EXPORT_RETRIES, config.maxExportRetries)
            putBoolean(KEY_ATTACH_CONTEXT_ATTRIBUTES, config.attachContextAttributes)
            putString(KEY_BUILD_CHANNEL, config.buildChannel)

            // Extract and save headers
            config.headers?.let { headers ->
                headers["Authorization"]?.let { auth ->
                    // Extract token from "Bearer token" format
                    val token = auth.removePrefix("Bearer ").trim()
                    putString(KEY_AUTH_TOKEN, token)
                }
                headers["Dash0-Dataset"]?.let { dataset ->
                    putString(KEY_DATASET, dataset)
                }
            }

            apply()
        }
    }

    /**
     * Resets configuration to defaults.
     */
    fun resetToDefaults(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /**
     * Loads bundled configuration from assets/otel-config.json.
     *
     * @return MobileConfig if bundled config exists and is valid, null otherwise
     */
    private fun loadBundledConfig(context: Context): MobileConfig? {
        return try {
            val json = context.assets.open(BUNDLED_CONFIG_FILE).bufferedReader().use { it.readText() }

            // Parse telemetry settings and save to SharedPreferences
            parseTelemetrySettings(context, json)

            // Parse and return the main config
            parseJsonConfig(context, json)
        } catch (e: IOException) {
            Log.w(TAG, "No bundled config found at assets/$BUNDLED_CONFIG_FILE", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bundled config", e)
            null
        }
    }

    /**
     * Parses JSON configuration string into MobileConfig.
     *
     * Expected JSON format:
     * ```json
     * {
     *   "serviceName": "my-app",
     *   "serviceVersion": "1.0.0",
     *   "collectorEndpoint": "https://collector.example.com:4317",
     *   "exportMode": "CONDITIONAL",
     *   "traceExportIntervalSeconds": 30,
     *   "metricExportIntervalSeconds": 60,
     *   "ramBufferSize": 5000,
     *   "diskBufferMb": 50,
     *   "diskBufferTtlHours": 24,
     *   "exportTimeoutSeconds": 30,
     *   "configPollIntervalSeconds": 300,
     *   "maxExportRetries": 3,
     *   "attachContextAttributes": false,
     *   "buildChannel": "prod",
     *   "headers": {
     *     "Authorization": "Bearer token",
     *     "Dash0-Dataset": "mobile-prod"
     *   }
     * }
     * ```
     *
     * @param json JSON string
     * @return MobileConfig
     */
    private fun parseJsonConfig(context: Context, json: String): MobileConfig {
        val jsonObj = JSONObject(json)

        // Parse export mode
        val exportModeStr = jsonObj.optString("exportMode", DEFAULT_EXPORT_MODE)
        val exportMode = try {
            ExportMode.valueOf(exportModeStr)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid export mode: $exportModeStr, using CONDITIONAL")
            ExportMode.CONDITIONAL
        }

        // Parse headers
        val headersObj = jsonObj.optJSONObject("headers")
        val headers = if (headersObj != null && headersObj.length() > 0) {
            mutableMapOf<String, String>().apply {
                headersObj.keys().forEach { key ->
                    put(key, headersObj.getString(key))
                }
            }
        } else {
            null
        }

        // Load device metrics config from telemetry settings that were already parsed
        val deviceMetricsConfig = loadDeviceMetricsConfig(context)

        val config = MobileConfig(
            serviceName = jsonObj.optString("serviceName", DEFAULT_SERVICE_NAME),
            serviceVersion = jsonObj.optString("serviceVersion", DEFAULT_SERVICE_VERSION),
            collectorEndpoint = jsonObj.optString("collectorEndpoint", DEFAULT_COLLECTOR_ENDPOINT),
            exportMode = exportMode,
            traceExportIntervalSeconds = jsonObj.optLong("traceExportIntervalSeconds", DEFAULT_TRACE_EXPORT_INTERVAL_SECONDS),
            metricExportIntervalSeconds = jsonObj.optLong("metricExportIntervalSeconds", DEFAULT_METRIC_EXPORT_INTERVAL_SECONDS),
            ramBufferSize = jsonObj.optInt("ramBufferSize", DEFAULT_RAM_BUFFER_SIZE),
            diskBufferMb = jsonObj.optInt("diskBufferMb", DEFAULT_DISK_BUFFER_MB),
            diskBufferTtlHours = jsonObj.optInt("diskBufferTtlHours", DEFAULT_DISK_BUFFER_TTL_HOURS),
            exportTimeoutSeconds = jsonObj.optLong("exportTimeoutSeconds", DEFAULT_EXPORT_TIMEOUT_SECONDS),
            configPollIntervalSeconds = jsonObj.optLong("configPollIntervalSeconds", DEFAULT_CONFIG_POLL_INTERVAL_SECONDS),
            maxExportRetries = jsonObj.optInt("maxExportRetries", DEFAULT_MAX_EXPORT_RETRIES),
            headers = headers,
            attachContextAttributes = jsonObj.optBoolean("attachContextAttributes", DEFAULT_ATTACH_CONTEXT_ATTRIBUTES),
            buildChannel = jsonObj.optString("buildChannel", DEFAULT_BUILD_CHANNEL),
            deviceMetricsConfig = deviceMetricsConfig
        )

        return config
    }

    /**
     * Parses telemetry settings from JSON and saves them to SharedPreferences.
     *
     * This is called when loading bundled config or remote config updates.
     * Telemetry settings include:
     * - Data collection toggles (logs, traces, metrics, device metrics)
     * - Device metric categories (memory, battery, CPU, etc.)
     * - Automatic triggers (UI freeze, crash, network error, low memory)
     *
     * @param context Android context
     * @param json JSON configuration string
     */
    fun parseTelemetrySettings(context: Context, json: String) {
        try {
            val jsonObj = JSONObject(json)
            val telemetrySettings = jsonObj.optJSONObject("telemetrySettings") ?: return

            val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            // Parse data collection settings
            val dataCollection = telemetrySettings.optJSONObject("dataCollection")
            if (dataCollection != null) {
                editor.putBoolean("collect_logs", dataCollection.optBoolean("logs", true))
                editor.putBoolean("collect_traces", dataCollection.optBoolean("traces", true))
                editor.putBoolean("collect_metrics", dataCollection.optBoolean("metrics", true))
                editor.putBoolean("collect_device_metrics", dataCollection.optBoolean("deviceMetrics", true))
            }

            // Parse device metric categories
            val deviceMetricCategories = telemetrySettings.optJSONObject("deviceMetricCategories")
            if (deviceMetricCategories != null) {
                editor.putBoolean("metric_memory", deviceMetricCategories.optBoolean("memory", true))
                editor.putBoolean("metric_battery", deviceMetricCategories.optBoolean("battery", true))
                editor.putBoolean("metric_cpu", deviceMetricCategories.optBoolean("cpu", true))
                editor.putBoolean("metric_network", deviceMetricCategories.optBoolean("network", true))
                editor.putBoolean("metric_storage", deviceMetricCategories.optBoolean("storage", true))
                editor.putBoolean("metric_thermal", deviceMetricCategories.optBoolean("thermal", false))
                editor.putBoolean("metric_display", deviceMetricCategories.optBoolean("display", true))
                editor.putBoolean("metric_system", deviceMetricCategories.optBoolean("system", true))
                editor.putBoolean("metric_app", deviceMetricCategories.optBoolean("app", true))
                editor.putBoolean("metric_location", deviceMetricCategories.optBoolean("location", false))
            }

            // Parse trigger settings
            val triggers = telemetrySettings.optJSONObject("triggers")
            if (triggers != null) {
                editor.putBoolean("trigger_ui_freeze", triggers.optBoolean("uiFreeze", true))
                editor.putBoolean("trigger_crash", triggers.optBoolean("crash", true))
                editor.putBoolean("trigger_network_error", triggers.optBoolean("networkError", true))
                editor.putBoolean("trigger_low_memory", triggers.optBoolean("lowMemory", true))
            }

            editor.apply()
            Log.i(TAG, "Telemetry settings loaded from JSON configuration")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse telemetry settings from JSON", e)
        }
    }

    /**
     * Loads configuration from JSON file (for external updates).
     *
     * This can be used to load configuration from external sources like:
     * - Control Plane push updates (remote configuration)
     * - Downloaded configuration files
     * - SD card stored configs
     * - Cloud-synced configurations
     *
     * @param context Android context
     * @param jsonString JSON configuration string
     * @return true if successfully loaded and saved, false otherwise
     */
    fun loadFromJson(context: Context, jsonString: String): Boolean {
        return try {
            // Parse telemetry settings and save to SharedPreferences
            parseTelemetrySettings(context, jsonString)

            // Parse main config
            val config = parseJsonConfig(context, jsonString)
            saveConfig(context, config)
            getPrefs(context).edit().putBoolean(KEY_CONFIG_LOADED, true).apply()

            Log.i(TAG, "Successfully loaded configuration from JSON (including telemetry settings)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load configuration from JSON", e)
            false
        }
    }
}
