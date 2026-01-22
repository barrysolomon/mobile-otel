package io.opentelemetry.android.demo

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.opentelemetry.android.mobile.config.MobileConfig

/**
 * Settings screen for configuring OpenTelemetry parameters.
 *
 * Allows users to modify:
 * - Service identity (name, version)
 * - Collector endpoint
 * - Buffer sizes and retention
 * - Export behavior
 * - Advanced options
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var editServiceName: EditText
    private lateinit var editServiceVersion: EditText
    private lateinit var radioGroupProtocol: RadioGroup
    private lateinit var radioGrpc: RadioButton
    private lateinit var radioHttp: RadioButton
    private lateinit var editCollectorEndpoint: EditText
    private lateinit var editAuthToken: EditText
    private lateinit var editDataset: EditText
    private lateinit var editRamBufferSize: EditText
    private lateinit var editDiskBufferMb: EditText
    private lateinit var editDiskBufferTtl: EditText
    private lateinit var editExportTimeout: EditText
    private lateinit var editMaxRetries: EditText
    private lateinit var checkboxAttachContext: CheckBox
    private lateinit var editBuildChannel: EditText
    private lateinit var btnSave: Button
    private lateinit var btnResetDefaults: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

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
        editExportTimeout = findViewById(R.id.editExportTimeout)
        editMaxRetries = findViewById(R.id.editMaxRetries)
        checkboxAttachContext = findViewById(R.id.checkboxAttachContext)
        editBuildChannel = findViewById(R.id.editBuildChannel)
        btnSave = findViewById(R.id.btnSave)
        btnResetDefaults = findViewById(R.id.btnResetDefaults)

        // Load current configuration
        loadConfiguration()

        // Set up button listeners
        btnSave.setOnClickListener {
            saveConfiguration()
        }

        btnResetDefaults.setOnClickListener {
            resetToDefaults()
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
        editExportTimeout.setText(config.exportTimeoutSeconds.toString())
        editMaxRetries.setText(config.maxExportRetries.toString())
        checkboxAttachContext.isChecked = config.attachContextAttributes
        editBuildChannel.setText(config.buildChannel ?: "")
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

            val config = MobileConfig(
                serviceName = editServiceName.text.toString().trim(),
                serviceVersion = editServiceVersion.text.toString().trim(),
                collectorEndpoint = editCollectorEndpoint.text.toString().trim(),
                ramBufferSize = editRamBufferSize.text.toString().toInt(),
                diskBufferMb = editDiskBufferMb.text.toString().toInt(),
                diskBufferTtlHours = editDiskBufferTtl.text.toString().toInt(),
                exportTimeoutSeconds = editExportTimeout.text.toString().toLong(),
                configPollIntervalSeconds = 300, // Not configurable in UI
                maxExportRetries = editMaxRetries.text.toString().toInt(),
                headers = headers.ifEmpty { null },
                attachContextAttributes = checkboxAttachContext.isChecked,
                buildChannel = editBuildChannel.text.toString().trim().ifBlank { null }
            )

            ConfigManager.saveConfig(this, config)

            Toast.makeText(this, "Settings saved. Restart app to apply changes.", Toast.LENGTH_LONG).show()
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
