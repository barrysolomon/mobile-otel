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
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.context.Scope
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
    private lateinit var tracer: Tracer
    private lateinit var meter: Meter
    private lateinit var buttonClickCounter: LongCounter
    private lateinit var operationDurationHistogram: DoubleHistogram
    private lateinit var loggerProvider: MobileLoggerProvider
    private lateinit var demoRunId: String

    private lateinit var statusText: TextView
    private lateinit var statusHeader: android.view.View
    private lateinit var statusExpandIcon: TextView
    private var isStatusExpanded = true
    private lateinit var btnScenarioA: Button
    private lateinit var btnScenarioB: Button
    private lateinit var btnScenarioC: Button
    private lateinit var btnScenarioD: Button
    private lateinit var btnUserLogin: Button
    private lateinit var btnPageNav: Button
    private lateinit var btnApiCall: Button
    private lateinit var btnBackgroundTask: Button
    private lateinit var btnUserInteraction: Button
    private lateinit var btnFormSubmit: Button
    private lateinit var btnForceFlush: Button
    private lateinit var btnForceQuit: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up toolbar as action bar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""  // Hide title text, using custom header instead

        // Initialize UI
        statusText = findViewById(R.id.statusText)
        statusHeader = findViewById(R.id.statusHeader)
        statusExpandIcon = findViewById(R.id.statusExpandIcon)

        // Set up status collapse/expand
        statusHeader.setOnClickListener {
            toggleStatusVisibility()
        }
        btnScenarioA = findViewById(R.id.btnScenarioA)
        btnScenarioB = findViewById(R.id.btnScenarioB)
        btnScenarioC = findViewById(R.id.btnScenarioC)
        btnScenarioD = findViewById(R.id.btnScenarioD)
        btnUserLogin = findViewById(R.id.btnUserLogin)
        btnPageNav = findViewById(R.id.btnPageNav)
        btnApiCall = findViewById(R.id.btnApiCall)
        btnBackgroundTask = findViewById(R.id.btnBackgroundTask)
        btnUserInteraction = findViewById(R.id.btnUserInteraction)
        btnFormSubmit = findViewById(R.id.btnFormSubmit)
        btnForceFlush = findViewById(R.id.btnForceFlush)
        btnForceQuit = findViewById(R.id.btnForceQuit)

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

        // Get tracer from the OpenTelemetry SDK
        tracer = loggerProvider.getOpenTelemetrySdk().getTracer("demo-app", "1.0.0")

        // Get meter from the OpenTelemetry SDK
        meter = loggerProvider.getOpenTelemetrySdk().getMeter("demo-app")

        // Create metrics instruments
        buttonClickCounter = meter.counterBuilder("app.button.clicks")
            .setDescription("Counts button clicks in the demo app")
            .setUnit("{clicks}")
            .build()

        operationDurationHistogram = meter.histogramBuilder("app.operation.duration")
            .setDescription("Measures operation durations in milliseconds")
            .setUnit("ms")
            .build()

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

        btnScenarioD.setOnClickListener {
            runScenarioD()
        }

        btnUserLogin.setOnClickListener {
            logUserLogin()
        }

        btnPageNav.setOnClickListener {
            logPageNavigation()
        }

        btnApiCall.setOnClickListener {
            logApiCall()
        }

        btnBackgroundTask.setOnClickListener {
            logBackgroundTask()
        }

        btnUserInteraction.setOnClickListener {
            logUserInteraction()
        }

        btnFormSubmit.setOnClickListener {
            logFormSubmission()
        }

        btnForceFlush.setOnClickListener {
            forceFlush()
        }

        btnForceQuit.setOnClickListener {
            forceQuitApp()
        }
    }

    /**
     * Toggles the visibility of the status text (collapse/expand).
     */
    private fun toggleStatusVisibility() {
        isStatusExpanded = !isStatusExpanded

        if (isStatusExpanded) {
            statusText.visibility = android.view.View.VISIBLE
            statusExpandIcon.text = "▼"
        } else {
            statusText.visibility = android.view.View.GONE
            statusExpandIcon.text = "▶"
        }
    }

    /**
     * Logs application start event and checks for crash/force quit recovery.
     *
     * Detection strategy:
     * - Sets "session_active" marker on app start
     * - Clears it on clean shutdown (onDestroy)
     * - If marker exists on next start = app was force killed
     */
    private fun logAppStart() {
        val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)

        // Check if app was force quit, crashed, or killed by system
        val manualForceQuit = prefs.getBoolean("force_quit_marker", false)
        val wasCrash = prefs.getBoolean("crash_marker", false)
        val wasLowMemory = prefs.getBoolean("low_memory_marker", false)
        val sessionWasActive = prefs.getBoolean("session_active", false)
        val lastSessionTimestamp = prefs.getLong("session_start_timestamp", 0L)

        val recoveryType = when {
            manualForceQuit -> "manual_force_quit"
            wasCrash -> "crash"
            wasLowMemory -> "low_memory_kill"  // Android killed due to memory pressure
            sessionWasActive -> "system_force_kill" // Swipe up to kill or other system kill
            else -> "clean_start"
        }

        // Set session active marker for this new session
        prefs.edit()
            .putBoolean("session_active", true)
            .putLong("session_start_timestamp", System.currentTimeMillis())
            .apply()

        Log.i(TAG, "Session started - recovery_type: $recoveryType")

        // Log app start with recovery info
        val startTime = System.currentTimeMillis()
        val timeSinceLastSession = if (lastSessionTimestamp > 0) {
            startTime - lastSessionTimestamp
        } else 0L

        logger.logRecordBuilder()
            .setBody("app.start")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("screen"), "MainActivity",
                    AttributeKey.stringKey("device_id"), loggerProvider.getDeviceId(),
                    AttributeKey.stringKey("recovery_type"), recoveryType,
                    AttributeKey.longKey("time_since_last_session_ms"), timeSinceLastSession
                )
            )
            .emit()

        Log.i(TAG, "Logged app.start event (recovery_type: $recoveryType)")

        // If recovering from abnormal termination, log recovery event and flush
        if (manualForceQuit || wasCrash || wasLowMemory || sessionWasActive) {
            val terminationType = when {
                manualForceQuit -> "Manual force quit button"
                wasCrash -> "Uncaught exception crash"
                wasLowMemory -> "Low memory / OOM kill by Android"
                sessionWasActive -> "System force kill (swipe up or other)"
                else -> "Unknown"
            }

            logger.logRecordBuilder()
                .setBody("app.recovery")
                .setSeverity(Severity.WARN)
                .setAllAttributes(
                    Attributes.of(
                        AttributeKey.stringKey("demo_run_id"), demoRunId,
                        AttributeKey.stringKey("recovery_type"), recoveryType,
                        AttributeKey.stringKey("termination_type"), terminationType,
                        AttributeKey.stringKey("previous_session"), "terminated_abnormally",
                        AttributeKey.stringKey("device_id"), loggerProvider.getDeviceId(),
                        AttributeKey.longKey("downtime_ms"), timeSinceLastSession
                    )
                )
                .emit()

            Log.w(TAG, "App recovered from $recoveryType - flushing buffered telemetry from disk")

            // Flush any buffered telemetry from previous session
            // This is CRITICAL - it sends all the telemetry that was written to disk
            // before the app was killed
            Thread {
                val result = loggerProvider.forceFlush(30)
                if (result.isSuccess) {
                    Log.i(TAG, "✅ Successfully flushed recovery telemetry from disk")
                    runOnUiThread {
                        updateStatus("✅ Recovered from $recoveryType\n📤 Sent buffered telemetry from previous session")
                    }
                } else {
                    Log.e(TAG, "❌ Failed to flush recovery telemetry")
                    runOnUiThread {
                        updateStatus("⚠️ Recovered from $recoveryType\n❌ Failed to send some telemetry")
                    }
                }
            }.start()

            // Clear recovery markers
            prefs.edit()
                .remove("force_quit_marker")
                .remove("crash_marker")
                .remove("low_memory_marker")
                .apply()
        }
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

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "scenario_a",
            AttributeKey.stringKey("button.category"), "demo_scenarios"
        ))

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

        // Trigger immediate flush (simulates workflow trigger action)
        Log.i(TAG, "Triggering forceFlush() due to ui.freeze trigger")
        loggerProvider.forceFlush(30)

        updateStatus("✅ Scenario A complete\nUI freeze logged (2500ms)\n📤 Data flushed immediately!")
        Log.i(TAG, "Scenario A complete: ui.freeze event logged and flushed")
    }

    /**
     * Scenario B: Real Crash
     *
     * Throws an uncaught exception causing a genuine crash:
     * - Crash telemetry captured in disk buffer
     * - Flushed on next app start with crash recovery event
     *
     * WARNING: This will actually crash the app IMMEDIATELY!
     */
    private fun runScenarioB() {
        updateStatus("⚠️ Running Scenario B: Real Crash\n\nCRASHING NOW!")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "scenario_b",
            AttributeKey.stringKey("button.category"), "demo_scenarios"
        ))

        // Set crash marker for recovery detection on next start
        val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("crash_marker", true)
            .putLong("crash_timestamp", System.currentTimeMillis())
            .apply()

        Log.w(TAG, "Set crash_marker - crashing app NOW")

        // Log crash event
        logger.logRecordBuilder()
            .setBody("app.crash")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("crash_type"), "uncaught_exception",
                    AttributeKey.stringKey("exception_message"), "Demo crash: Immediate uncaught exception",
                    AttributeKey.stringKey("screen"), "MainActivity"
                )
            )
            .emit()

        Log.e(TAG, "CRASHING APP NOW!")

        // Throw exception on MAIN THREAD immediately to crash the app
        throw RuntimeException("Demo crash: Immediate uncaught exception - APP CRASHING NOW!")
    }

    /**
     * Scenario C: Real Network Error Escalation
     *
     * Makes real HTTP calls that result in actual server errors:
     * - Makes 3 successful GET requests
     * - Makes 1 failing request that returns HTTP 500
     * - Event: http.error with status >= 500 triggers export policy
     * - Action: Flush last 2 minutes + increase sampling to 100% for 10 minutes
     *
     * WARNING: This makes actual network calls to httpstat.us!
     */
    private fun runScenarioC() {
        updateStatus("🔵 Running Scenario C: Real Network Errors\n\nMaking real HTTP calls...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "scenario_c",
            AttributeKey.stringKey("button.category"), "demo_scenarios"
        ))

        Thread {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            // Make 3 successful HTTP calls
            for (i in 1..3) {
                try {
                    val startTime = System.currentTimeMillis()
                    val request = okhttp3.Request.Builder()
                        .url("https://httpstat.us/200?sleep=100")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val duration = System.currentTimeMillis() - startTime

                    logger.logRecordBuilder()
                        .setBody("http.request")
                        .setSeverity(Severity.INFO)
                        .setAllAttributes(
                            Attributes.builder()
                                .put("demo_run_id", demoRunId)
                                .put("http.method", "GET")
                                .put("http.url", "https://httpstat.us/200")
                                .put("http.route", "/appointments")
                                .put("http.status_code", response.code.toLong())
                                .put("http.duration_ms", duration)
                                .build()
                        )
                        .emit()

                    response.close()
                    Log.i(TAG, "Scenario C: Successful request #$i - ${response.code} in ${duration}ms")

                    Thread.sleep(200)
                } catch (e: Exception) {
                    Log.e(TAG, "Scenario C: Network error during successful call #$i", e)

                    // Log network failure as telemetry
                    logger.logRecordBuilder()
                        .setBody("http.error")
                        .setSeverity(Severity.WARN)
                        .setAllAttributes(
                            Attributes.builder()
                                .put("demo_run_id", demoRunId)
                                .put("http.method", "GET")
                                .put("http.url", "https://httpstat.us/200")
                                .put("http.route", "/appointments")
                                .put("request_number", i.toLong())
                                .put("error.type", "network_failure")
                                .put("error.message", e.message ?: "Network call failed")
                                .put("exception.type", e.javaClass.simpleName)
                                .build()
                        )
                        .emit()
                }
            }

            // Now make a failing HTTP call (real 500 error)
            try {
                val startTime = System.currentTimeMillis()
                val request = okhttp3.Request.Builder()
                    .url("https://httpstat.us/500")
                    .post(okhttp3.RequestBody.create(null, ""))
                    .build()

                val response = client.newCall(request).execute()
                val duration = System.currentTimeMillis() - startTime

                // Log the REAL HTTP 500 error
                logger.logRecordBuilder()
                    .setBody("http.error")
                    .setSeverity(Severity.ERROR)
                    .setAllAttributes(
                        Attributes.builder()
                            .put("demo_run_id", demoRunId)
                            .put("http.method", "POST")
                            .put("http.url", "https://httpstat.us/500")
                            .put("http.route", "/appointments")
                            .put("http.status_code", response.code.toLong())
                            .put("error.message", response.message)
                            .put("http.duration_ms", duration)
                            .build()
                    )
                    .emit()

                response.close()
                Log.e(TAG, "Scenario C: HTTP 500 error received - ${response.code} in ${duration}ms")

                // Trigger immediate flush (simulates workflow trigger action)
                Log.i(TAG, "Triggering forceFlush() due to real http.error")
                loggerProvider.forceFlush(30)

                runOnUiThread {
                    updateStatus("✅ Scenario C complete\nReal HTTP 500 error received\n📤 Data flushed immediately!")
                }
                Log.i(TAG, "Scenario C complete: Real HTTP 500 error captured and flushed")

            } catch (e: Exception) {
                Log.e(TAG, "Scenario C: Network exception during 500 call", e)

                // Log the network failure as telemetry
                logger.logRecordBuilder()
                    .setBody("http.error")
                    .setSeverity(Severity.ERROR)
                    .setAllAttributes(
                        Attributes.builder()
                            .put("demo_run_id", demoRunId)
                            .put("http.method", "POST")
                            .put("http.url", "https://httpstat.us/500")
                            .put("http.route", "/appointments")
                            .put("error.type", "network_failure")
                            .put("error.message", e.message ?: "Network call failed")
                            .put("exception.type", e.javaClass.simpleName)
                            .build()
                    )
                    .emit()

                // Trigger immediate flush (simulates workflow trigger action)
                Log.i(TAG, "Triggering forceFlush() due to network failure")
                loggerProvider.forceFlush(30)

                runOnUiThread {
                    updateStatus("✅ Scenario C complete\nNetwork failure captured\n📤 Data flushed immediately!")
                }
                Log.i(TAG, "Scenario C complete: Network failure captured and flushed")
            }
        }.start()
    }

    /**
     * Scenario D: Low Memory / Out of Memory Kill
     *
     * Rapidly allocates memory to trigger Android's low memory killer:
     * - Memory exhaustion triggers system kill
     * - Telemetry captured in disk buffer
     * - Flushed on next app start with recovery event
     *
     * WARNING: This will cause Android to kill the app due to memory pressure!
     */
    private fun runScenarioD() {
        updateStatus("⚠️ Running Scenario D: Low Memory Kill\n\nAllocating memory rapidly...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "scenario_d",
            AttributeKey.stringKey("button.category"), "demo_scenarios"
        ))

        // Set low memory marker for recovery detection on next start
        val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("low_memory_marker", true)
            .putLong("low_memory_timestamp", System.currentTimeMillis())
            .apply()

        Log.w(TAG, "Set low_memory_marker - starting memory allocation")

        // Log pre-OOM event
        logger.logRecordBuilder()
            .setBody("app.low_memory")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("event_type"), "memory_exhaustion",
                    AttributeKey.stringKey("screen"), "MainActivity"
                )
            )
            .emit()

        // Force flush to disk before OOM
        loggerProvider.forceFlush(2)

        Log.e(TAG, "Starting aggressive memory allocation - Android will kill the app")

        // Allocate memory aggressively in a background thread
        Thread {
            try {
                val memoryHog = mutableListOf<ByteArray>()

                // Allocate 100MB chunks until we run out of memory
                while (true) {
                    // Allocate 100MB at a time
                    val chunk = ByteArray(100 * 1024 * 1024)
                    memoryHog.add(chunk)

                    Log.w(TAG, "Allocated ${memoryHog.size * 100}MB - continuing...")

                    // Fill the array to ensure it's actually allocated
                    for (i in 0 until minOf(1000, chunk.size)) {
                        chunk[i] = 0xFF.toByte()
                    }

                    Thread.sleep(100) // Small delay between allocations
                }
            } catch (e: OutOfMemoryError) {
                // This should trigger before Android kills us
                Log.e(TAG, "OutOfMemoryError caught - app will be killed by Android", e)

                // Try to log OOM event if possible
                try {
                    logger.logRecordBuilder()
                        .setBody("app.out_of_memory")
                        .setSeverity(Severity.FATAL)
                        .setAllAttributes(
                            Attributes.of(
                                AttributeKey.stringKey("demo_run_id"), demoRunId,
                                AttributeKey.stringKey("error_type"), "OutOfMemoryError",
                                AttributeKey.stringKey("screen"), "MainActivity"
                            )
                        )
                        .emit()
                } catch (ignored: Exception) {
                    // May not have memory to log
                }

                // System will kill the app shortly
            }
        }.start()

        Log.i(TAG, "Scenario D: Memory allocation started - Android will kill app when memory is exhausted")
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
     * Force quits the application after flushing telemetry.
     *
     * WARNING: This will forcefully terminate the app!
     * - Sets marker for recovery detection on next start
     * - Logs pre-quit event
     * - Attempts to flush all telemetry to disk
     * - Kills the process
     * - On next app start, recovery logic will send buffered telemetry
     */
    private fun forceQuitApp() {
        updateStatus("⚠️ Force Quit Initiated\n\nFlushing telemetry and exiting in 3 seconds...")
        Log.w(TAG, "Force quit requested - flushing telemetry before exit")

        // Set marker in SharedPreferences for recovery detection
        val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("force_quit_marker", true)
            .putLong("force_quit_timestamp", System.currentTimeMillis())
            .apply()

        Log.i(TAG, "Set force_quit_marker for recovery on next startup")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "force_quit",
            AttributeKey.stringKey("button.category"), "manual_controls"
        ))

        Thread {
            // Log pre-quit event
            logger.logRecordBuilder()
                .setBody("app.force_quit")
                .setSeverity(Severity.WARN)
                .setAllAttributes(
                    Attributes.of(
                        AttributeKey.stringKey("demo_run_id"), demoRunId,
                        AttributeKey.stringKey("quit_type"), "manual_force_quit",
                        AttributeKey.stringKey("screen"), "MainActivity",
                        AttributeKey.stringKey("device_id"), loggerProvider.getDeviceId()
                    )
                )
                .emit()

            Log.w(TAG, "Logged app.force_quit event - attempting flush to disk...")

            // Force flush to persist to disk
            // This ensures data is written to disk buffer before process dies
            val flushResult = loggerProvider.forceFlush(15)
            if (flushResult.isSuccess) {
                Log.i(TAG, "Telemetry flushed to disk successfully before force quit")
            } else {
                Log.w(TAG, "Flush before force quit may be incomplete")
            }

            // Give user time to read the message
            Thread.sleep(3000)

            // Log final goodbye
            Log.w(TAG, "Killing app process now - data will be sent on next app start")

            // Force quit the app
            // This is equivalent to a force close - the app is terminated immediately
            // The telemetry has been flushed to disk and will be sent on next app start
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()

        Log.i(TAG, "Force quit will occur in 3 seconds - recovery marker set")
    }

    // ========== Regular App Activities ==========

    /**
     * Simulates a user login flow with authentication logs and traces.
     */
    private fun logUserLogin() {
        updateStatus("🔵 Logging user login flow...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "user_login",
            AttributeKey.stringKey("button.category"), "regular_activities"
        ))

        val startTime = System.currentTimeMillis()

        // Create a span for the entire login operation
        val span = tracer.spanBuilder("auth.login")
            .setAttribute("demo_run_id", demoRunId)
            .setAttribute("auth.method", "email_password")
            .setAttribute("user.email", "demo@example.com")
            .startSpan()

        try {
            span.makeCurrent().use { scope ->
                // Login attempt (child span)
                val attemptSpan = tracer.spanBuilder("auth.validate_credentials")
                    .startSpan()

                try {
                    attemptSpan.makeCurrent().use {
                        logger.logRecordBuilder()
                            .setBody("auth.login.attempt")
                            .setSeverity(Severity.INFO)
                            .setAllAttributes(
                                Attributes.of(
                                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                                    AttributeKey.stringKey("auth.method"), "email_password",
                                    AttributeKey.stringKey("user.email"), "demo@example.com",
                                    AttributeKey.stringKey("screen"), "LoginActivity"
                                )
                            )
                            .emit()

                        // Simulate authentication delay
                        Thread.sleep(300)

                        attemptSpan.setStatus(StatusCode.OK)
                    }
                } finally {
                    attemptSpan.end()
                }

                // Login success
                val sessionId = UUID.randomUUID().toString()
                span.setAttribute("user.id", "user_12345")
                span.setAttribute("session.id", sessionId)

                logger.logRecordBuilder()
                    .setBody("auth.login.success")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        Attributes.of(
                            AttributeKey.stringKey("demo_run_id"), demoRunId,
                            AttributeKey.stringKey("user.id"), "user_12345",
                            AttributeKey.stringKey("user.email"), "demo@example.com",
                            AttributeKey.stringKey("session.id"), sessionId,
                            AttributeKey.longKey("auth.duration_ms"), 300L
                        )
                    )
                    .emit()

                span.setStatus(StatusCode.OK)
            }
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, "Login failed: ${e.message}")
            span.recordException(e)
            throw e
        } finally {
            span.end()

            // Record operation duration metric
            val duration = (System.currentTimeMillis() - startTime).toDouble()
            operationDurationHistogram.record(duration, Attributes.of(
                AttributeKey.stringKey("operation.name"), "auth.login",
                AttributeKey.stringKey("operation.status"), "success"
            ))
        }

        updateStatus("✅ User login logged\nEvent: auth.login.success\nUser ID: user_12345\nTrace + Metrics created!")
        Log.i(TAG, "User login flow logged with trace and metrics")
    }

    /**
     * Simulates page navigation with screen transition logs.
     */
    private fun logPageNavigation() {
        updateStatus("🔵 Logging page navigation...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "page_navigation",
            AttributeKey.stringKey("button.category"), "regular_activities"
        ))

        val screens = listOf("HomeActivity", "ProfileActivity", "SettingsActivity")
        val currentScreen = screens.random()
        val nextScreen = (screens - currentScreen).random()

        // Screen exit
        logger.logRecordBuilder()
            .setBody("screen.exit")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("screen.name"), currentScreen,
                    AttributeKey.longKey("screen.duration_ms"), (2000..5000).random().toLong()
                )
            )
            .emit()

        Thread.sleep(100)

        // Screen enter
        logger.logRecordBuilder()
            .setBody("screen.enter")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("screen.name"), nextScreen,
                    AttributeKey.stringKey("screen.source"), currentScreen,
                    AttributeKey.stringKey("navigation.method"), "button_click"
                )
            )
            .emit()

        updateStatus("✅ Page navigation logged\n$currentScreen → $nextScreen")
        Log.i(TAG, "Page navigation logged: $currentScreen -> $nextScreen")
    }

    /**
     * Simulates an API call with request/response logs and HTTP span.
     */
    private fun logApiCall() {
        updateStatus("🔵 Logging API call...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "api_call",
            AttributeKey.stringKey("button.category"), "regular_activities"
        ))

        val endpoints = listOf("/api/users", "/api/posts", "/api/comments", "/api/settings")
        val endpoint = endpoints.random()
        val duration = (50..300).random()
        val responseSize = (1024..10240).random()

        // Create HTTP span (follows semantic conventions)
        val span = tracer.spanBuilder("HTTP GET")
            .setAttribute("http.method", "GET")
            .setAttribute("http.url", "https://api.example.com$endpoint")
            .setAttribute("http.route", endpoint)
            .setAttribute("http.scheme", "https")
            .setAttribute("http.target", endpoint)
            .setAttribute("net.peer.name", "api.example.com")
            .setAttribute("demo_run_id", demoRunId)
            .startSpan()

        try {
            span.makeCurrent().use {
                // Log API request
                logger.logRecordBuilder()
                    .setBody("http.request")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        Attributes.of(
                            AttributeKey.stringKey("demo_run_id"), demoRunId,
                            AttributeKey.stringKey("http.method"), "GET",
                            AttributeKey.stringKey("http.url"), "https://api.example.com$endpoint",
                            AttributeKey.stringKey("http.route"), endpoint,
                            AttributeKey.stringKey("request.id"), UUID.randomUUID().toString()
                        )
                    )
                    .emit()

                span.addEvent("request_sent")

                // Simulate network delay
                Thread.sleep(duration.toLong())

                span.addEvent("response_received")

                // Set response attributes
                span.setAttribute("http.status_code", 200L)
                span.setAttribute("http.response_content_length", responseSize.toLong())

                // Log API response
                logger.logRecordBuilder()
                    .setBody("http.response")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        Attributes.of(
                            AttributeKey.stringKey("demo_run_id"), demoRunId,
                            AttributeKey.stringKey("http.method"), "GET",
                            AttributeKey.stringKey("http.route"), endpoint,
                            AttributeKey.longKey("http.status_code"), 200L,
                            AttributeKey.longKey("http.duration_ms"), duration.toLong(),
                            AttributeKey.longKey("http.response_size_bytes"), responseSize.toLong()
                        )
                    )
                    .emit()

                span.setStatus(StatusCode.OK)

                // Record HTTP duration metric
                operationDurationHistogram.record(duration.toDouble(), Attributes.of(
                    AttributeKey.stringKey("operation.name"), "http.request",
                    AttributeKey.stringKey("http.method"), "GET",
                    AttributeKey.stringKey("http.route"), endpoint,
                    AttributeKey.longKey("http.status_code"), 200L
                ))
            }
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, "HTTP request failed")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }

        updateStatus("✅ API call logged\nGET $endpoint\nStatus: 200 (${duration}ms)\nTrace + Metrics created!")
        Log.i(TAG, "API call logged: GET $endpoint -> 200 OK (${duration}ms)")
    }

    /**
     * Simulates a background task with trace span.
     */
    private fun logBackgroundTask() {
        updateStatus("🔵 Logging background task...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "background_task",
            AttributeKey.stringKey("button.category"), "regular_activities"
        ))

        val tasks = listOf(
            "data_sync" to "Syncing user data",
            "image_upload" to "Uploading profile photo",
            "cache_cleanup" to "Cleaning cache",
            "analytics_batch" to "Sending analytics batch"
        )
        val (taskType, taskDescription) = tasks.random()
        val duration = (500..3000).random()
        val taskId = UUID.randomUUID().toString()

        // Create span for background task
        val span = tracer.spanBuilder("background.task")
            .setAttribute("task.type", taskType)
            .setAttribute("task.description", taskDescription)
            .setAttribute("task.id", taskId)
            .setAttribute("demo_run_id", demoRunId)
            .startSpan()

        try {
            span.makeCurrent().use {
                // Task started
                logger.logRecordBuilder()
                    .setBody("background.task.started")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        Attributes.of(
                            AttributeKey.stringKey("demo_run_id"), demoRunId,
                            AttributeKey.stringKey("task.type"), taskType,
                            AttributeKey.stringKey("task.description"), taskDescription,
                            AttributeKey.stringKey("task.id"), taskId
                        )
                    )
                    .emit()

                span.addEvent("task_processing_started")

                // Simulate task execution
                Thread.sleep(duration.toLong())

                span.addEvent("task_processing_completed")
                span.setAttribute("task.status", "success")
                span.setAttribute("task.duration_ms", duration.toLong())

                // Task completed
                logger.logRecordBuilder()
                    .setBody("background.task.completed")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        Attributes.of(
                            AttributeKey.stringKey("demo_run_id"), demoRunId,
                            AttributeKey.stringKey("task.type"), taskType,
                            AttributeKey.stringKey("task.description"), taskDescription,
                            AttributeKey.longKey("task.duration_ms"), duration.toLong(),
                            AttributeKey.stringKey("task.status"), "success"
                        )
                    )
                    .emit()

                span.setStatus(StatusCode.OK)

                // Record task duration metric
                operationDurationHistogram.record(duration.toDouble(), Attributes.of(
                    AttributeKey.stringKey("operation.name"), "background.task",
                    AttributeKey.stringKey("task.type"), taskType,
                    AttributeKey.stringKey("task.status"), "success"
                ))
            }
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, "Task failed")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }

        updateStatus("✅ Background task logged\n$taskDescription\nDuration: ${duration}ms\nTrace + Metrics created!")
        Log.i(TAG, "Background task logged: $taskType completed in ${duration}ms")
    }

    /**
     * Simulates user interaction events (button clicks, gestures).
     */
    private fun logUserInteraction() {
        updateStatus("🔵 Logging user interaction...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "user_interaction",
            AttributeKey.stringKey("button.category"), "regular_activities"
        ))

        val interactions = listOf(
            "button_click" to "like_button",
            "button_click" to "share_button",
            "swipe" to "post_card",
            "long_press" to "user_avatar",
            "double_tap" to "image_view"
        )
        val (interactionType, elementId) = interactions.random()

        logger.logRecordBuilder()
            .setBody("user.interaction")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("interaction.type"), interactionType,
                    AttributeKey.stringKey("interaction.element_id"), elementId,
                    AttributeKey.stringKey("screen"), "FeedActivity",
                    AttributeKey.longKey("interaction.timestamp_ms"), System.currentTimeMillis()
                )
            )
            .emit()

        updateStatus("✅ User interaction logged\nType: $interactionType\nElement: $elementId")
        Log.i(TAG, "User interaction logged: $interactionType on $elementId")
    }

    /**
     * Simulates form submission with validation.
     */
    private fun logFormSubmission() {
        updateStatus("🔵 Logging form submission...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "form_submission",
            AttributeKey.stringKey("button.category"), "regular_activities"
        ))

        val formTypes = listOf(
            "contact_form" to mapOf("name" to "John Doe", "email" to "john@example.com", "message" to "Hello!"),
            "feedback_form" to mapOf("rating" to "5", "comment" to "Great app!"),
            "profile_update" to mapOf("display_name" to "JohnDoe", "bio" to "Software developer")
        )
        val (formType, fields) = formTypes.random()

        // Form validation
        logger.logRecordBuilder()
            .setBody("form.validation")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("form.type"), formType,
                    AttributeKey.longKey("form.field_count"), fields.size.toLong(),
                    AttributeKey.stringKey("validation.status"), "passed"
                )
            )
            .emit()

        Thread.sleep(100)

        // Form submission
        logger.logRecordBuilder()
            .setBody("form.submitted")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.of(
                    AttributeKey.stringKey("demo_run_id"), demoRunId,
                    AttributeKey.stringKey("form.type"), formType,
                    AttributeKey.stringKey("form.id"), UUID.randomUUID().toString(),
                    AttributeKey.longKey("form.field_count"), fields.size.toLong(),
                    AttributeKey.stringKey("submission.status"), "success",
                    AttributeKey.longKey("submission.duration_ms"), 150L
                )
            )
            .emit()

        updateStatus("✅ Form submission logged\nForm: $formType\nFields: ${fields.size}\nStatus: success")
        Log.i(TAG, "Form submission logged: $formType with ${fields.size} fields")
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
            R.id.action_config -> {
                startActivity(Intent(this, ConfigActivity::class.java))
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

        // Clear session active marker on clean shutdown
        // This allows detection of system force kills (swipe up) on next app start
        val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("session_active", false)
            .apply()

        Log.i(TAG, "Cleared session_active marker - clean shutdown")

        // Shutdown OpenTelemetry on app close
        Log.i(TAG, "Shutting down OpenTelemetry")
        loggerProvider.shutdown(30)
    }
}
