package io.opentelemetry.android.demo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.UUID

/**
 * Demo application showcasing OpenTelemetry-native mobile observability.
 *
 * This app demonstrates:
 * - Initialization of MobileLoggerProvider with OTEL SDK
 * - Capturing events using OTEL Logger API
 * - Automatic buffering and conditional export
 * - Export policy-based selective flushing
 *
 * **Demo Scenarios:**
 * 1. UI Freeze Detection - Simulates a slow operation
 * 2. Crash Simulation - Demonstrates crash recovery
 * 3. Network Error - Triggers error-based workflow
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "OTELDemoApp"

    private lateinit var logger: Logger
    private lateinit var loggerProvider: MobileLoggerProvider
    private lateinit var demoRunId: String

    private lateinit var statusText: TextView
    private lateinit var btnScenarioA: Button
    private lateinit var btnScenarioB: Button
    private lateinit var btnScenarioC: Button
    private lateinit var btnForceFlush: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI
        statusText = findViewById(R.id.statusText)
        btnScenarioA = findViewById(R.id.btnScenarioA)
        btnScenarioB = findViewById(R.id.btnScenarioB)
        btnScenarioC = findViewById(R.id.btnScenarioC)
        btnForceFlush = findViewById(R.id.btnForceFlush)

        // Initialize OpenTelemetry
        initializeOTEL()

        // Set up button listeners
        setupButtons()

        // Log app start
        logAppStart()
    }

    /**
     * Initializes the OpenTelemetry MobileLoggerProvider.
     * Configuration is loaded from SharedPreferences (managed by ConfigManager).
     */
    private fun initializeOTEL() {
        demoRunId = UUID.randomUUID().toString()

        // Load configuration from SharedPreferences
        val config = ConfigManager.loadConfig(this)

        loggerProvider = MobileLoggerProvider.getInstance(this, config)
        logger = loggerProvider.get("demo-app")

        updateStatus("✅ OpenTelemetry initialized\nDevice ID: ${loggerProvider.getDeviceId()}\nRun ID: $demoRunId\nEndpoint: ${config.collectorEndpoint}")
        Log.i(TAG, "OpenTelemetry initialized: deviceId=${loggerProvider.getDeviceId()}, runId=$demoRunId, endpoint=${config.collectorEndpoint}")
    }

    /**
     * Sets up button click listeners for demo scenarios.
     */
    private fun setupButtons() {
        btnScenarioA.setOnClickListener {
            runScenarioA()
        }

        btnScenarioB.setOnClickListener {
            runScenarioB()
        }

        btnScenarioC.setOnClickListener {
            runScenarioC()
        }

        btnForceFlush.setOnClickListener {
            forceFlush()
        }
    }

    /**
     * Logs application start event.
     */
    private fun logAppStart() {
        logger.logRecordBuilder()
            .setBody("app.start")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("screen"), "MainActivity",
                    AttributeKey.stringKey("device_id"), loggerProvider.getDeviceId()
                )
            )
            .emit()

        Log.i(TAG, "Logged app.start event")
    }

    /**
     * Scenario A: UI Freeze Detection
     *
     * Simulates a UI freeze (>2s) which should trigger the export policy:
     * - Event: ui.freeze with duration_ms > 2000
     * - Action: Flush last 2 minutes of events
     */
    private fun runScenarioA() {
        updateStatus("🔵 Running Scenario A: UI Freeze Detection")

        // Log some user activity before the freeze
        for (i in 1..5) {
            logger.logRecordBuilder()
                .setBody("user.action")
                .setSeverity(Severity.INFO)
                .setAllAttributes(
                    Attributes.of(
                        AttributeKey.stringKey("demo_run_id"), demoRunId,
                        AttributeKey.stringKey("action_type"), "button_click",
                        AttributeKey.stringKey("button_id"), "btn_$i",
                        AttributeKey.stringKey("screen"), "MainActivity"
                    )
                )
                .emit()

            Thread.sleep(100) // Simulate time between actions
        }

        // Simulate UI freeze
        Log.w(TAG, "Simulating UI freeze...")
        Thread.sleep(2500) // 2.5 seconds

        // Log the UI freeze event (should trigger workflow)
        logger.logRecordBuilder()
            .setBody("ui.freeze")
            .setSeverity(Severity.WARN)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.longKey("duration_ms"), 2500L,
                    AttributeKey.stringKey("screen"), "MainActivity",
                    AttributeKey.stringKey("thread"), "main"
                )
            )
            .emit()

        updateStatus("✅ Scenario A complete\nUI freeze logged (2500ms)\nExport policy should flush last 2 minutes")
        Log.i(TAG, "Scenario A complete: ui.freeze event logged")
    }

    /**
     * Scenario B: Crash Recovery
     *
     * Marks a crash flag that would be detected on next app start:
     * - Event: crash.detected on restart
     * - Action: Flush last 5 minutes from disk buffer
     */
    private fun runScenarioB() {
        updateStatus("🔵 Running Scenario B: Crash Simulation")

        // Log some events
        for (i in 1..10) {
            logger.logRecordBuilder()
                .setBody("background.task")
                .setSeverity(Severity.INFO)
                .setAllAttributes(
                    Attributes.of(
                        AttributeKey.stringKey("demo_run_id"), demoRunId,
                        AttributeKey.stringKey("task_type"), "data_sync",
                        AttributeKey.longKey("task_id"), i.toLong()
                    )
                )
                .emit()

            Thread.sleep(50)
        }

        // Mark crash flag
        getSharedPreferences("otel_demo", MODE_PRIVATE)
            .edit()
            .putBoolean("crash_marker", true)
            .putLong("crash_time", System.currentTimeMillis())
            .apply()

        // Log crash event
        logger.logRecordBuilder()
            .setBody("app.crash")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("crash_type"), "simulated",
                    AttributeKey.stringKey("screen"), "MainActivity"
                )
            )
            .emit()

        updateStatus("✅ Scenario B complete\nCrash marker set\nOn next start, crash recovery policy would trigger")
        Log.i(TAG, "Scenario B complete: crash marker set")
    }

    /**
     * Scenario C: Network Error Escalation
     *
     * Simulates a server error (HTTP 500) which should trigger export policy:
     * - Event: http.error with status >= 500 on /appointments
     * - Action: Flush last 2 minutes + increase sampling to 100% for 10 minutes
     */
    private fun runScenarioC() {
        updateStatus("🔵 Running Scenario C: Network Error")

        // Log successful API calls
        for (i in 1..3) {
            logger.logRecordBuilder()
                .setBody("http.request")
                .setSeverity(Severity.INFO)
                .setAllAttributes(
                    Attributes.of(
                        AttributeKey.stringKey("demo_run_id"), demoRunId,
                        AttributeKey.stringKey("http.method"), "GET",
                        AttributeKey.stringKey("http.route"), "/appointments",
                        AttributeKey.longKey("http.status_code"), 200L,
                        AttributeKey.longKey("http.duration_ms"), (50 + i * 10).toLong()
                    )
                )
                .emit()

            Thread.sleep(100)
        }

        // Log server error (should trigger workflow)
        logger.logRecordBuilder()
            .setBody("http.error")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("http.method"), "POST",
                    AttributeKey.stringKey("http.route"), "/appointments",
                    AttributeKey.longKey("http.status_code"), 500L,
                    AttributeKey.stringKey("error.message"), "Internal Server Error",
                    AttributeKey.stringKey("http.duration_ms"), "1250"
                )
            )
            .emit()

        updateStatus("✅ Scenario C complete\nHTTP 500 error logged\nExport policy should flush + increase sampling")
        Log.i(TAG, "Scenario C complete: HTTP 500 error logged")
    }

    /**
     * Forces an immediate flush of all buffered events.
     */
    private fun forceFlush() {
        updateStatus("🔵 Force flushing all events...")
        Log.i(TAG, "Force flush requested")

        Thread {
            val result = loggerProvider.forceFlush(30)
            runOnUiThread {
                if (result.isSuccess) {
                    updateStatus("✅ Force flush complete\nAll events exported via OTLP")
                    Log.i(TAG, "Force flush successful")
                } else {
                    updateStatus("❌ Force flush failed")
                    Log.e(TAG, "Force flush failed")
                }
            }
        }.start()
    }

    /**
     * Updates the status text view.
     */
    private fun updateStatus(status: String) {
        statusText.text = status
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Shutdown OpenTelemetry on app close
        Log.i(TAG, "Shutting down OpenTelemetry")
        loggerProvider.shutdown(30)
    }
}
