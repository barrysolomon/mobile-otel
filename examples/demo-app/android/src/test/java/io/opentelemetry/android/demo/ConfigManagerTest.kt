// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.UiTelemetryMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for [ConfigManager].
 *
 * Covers:
 * - Default config loading (no bundled config, no saved prefs)
 * - JSON config parsing (exportMode, uiTelemetryMode, samplingRate, predictionIntervalSeconds)
 * - Backend URL getter/setter
 * - Auth token and dataset getter/setter
 * - UI telemetry mode getter/setter
 * - Sampling rate getter/setter
 * - Protocol getter/setter
 * - Text input options
 * - Capture options (all 9 flags)
 * - isDash0Configured with various scenarios
 * - parseTelemetrySettings from JSON
 * - loadFromJson round-trip
 * - saveConfig/loadConfig round-trip
 * - resetToDefaults
 * - captureKey mapping
 * - Export mode parsing (valid and invalid)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestDemoApplication::class)
class ConfigManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear both prefs stores to start fresh
        context.getSharedPreferences("otel_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE).edit().clear().commit()
        // Mark bundled config as already loaded so loadConfig() uses prefs-based defaults.
        // Without this, Robolectric would find the real otel-config.json in debug assets
        // and loadConfig() would return bundled values instead of defaults.
        context.getSharedPreferences("otel_config", Context.MODE_PRIVATE)
            .edit().putBoolean("config_loaded_from_bundle", true).commit()
    }

    // ── Default config loading ─────────────────────────────────────────────

    @Test
    fun `loadConfig returns defaults when no prefs saved and no bundled config`() {
        val config = ConfigManager.loadConfig(context)
        assertEquals("otel-mobile-demo", config.serviceName)
        assertEquals("1.0.0", config.serviceVersion)
        assertEquals("http://10.0.2.2:4317", config.collectorEndpoint)
    }

    @Test
    fun `default export mode is CONTINUOUS`() {
        val config = ConfigManager.loadConfig(context)
        assertEquals(ExportMode.CONTINUOUS, config.exportMode)
    }

    @Test
    fun `default uiTelemetryMode is EVENTS`() {
        val config = ConfigManager.loadConfig(context)
        assertEquals(UiTelemetryMode.EVENTS, config.uiTelemetryMode)
    }

    @Test
    fun `default sampling rate is 1_0`() {
        assertEquals(1.0f, ConfigManager.getSamplingRate(context))
    }

    @Test
    fun `default buffer sizes are correct`() {
        val config = ConfigManager.loadConfig(context)
        assertEquals(5000, config.ramBufferSize)
        assertEquals(50, config.diskBufferMb)
        assertEquals(24, config.diskBufferTtlHours)
    }

    @Test
    fun `default export intervals are correct`() {
        val config = ConfigManager.loadConfig(context)
        assertEquals(30L, config.traceExportIntervalSeconds)
        assertEquals(60L, config.metricExportIntervalSeconds)
        assertEquals(30L, config.predictionIntervalSeconds)
    }

    @Test
    fun `default session config is enabled`() {
        val config = ConfigManager.loadConfig(context)
        assertTrue(config.sessionConfig.enabled)
        assertTrue(config.sessionConfig.flushOnTermination)
        assertTrue(config.sessionConfig.persistSession)
    }

    @Test
    fun `default vitals config has jank detection enabled`() {
        val config = ConfigManager.loadConfig(context)
        assertTrue(config.vitalsConfig.enabled)
        assertTrue(config.vitalsConfig.detectJank)
        assertFalse(config.vitalsConfig.monitorThermalState)
        assertEquals(3000L, config.vitalsConfig.anrRiskThresholdMs)
    }

    @Test
    fun `default network config scrubs URLs and headers`() {
        val config = ConfigManager.loadConfig(context)
        assertTrue(config.networkConfig.scrubUrls)
        assertTrue(config.networkConfig.scrubHeaders)
        assertEquals(400, config.networkConfig.errorStatusThreshold)
    }

    @Test
    fun `default error config captures uncaught exceptions`() {
        val config = ConfigManager.loadConfig(context)
        assertTrue(config.errorConfig.captureUncaughtExceptions)
        assertTrue(config.errorConfig.captureCoroutineExceptions)
        assertTrue(config.errorConfig.scrubStackTraces)
        assertTrue(config.errorConfig.flushOnError)
        assertEquals(10, config.errorConfig.rateLimit)
    }

    // ── Backend URL ────────────────────────────────────────────────────────

    @Test
    fun `default backend URL is emulator bridge`() {
        assertEquals("http://10.0.2.2:3001", ConfigManager.getBackendUrl(context))
    }

    @Test
    fun `saveBackendUrl persists and getBackendUrl reads it`() {
        ConfigManager.saveBackendUrl(context, "http://192.168.1.100:3001")
        assertEquals("http://192.168.1.100:3001", ConfigManager.getBackendUrl(context))
    }

    @Test
    fun `saveBackendUrl overwrites previous value`() {
        ConfigManager.saveBackendUrl(context, "http://first:3001")
        ConfigManager.saveBackendUrl(context, "http://second:3001")
        assertEquals("http://second:3001", ConfigManager.getBackendUrl(context))
    }

    // ── Auth token ─────────────────────────────────────────────────────────

    @Test
    fun `default auth token is empty`() {
        assertEquals("", ConfigManager.getAuthToken(context))
    }

    @Test
    fun `saveAuthToken persists and getAuthToken reads it`() {
        ConfigManager.saveAuthToken(context, "my-secret-token")
        assertEquals("my-secret-token", ConfigManager.getAuthToken(context))
    }

    // ── Dataset ────────────────────────────────────────────────────────────

    @Test
    fun `default dataset is empty`() {
        assertEquals("", ConfigManager.getDataset(context))
    }

    @Test
    fun `saveDataset persists and getDataset reads it`() {
        ConfigManager.saveDataset(context, "otel-mobile")
        assertEquals("otel-mobile", ConfigManager.getDataset(context))
    }

    // ── isDash0Configured ──────────────────────────────────────────────────

    @Test
    fun `isDash0Configured returns false with defaults`() {
        assertFalse(ConfigManager.isDash0Configured(context))
    }

    @Test
    fun `isDash0Configured returns false with template placeholder token`() {
        ConfigManager.saveAuthToken(context, "YOUR_AUTH_TOKEN")
        assertFalse(ConfigManager.isDash0Configured(context))
    }

    @Test
    fun `isDash0Configured returns false with blank token`() {
        ConfigManager.saveAuthToken(context, "")
        assertFalse(ConfigManager.isDash0Configured(context))
    }

    @Test
    fun `isDash0Configured returns false when endpoint is default localhost`() {
        ConfigManager.saveAuthToken(context, "real-token-123")
        // Endpoint is still the default http://10.0.2.2:4317
        assertFalse(ConfigManager.isDash0Configured(context))
    }

    @Test
    fun `isDash0Configured returns true with real credentials`() {
        ConfigManager.saveAuthToken(context, "real-token-123")
        // Also need a real endpoint
        val prefs = context.getSharedPreferences("otel_config", Context.MODE_PRIVATE)
        prefs.edit().putString("collector_endpoint", "https://ingress.us1.aws.dash0.com:4317").commit()
        assertTrue(ConfigManager.isDash0Configured(context))
    }

    // ── Sampling rate ──────────────────────────────────────────────────────

    @Test
    fun `saveSamplingRate persists and getSamplingRate reads it`() {
        ConfigManager.saveSamplingRate(context, 0.5f)
        assertEquals(0.5f, ConfigManager.getSamplingRate(context))
    }

    @Test
    fun `sampling rate 0 is valid`() {
        ConfigManager.saveSamplingRate(context, 0.0f)
        assertEquals(0.0f, ConfigManager.getSamplingRate(context))
    }

    // ── UI telemetry mode ──────────────────────────────────────────────────

    @Test
    fun `default UI telemetry mode is EVENTS`() {
        assertEquals("EVENTS", ConfigManager.getUiTelemetryMode(context))
    }

    @Test
    fun `saveUiTelemetryMode persists SPANS`() {
        ConfigManager.saveUiTelemetryMode(context, "SPANS")
        assertEquals("SPANS", ConfigManager.getUiTelemetryMode(context))
    }

    @Test
    fun `saveUiTelemetryMode persists BOTH`() {
        ConfigManager.saveUiTelemetryMode(context, "BOTH")
        assertEquals("BOTH", ConfigManager.getUiTelemetryMode(context))
    }

    @Test
    fun `loadConfig parses saved uiTelemetryMode SPANS`() {
        ConfigManager.saveUiTelemetryMode(context, "SPANS")
        val config = ConfigManager.loadConfig(context)
        assertEquals(UiTelemetryMode.SPANS, config.uiTelemetryMode)
    }

    @Test
    fun `loadConfig falls back to EVENTS for invalid uiTelemetryMode`() {
        val prefs = context.getSharedPreferences("otel_config", Context.MODE_PRIVATE)
        prefs.edit().putString("ui_telemetry_mode", "INVALID_MODE").commit()
        val config = ConfigManager.loadConfig(context)
        assertEquals(UiTelemetryMode.EVENTS, config.uiTelemetryMode)
    }

    // ── Protocol ───────────────────────────────────────────────────────────

    @Test
    fun `default protocol is grpc`() {
        assertEquals("grpc", ConfigManager.getProtocol(context))
    }

    @Test
    fun `saveProtocol persists http`() {
        ConfigManager.saveProtocol(context, "http")
        assertEquals("http", ConfigManager.getProtocol(context))
    }

    // ── Text input options ─────────────────────────────────────────────────

    @Test
    fun `default text capture char count is true`() {
        assertTrue(ConfigManager.getTextCaptureCharCount(context))
    }

    @Test
    fun `default text capture is set is true`() {
        assertTrue(ConfigManager.getTextCaptureIsSet(context))
    }

    @Test
    fun `default text capture content is false`() {
        assertFalse(ConfigManager.getTextCaptureContent(context))
    }

    @Test
    fun `saveTextInputOptions persists all three flags`() {
        ConfigManager.saveTextInputOptions(context, charCount = false, isSet = false, content = true)
        assertFalse(ConfigManager.getTextCaptureCharCount(context))
        assertFalse(ConfigManager.getTextCaptureIsSet(context))
        assertTrue(ConfigManager.getTextCaptureContent(context))
    }

    @Test
    fun `loadConfig reflects text input config`() {
        ConfigManager.saveTextInputOptions(context, charCount = false, isSet = true, content = true)
        val config = ConfigManager.loadConfig(context)
        assertFalse(config.textInputConfig.captureCharCount)
        assertTrue(config.textInputConfig.captureIsSet)
        assertTrue(config.textInputConfig.captureTextContent)
    }

    // ── Capture options ────────────────────────────────────────────────────

    @Test
    fun `default capture options are all true`() {
        val options = ConfigManager.loadCaptureOptions(context)
        assertEquals(9, options.size)
        assertTrue(options.values.all { it })
    }

    @Test
    fun `saveCaptureOptions persists individual flags`() {
        val modified = ConfigManager.loadCaptureOptions(context).toMutableMap()
        val tapsKey = ConfigManager.captureKey("taps")
        val scrollKey = ConfigManager.captureKey("scroll")
        modified[tapsKey] = false
        modified[scrollKey] = false
        ConfigManager.saveCaptureOptions(context, modified)

        val reloaded = ConfigManager.loadCaptureOptions(context)
        assertFalse(reloaded[tapsKey]!!)
        assertFalse(reloaded[scrollKey]!!)
        assertTrue(reloaded[ConfigManager.captureKey("lifecycle")]!!)
    }

    // ── captureKey mapping ─────────────────────────────────────────────────

    @Test
    fun `captureKey maps all known names`() {
        assertEquals("capture_lifecycle", ConfigManager.captureKey("lifecycle"))
        assertEquals("capture_screens", ConfigManager.captureKey("screens"))
        assertEquals("capture_taps", ConfigManager.captureKey("taps"))
        assertEquals("capture_long_press", ConfigManager.captureKey("long_press"))
        assertEquals("capture_swipe", ConfigManager.captureKey("swipe"))
        assertEquals("capture_scroll", ConfigManager.captureKey("scroll"))
        assertEquals("capture_text_input", ConfigManager.captureKey("text_input"))
        assertEquals("capture_back_press", ConfigManager.captureKey("back_press"))
        assertEquals("capture_fragments", ConfigManager.captureKey("fragments"))
    }

    @Test
    fun `captureKey returns unknown name as-is`() {
        assertEquals("unknown_key", ConfigManager.captureKey("unknown_key"))
    }

    // ── Export mode parsing ────────────────────────────────────────────────

    @Test
    fun `loadConfig parses CONDITIONAL export mode`() {
        val prefs = context.getSharedPreferences("otel_config", Context.MODE_PRIVATE)
        prefs.edit().putString("export_mode", "CONDITIONAL").commit()
        val config = ConfigManager.loadConfig(context)
        assertEquals(ExportMode.CONDITIONAL, config.exportMode)
    }

    @Test
    fun `loadConfig parses HYBRID export mode`() {
        val prefs = context.getSharedPreferences("otel_config", Context.MODE_PRIVATE)
        prefs.edit().putString("export_mode", "HYBRID").commit()
        val config = ConfigManager.loadConfig(context)
        assertEquals(ExportMode.HYBRID, config.exportMode)
    }

    @Test
    fun `loadConfig falls back to CONDITIONAL for invalid export mode`() {
        val prefs = context.getSharedPreferences("otel_config", Context.MODE_PRIVATE)
        prefs.edit().putString("export_mode", "INVALID").commit()
        val config = ConfigManager.loadConfig(context)
        assertEquals(ExportMode.CONDITIONAL, config.exportMode)
    }

    // ── saveConfig and loadConfig round-trip ───────────────────────────────

    @Test
    fun `saveConfig then loadConfig preserves all fields`() {
        val config = ConfigManager.loadConfig(context)
        // Modify config via individual setters
        ConfigManager.saveUiTelemetryMode(context, "BOTH")
        ConfigManager.saveSamplingRate(context, 0.75f)
        ConfigManager.saveBackendUrl(context, "http://custom:4000")
        ConfigManager.saveAuthToken(context, "test-token")
        ConfigManager.saveDataset(context, "test-dataset")

        // Reload and verify
        assertEquals("BOTH", ConfigManager.getUiTelemetryMode(context))
        assertEquals(0.75f, ConfigManager.getSamplingRate(context))
        assertEquals("http://custom:4000", ConfigManager.getBackendUrl(context))
        assertEquals("test-token", ConfigManager.getAuthToken(context))
        assertEquals("test-dataset", ConfigManager.getDataset(context))
    }

    @Test
    fun `saveConfig persists headers with auth token and dataset`() {
        val config = ConfigManager.loadConfig(context).copy(
            headers = mapOf(
                "Authorization" to "Bearer my-token-123",
                "Dash0-Dataset" to "mobile-prod"
            )
        )
        ConfigManager.saveConfig(context, config)

        assertEquals("my-token-123", ConfigManager.getAuthToken(context))
        assertEquals("mobile-prod", ConfigManager.getDataset(context))
    }

    @Test
    fun `saveConfig persists session config`() {
        val config = ConfigManager.loadConfig(context)
        val modified = config.copy(
            sessionConfig = config.sessionConfig.copy(
                enabled = false,
                flushOnTermination = false
            )
        )
        ConfigManager.saveConfig(context, modified)

        val reloaded = ConfigManager.loadConfig(context)
        assertFalse(reloaded.sessionConfig.enabled)
        assertFalse(reloaded.sessionConfig.flushOnTermination)
    }

    @Test
    fun `saveConfig persists vitals config`() {
        val config = ConfigManager.loadConfig(context)
        val modified = config.copy(
            vitalsConfig = config.vitalsConfig.copy(
                enabled = false,
                monitorThermalState = true,
                anrRiskThresholdMs = 5000L
            )
        )
        ConfigManager.saveConfig(context, modified)

        val reloaded = ConfigManager.loadConfig(context)
        assertFalse(reloaded.vitalsConfig.enabled)
        assertTrue(reloaded.vitalsConfig.monitorThermalState)
        assertEquals(5000L, reloaded.vitalsConfig.anrRiskThresholdMs)
    }

    @Test
    fun `saveConfig persists network config`() {
        val config = ConfigManager.loadConfig(context)
        val modified = config.copy(
            networkConfig = config.networkConfig.copy(
                scrubUrls = false,
                scrubHeaders = false,
                errorStatusThreshold = 500,
                minDurationMs = 100L
            )
        )
        ConfigManager.saveConfig(context, modified)

        val reloaded = ConfigManager.loadConfig(context)
        assertFalse(reloaded.networkConfig.scrubUrls)
        assertFalse(reloaded.networkConfig.scrubHeaders)
        assertEquals(500, reloaded.networkConfig.errorStatusThreshold)
        assertEquals(100L, reloaded.networkConfig.minDurationMs)
    }

    @Test
    fun `saveConfig persists error config`() {
        val config = ConfigManager.loadConfig(context)
        val modified = config.copy(
            errorConfig = config.errorConfig.copy(
                captureUncaughtExceptions = false,
                captureCoroutineExceptions = false,
                flushOnError = false,
                rateLimit = 5
            )
        )
        ConfigManager.saveConfig(context, modified)

        val reloaded = ConfigManager.loadConfig(context)
        assertFalse(reloaded.errorConfig.captureUncaughtExceptions)
        assertFalse(reloaded.errorConfig.captureCoroutineExceptions)
        assertFalse(reloaded.errorConfig.flushOnError)
        assertEquals(5, reloaded.errorConfig.rateLimit)
    }

    @Test
    fun `saveConfig persists buffer and export settings`() {
        val config = ConfigManager.loadConfig(context)
        val modified = config.copy(
            ramBufferSize = 10000,
            diskBufferMb = 100,
            diskBufferTtlHours = 48,
            exportTimeoutSeconds = 60L,
            maxExportRetries = 5,
            predictionIntervalSeconds = 60L
        )
        ConfigManager.saveConfig(context, modified)

        val reloaded = ConfigManager.loadConfig(context)
        assertEquals(10000, reloaded.ramBufferSize)
        assertEquals(100, reloaded.diskBufferMb)
        assertEquals(48, reloaded.diskBufferTtlHours)
        assertEquals(60L, reloaded.exportTimeoutSeconds)
        assertEquals(5, reloaded.maxExportRetries)
        assertEquals(60L, reloaded.predictionIntervalSeconds)
    }

    // ── resetToDefaults ────────────────────────────────────────────────────

    @Test
    fun `resetToDefaults clears all saved config`() {
        ConfigManager.saveAuthToken(context, "my-token")
        ConfigManager.saveDataset(context, "my-dataset")
        ConfigManager.saveBackendUrl(context, "http://custom:9999")
        ConfigManager.saveSamplingRate(context, 0.1f)

        ConfigManager.resetToDefaults(context)

        assertEquals("", ConfigManager.getAuthToken(context))
        assertEquals("", ConfigManager.getDataset(context))
        assertEquals("http://10.0.2.2:3001", ConfigManager.getBackendUrl(context))
        assertEquals(1.0f, ConfigManager.getSamplingRate(context))
    }

    // ── parseTelemetrySettings ─────────────────────────────────────────────

    @Test
    fun `parseTelemetrySettings parses data collection flags`() {
        val json = """
        {
          "telemetrySettings": {
            "dataCollection": {
              "logs": false,
              "traces": true,
              "metrics": false,
              "deviceMetrics": true
            }
          }
        }
        """.trimIndent()

        ConfigManager.parseTelemetrySettings(context, json)

        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("collect_logs", true))
        assertTrue(prefs.getBoolean("collect_traces", false))
        assertFalse(prefs.getBoolean("collect_metrics", true))
        assertTrue(prefs.getBoolean("collect_device_metrics", false))
    }

    @Test
    fun `parseTelemetrySettings parses device metric categories`() {
        val json = """
        {
          "telemetrySettings": {
            "deviceMetricCategories": {
              "memory": true,
              "battery": false,
              "cpu": true,
              "network": false,
              "storage": true,
              "thermal": true,
              "display": false,
              "system": true,
              "app": false,
              "location": true
            }
          }
        }
        """.trimIndent()

        ConfigManager.parseTelemetrySettings(context, json)

        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("metric_memory", false))
        assertFalse(prefs.getBoolean("metric_battery", true))
        assertTrue(prefs.getBoolean("metric_cpu", false))
        assertFalse(prefs.getBoolean("metric_network", true))
        assertTrue(prefs.getBoolean("metric_storage", false))
        assertTrue(prefs.getBoolean("metric_thermal", false))
        assertFalse(prefs.getBoolean("metric_display", true))
        assertTrue(prefs.getBoolean("metric_system", false))
        assertFalse(prefs.getBoolean("metric_app", true))
        assertTrue(prefs.getBoolean("metric_location", false))
    }

    @Test
    fun `parseTelemetrySettings parses trigger settings`() {
        val json = """
        {
          "telemetrySettings": {
            "triggers": {
              "uiFreeze": false,
              "crash": true,
              "networkError": false,
              "lowMemory": true
            }
          }
        }
        """.trimIndent()

        ConfigManager.parseTelemetrySettings(context, json)

        val prefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("trigger_ui_freeze", true))
        assertTrue(prefs.getBoolean("trigger_crash", false))
        assertFalse(prefs.getBoolean("trigger_network_error", true))
        assertTrue(prefs.getBoolean("trigger_low_memory", false))
    }

    @Test
    fun `parseTelemetrySettings ignores JSON without telemetrySettings`() {
        val json = """{"serviceName": "test"}"""
        // Should not throw
        ConfigManager.parseTelemetrySettings(context, json)
    }

    @Test
    fun `parseTelemetrySettings ignores invalid JSON gracefully`() {
        // Should not throw
        ConfigManager.parseTelemetrySettings(context, "not valid json {{{")
    }

    // ── loadFromJson ───────────────────────────────────────────────────────

    @Test
    fun `loadFromJson parses complete config and returns true`() {
        val json = """
        {
          "serviceName": "json-app",
          "serviceVersion": "2.0.0",
          "collectorEndpoint": "https://collector.example.com:4317",
          "exportMode": "HYBRID",
          "uiTelemetryMode": "BOTH",
          "samplingRate": 0.5,
          "predictionIntervalSeconds": 45,
          "traceExportIntervalSeconds": 15,
          "metricExportIntervalSeconds": 45,
          "ramBufferSize": 3000,
          "diskBufferMb": 25,
          "diskBufferTtlHours": 12,
          "exportTimeoutSeconds": 15,
          "configPollIntervalSeconds": 600,
          "maxExportRetries": 5,
          "attachContextAttributes": true,
          "buildChannel": "release",
          "headers": {
            "Authorization": "Bearer json-token",
            "Dash0-Dataset": "json-dataset"
          },
          "telemetrySettings": {
            "dataCollection": {
              "logs": true,
              "traces": true,
              "metrics": false,
              "deviceMetrics": true
            },
            "triggers": {
              "uiFreeze": true,
              "crash": true,
              "networkError": false,
              "lowMemory": false
            }
          }
        }
        """.trimIndent()

        val result = ConfigManager.loadFromJson(context, json)
        assertTrue(result)

        // Verify main config was saved
        val config = ConfigManager.loadConfig(context)
        assertEquals("json-app", config.serviceName)
        assertEquals("2.0.0", config.serviceVersion)
        assertEquals("https://collector.example.com:4317", config.collectorEndpoint)
        assertEquals(ExportMode.HYBRID, config.exportMode)
        assertEquals(UiTelemetryMode.BOTH, config.uiTelemetryMode)
        assertEquals(3000, config.ramBufferSize)
        assertEquals(25, config.diskBufferMb)
        assertEquals(12, config.diskBufferTtlHours)
        assertEquals(15L, config.traceExportIntervalSeconds)
        assertEquals(45L, config.metricExportIntervalSeconds)
        assertEquals(45L, config.predictionIntervalSeconds)
        assertEquals(15L, config.exportTimeoutSeconds)
        assertEquals(600L, config.configPollIntervalSeconds)
        assertEquals(5, config.maxExportRetries)
        assertTrue(config.attachContextAttributes)
        assertEquals("release", config.buildChannel)

        // Verify auth token extracted from headers
        assertEquals("json-token", ConfigManager.getAuthToken(context))
        assertEquals("json-dataset", ConfigManager.getDataset(context))

        // Verify telemetry settings were parsed
        val telPrefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        assertFalse(telPrefs.getBoolean("trigger_network_error", true))
        assertFalse(telPrefs.getBoolean("trigger_low_memory", true))
    }

    @Test
    fun `loadFromJson returns false for invalid JSON`() {
        val result = ConfigManager.loadFromJson(context, "not valid json")
        assertFalse(result)
    }

    @Test
    fun `loadFromJson parses uiTelemetryMode SPANS`() {
        val json = """
        {
          "serviceName": "test-app",
          "serviceVersion": "1.0.0",
          "collectorEndpoint": "http://localhost:4317",
          "uiTelemetryMode": "SPANS"
        }
        """.trimIndent()

        assertTrue(ConfigManager.loadFromJson(context, json))
        val config = ConfigManager.loadConfig(context)
        assertEquals(UiTelemetryMode.SPANS, config.uiTelemetryMode)
    }

    @Test
    fun `loadFromJson with invalid uiTelemetryMode defaults to EVENTS`() {
        val json = """
        {
          "serviceName": "test-app",
          "serviceVersion": "1.0.0",
          "collectorEndpoint": "http://localhost:4317",
          "uiTelemetryMode": "NOT_A_MODE"
        }
        """.trimIndent()

        assertTrue(ConfigManager.loadFromJson(context, json))
        val config = ConfigManager.loadConfig(context)
        assertEquals(UiTelemetryMode.EVENTS, config.uiTelemetryMode)
    }

    @Test
    fun `loadFromJson parses samplingRate`() {
        val json = """
        {
          "serviceName": "test-app",
          "serviceVersion": "1.0.0",
          "collectorEndpoint": "http://localhost:4317",
          "samplingRate": 0.25
        }
        """.trimIndent()

        assertTrue(ConfigManager.loadFromJson(context, json))
        val config = ConfigManager.loadConfig(context)
        assertEquals(0.25, config.samplingConfig.samplingRate, 0.01)
    }

    @Test
    fun `loadFromJson parses predictionIntervalSeconds`() {
        val json = """
        {
          "serviceName": "test-app",
          "serviceVersion": "1.0.0",
          "collectorEndpoint": "http://localhost:4317",
          "predictionIntervalSeconds": 120
        }
        """.trimIndent()

        assertTrue(ConfigManager.loadFromJson(context, json))
        val config = ConfigManager.loadConfig(context)
        assertEquals(120L, config.predictionIntervalSeconds)
    }

    // ── Device metrics config via telemetry settings ───────────────────────

    @Test
    fun `loadConfig reflects device metrics from telemetry_settings prefs`() {
        val telPrefs = context.getSharedPreferences("telemetry_settings", Context.MODE_PRIVATE)
        telPrefs.edit()
            .putBoolean("metric_memory", false)
            .putBoolean("metric_battery", false)
            .putBoolean("metric_thermal", true)
            .putBoolean("metric_location", true)
            .commit()

        val config = ConfigManager.loadConfig(context)
        assertFalse(config.deviceMetricsConfig.captureMemory)
        assertFalse(config.deviceMetricsConfig.captureBattery)
        assertTrue(config.deviceMetricsConfig.captureThermal)
        assertTrue(config.deviceMetricsConfig.captureLocation)
    }

    // ── Headers construction ───────────────────────────────────────────────

    @Test
    fun `loadConfig constructs headers from auth token and dataset`() {
        ConfigManager.saveAuthToken(context, "bearer-test")
        ConfigManager.saveDataset(context, "my-dataset")

        val config = ConfigManager.loadConfig(context)
        assertNotNull(config.headers)
        assertEquals("Bearer bearer-test", config.headers!!["Authorization"])
        assertEquals("my-dataset", config.headers!!["Dash0-Dataset"])
    }

    @Test
    fun `loadConfig returns null headers when token and dataset are blank`() {
        val config = ConfigManager.loadConfig(context)
        assertNull(config.headers)
    }

    @Test
    fun `loadConfig returns headers with only auth when dataset is blank`() {
        ConfigManager.saveAuthToken(context, "only-auth")
        val config = ConfigManager.loadConfig(context)
        assertNotNull(config.headers)
        assertEquals("Bearer only-auth", config.headers!!["Authorization"])
        assertNull(config.headers!!["Dash0-Dataset"])
    }
}
