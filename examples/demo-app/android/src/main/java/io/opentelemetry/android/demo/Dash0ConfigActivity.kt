// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText

/**
 * Dash0 backend connection configuration screen.
 *
 * Manages the collector endpoint, transport protocol, auth token, and dataset
 * (Dash0-Dataset header). All OTel SDK parameters are in [ConfigActivity].
 */
class Dash0ConfigActivity : AppCompatActivity() {

    private lateinit var radioGroupProtocol: RadioGroup
    private lateinit var radioGrpc: RadioButton
    private lateinit var radioHttp: RadioButton
    private lateinit var editCollectorEndpoint: TextInputEditText
    private lateinit var editAuthToken: TextInputEditText
    private lateinit var editDataset: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dash0_config)

        val toolbar = findViewById<MaterialToolbar>(R.id.dash0Toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        radioGroupProtocol      = findViewById(R.id.radioGroupProtocol)
        radioGrpc               = findViewById(R.id.radioGrpc)
        radioHttp               = findViewById(R.id.radioHttp)
        editCollectorEndpoint   = findViewById(R.id.editCollectorEndpoint)
        editAuthToken           = findViewById(R.id.editAuthToken)
        editDataset             = findViewById(R.id.editDataset)

        loadConfiguration()

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDash0Save)
            .setOnClickListener { saveConfiguration() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDash0Reset)
            .setOnClickListener { resetToDefaults() }
    }

    private fun loadConfiguration() {
        val config = ConfigManager.loadConfig(this)
        editCollectorEndpoint.setText(config.collectorEndpoint)
        editAuthToken.setText(ConfigManager.getAuthToken(this))
        editDataset.setText(ConfigManager.getDataset(this))

        when (ConfigManager.getProtocol(this)) {
            "http" -> radioHttp.isChecked = true
            else   -> radioGrpc.isChecked = true
        }
    }

    private fun saveConfiguration() {
        val endpoint  = editCollectorEndpoint.text?.toString()?.trim() ?: ""
        val authToken = editAuthToken.text?.toString()?.trim() ?: ""
        val dataset   = editDataset.text?.toString()?.trim() ?: ""
        val protocol  = if (radioHttp.isChecked) "http" else "grpc"

        // Persist Dash0-specific fields
        ConfigManager.saveProtocol(this, protocol)
        ConfigManager.saveAuthToken(this, authToken)
        ConfigManager.saveDataset(this, dataset)

        // Build headers and persist the full config with updated endpoint + headers
        val headers = mutableMapOf<String, String>()
        if (authToken.isNotBlank()) headers["Authorization"] = "Bearer $authToken"
        if (dataset.isNotBlank())   headers["Dash0-Dataset"]  = dataset

        val existing = ConfigManager.loadConfig(this)
        ConfigManager.saveConfig(this, existing.copy(
            collectorEndpoint = endpoint,
            headers           = headers.ifEmpty { null }
        ))

        Toast.makeText(this, "Saved. Restart app to apply changes.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetToDefaults() {
        editCollectorEndpoint.setText("http://10.0.2.2:4317")
        editAuthToken.setText("")
        editDataset.setText("")
        radioGrpc.isChecked = true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
