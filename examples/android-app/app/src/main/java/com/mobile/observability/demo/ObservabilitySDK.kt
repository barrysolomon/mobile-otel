package com.mobile.observability.demo

import android.content.Context
import android.util.Log
import com.mobile.observability.demo.buffer.RingBufferManager
import com.mobile.observability.demo.data.CrashMarkerEntity
import com.mobile.observability.demo.data.ObservabilityDatabase
import com.mobile.observability.demo.network.GatewayClient
import com.mobile.observability.demo.network.Heartbeat
import com.mobile.observability.demo.workflow.DSLConfig
import com.mobile.observability.demo.workflow.Event
import com.mobile.observability.demo.workflow.WorkflowEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class ObservabilitySDK private constructor(
    private val context: Context,
    private val appId: String,
    private val gatewayUrl: String,
    private val authToken: String? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = ObservabilityDatabase.getDatabase(context)
    private val gatewayClient = GatewayClient(gatewayUrl, authToken)

    private val deviceId = getOrCreateDeviceId()
    private val sessionId = UUID.randomUUID().toString()
    private val demoRunId = "run-${System.currentTimeMillis()}"

    private lateinit var bufferManager: RingBufferManager
    private lateinit var workflowEvaluator: WorkflowEvaluator
    private var currentConfig: DSLConfig? = null
    private val lastTriggers = mutableListOf<String>()

    init {
        scope.launch {
            initialize()
        }
    }

    private suspend fun initialize() {
        Log.d(TAG, "Initializing ObservabilitySDK")
        Log.d(TAG, "Device ID: $deviceId")
        Log.d(TAG, "Session ID: $sessionId")
        Log.d(TAG, "Demo Run ID: $demoRunId")

        // Check for crash marker from previous session
        checkCrashMarker()

        // Fetch config from gateway
        fetchConfig()

        // Start periodic heartbeat
        startHeartbeat()
    }

    private suspend fun checkCrashMarker() {
        val marker = database.crashMarkerDao().getUnprocessedMarker()
        if (marker != null) {
            Log.w(TAG, "Found unprocessed crash marker from session ${marker.sessionId}")

            // Emit crash_marker event
            captureEvent(
                eventName = "crash_marker",
                attributes = mapOf(
                    "previous_session_id" to marker.sessionId,
                    "crash_time" to marker.timestamp
                )
            )

            // Mark as processed
            database.crashMarkerDao().markAsProcessed(marker.id)
        }
    }

    private suspend fun fetchConfig() {
        val result = gatewayClient.getConfig(appId, deviceId)
        result.onSuccess { config ->
            currentConfig = config
            workflowEvaluator = WorkflowEvaluator(config)

            bufferManager = RingBufferManager(
                eventDao = database.eventDao(),
                sessionId = sessionId,
                deviceId = deviceId,
                configVersion = config.version,
                maxRamEvents = config.limits.ramEvents,
                maxDiskMb = config.limits.diskMb,
                retentionHours = config.limits.retentionHours
            )

            Log.d(TAG, "Config loaded: version ${config.version}, ${config.workflows.size} workflows")
        }.onFailure { error ->
            Log.e(TAG, "Failed to fetch config, using defaults", error)
            // Use default config (will be created by RingBufferManager)
        }
    }

    fun captureEvent(eventName: String, attributes: Map<String, Any> = emptyMap()) {
        // Add demo_run_id to all events for correlation
        val enrichedAttributes = attributes.toMutableMap().apply {
            put("demo_run_id", demoRunId)
        }

        val event = Event(
            eventName = eventName,
            timestamp = System.currentTimeMillis(),
            attributes = enrichedAttributes
        )
        captureEvent(event)
    }

    private fun captureEvent(event: Event) {
        scope.launch {
            try {
                // Evaluate workflows
                val results = if (::workflowEvaluator.isInitialized) {
                    workflowEvaluator.evaluate(event)
                } else {
                    emptyList()
                }

                // Add event to buffer
                if (::bufferManager.isInitialized) {
                    val triggerId = results.firstOrNull()?.workflowId
                    bufferManager.addEvent(event, triggerId)

                    // Execute actions for triggered workflows
                    for (result in results) {
                        lastTriggers.add(0, result.workflowId)
                        if (lastTriggers.size > 10) {
                            lastTriggers.removeAt(lastTriggers.size - 1)
                        }

                        executeActions(result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing event", e)
            }
        }
    }

    private suspend fun executeActions(result: com.mobile.observability.demo.workflow.TriggerResult) {
        for (action in result.actions) {
            try {
                when (action.type) {
                    "annotate_trigger" -> {
                        Log.d(TAG, "Annotated trigger: ${action.triggerId} - ${action.reason}")
                    }
                    "flush_window" -> {
                        val minutes = action.minutes ?: 5
                        val scope = action.scope ?: "session"
                        flushWindow(minutes, scope)
                    }
                    "set_sampling" -> {
                        val rate = action.rate ?: 1.0
                        val duration = action.durationMinutes ?: 10
                        Log.d(TAG, "Set sampling rate to $rate for $duration minutes")
                        // Sampling logic would be implemented here
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing action: ${action.type}", e)
            }
        }
    }

    private suspend fun flushWindow(minutes: Int, scope: String) {
        if (!::bufferManager.isInitialized) return

        Log.d(TAG, "Flushing $minutes minute window (scope: $scope)")

        // Get events in window
        val events = bufferManager.getEventsForFlush(minutes, scope)
        if (events.isEmpty()) {
            Log.d(TAG, "No events to flush")
            return
        }

        // Send to gateway
        val result = gatewayClient.ingestEvents(events)
        result.onSuccess {
            // Mark as flushed
            val eventIds = events.map { it.id }
            bufferManager.markEventsFlushed(eventIds)
            Log.d(TAG, "Flushed ${events.size} events to gateway")
        }.onFailure { error ->
            Log.e(TAG, "Failed to flush events", error)
        }
    }

    private fun startHeartbeat() {
        scope.launch {
            while (true) {
                try {
                    kotlinx.coroutines.delay(30_000) // 30 seconds

                    if (!::bufferManager.isInitialized) continue

                    val stats = bufferManager.getBufferStats()
                    val heartbeat = Heartbeat(
                        deviceId = deviceId,
                        appId = appId,
                        sessionId = sessionId,
                        bufferUsageMb = stats.diskSizeMb,
                        lastTriggers = lastTriggers.toList(),
                        configVersion = currentConfig?.version ?: 0
                    )

                    gatewayClient.sendHeartbeat(heartbeat)
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                }
            }
        }
    }

    fun writeCrashMarker() {
        scope.launch {
            val marker = CrashMarkerEntity(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis()
            )
            database.crashMarkerDao().insert(marker)
            Log.d(TAG, "Crash marker written")
        }
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("observability", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    companion object {
        private const val TAG = "ObservabilitySDK"

        @Volatile
        private var INSTANCE: ObservabilitySDK? = null

        fun initialize(
            context: Context,
            appId: String,
            gatewayUrl: String,
            authToken: String? = null
        ): ObservabilitySDK {
            return INSTANCE ?: synchronized(this) {
                val instance = ObservabilitySDK(
                    context.applicationContext,
                    appId,
                    gatewayUrl,
                    authToken
                )
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(): ObservabilitySDK {
            return INSTANCE ?: throw IllegalStateException("ObservabilitySDK not initialized")
        }
    }
}
