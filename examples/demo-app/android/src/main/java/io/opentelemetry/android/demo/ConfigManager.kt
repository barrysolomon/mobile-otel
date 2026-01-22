package io.opentelemetry.android.demo

import android.content.Context
import android.content.SharedPreferences
import io.opentelemetry.android.mobile.config.MobileConfig

/**
 * Manages configuration persistence using SharedPreferences.
 *
 * Provides default values and allows runtime configuration changes.
 */
object ConfigManager {
    private const val PREFS_NAME = "otel_config"

    // Keys
    private const val KEY_SERVICE_NAME = "service_name"
    private const val KEY_SERVICE_VERSION = "service_version"
    private const val KEY_COLLECTOR_ENDPOINT = "collector_endpoint"
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
     * Loads the current MobileConfig from SharedPreferences.
     * Returns default configuration if no saved values exist.
     */
    fun loadConfig(context: Context): MobileConfig {
        val prefs = getPrefs(context)

        val authToken = prefs.getString(KEY_AUTH_TOKEN, DEFAULT_AUTH_TOKEN) ?: DEFAULT_AUTH_TOKEN
        val dataset = prefs.getString(KEY_DATASET, DEFAULT_DATASET) ?: DEFAULT_DATASET

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
            ramBufferSize = prefs.getInt(KEY_RAM_BUFFER_SIZE, DEFAULT_RAM_BUFFER_SIZE),
            diskBufferMb = prefs.getInt(KEY_DISK_BUFFER_MB, DEFAULT_DISK_BUFFER_MB),
            diskBufferTtlHours = prefs.getInt(KEY_DISK_BUFFER_TTL_HOURS, DEFAULT_DISK_BUFFER_TTL_HOURS),
            exportTimeoutSeconds = prefs.getLong(KEY_EXPORT_TIMEOUT_SECONDS, DEFAULT_EXPORT_TIMEOUT_SECONDS),
            configPollIntervalSeconds = prefs.getLong(KEY_CONFIG_POLL_INTERVAL_SECONDS, DEFAULT_CONFIG_POLL_INTERVAL_SECONDS),
            maxExportRetries = prefs.getInt(KEY_MAX_EXPORT_RETRIES, DEFAULT_MAX_EXPORT_RETRIES),
            headers = headers.ifEmpty { null },
            attachContextAttributes = prefs.getBoolean(KEY_ATTACH_CONTEXT_ATTRIBUTES, DEFAULT_ATTACH_CONTEXT_ATTRIBUTES),
            buildChannel = prefs.getString(KEY_BUILD_CHANNEL, DEFAULT_BUILD_CHANNEL)
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
     * Note: Auth token and dataset are saved separately via saveAuthToken/saveDataset.
     */
    fun saveConfig(context: Context, config: MobileConfig) {
        getPrefs(context).edit().apply {
            putString(KEY_SERVICE_NAME, config.serviceName)
            putString(KEY_SERVICE_VERSION, config.serviceVersion)
            putString(KEY_COLLECTOR_ENDPOINT, config.collectorEndpoint)
            putInt(KEY_RAM_BUFFER_SIZE, config.ramBufferSize)
            putInt(KEY_DISK_BUFFER_MB, config.diskBufferMb)
            putInt(KEY_DISK_BUFFER_TTL_HOURS, config.diskBufferTtlHours)
            putLong(KEY_EXPORT_TIMEOUT_SECONDS, config.exportTimeoutSeconds)
            putLong(KEY_CONFIG_POLL_INTERVAL_SECONDS, config.configPollIntervalSeconds)
            putInt(KEY_MAX_EXPORT_RETRIES, config.maxExportRetries)
            putBoolean(KEY_ATTACH_CONTEXT_ATTRIBUTES, config.attachContextAttributes)
            putString(KEY_BUILD_CHANNEL, config.buildChannel)
            apply()
        }
    }

    /**
     * Resets configuration to defaults.
     */
    fun resetToDefaults(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
