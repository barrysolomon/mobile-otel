package io.opentelemetry.android.demo

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.core.SessionConfig
import io.opentelemetry.android.mobile.errors.ErrorConfig
import io.opentelemetry.android.mobile.network.NetworkConfig
import io.opentelemetry.android.mobile.sampling.SamplingConfig
import io.opentelemetry.android.mobile.vitals.VitalsConfig

/**
 * Comprehensive configuration screen for all OpenTelemetry parameters.
 *
 * Covers every configurable surface in MobileConfig and its sub-configs:
 * - Service identity
 * - Collector endpoint, protocol, authentication
 * - Buffer sizes and retention
 * - Export mode (CONDITIONAL / CONTINUOUS / HYBRID) + intervals
 * - Prediction cycle interval
 * - Advanced: context attributes, build channel
 * - Trace sampling rate (live — no restart needed)
 * - Session management
 * - Vitals monitoring
 * - Network instrumentation
 * - Error handling
 */
class ConfigActivity : AppCompatActivity() {

    // Service Identity
    private lateinit var editServiceName: EditText
    private lateinit var editServiceVersion: EditText

    // Collector
    private lateinit var radioGroupProtocol: RadioGroup
    private lateinit var radioGrpc: RadioButton
    private lateinit var radioHttp: RadioButton
    private lateinit var editCollectorEndpoint: EditText
    private lateinit var editAuthToken: EditText
    private lateinit var editDataset: EditText

    // Buffer
    private lateinit var editRamBufferSize: EditText
    private lateinit var editDiskBufferMb: EditText
    private lateinit var editDiskBufferTtl: EditText

    // Export
    private lateinit var radioGroupExportMode: RadioGroup
    private lateinit var radioConditional: RadioButton
    private lateinit var radioContinuous: RadioButton
    private lateinit var radioHybrid: RadioButton
    private lateinit var editTraceExportInterval: EditText
    private lateinit var editMetricExportInterval: EditText
    private lateinit var editExportTimeout: EditText
    private lateinit var editMaxRetries: EditText

    // Advanced
    private lateinit var checkboxAttachContext: CheckBox
    private lateinit var editBuildChannel: EditText

    // Trace Sampling
    private lateinit var sliderSamplingRate: Slider
    private lateinit var tvSamplingRateValue: TextView

    // Prediction
    private lateinit var sliderPredictionInterval: Slider
    private lateinit var tvPredictionIntervalValue: TextView

    // Session
    private lateinit var checkboxSessionEnabled: CheckBox
    private lateinit var editSessionTimeoutMinutes: EditText
    private lateinit var checkboxFlushOnSessionEnd: CheckBox
    private lateinit var checkboxPersistSession: CheckBox

    // Vitals
    private lateinit var checkboxVitalsEnabled: CheckBox
    private lateinit var checkboxDetectJank: CheckBox
    private lateinit var checkboxMonitorThermal: CheckBox
    private lateinit var editAnrThresholdMs: EditText

    // Network
    private lateinit var checkboxScrubUrls: CheckBox
    private lateinit var checkboxScrubHeaders: CheckBox
    private lateinit var editHttpErrorThreshold: EditText
    private lateinit var editMinRequestDurationMs: EditText

    // Error Handling
    private lateinit var checkboxCaptureUncaughtExceptions: CheckBox
    private lateinit var checkboxCaptureCoroutineExceptions: CheckBox
    private lateinit var checkboxScrubStackTraces: CheckBox
    private lateinit var checkboxFlushOnError: CheckBox
    private lateinit var editErrorRateLimit: EditText
    private lateinit var editErrorDedupeWindowMinutes: EditText

    // What to Capture
    private lateinit var checkboxCaptureLifecycle: CheckBox
    private lateinit var checkboxCaptureScreens: CheckBox
    private lateinit var checkboxCaptureTaps: CheckBox
    private lateinit var checkboxCaptureLongPress: CheckBox
    private lateinit var checkboxCaptureSwipe: CheckBox
    private lateinit var checkboxCaptureScroll: CheckBox
    private lateinit var checkboxCaptureTextInput: CheckBox
    private lateinit var checkboxCaptureBackPress: CheckBox
    private lateinit var checkboxCaptureFragments: CheckBox

    // Buttons
    private lateinit var btnSave: Button
    private lateinit var btnResetDefaults: Button
    private lateinit var btnClose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Configuration"

        // Initialize views
        editServiceName = findViewById(R.id.editServiceName)
        editServiceVersion = findViewById(R.id.editServiceVersion)
        radioGroupProtocol = findViewById(R.id.radioGroupProtocol)
        radioGrpc = findViewById(R.id.radioGrpc)
        radioHttp = findViewById(R.id.radioHttp)
        editCollectorEndpoint = findViewById(R.id.editCollectorEndpoint)
        editAuthToken = findViewById(R.id.editAuthToken)
        editDataset = findViewById(R.id.editDataset)
        editRamBufferSize = findViewById(R.id.editRamBufferSize)
        editDiskBufferMb = findViewById(R.id.editDiskBufferMb)
        editDiskBufferTtl = findViewById(R.id.editDiskBufferTtl)
        radioGroupExportMode = findViewById(R.id.radioGroupExportMode)
        radioConditional = findViewById(R.id.radioConditional)
        radioContinuous = findViewById(R.id.radioContinuous)
        radioHybrid = findViewById(R.id.radioHybrid)
        editTraceExportInterval = findViewById(R.id.editTraceExportInterval)
        editMetricExportInterval = findViewById(R.id.editMetricExportInterval)
        editExportTimeout = findViewById(R.id.editExportTimeout)
        editMaxRetries = findViewById(R.id.editMaxRetries)
        checkboxAttachContext = findViewById(R.id.checkboxAttachContext)
        editBuildChannel = findViewById(R.id.editBuildChannel)
        sliderSamplingRate = findViewById(R.id.sliderSamplingRate)
        tvSamplingRateValue = findViewById(R.id.tvSamplingRateValue)
        sliderPredictionInterval = findViewById(R.id.sliderPredictionInterval)
        tvPredictionIntervalValue = findViewById(R.id.tvPredictionIntervalValue)
        checkboxSessionEnabled = findViewById(R.id.checkboxSessionEnabled)
        editSessionTimeoutMinutes = findViewById(R.id.editSessionTimeoutMinutes)
        checkboxFlushOnSessionEnd = findViewById(R.id.checkboxFlushOnSessionEnd)
        checkboxPersistSession = findViewById(R.id.checkboxPersistSession)
        checkboxVitalsEnabled = findViewById(R.id.checkboxVitalsEnabled)
        checkboxDetectJank = findViewById(R.id.checkboxDetectJank)
        checkboxMonitorThermal = findViewById(R.id.checkboxMonitorThermal)
        editAnrThresholdMs = findViewById(R.id.editAnrThresholdMs)
        checkboxScrubUrls = findViewById(R.id.checkboxScrubUrls)
        checkboxScrubHeaders = findViewById(R.id.checkboxScrubHeaders)
        editHttpErrorThreshold = findViewById(R.id.editHttpErrorThreshold)
        editMinRequestDurationMs = findViewById(R.id.editMinRequestDurationMs)
        checkboxCaptureUncaughtExceptions = findViewById(R.id.checkboxCaptureUncaughtExceptions)
        checkboxCaptureCoroutineExceptions = findViewById(R.id.checkboxCaptureCoroutineExceptions)
        checkboxScrubStackTraces = findViewById(R.id.checkboxScrubStackTraces)
        checkboxFlushOnError = findViewById(R.id.checkboxFlushOnError)
        editErrorRateLimit = findViewById(R.id.editErrorRateLimit)
        editErrorDedupeWindowMinutes = findViewById(R.id.editErrorDedupeWindowMinutes)
        checkboxCaptureLifecycle = findViewById(R.id.checkboxCaptureLifecycle)
        checkboxCaptureScreens = findViewById(R.id.checkboxCaptureScreens)
        checkboxCaptureTaps = findViewById(R.id.checkboxCaptureTaps)
        checkboxCaptureLongPress = findViewById(R.id.checkboxCaptureLongPress)
        checkboxCaptureSwipe = findViewById(R.id.checkboxCaptureSwipe)
        checkboxCaptureScroll = findViewById(R.id.checkboxCaptureScroll)
        checkboxCaptureTextInput = findViewById(R.id.checkboxCaptureTextInput)
        checkboxCaptureBackPress = findViewById(R.id.checkboxCaptureBackPress)
        checkboxCaptureFragments = findViewById(R.id.checkboxCaptureFragments)
        btnSave = findViewById(R.id.btnSave)
        btnResetDefaults = findViewById(R.id.btnResetDefaults)
        btnClose = findViewById(R.id.btnClose)

        // Load current configuration
        loadConfiguration()

        // Set up button listeners
        btnSave.setOnClickListener {
            saveConfiguration()
        }

        btnResetDefaults.setOnClickListener {
            resetToDefaults()
        }

        btnClose.setOnClickListener {
            finish()
        }

        // Live sampling rate update — no restart needed
        sliderSamplingRate.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                tvSamplingRateValue.text = "${value.toInt()}%"
                val rate = value / 100.0
                MobileOtel.getProvider().setSamplingRate(rate)
                ConfigManager.saveSamplingRate(this, value / 100f)
            }
        }

        // Prediction interval slider — display only, saved on Save button
        sliderPredictionInterval.addOnChangeListener { _, value, _ ->
            tvPredictionIntervalValue.text = "${value.toInt()}s"
        }

        // Update endpoint hint based on protocol selection
        radioGroupProtocol.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioGrpc -> {
                    editCollectorEndpoint.hint = "e.g., https://ingress.us-west-2.aws.dash0.com:4317"
                }
                R.id.radioHttp -> {
                    editCollectorEndpoint.hint = "e.g., https://ingress.us-west-2.aws.dash0.com/v1/logs"
                }
            }
        }
    }

    /**
     * Loads the current configuration into the UI fields.
     */
    private fun loadConfiguration() {
        val config = ConfigManager.loadConfig(this)
        val protocol = ConfigManager.getProtocol(this)

        editServiceName.setText(config.serviceName)
        editServiceVersion.setText(config.serviceVersion)

        // Set protocol radio button
        when (protocol) {
            "grpc" -> radioGrpc.isChecked = true
            "http" -> radioHttp.isChecked = true
            else -> radioGrpc.isChecked = true
        }

        editCollectorEndpoint.setText(config.collectorEndpoint)
        editAuthToken.setText(ConfigManager.getAuthToken(this))
        editDataset.setText(ConfigManager.getDataset(this))
        editRamBufferSize.setText(config.ramBufferSize.toString())
        editDiskBufferMb.setText(config.diskBufferMb.toString())
        editDiskBufferTtl.setText(config.diskBufferTtlHours.toString())

        // Export mode
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

        val savedRate = ConfigManager.getSamplingRate(this)
        val pct = (savedRate * 100).toInt().coerceIn(0, 100).toFloat()
        sliderSamplingRate.value = pct
        tvSamplingRateValue.text = "${pct.toInt()}%"

        val predIntervalSec = config.predictionIntervalSeconds.coerceIn(10L, 120L).toFloat()
        sliderPredictionInterval.value = predIntervalSec
        tvPredictionIntervalValue.text = "${predIntervalSec.toInt()}s"

        // Session
        val session = config.sessionConfig
        checkboxSessionEnabled.isChecked = session.enabled
        editSessionTimeoutMinutes.setText((session.inactivityTimeoutMs / 60_000L).toString())
        checkboxFlushOnSessionEnd.isChecked = session.flushOnTermination
        checkboxPersistSession.isChecked = session.persistSession

        // Vitals
        val vitals = config.vitalsConfig
        checkboxVitalsEnabled.isChecked = vitals.enabled
        checkboxDetectJank.isChecked = vitals.detectJank
        checkboxMonitorThermal.isChecked = vitals.monitorThermalState
        editAnrThresholdMs.setText(vitals.anrRiskThresholdMs.toString())

        // Network
        val network = config.networkConfig
        checkboxScrubUrls.isChecked = network.scrubUrls
        checkboxScrubHeaders.isChecked = network.scrubHeaders
        editHttpErrorThreshold.setText(network.errorStatusThreshold.toString())
        editMinRequestDurationMs.setText(network.minDurationMs.toString())

        // Error handling
        val error = config.errorConfig
        checkboxCaptureUncaughtExceptions.isChecked = error.captureUncaughtExceptions
        checkboxCaptureCoroutineExceptions.isChecked = error.captureCoroutineExceptions
        checkboxScrubStackTraces.isChecked = error.scrubStackTraces
        checkboxFlushOnError.isChecked = error.flushOnError
        editErrorRateLimit.setText(error.rateLimit.toString())
        editErrorDedupeWindowMinutes.setText((error.deduplicateWindowMs / 60_000L).toString())

        // What to Capture
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

    /**
     * Saves the configuration from UI fields to SharedPreferences.
     */
    private fun saveConfiguration() {
        try {
            // Save protocol preference
            val protocol = when (radioGroupProtocol.checkedRadioButtonId) {
                R.id.radioGrpc -> "grpc"
                R.id.radioHttp -> "http"
                else -> "grpc"
            }
            ConfigManager.saveProtocol(this, protocol)

            val exportModeStr = when (radioGroupExportMode.checkedRadioButtonId) {
                R.id.radioConditional -> "CONDITIONAL"
                R.id.radioContinuous  -> "CONTINUOUS"
                R.id.radioHybrid      -> "HYBRID"
                else                  -> "CONDITIONAL"
            }

            // Save auth token and dataset separately
            val authToken = editAuthToken.text.toString().trim()
            val dataset = editDataset.text.toString().trim()

            ConfigManager.saveAuthToken(this, authToken)
            ConfigManager.saveDataset(this, dataset)

            // Build headers map
            val headers = mutableMapOf<String, String>()
            if (authToken.isNotBlank()) {
                headers["Authorization"] = "Bearer $authToken"
            }
            if (dataset.isNotBlank()) {
                headers["Dash0-Dataset"] = dataset
            }

            val samplingRate = sliderSamplingRate.value / 100.0
            val predictionIntervalSec = sliderPredictionInterval.value.toLong()

            val sessionConfig = SessionConfig(
                enabled = checkboxSessionEnabled.isChecked,
                inactivityTimeoutMs = editSessionTimeoutMinutes.text.toString().toLong() * 60_000L,
                flushOnTermination = checkboxFlushOnSessionEnd.isChecked,
                persistSession = checkboxPersistSession.isChecked
            )

            val vitalsConfig = VitalsConfig(
                enabled = checkboxVitalsEnabled.isChecked,
                detectJank = checkboxDetectJank.isChecked,
                monitorThermalState = checkboxMonitorThermal.isChecked,
                anrRiskThresholdMs = editAnrThresholdMs.text.toString().toLong()
            )

            val networkConfig = NetworkConfig(
                scrubUrls = checkboxScrubUrls.isChecked,
                scrubHeaders = checkboxScrubHeaders.isChecked,
                errorStatusThreshold = editHttpErrorThreshold.text.toString().toInt(),
                minDurationMs = editMinRequestDurationMs.text.toString().toLong()
            )

            val errorConfig = ErrorConfig(
                captureUncaughtExceptions = checkboxCaptureUncaughtExceptions.isChecked,
                captureCoroutineExceptions = checkboxCaptureCoroutineExceptions.isChecked,
                scrubStackTraces = checkboxScrubStackTraces.isChecked,
                flushOnError = checkboxFlushOnError.isChecked,
                rateLimit = editErrorRateLimit.text.toString().toInt(),
                deduplicateWindowMs = editErrorDedupeWindowMinutes.text.toString().toLong() * 60_000L
            )

            val exportMode = when (exportModeStr) {
                "CONTINUOUS" -> ExportMode.CONTINUOUS
                "HYBRID"     -> ExportMode.HYBRID
                else         -> ExportMode.CONDITIONAL
            }

            val config = MobileConfig(
                serviceName = editServiceName.text.toString().trim(),
                serviceVersion = editServiceVersion.text.toString().trim(),
                collectorEndpoint = editCollectorEndpoint.text.toString().trim(),
                ramBufferSize = editRamBufferSize.text.toString().toInt(),
                diskBufferMb = editDiskBufferMb.text.toString().toInt(),
                diskBufferTtlHours = editDiskBufferTtl.text.toString().toInt(),
                traceExportIntervalSeconds = editTraceExportInterval.text.toString().toLong(),
                metricExportIntervalSeconds = editMetricExportInterval.text.toString().toLong(),
                exportTimeoutSeconds = editExportTimeout.text.toString().toLong(),
                exportMode = exportMode,
                configPollIntervalSeconds = 300,
                predictionIntervalSeconds = predictionIntervalSec,
                maxExportRetries = editMaxRetries.text.toString().toInt(),
                headers = headers.ifEmpty { null },
                attachContextAttributes = checkboxAttachContext.isChecked,
                buildChannel = editBuildChannel.text.toString().trim().ifBlank { null },
                samplingConfig = SamplingConfig.dynamic(normalRate = samplingRate, highPriorityRate = 1.0),
                sessionConfig = sessionConfig,
                vitalsConfig = vitalsConfig,
                networkConfig = networkConfig,
                errorConfig = errorConfig
            )

            ConfigManager.saveConfig(this, config)
            ConfigManager.saveCaptureOptions(this, mapOf(
                ConfigManager.captureKey("lifecycle")   to checkboxCaptureLifecycle.isChecked,
                ConfigManager.captureKey("screens")     to checkboxCaptureScreens.isChecked,
                ConfigManager.captureKey("taps")        to checkboxCaptureTaps.isChecked,
                ConfigManager.captureKey("long_press")  to checkboxCaptureLongPress.isChecked,
                ConfigManager.captureKey("swipe")       to checkboxCaptureSwipe.isChecked,
                ConfigManager.captureKey("scroll")      to checkboxCaptureScroll.isChecked,
                ConfigManager.captureKey("text_input")  to checkboxCaptureTextInput.isChecked,
                ConfigManager.captureKey("back_press")  to checkboxCaptureBackPress.isChecked,
                ConfigManager.captureKey("fragments")   to checkboxCaptureFragments.isChecked
            ))

            Toast.makeText(this, "Configuration saved. Restart app to apply changes.", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Resets all configuration to default values.
     */
    private fun resetToDefaults() {
        ConfigManager.resetToDefaults(this)
        loadConfiguration()
        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
