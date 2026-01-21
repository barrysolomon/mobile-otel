package com.mobile.observability.demo

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var sdk: ObservabilitySDK
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SDK
        sdk = ObservabilitySDK.initialize(
            context = this,
            appId = "mobile-observability-demo",
            gatewayUrl = BuildConfig.GATEWAY_URL
        )

        statusText = findViewById(R.id.statusText)
        updateStatus("SDK initialized")

        setupDemoButtons()
    }

    private fun setupDemoButtons() {
        // Scenario A: UI Freeze
        findViewById<Button>(R.id.btnUiFreeze).setOnClickListener {
            simulateUiFreeze()
        }

        // Scenario A: UI Jank
        findViewById<Button>(R.id.btnUiJank).setOnClickListener {
            simulateUiJank()
        }

        // Scenario B: Simulate Crash
        findViewById<Button>(R.id.btnSimulateCrash).setOnClickListener {
            simulateCrash()
        }

        // Scenario C: Network Error
        findViewById<Button>(R.id.btnNetworkError).setOnClickListener {
            simulateNetworkError()
        }

        // Generate normal traffic
        findViewById<Button>(R.id.btnNormalTraffic).setOnClickListener {
            generateNormalTraffic()
        }
    }

    private fun simulateUiFreeze() {
        updateStatus("Simulating UI freeze...")

        sdk.captureEvent(
            eventName = "ui.freeze",
            attributes = mapOf(
                "duration_ms" to 3500,
                "screen" to "MainActivity",
                "thread" to "main"
            )
        )

        updateStatus("UI freeze event captured\nShould trigger: flush last 2 minutes")
    }

    private fun simulateUiJank() {
        updateStatus("Simulating UI jank...")

        sdk.captureEvent(
            eventName = "ui.jank",
            attributes = mapOf(
                "duration_ms" to 2500,
                "screen" to "MainActivity",
                "frame_drops" to 45
            )
        )

        updateStatus("UI jank event captured (>2000ms)\nShould trigger: flush last 2 minutes")
    }

    private fun simulateCrash() {
        updateStatus("Writing crash marker...")

        // Write crash marker
        sdk.writeCrashMarker()

        updateStatus("Crash marker written.\nRestart app to trigger crash workflow.")

        // In a real crash, the app would terminate here
        // For demo, we just write the marker
        scope.launch {
            delay(2000)
            updateStatus("(Simulated) - Restart app to see crash recovery")
        }
    }

    private fun simulateNetworkError() {
        updateStatus("Simulating network errors...")

        scope.launch(Dispatchers.IO) {
            repeat(3) { i ->
                sdk.captureEvent(
                    eventName = "http.response",
                    attributes = mapOf(
                        "status" to 503,
                        "route" to "/appointments",
                        "method" to "GET",
                        "duration_ms" to Random.nextInt(1000, 3000),
                        "attempt" to i + 1
                    )
                )
                delay(500)
            }

            launch(Dispatchers.Main) {
                updateStatus("Network errors captured (3x 503 on /appointments)\nShould trigger: targeted flush + sampling rate change")
            }
        }
    }

    private fun generateNormalTraffic() {
        updateStatus("Generating normal traffic...")

        scope.launch(Dispatchers.IO) {
            val events = listOf(
                "screen.view" to mapOf("screen" to "MainActivity"),
                "button.click" to mapOf("button_id" to "btn_demo", "screen" to "MainActivity"),
                "http.response" to mapOf("status" to 200, "route" to "/config", "duration_ms" to 45),
                "user.action" to mapOf("action" to "scroll", "direction" to "down"),
                "http.response" to mapOf("status" to 200, "route" to "/status", "duration_ms" to 32)
            )

            events.forEach { (eventName, attrs) ->
                sdk.captureEvent(eventName, attrs)
                delay(200)
            }

            launch(Dispatchers.Main) {
                updateStatus("Generated ${events.size} normal events\nStored in buffer")
            }
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }
}
