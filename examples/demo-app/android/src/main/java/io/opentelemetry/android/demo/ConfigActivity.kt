// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.UiTelemetryMode
import io.opentelemetry.android.mobile.instrumentation.TextInputConfig
import io.opentelemetry.android.mobile.core.SessionConfig
import io.opentelemetry.android.mobile.errors.ErrorConfig
import io.opentelemetry.android.mobile.network.NetworkConfig
import io.opentelemetry.android.mobile.instrumentation.ScreenshotConfig
import io.opentelemetry.android.mobile.instrumentation.WireframeConfig
import io.opentelemetry.android.mobile.sampling.SamplingConfig
import io.opentelemetry.android.mobile.vitals.VitalsConfig

/**
 * OpenTelemetry SDK configuration screen.
 *
 * Covers all SDK-level parameters: service identity, buffering, export, sampling,
 * prediction, session, vitals, network, error handling, and auto-capture toggles.
 *
 * Dash0-specific connection settings (endpoint, auth token, dataset) are in
 * [Dash0ConfigActivity].
 */
class ConfigActivity : AppCompatActivity() {

    // Service Identity
    private lateinit var editServiceName: EditText
    private lateinit var editServiceVersion: EditText

    // Buffer
    private lateinit var editRamBufferSize: EditText
    private lateinit var editDiskBufferMb: EditText
    private lateinit var editDiskBufferTtl: EditText

    // Export & Advanced
    private lateinit var radioGroupExportMode: RadioGroup
    private lateinit var radioConditional: RadioButton
    private lateinit var radioContinuous: RadioButton
    private lateinit var radioHybrid: RadioButton
    private lateinit var editTraceExportInterval: EditText
    private lateinit var editMetricExportInterval: EditText
    private lateinit var editExportTimeout: EditText
    private lateinit var editMaxRetries: EditText
    private lateinit var checkboxAttachContext: SwitchMaterial
    private lateinit var editBuildChannel: EditText

    // Sampling & Prediction
    private lateinit var sliderSamplingRate: Slider
    private lateinit var tvSamplingRateValue: TextView
    private lateinit var sliderPredictionInterval: Slider
    private lateinit var tvPredictionIntervalValue: TextView

    // Session
    private lateinit var checkboxSessionEnabled: SwitchMaterial
    private lateinit var editSessionTimeoutMinutes: EditText
    private lateinit var checkboxFlushOnSessionEnd: SwitchMaterial
    private lateinit var checkboxPersistSession: SwitchMaterial

    // Vitals
    private lateinit var checkboxVitalsEnabled: SwitchMaterial
    private lateinit var checkboxDetectJank: SwitchMaterial
    private lateinit var checkboxMonitorThermal: SwitchMaterial
    private lateinit var editAnrThresholdMs: EditText

    // Network
    private lateinit var checkboxScrubUrls: SwitchMaterial
    private lateinit var checkboxScrubHeaders: SwitchMaterial
    private lateinit var editHttpErrorThreshold: EditText
    private lateinit var editMinRequestDurationMs: EditText

    // Error Handling
    private lateinit var checkboxCaptureUncaughtExceptions: SwitchMaterial
    private lateinit var checkboxCaptureCoroutineExceptions: SwitchMaterial
    private lateinit var checkboxScrubStackTraces: SwitchMaterial
    private lateinit var checkboxFlushOnError: SwitchMaterial
    private lateinit var editErrorRateLimit: EditText
    private lateinit var editErrorDedupeWindowMinutes: EditText

    // UI Telemetry
    private lateinit var radioGroupUiTelemetryMode: RadioGroup
    private lateinit var radioUiEvents: RadioButton
    private lateinit var radioUiSpans: RadioButton
    private lateinit var radioUiBoth: RadioButton
    private lateinit var switchTextCharCount: SwitchMaterial
    private lateinit var switchTextIsSet: SwitchMaterial
    private lateinit var switchTextContent: SwitchMaterial

    // Incubating
    private lateinit var switchScreenshotEnabled: SwitchMaterial
    private lateinit var switchScreenshotOnScreenView: SwitchMaterial
    private lateinit var switchWireframeEnabled: SwitchMaterial

    // What to Capture
    private lateinit var checkboxCaptureLifecycle: SwitchMaterial
    private lateinit var checkboxCaptureScreens: SwitchMaterial
    private lateinit var checkboxCaptureTaps: SwitchMaterial
    private lateinit var checkboxCaptureLongPress: SwitchMaterial
    private lateinit var checkboxCaptureSwipe: SwitchMaterial
    private lateinit var checkboxCaptureScroll: SwitchMaterial
    private lateinit var checkboxCaptureTextInput: SwitchMaterial
    private lateinit var checkboxCaptureBackPress: SwitchMaterial
    private lateinit var checkboxCaptureFragments: SwitchMaterial

    // Buttons
    private lateinit var btnSave: Button
    private lateinit var btnResetDefaults: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Service Identity
        editServiceName          = findViewById(R.id.editServiceName)
        editServiceVersion       = findViewById(R.id.editServiceVersion)

        // Buffer
        editRamBufferSize        = findViewById(R.id.editRamBufferSize)
        editDiskBufferMb         = findViewById(R.id.editDiskBufferMb)
        editDiskBufferTtl        = findViewById(R.id.editDiskBufferTtl)

        // Export & Advanced
        radioGroupExportMode     = findViewById(R.id.radioGroupExportMode)
        radioConditional         = findViewById(R.id.radioConditional)
        radioContinuous          = findViewById(R.id.radioContinuous)
        radioHybrid              = findViewById(R.id.radioHybrid)
        editTraceExportInterval  = findViewById(R.id.editTraceExportInterval)
        editMetricExportInterval = findViewById(R.id.editMetricExportInterval)
        editExportTimeout        = findViewById(R.id.editExportTimeout)
        editMaxRetries           = findViewById(R.id.editMaxRetries)
        checkboxAttachContext    = findViewById(R.id.checkboxAttachContext)
        editBuildChannel         = findViewById(R.id.editBuildChannel)

        // Sampling & Prediction
        sliderSamplingRate       = findViewById(R.id.sliderSamplingRate)
        tvSamplingRateValue      = findViewById(R.id.tvSamplingRateValue)
        sliderPredictionInterval = findViewById(R.id.sliderPredictionInterval)
        tvPredictionIntervalValue= findViewById(R.id.tvPredictionIntervalValue)

        // Session
        checkboxSessionEnabled   = findViewById(R.id.checkboxSessionEnabled)
        editSessionTimeoutMinutes= findViewById(R.id.editSessionTimeoutMinutes)
        checkboxFlushOnSessionEnd= findViewById(R.id.checkboxFlushOnSessionEnd)
        checkboxPersistSession   = findViewById(R.id.checkboxPersistSession)

        // Vitals
        checkboxVitalsEnabled    = findViewById(R.id.checkboxVitalsEnabled)
        checkboxDetectJank       = findViewById(R.id.checkboxDetectJank)
        checkboxMonitorThermal   = findViewById(R.id.checkboxMonitorThermal)
        editAnrThresholdMs       = findViewById(R.id.editAnrThresholdMs)

        // Network
        checkboxScrubUrls        = findViewById(R.id.checkboxScrubUrls)
        checkboxScrubHeaders     = findViewById(R.id.checkboxScrubHeaders)
        editHttpErrorThreshold   = findViewById(R.id.editHttpErrorThreshold)
        editMinRequestDurationMs = findViewById(R.id.editMinRequestDurationMs)

        // Error Handling
        checkboxCaptureUncaughtExceptions  = findViewById(R.id.checkboxCaptureUncaughtExceptions)
        checkboxCaptureCoroutineExceptions = findViewById(R.id.checkboxCaptureCoroutineExceptions)
        checkboxScrubStackTraces           = findViewById(R.id.checkboxScrubStackTraces)
        checkboxFlushOnError               = findViewById(R.id.checkboxFlushOnError)
        editErrorRateLimit                 = findViewById(R.id.editErrorRateLimit)
        editErrorDedupeWindowMinutes       = findViewById(R.id.editErrorDedupeWindowMinutes)

        // UI Telemetry
        radioGroupUiTelemetryMode = findViewById(R.id.radioGroupUiTelemetryMode)
        radioUiEvents             = findViewById(R.id.radioUiEvents)
        radioUiSpans              = findViewById(R.id.radioUiSpans)
        radioUiBoth               = findViewById(R.id.radioUiBoth)
        switchTextCharCount       = findViewById(R.id.switchTextCharCount)
        switchTextIsSet           = findViewById(R.id.switchTextIsSet)
        switchTextContent         = findViewById(R.id.switchTextContent)

        // Incubating
        switchScreenshotEnabled       = findViewById(R.id.switchScreenshotEnabled)
        switchScreenshotOnScreenView  = findViewById(R.id.switchScreenshotOnScreenView)
        switchWireframeEnabled        = findViewById(R.id.switchWireframeEnabled)

        // Capture
        checkboxCaptureLifecycle  = findViewById(R.id.checkboxCaptureLifecycle)
        checkboxCaptureScreens    = findViewById(R.id.checkboxCaptureScreens)
        checkboxCaptureTaps       = findViewById(R.id.checkboxCaptureTaps)
        checkboxCaptureLongPress  = findViewById(R.id.checkboxCaptureLongPress)
        checkboxCaptureSwipe      = findViewById(R.id.checkboxCaptureSwipe)
        checkboxCaptureScroll     = findViewById(R.id.checkboxCaptureScroll)
        checkboxCaptureTextInput  = findViewById(R.id.checkboxCaptureTextInput)
        checkboxCaptureBackPress  = findViewById(R.id.checkboxCaptureBackPress)
        checkboxCaptureFragments  = findViewById(R.id.checkboxCaptureFragments)

        // Buttons
        btnSave         = findViewById(R.id.btnSave)
        btnResetDefaults= findViewById(R.id.btnResetDefaults)
        btnCancel       = findViewById(R.id.btnCancel)

        loadConfiguration()

        btnSave.setOnClickListener { saveConfiguration() }
        btnResetDefaults.setOnClickListener { resetToDefaults() }
        btnCancel.setOnClickListener { finish() }

        // Live sampling rate
        sliderSamplingRate.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                tvSamplingRateValue.text = "${value.toInt()}%"
                MobileOtel.getProvider().setSamplingRate(value / 100.0)
                ConfigManager.saveSamplingRate(this, value / 100f)
            }
        }

        sliderPredictionInterval.addOnChangeListener { _, value, _ ->
            tvPredictionIntervalValue.text = "${value.toInt()}s"
        }
    }

    private fun loadConfiguration() {
        val config = ConfigManager.loadConfig(this)

        editServiceName.setText(config.serviceName)
        editServiceVersion.setText(config.serviceVersion)
        editRamBufferSize.setText(config.ramBufferSize.toString())
        editDiskBufferMb.setText(config.diskBufferMb.toString())
        editDiskBufferTtl.setText(config.diskBufferTtlHours.toString())

        when (config.exportMode) {
            ExportMode.CONDITIONAL -> radioConditional.isChecked = true
            ExportMode.CONTINUOUS  -> radioContinuous.isChecked = true
            ExportMode.HYBRID      -> radioHybrid.isChecked = true
        }
        editTraceExportInterval.setText(config.traceExportIntervalSeconds.toString())
        editMetricExportInterval.setText(config.metricExportIntervalSeconds.toString())
        editExportTimeout.setText(config.exportTimeoutSeconds.toString())
        editMaxRetries.setText(config.maxExportRetries.toString())
        checkboxAttachContext.isChecked = config.attachContextAttributes
        editBuildChannel.setText(config.buildChannel ?: "")

        val pct = (ConfigManager.getSamplingRate(this) * 100).toInt().coerceIn(0, 100).toFloat()
        sliderSamplingRate.value = pct
        tvSamplingRateValue.text = "${pct.toInt()}%"

        val predIntervalSec = config.predictionIntervalSeconds.coerceIn(10L, 120L).toFloat()
        sliderPredictionInterval.value = predIntervalSec
        tvPredictionIntervalValue.text = "${predIntervalSec.toInt()}s"

        val session = config.sessionConfig
        checkboxSessionEnabled.isChecked    = session.enabled
        editSessionTimeoutMinutes.setText((session.inactivityTimeoutMs / 60_000L).toString())
        checkboxFlushOnSessionEnd.isChecked = session.flushOnTermination
        checkboxPersistSession.isChecked    = session.persistSession

        val vitals = config.vitalsConfig
        checkboxVitalsEnabled.isChecked  = vitals.enabled
        checkboxDetectJank.isChecked     = vitals.detectJank
        checkboxMonitorThermal.isChecked = vitals.monitorThermalState
        editAnrThresholdMs.setText(vitals.anrRiskThresholdMs.toString())

        val network = config.networkConfig
        checkboxScrubUrls.isChecked    = network.scrubUrls
        checkboxScrubHeaders.isChecked = network.scrubHeaders
        editHttpErrorThreshold.setText(network.errorStatusThreshold.toString())
        editMinRequestDurationMs.setText(network.minDurationMs.toString())

        val error = config.errorConfig
        checkboxCaptureUncaughtExceptions.isChecked  = error.captureUncaughtExceptions
        checkboxCaptureCoroutineExceptions.isChecked = error.captureCoroutineExceptions
        checkboxScrubStackTraces.isChecked           = error.scrubStackTraces
        checkboxFlushOnError.isChecked               = error.flushOnError
        editErrorRateLimit.setText(error.rateLimit.toString())
        editErrorDedupeWindowMinutes.setText((error.deduplicateWindowMs / 60_000L).toString())

        val screenshot = config.screenshotConfig
        switchScreenshotEnabled.isChecked      = screenshot.enabled
        switchScreenshotOnScreenView.isChecked = screenshot.captureOnScreenView
        switchScreenshotOnScreenView.isEnabled = screenshot.enabled
        switchWireframeEnabled.isChecked       = config.wireframeConfig.enabled

        // Toggle sub-option availability when screenshot is toggled.
        switchScreenshotEnabled.setOnCheckedChangeListener { _, isChecked ->
            switchScreenshotOnScreenView.isEnabled = isChecked
        }

        when (ConfigManager.getUiTelemetryMode(this)) {
            "SPANS" -> radioUiSpans.isChecked = true
            "BOTH"  -> radioUiBoth.isChecked  = true
            else    -> radioUiEvents.isChecked = true
        }
        switchTextCharCount.isChecked = ConfigManager.getTextCaptureCharCount(this)
        switchTextIsSet.isChecked     = ConfigManager.getTextCaptureIsSet(this)
        switchTextContent.isChecked   = ConfigManager.getTextCaptureContent(this)

        val capture = ConfigManager.loadCaptureOptions(this)
        checkboxCaptureLifecycle.isChecked  = capture[ConfigManager.captureKey("lifecycle")]  ?: true
        checkboxCaptureScreens.isChecked    = capture[ConfigManager.captureKey("screens")]    ?: true
        checkboxCaptureTaps.isChecked       = capture[ConfigManager.captureKey("taps")]       ?: true
        checkboxCaptureLongPress.isChecked  = capture[ConfigManager.captureKey("long_press")] ?: true
        checkboxCaptureSwipe.isChecked      = capture[ConfigManager.captureKey("swipe")]      ?: true
        checkboxCaptureScroll.isChecked     = capture[ConfigManager.captureKey("scroll")]     ?: true
        checkboxCaptureTextInput.isChecked  = capture[ConfigManager.captureKey("text_input")] ?: true
        checkboxCaptureBackPress.isChecked  = capture[ConfigManager.captureKey("back_press")] ?: true
        checkboxCaptureFragments.isChecked  = capture[ConfigManager.captureKey("fragments")]  ?: true
    }

    private fun saveConfiguration() {
        try {
            val exportModeStr = when (radioGroupExportMode.checkedRadioButtonId) {
                R.id.radioConditional -> "CONDITIONAL"
                R.id.radioContinuous  -> "CONTINUOUS"
                R.id.radioHybrid      -> "HYBRID"
                else                  -> "CONDITIONAL"
            }

            val samplingRate        = sliderSamplingRate.value / 100.0
            val predictionIntervalSec = sliderPredictionInterval.value.toLong()

            // Load existing Dash0 connection config (preserved — not overwritten here)
            val existing = ConfigManager.loadConfig(this)

            val uiTelemetryMode = when (radioGroupUiTelemetryMode.checkedRadioButtonId) {
                R.id.radioUiSpans -> UiTelemetryMode.SPANS
                R.id.radioUiBoth  -> UiTelemetryMode.BOTH
                else              -> UiTelemetryMode.EVENTS
            }
            val textInputConfig = TextInputConfig(
                captureCharCount    = switchTextCharCount.isChecked,
                captureIsSet        = switchTextIsSet.isChecked,
                captureTextContent  = switchTextContent.isChecked
            )

            val config = MobileConfig(
                serviceName              = editServiceName.text.toString().trim(),
                serviceVersion           = editServiceVersion.text.toString().trim(),
                collectorEndpoint        = existing.collectorEndpoint,
                uiTelemetryMode          = uiTelemetryMode,
                textInputConfig          = textInputConfig,
                ramBufferSize            = editRamBufferSize.text.toString().toInt(),
                diskBufferMb             = editDiskBufferMb.text.toString().toInt(),
                diskBufferTtlHours       = editDiskBufferTtl.text.toString().toInt(),
                traceExportIntervalSeconds  = editTraceExportInterval.text.toString().toLong(),
                metricExportIntervalSeconds = editMetricExportInterval.text.toString().toLong(),
                exportTimeoutSeconds     = editExportTimeout.text.toString().toLong(),
                exportMode               = when (exportModeStr) {
                    "CONTINUOUS" -> ExportMode.CONTINUOUS
                    "HYBRID"     -> ExportMode.HYBRID
                    else         -> ExportMode.CONDITIONAL
                },
                configPollIntervalSeconds  = 300,
                predictionIntervalSeconds  = predictionIntervalSec,
                maxExportRetries           = editMaxRetries.text.toString().toInt(),
                headers                    = existing.headers,
                attachContextAttributes    = checkboxAttachContext.isChecked,
                buildChannel               = editBuildChannel.text.toString().trim().ifBlank { null },
                samplingConfig             = SamplingConfig.dynamic(normalRate = samplingRate, highPriorityRate = 1.0),
                sessionConfig              = SessionConfig(
                    enabled              = checkboxSessionEnabled.isChecked,
                    inactivityTimeoutMs  = editSessionTimeoutMinutes.text.toString().toLong() * 60_000L,
                    flushOnTermination   = checkboxFlushOnSessionEnd.isChecked,
                    persistSession       = checkboxPersistSession.isChecked
                ),
                vitalsConfig               = VitalsConfig(
                    enabled              = checkboxVitalsEnabled.isChecked,
                    detectJank           = checkboxDetectJank.isChecked,
                    monitorThermalState  = checkboxMonitorThermal.isChecked,
                    anrRiskThresholdMs   = editAnrThresholdMs.text.toString().toLong()
                ),
                networkConfig              = NetworkConfig(
                    scrubUrls            = checkboxScrubUrls.isChecked,
                    scrubHeaders         = checkboxScrubHeaders.isChecked,
                    errorStatusThreshold = editHttpErrorThreshold.text.toString().toInt(),
                    minDurationMs        = editMinRequestDurationMs.text.toString().toLong()
                ),
                errorConfig                = ErrorConfig(
                    captureUncaughtExceptions  = checkboxCaptureUncaughtExceptions.isChecked,
                    captureCoroutineExceptions = checkboxCaptureCoroutineExceptions.isChecked,
                    scrubStackTraces           = checkboxScrubStackTraces.isChecked,
                    flushOnError               = checkboxFlushOnError.isChecked,
                    rateLimit                  = editErrorRateLimit.text.toString().toInt(),
                    deduplicateWindowMs        = editErrorDedupeWindowMinutes.text.toString().toLong() * 60_000L
                ),
                screenshotConfig           = ScreenshotConfig(
                    enabled                    = switchScreenshotEnabled.isChecked,
                    captureOnScreenView        = switchScreenshotOnScreenView.isChecked
                ),
                wireframeConfig            = WireframeConfig(
                    enabled                    = switchWireframeEnabled.isChecked
                )
            )

            ConfigManager.saveConfig(this, config)
            ConfigManager.saveCaptureOptions(this, mapOf(
                ConfigManager.captureKey("lifecycle")  to checkboxCaptureLifecycle.isChecked,
                ConfigManager.captureKey("screens")    to checkboxCaptureScreens.isChecked,
                ConfigManager.captureKey("taps")       to checkboxCaptureTaps.isChecked,
                ConfigManager.captureKey("long_press") to checkboxCaptureLongPress.isChecked,
                ConfigManager.captureKey("swipe")      to checkboxCaptureSwipe.isChecked,
                ConfigManager.captureKey("scroll")     to checkboxCaptureScroll.isChecked,
                ConfigManager.captureKey("text_input") to checkboxCaptureTextInput.isChecked,
                ConfigManager.captureKey("back_press") to checkboxCaptureBackPress.isChecked,
                ConfigManager.captureKey("fragments")  to checkboxCaptureFragments.isChecked
            ))

            Toast.makeText(this, "Saved. Restart app to apply changes.", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetToDefaults() {
        ConfigManager.resetToDefaults(this)
        // Reset incubating switches to off (loadConfiguration will also set them, but be explicit)
        switchScreenshotEnabled.isChecked = false
        switchScreenshotOnScreenView.isChecked = false
        switchScreenshotOnScreenView.isEnabled = false
        switchWireframeEnabled.isChecked = false
        loadConfiguration()
        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
