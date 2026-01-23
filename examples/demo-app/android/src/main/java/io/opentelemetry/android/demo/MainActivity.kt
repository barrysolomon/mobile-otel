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
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.DoubleHistogram
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
 * 1. UI Freeze - Blocks main thread for 2-11s (random) to trigger freeze workflow
 * 2. Crash Simulation - Demonstrates crash recovery
 * 3. Network Error - Triggers error-based workflow
 * 4. Low Memory Kill - Forces OOM kill by Android
 * 5. True ANR - Blocks main thread for 30s causing OS ANR dialog
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
    private lateinit var btnScenarioE: Button
    private lateinit var btnUserLogin: Button
    private lateinit var btnPageNav: Button
    private lateinit var btnApiCall: Button
    private lateinit var btnBackgroundTask: Button
    private lateinit var btnUserInteraction: Button
    private lateinit var btnFormSubmit: Button
    private lateinit var btnForceFlush: Button
    private lateinit var btnForceQuit: Button

    /**
     * Helper function to add standard thread context to log attributes.
     * Follows OpenTelemetry semantic convention for thread identification.
     */
    @Suppress("DEPRECATION")
    private fun addThreadContext(builder: AttributesBuilder): AttributesBuilder {
        val thread = Thread.currentThread()
        return builder
            .put("thread.name", thread.name)
            .put("thread.id", thread.id)
    }

    /**
     * Helper function to add code location context to log attributes.
     * Follows OpenTelemetry semantic convention for code traceability.
     * Extracts caller information from stack trace.
     */
    private fun addCodeLocation(builder: AttributesBuilder, functionName: String): AttributesBuilder {
        return builder
            .put("code.namespace", "io.opentelemetry.android.demo")
            .put("code.function", functionName)
            .put("code.filepath", "MainActivity.kt")
    }

    /**
     * Helper function to create base attributes with demo context, thread, and code location.
     * Reduces boilerplate and ensures consistency across all log events.
     */
    private fun createBaseAttributes(functionName: String): AttributesBuilder {
        return Attributes.builder()
            .put("demo_run_id", demoRunId)
            .also { addThreadContext(it) }
            .also { addCodeLocation(it, functionName) }
    }

    /**
     * Transaction outcome configuration for testing
     * Default: 70% pass, 20% fail, 10% crash
     */
    data class TransactionOutcomeConfig(
        val passRate: Int = 70,    // Percentage that complete successfully
        val failRate: Int = 20,    // Percentage that fail gracefully
        val crashRate: Int = 10    // Percentage that crash before completion
    ) {
        init {
            require(passRate + failRate + crashRate == 100) {
                "Transaction outcome rates must sum to 100% (got: ${passRate + failRate + crashRate})"
            }
        }
    }

    private val transactionOutcomeConfig = TransactionOutcomeConfig()

    enum class TransactionOutcome {
        PASS, FAIL, CRASH
    }

    /**
     * Determines transaction outcome based on configured rates.
     * Returns PASS, FAIL, or CRASH.
     */
    private fun determineTransactionOutcome(): TransactionOutcome {
        val random = (1..100).random()
        return when {
            random <= transactionOutcomeConfig.passRate -> TransactionOutcome.PASS
            random <= transactionOutcomeConfig.passRate + transactionOutcomeConfig.failRate -> TransactionOutcome.FAIL
            else -> TransactionOutcome.CRASH
        }
    }

    /**
     * Starts a tracked transaction that will be monitored for completion.
     * If app crashes before endTrackedTransaction() is called, it will be detected on restart.
     */
    private fun startTrackedTransaction(
        transactionId: String,
        transactionType: String,
        spanBuilder: io.opentelemetry.api.trace.SpanBuilder
    ): Span {
        val span = spanBuilder
            .setAttribute("transaction.id", transactionId)
            .setAttribute("transaction.type", transactionType)
            .startSpan()

        // Track active transaction in SharedPreferences for crash detection
        val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("transaction_active", true)
            putString("transaction_id", transactionId)
            putString("transaction_type", transactionType)
            putLong("transaction_start_time", System.currentTimeMillis())
            apply()
        }

        Log.d(TAG, "Started tracked transaction: type=$transactionType, id=$transactionId")
        return span
    }

    /**
     * Ends a tracked transaction and clears the tracking markers.
     * Should be called when transaction completes (successfully or with error).
     */
    private fun endTrackedTransaction(
        span: Span,
        statusCode: StatusCode = StatusCode.OK,
        statusMessage: String? = null
    ) {
        if (statusMessage != null) {
            span.setStatus(statusCode, statusMessage)
        } else {
            span.setStatus(statusCode)
        }
        span.end()

        // Clear transaction tracking
        val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            remove("transaction_active")
            remove("transaction_id")
            remove("transaction_type")
            remove("transaction_start_time")
            apply()
        }

        Log.d(TAG, "Ended tracked transaction with status: $statusCode")
    }

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
        btnScenarioE = findViewById(R.id.btnScenarioE)
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

        // Handle demographics from intent extras (for monkey test support)
        handleDemographics(intent)

        // Set up button listeners
        setupButtons()

        // Log app start
        checkIncompleteTransaction()
    }

    /**
     * Handles demographic attributes from intent extras.
     * This allows the monkey test script to set demographics via adb shell.
     *
     * Usage: adb shell am start -n io.opentelemetry.android.demo/.MainActivity \
     *        --es device_type "smartphone" --es region "us" --es age_group "25-34" --es tier "premium"
     */
    private fun handleDemographics(intent: Intent?) {
        intent?.extras?.let { extras ->
            val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)
            val editor = prefs.edit()
            var updated = false

            extras.getString("device_type")?.let {
                editor.putString("user_device_type", it)
                updated = true
                Log.i(TAG, "Updated demographics: device_type=$it")
            }

            extras.getString("region")?.let {
                editor.putString("user_region", it)
                updated = true
                Log.i(TAG, "Updated demographics: region=$it")
            }

            extras.getString("age_group")?.let {
                editor.putString("user_age_group", it)
                updated = true
                Log.i(TAG, "Updated demographics: age_group=$it")
            }

            extras.getString("tier")?.let {
                editor.putString("user_tier", it)
                updated = true
                Log.i(TAG, "Updated demographics: tier=$it")
            }

            if (updated) {
                editor.apply()
                Log.i(TAG, "Demographics updated from intent extras")
            }
        }
    }

    /**
     * Handles new intents when activity is already running.
     * This allows demographics to be updated without restarting the activity.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDemographics(intent)
    }

    /**
     * Initializes the OpenTelemetry MobileLoggerProvider.
     * Configuration is loaded from SharedPreferences (managed by ConfigManager).
     */
    private fun initializeOTEL() {
        demoRunId = UUID.randomUUID().toString()

        val config = ConfigManager.loadConfig(this)

        loggerProvider = OTelMobile.getLoggerProvider()
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

        // Hook into export results to display status
        io.opentelemetry.android.mobile.export.LoggingHttpExporter.onExportResult = { success, message ->
            runOnUiThread {
                val currentStatus = statusText.text.toString()
                val exportStatus = "\n\n📡 Last Export:\n$message"
                updateStatus(currentStatus + exportStatus)
            }
        }
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

        btnScenarioE.setOnClickListener {
            runScenarioE()
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

    private fun checkIncompleteTransaction() {
        val prefs = getSharedPreferences("demo_app_prefs", MODE_PRIVATE)
        val transactionWasActive = prefs.getBoolean("transaction_active", false)
        val transactionId = prefs.getString("transaction_id", null)
        val transactionType = prefs.getString("transaction_type", null)
        val transactionStartTime = prefs.getLong("transaction_start_time", 0L)

        if (transactionWasActive && transactionId != null && transactionType != null) {
            val duration = System.currentTimeMillis() - transactionStartTime
            val recoveryType = OTelMobile.getLastRecoveryType() ?: "clean_start"

            logger.logRecordBuilder()
                .setBody("transaction.incomplete")
                .setSeverity(Severity.WARN)
                .setAllAttributes(
                    createBaseAttributes("checkIncompleteTransaction")
                        .put("transaction.id", transactionId)
                        .put("transaction.type", transactionType)
                        .put("transaction.status", "incomplete_due_to_crash")
                        .put("transaction.duration_before_crash_ms", duration)
                        .put("recovery_type", recoveryType)
                        .put("device_id", loggerProvider.getDeviceId())
                        .build()
                )
                .emit()

            val syntheticSpan = tracer.spanBuilder(transactionType)
                .setAttribute("transaction.id", transactionId)
                .setAttribute("transaction.synthetic", true)
                .setAttribute("transaction.incomplete", true)
                .setAttribute("recovery_type", recoveryType)
                .setAttribute("duration_before_crash_ms", duration)
                .setStartTimestamp(transactionStartTime, java.util.concurrent.TimeUnit.MILLISECONDS)
                .startSpan()

            syntheticSpan.addEvent("transaction_interrupted_by_crash")
            syntheticSpan.setStatus(StatusCode.ERROR, "Transaction interrupted by app crash")
            syntheticSpan.end(System.currentTimeMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)

            prefs.edit().apply {
                remove("transaction_active")
                remove("transaction_id")
                remove("transaction_type")
                remove("transaction_start_time")
                apply()
            }
        }
    }

    /**
     * Scenario A: UI Freeze (2-11 seconds)
     *
     * Blocks the main thread for a random duration between 2-11 seconds:
     * - Triggers the ui.freeze workflow when duration exceeds 2 seconds
     * - Event: ui.freeze logged after blocking completes
     * - Action: Workflow flushes last 2 minutes of telemetry
     *
     * Random duration ensures varied testing of the freeze detection threshold.
     */
    private fun runScenarioA() {
        // Generate random freeze duration between 2-11 seconds
        val freezeDurationSeconds = (2..11).random()
        val freezeDurationMs = freezeDurationSeconds * 1000L

        updateStatus("⚠️ Running Scenario A: UI Freeze\n\nBLOCKING MAIN THREAD FOR ${freezeDurationSeconds}s!")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "scenario_a",
            AttributeKey.stringKey("button.category"), "demo_scenarios"
        ))

        Log.w(TAG, "Triggering UI freeze for ${freezeDurationSeconds} seconds")

        // Block the main thread
        val startTime = System.currentTimeMillis()

        // Busy-wait loop to block main thread
        while (System.currentTimeMillis() - startTime < freezeDurationMs) {
            // Intensive computation to ensure main thread is truly blocked
            var dummy = 0.0
            for (i in 0..1000) {
                dummy += Math.sqrt(i.toDouble())
            }
        }

        val actualDuration = System.currentTimeMillis() - startTime

        Log.i(TAG, "UI freeze completed - main thread was blocked for ${actualDuration}ms")

        // Log UI freeze event using OpenTelemetry semantic conventions
        logger.logRecordBuilder()
            .setBody("ui.freeze")
            .setSeverity(Severity.WARN)
            .setAllAttributes(
                createBaseAttributes("runScenarioA")
                    // UI freeze attributes
                    .put("duration_ms", actualDuration)
                    .put("freeze.type", "main_thread_blocked")
                    .put("freeze.intentional", true)
                    // Mobile context
                    .put("screen.name", "MainActivity")
                    .build()
            )
            .emit()

        // Note: The ui-freeze-detector workflow will trigger a flush if duration > 2000ms
        updateStatus("✅ Scenario A complete\nUI freeze: ${actualDuration}ms\n📤 Workflow triggered!")
        Log.i(TAG, "Scenario A complete: UI freeze event logged (${actualDuration}ms)")
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

        OTelMobile.markCrashForNextStart()
        Log.w(TAG, "Marked crash for recovery - crashing app now")

        // Log crash event using OpenTelemetry semantic conventions
        logger.logRecordBuilder()
            .setBody("app.crash")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(
                createBaseAttributes("runScenarioB")
                    // Standard error semantic conventions
                    .put("error.type", "java.lang.RuntimeException")
                    .put("error.message", "Demo crash: Immediate uncaught exception")
                    .put("exception.type", "RuntimeException")
                    // Mobile context
                    .put("screen.name", "MainActivity")
                    .build()
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
                            createBaseAttributes("runScenarioC")
                                // Standard HTTP semantic conventions
                                .put("http.method", "GET")
                                .put("http.url", "https://httpstat.us/200")
                                .put("http.route", "/appointments")
                                .put("http.scheme", "https")
                                .put("net.peer.name", "httpstat.us")
                                .put("http.status_code", response.code.toLong())
                                .put("http.duration_ms", duration)
                                // Mobile context
                                .put("screen.name", "MainActivity")
                                .build()
                        )
                        .emit()

                    response.close()
                    Log.i(TAG, "Scenario C: Successful request #$i - ${response.code} in ${duration}ms")

                    Thread.sleep(200)
                } catch (e: Exception) {
                    Log.e(TAG, "Scenario C: Network error during successful call #$i", e)

                    // Log network failure as telemetry using semantic conventions
                    logger.logRecordBuilder()
                        .setBody("http.error")
                        .setSeverity(Severity.WARN)
                        .setAllAttributes(
                            createBaseAttributes("runScenarioC")
                                // Standard HTTP semantic conventions
                                .put("http.method", "GET")
                                .put("http.url", "https://httpstat.us/200")
                                .put("http.route", "/appointments")
                                .put("http.scheme", "https")
                                .put("net.peer.name", "httpstat.us")
                                // Error attributes
                                .put("error.type", "network_failure")
                                .put("error.message", e.message ?: "Network call failed")
                                .put("exception.type", e.javaClass.simpleName)
                                // Context
                                .put("request_number", i.toLong())
                                .put("screen.name", "MainActivity")
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

                // Log the REAL HTTP 500 error using semantic conventions
                logger.logRecordBuilder()
                    .setBody("http.error")
                    .setSeverity(Severity.ERROR)
                    .setAllAttributes(
                        createBaseAttributes("runScenarioC")
                            // Standard HTTP semantic conventions
                            .put("http.method", "POST")
                            .put("http.url", "https://httpstat.us/500")
                            .put("http.route", "/appointments")
                            .put("http.scheme", "https")
                            .put("net.peer.name", "httpstat.us")
                            .put("http.status_code", response.code.toLong())
                            .put("http.duration_ms", duration)
                            // Error attributes
                            .put("error.type", "http.server_error")
                            .put("error.message", response.message)
                            // Mobile context
                            .put("screen.name", "MainActivity")
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

                // Log the network failure as telemetry using semantic conventions
                logger.logRecordBuilder()
                    .setBody("http.error")
                    .setSeverity(Severity.ERROR)
                    .setAllAttributes(
                        createBaseAttributes("runScenarioC")
                            // Standard HTTP semantic conventions
                            .put("http.method", "POST")
                            .put("http.url", "https://httpstat.us/500")
                            .put("http.route", "/appointments")
                            .put("http.scheme", "https")
                            .put("net.peer.name", "httpstat.us")
                            // Error attributes
                            .put("error.type", "network_failure")
                            .put("error.message", e.message ?: "Network call failed")
                            .put("exception.type", e.javaClass.simpleName)
                            // Mobile context
                            .put("screen.name", "MainActivity")
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

        OTelMobile.markLowMemoryForNextStart()
        Log.w(TAG, "Marked low memory for recovery - starting memory allocation")

        // Log pre-OOM event using OpenTelemetry semantic conventions
        logger.logRecordBuilder()
            .setBody("app.low_memory")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(
                createBaseAttributes("runScenarioD")
                    // Standard error semantic conventions
                    .put("error.type", "memory.exhaustion")
                    .put("error.message", "Memory exhaustion initiated - triggering OOM")
                    // Mobile context
                    .put("screen.name", "MainActivity")
                    .build()
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

                // Try to log OOM event if possible using semantic conventions
                try {
                    logger.logRecordBuilder()
                        .setBody("app.out_of_memory")
                        .setSeverity(Severity.FATAL)
                        .setAllAttributes(
                            createBaseAttributes("runScenarioD")
                                // Standard error semantic conventions
                                .put("error.type", "java.lang.OutOfMemoryError")
                                .put("error.message", "Out of memory - app will be killed")
                                .put("exception.type", "OutOfMemoryError")
                                // Mobile context
                                .put("screen.name", "MainActivity")
                                .build()
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
     * Scenario E: True ANR (Application Not Responding)
     *
     * Blocks the main thread for an extended period causing a genuine ANR:
     * - Android will display "App isn't responding" dialog after ~5 seconds
     * - User must force close or wait for the app to recover
     * - Event: app.anr.recovered logged after unblocking
     * - Action: Demonstrates real ANR telemetry capture
     *
     * WARNING: This will make the app completely unresponsive for 30 seconds!
     * The OS will show an ANR dialog and offer to force close the app.
     */
    private fun runScenarioE() {
        updateStatus("⚠️ Running Scenario E: True ANR\n\nBLOCKING MAIN THREAD FOR 30 SECONDS!")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "scenario_e",
            AttributeKey.stringKey("button.category"), "demo_scenarios"
        ))

        OTelMobile.markAnrForNextStart()
        Log.w(TAG, "Marked ANR for recovery - about to trigger ANR")


        Log.e(TAG, "TRIGGERING ANR - Blocking main thread for 30 seconds!")
        Log.e(TAG, "Android will show ANR dialog - you can force close or wait")

        // Block the main thread for 30 seconds
        // This WILL trigger Android's ANR detection and show the system dialog
        val startTime = System.currentTimeMillis()
        val blockDuration = 30000L // 30 seconds

        // Busy-wait loop (more reliable than Thread.sleep for triggering ANR)
        while (System.currentTimeMillis() - startTime < blockDuration) {
            // Intensive computation to ensure main thread is truly blocked
            var dummy = 0.0
            for (i in 0..1000) {
                dummy += Math.sqrt(i.toDouble())
            }
        }

        Log.i(TAG, "ANR completed - main thread unblocked after 30 seconds")

        // If we reach here, user waited through the ANR
        logger.logRecordBuilder()
            .setBody("app.anr.recovered")
            .setSeverity(Severity.WARN)
            .setAllAttributes(
                createBaseAttributes("runScenarioE")
                    // Standard error type for recovery
                    .put("error.type", "android.anr")
                    // Recovery-specific details
                    .put("android.anr.recovery_type", "user_waited")
                    .put("android.anr.duration_ms", blockDuration)
                    // Mobile context
                    .put("screen.name", "MainActivity")
                    .build()
            )
            .emit()

        // No ANR marker clearing needed when app recovers

        updateStatus("✅ Scenario E complete\nANR recovered (user waited 30s)\n📤 Telemetry captured!")
        Log.i(TAG, "Scenario E complete: ANR event logged")
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

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "force_quit",
            AttributeKey.stringKey("button.category"), "manual_controls"
        ))

        Thread {
            // Log pre-quit event using semantic conventions
            logger.logRecordBuilder()
                .setBody("app.force_quit")
                .setSeverity(Severity.WARN)
                .setAllAttributes(
                    createBaseAttributes("forceQuitApp")
                        .put("quit_type", "manual_force_quit")
                        .put("screen.name", "MainActivity")
                        .put("device_id", loggerProvider.getDeviceId())
                        .build()
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
     * Uses tracked transactions with configurable pass/fail/crash outcomes.
     */
    private fun logUserLogin() {
        updateStatus("🔵 Logging user login flow...")

        // Record button click metric
        buttonClickCounter.add(1, Attributes.of(
            AttributeKey.stringKey("button.name"), "user_login",
            AttributeKey.stringKey("button.category"), "regular_activities"
        ))

        val startTime = System.currentTimeMillis()
        val transactionId = UUID.randomUUID().toString()
        val outcome = determineTransactionOutcome()

        // Create a tracked span for the entire login operation
        val span = startTrackedTransaction(
            transactionId = transactionId,
            transactionType = "auth.login",
            spanBuilder = tracer.spanBuilder("auth.login")
                .setAttribute("demo_run_id", demoRunId)
                .setAttribute("auth.method", "email_password")
                .setAttribute("user.email", "demo@example.com")
                .setAttribute("transaction.expected_outcome", outcome.name)
        )

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
                                createBaseAttributes("logUserLogin")
                                    .put("auth.method", "email_password")
                                    .put("user.email", "demo@example.com")
                                    .put("screen.name", "LoginActivity")
                                    .put("transaction.id", transactionId)
                                    .build()
                            )
                            .emit()

                        // Simulate authentication delay
                        Thread.sleep(300)

                        attemptSpan.setStatus(StatusCode.OK)
                    }
                } finally {
                    attemptSpan.end()
                }

                // Handle transaction outcome
                when (outcome) {
                    TransactionOutcome.PASS -> {
                        // Login success
                        val sessionId = UUID.randomUUID().toString()
                        span.setAttribute("user.id", "user_12345")
                        span.setAttribute("session.id", sessionId)
                        span.setAttribute("transaction.outcome", "PASS")

                        logger.logRecordBuilder()
                            .setBody("auth.login.success")
                            .setSeverity(Severity.INFO)
                            .setAllAttributes(
                                createBaseAttributes("logUserLogin")
                                    .put("user.id", "user_12345")
                                    .put("user.email", "demo@example.com")
                                    .put("session.id", sessionId)
                                    .put("auth.duration_ms", 300L)
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "PASS")
                                    .build()
                            )
                            .emit()

                        endTrackedTransaction(span, StatusCode.OK)
                        updateStatus("✅ User login logged\nOutcome: PASS\nUser ID: user_12345\nTransaction ID: $transactionId")
                    }

                    TransactionOutcome.FAIL -> {
                        // Login fails gracefully (wrong password, etc.)
                        span.setAttribute("transaction.outcome", "FAIL")
                        span.setAttribute("error.type", "auth.invalid_credentials")

                        logger.logRecordBuilder()
                            .setBody("auth.login.failure")
                            .setSeverity(Severity.WARN)
                            .setAllAttributes(
                                createBaseAttributes("logUserLogin")
                                    .put("user.email", "demo@example.com")
                                    .put("error.type", "invalid_credentials")
                                    .put("error.message", "Invalid username or password")
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "FAIL")
                                    .build()
                            )
                            .emit()

                        endTrackedTransaction(span, StatusCode.ERROR, "Invalid credentials")
                        updateStatus("⚠️ User login failed\nOutcome: FAIL\nReason: Invalid credentials\nTransaction ID: $transactionId")
                    }

                    TransactionOutcome.CRASH -> {
                        // Crash before transaction completes - transaction will be marked incomplete on restart
                        span.setAttribute("transaction.outcome", "CRASH")
                        span.addEvent("transaction_about_to_crash")

                        logger.logRecordBuilder()
                            .setBody("auth.login.crash_simulated")
                            .setSeverity(Severity.ERROR)
                            .setAllAttributes(
                                createBaseAttributes("logUserLogin")
                                    .put("user.email", "demo@example.com")
                                    .put("error.type", "simulated_crash")
                                    .put("error.message", "Simulated crash during login transaction")
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "CRASH")
                                    .build()
                            )
                            .emit()

                        // Set crash marker
                        OTelMobile.markCrashForNextStart()
                        Log.w(TAG, "Transaction crashing - recovery marker set")
                        updateStatus("💥 Transaction crashing!\nOutcome: CRASH\nTransaction will be incomplete")

                        // DON'T end the tracked transaction - let it remain incomplete
                        span.end()  // End span but leave transaction tracking active

                        // Give time for logs to be written
                        Thread.sleep(100)

                        // Crash the app
                        throw RuntimeException("Simulated crash during transaction: $transactionId")
                    }
                }

                // Record operation duration metric (only for PASS/FAIL, not CRASH)
                if (outcome != TransactionOutcome.CRASH) {
                    val duration = (System.currentTimeMillis() - startTime).toDouble()
                    operationDurationHistogram.record(duration, Attributes.of(
                        AttributeKey.stringKey("operation.name"), "auth.login",
                        AttributeKey.stringKey("operation.status"), outcome.name.lowercase()
                    ))
                }
            }
        } catch (e: Exception) {
            // Only catch non-crash exceptions
            if (outcome != TransactionOutcome.CRASH) {
                span.recordException(e)
                endTrackedTransaction(span, StatusCode.ERROR, "Unexpected error: ${e.message}")
            }
            throw e
        }

        Log.i(TAG, "User login flow logged with outcome: $outcome, transaction_id: $transactionId")
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
        val transactionId = UUID.randomUUID().toString()
        val outcome = determineTransactionOutcome()

        // Create tracked span for navigation
        val span = startTrackedTransaction(
            transactionId = transactionId,
            transactionType = "screen.navigation",
            spanBuilder = tracer.spanBuilder("screen.navigation")
                .setAttribute("demo_run_id", demoRunId)
                .setAttribute("screen.from", currentScreen)
                .setAttribute("screen.to", nextScreen)
                .setAttribute("transaction.expected_outcome", outcome.name)
        )

        try {
            span.makeCurrent().use {
                // Screen exit
                logger.logRecordBuilder()
                    .setBody("screen.exit")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        createBaseAttributes("logPageNavigation")
                            .put("screen.name", currentScreen)
                            .put("screen.duration_ms", (2000..5000).random().toLong())
                            .put("transaction.id", transactionId)
                            .build()
                    )
                    .emit()

                Thread.sleep(100)

                // Handle transaction outcome
                when (outcome) {
                    TransactionOutcome.PASS -> {
                        span.setAttribute("transaction.outcome", "PASS")

                        logger.logRecordBuilder()
                            .setBody("screen.enter")
                            .setSeverity(Severity.INFO)
                            .setAllAttributes(
                                createBaseAttributes("logPageNavigation")
                                    .put("screen.name", nextScreen)
                                    .put("screen.source", currentScreen)
                                    .put("navigation.method", "button_click")
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "PASS")
                                    .build()
                            )
                            .emit()

                        endTrackedTransaction(span, StatusCode.OK)
                        updateStatus("✅ Navigation: PASS\n$currentScreen → $nextScreen")
                    }

                    TransactionOutcome.FAIL -> {
                        span.setAttribute("transaction.outcome", "FAIL")
                        span.setAttribute("error.type", "navigation.not_found")

                        logger.logRecordBuilder()
                            .setBody("screen.navigation_failed")
                            .setSeverity(Severity.WARN)
                            .setAllAttributes(
                                createBaseAttributes("logPageNavigation")
                                    .put("screen.from", currentScreen)
                                    .put("screen.to", nextScreen)
                                    .put("error.type", "screen_not_found")
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "FAIL")
                                    .build()
                            )
                            .emit()

                        endTrackedTransaction(span, StatusCode.ERROR, "Navigation failed")
                        updateStatus("⚠️ Navigation: FAIL\n$currentScreen ╳ $nextScreen")
                    }

                    TransactionOutcome.CRASH -> {
                        span.setAttribute("transaction.outcome", "CRASH")
                        span.addEvent("transaction_about_to_crash")

                        logger.logRecordBuilder()
                            .setBody("screen.navigation_crash_simulated")
                            .setSeverity(Severity.ERROR)
                            .setAllAttributes(
                                createBaseAttributes("logPageNavigation")
                                    .put("screen.from", currentScreen)
                                    .put("screen.to", nextScreen)
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "CRASH")
                                    .build()
                            )
                            .emit()

                        OTelMobile.markCrashForNextStart()

                        span.end()
                        Thread.sleep(100)
                        throw RuntimeException("Crash during navigation transaction: $transactionId")
                    }
                }
            }
        } catch (e: Exception) {
            if (outcome != TransactionOutcome.CRASH) {
                span.recordException(e)
                endTrackedTransaction(span, StatusCode.ERROR)
            }
            throw e
        }

        Log.i(TAG, "Navigation: $currentScreen -> $nextScreen, outcome: $outcome, tx: $transactionId")
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
        val transactionId = UUID.randomUUID().toString()
        val outcome = determineTransactionOutcome()

        // Create tracked HTTP span (follows semantic conventions)
        val span = startTrackedTransaction(
            transactionId = transactionId,
            transactionType = "http.request",
            spanBuilder = tracer.spanBuilder("HTTP GET")
                .setAttribute("http.method", "GET")
                .setAttribute("http.url", "https://api.example.com$endpoint")
                .setAttribute("http.route", endpoint)
                .setAttribute("http.scheme", "https")
                .setAttribute("http.target", endpoint)
                .setAttribute("net.peer.name", "api.example.com")
                .setAttribute("demo_run_id", demoRunId)
                .setAttribute("transaction.expected_outcome", outcome.name)
        )

        try {
            span.makeCurrent().use {
                // Log API request using semantic conventions
                logger.logRecordBuilder()
                    .setBody("http.request")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        createBaseAttributes("logApiCall")
                            .put("http.method", "GET")
                            .put("http.url", "https://api.example.com$endpoint")
                            .put("http.route", endpoint)
                            .put("http.scheme", "https")
                            .put("net.peer.name", "api.example.com")
                            .put("request.id", UUID.randomUUID().toString())
                            .put("transaction.id", transactionId)
                            .build()
                    )
                    .emit()

                span.addEvent("request_sent")

                // Simulate network delay
                Thread.sleep(duration.toLong())

                // Handle transaction outcome
                when (outcome) {
                    TransactionOutcome.PASS -> {
                        span.setAttribute("http.status_code", 200L)
                        span.setAttribute("http.response_content_length", responseSize.toLong())
                        span.setAttribute("transaction.outcome", "PASS")
                        span.addEvent("response_received")

                        logger.logRecordBuilder()
                            .setBody("http.response")
                            .setSeverity(Severity.INFO)
                            .setAllAttributes(
                                createBaseAttributes("logApiCall")
                                    .put("http.method", "GET")
                                    .put("http.route", endpoint)
                                    .put("http.status_code", 200L)
                                    .put("http.duration_ms", duration.toLong())
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "PASS")
                                    .build()
                            )
                            .emit()

                        endTrackedTransaction(span, StatusCode.OK)
                        updateStatus("✅ API call: PASS\nGET $endpoint (200 OK)")
                    }

                    TransactionOutcome.FAIL -> {
                        val errorCode = listOf(500, 502, 503).random()
                        span.setAttribute("http.status_code", errorCode.toLong())
                        span.setAttribute("transaction.outcome", "FAIL")
                        span.addEvent("response_received")

                        logger.logRecordBuilder()
                            .setBody("http.response")
                            .setSeverity(Severity.ERROR)
                            .setAllAttributes(
                                createBaseAttributes("logApiCall")
                                    .put("http.method", "GET")
                                    .put("http.route", endpoint)
                                    .put("http.status_code", errorCode.toLong())
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "FAIL")
                                    .build()
                            )
                            .emit()

                        endTrackedTransaction(span, StatusCode.ERROR, "HTTP $errorCode")
                        updateStatus("⚠️ API call: FAIL\nGET $endpoint (HTTP $errorCode)")
                    }

                    TransactionOutcome.CRASH -> {
                        span.setAttribute("transaction.outcome", "CRASH")
                        span.addEvent("transaction_about_to_crash")

                        logger.logRecordBuilder()
                            .setBody("http.crash_simulated")
                            .setSeverity(Severity.ERROR)
                            .setAllAttributes(
                                createBaseAttributes("logApiCall")
                                    .put("http.method", "GET")
                                    .put("http.route", endpoint)
                                    .put("transaction.id", transactionId)
                                    .put("transaction.outcome", "CRASH")
                                    .build()
                            )
                            .emit()

                        OTelMobile.markCrashForNextStart()

                        span.end()
                        Thread.sleep(100)
                        throw RuntimeException("Crash during API transaction: $transactionId")
                    }
                }

                if (outcome != TransactionOutcome.CRASH) {
                    operationDurationHistogram.record(duration.toDouble(), Attributes.of(
                        AttributeKey.stringKey("operation.name"), "http.request",
                        AttributeKey.stringKey("http.method"), "GET",
                        AttributeKey.stringKey("http.route"), endpoint,
                        AttributeKey.stringKey("transaction.outcome"), outcome.name
                    ))
                }
            }
        } catch (e: Exception) {
            if (outcome != TransactionOutcome.CRASH) {
                span.recordException(e)
                endTrackedTransaction(span, StatusCode.ERROR)
            }
            throw e
        }

        Log.i(TAG, "API call: $endpoint, outcome: $outcome, tx: $transactionId")
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
                // Task started using semantic conventions
                logger.logRecordBuilder()
                    .setBody("background.task.started")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        createBaseAttributes("logBackgroundTask")
                            .put("task.type", taskType)
                            .put("task.description", taskDescription)
                            .put("task.id", taskId)
                            .build()
                    )
                    .emit()

                span.addEvent("task_processing_started", Attributes.of(
                    AttributeKey.stringKey("task.type"), taskType,
                    AttributeKey.stringKey("task.id"), taskId,
                    AttributeKey.stringKey("phase"), "processing",
                    AttributeKey.longKey("timestamp_ms"), System.currentTimeMillis()
                ))

                // Simulate task execution
                Thread.sleep(duration.toLong())

                span.addEvent("task_processing_completed", Attributes.of(
                    AttributeKey.stringKey("task.type"), taskType,
                    AttributeKey.stringKey("task.id"), taskId,
                    AttributeKey.stringKey("task.status"), "success",
                    AttributeKey.longKey("task.duration_ms"), duration.toLong(),
                    AttributeKey.stringKey("phase"), "completed",
                    AttributeKey.longKey("timestamp_ms"), System.currentTimeMillis()
                ))
                span.setAttribute("task.status", "success")
                span.setAttribute("task.duration_ms", duration.toLong())

                // Task completed using semantic conventions
                logger.logRecordBuilder()
                    .setBody("background.task.completed")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(
                        createBaseAttributes("logBackgroundTask")
                            .put("task.type", taskType)
                            .put("task.description", taskDescription)
                            .put("task.duration_ms", duration.toLong())
                            .put("task.status", "success")
                            .build()
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
                createBaseAttributes("logUserInteraction")
                    .put("interaction.type", interactionType)
                    .put("interaction.element_id", elementId)
                    .put("screen.name", "FeedActivity")
                    .put("interaction.timestamp_ms", System.currentTimeMillis())
                    .build()
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

        // Form validation using semantic conventions
        logger.logRecordBuilder()
            .setBody("form.validation")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                createBaseAttributes("logFormSubmission")
                    .put("form.type", formType)
                    .put("form.field_count", fields.size.toLong())
                    .put("validation.status", "passed")
                    .build()
            )
            .emit()

        Thread.sleep(100)

        // Form submission using semantic conventions
        logger.logRecordBuilder()
            .setBody("form.submitted")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                createBaseAttributes("logFormSubmission")
                    .put("form.type", formType)
                    .put("form.id", UUID.randomUUID().toString())
                    .put("form.field_count", fields.size.toLong())
                    .put("submission.status", "success")
                    .put("submission.duration_ms", 150L)
                    .build()
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
            R.id.action_logs -> {
                startActivity(Intent(this, LogsActivity::class.java))
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
        Log.i(TAG, "MainActivity destroyed")
    }
}
