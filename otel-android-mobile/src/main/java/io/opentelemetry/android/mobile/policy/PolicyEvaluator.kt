package io.opentelemetry.android.mobile.policy

import android.util.Log
import io.opentelemetry.sdk.logs.data.LogRecordData
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Evaluates workflow policies to determine when to flush events.
 *
 * The PolicyEvaluator:
 * 1. Fetches workflow configurations from the collector/gateway
 * 2. Evaluates each log record against active policies
 * 3. Returns flush instructions when policies match
 *
 * **Policy Structure:**
 * ```json
 * {
 *   "id": "ui-freeze-handler",
 *   "enabled": true,
 *   "match": {
 *     "logical_operator": "and",
 *     "attributes": {
 *       "event.name": {"equals": "ui.freeze"},
 *       "duration_ms": {"gt": 2000.0}
 *     }
 *   },
 *   "actions": {
 *     "flush_window_minutes": 2
 *   }
 * }
 * ```
 *
 * **Evaluation Process:**
 * 1. Check if policy is enabled
 * 2. Extract attributes from LogRecordData
 * 3. Apply match conditions (equals, gt, lt, contains, etc.)
 * 4. Combine with logical operator (and/or)
 * 5. Return flush action if matched
 *
 * Thread Safety: Uses atomic reference for policy config
 * Config Refresh: Polls for updates every 5 minutes (configurable)
 *
 * @property collectorEndpoint Base URL for configuration endpoint
 */
class PolicyEvaluator(
    private val collectorEndpoint: String,
    private val configPollIntervalSeconds: Long = 300
) {
    private val TAG = "PolicyEvaluator"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Current policy configuration (thread-safe)
    private val policyConfig = AtomicReference<PolicyConfig?>(null)

    // Coroutine scope for background tasks
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Initial config fetch
        fetchConfig()

        // Schedule periodic config refresh
        scope.launch {
            while (isActive) {
                delay(configPollIntervalSeconds * 1000)
                fetchConfig()
            }
        }
    }

    /**
     * Evaluates a log record against all active policies.
     *
     * @param logRecord The log record to evaluate
     * @return PolicyMatchResult if a policy matched, null otherwise
     */
    fun evaluate(logRecord: LogRecordData): PolicyMatchResult? {
        val config = policyConfig.get() ?: return null

        for (policy in config.policies) {
            if (!policy.enabled) continue

            if (matchesPolicy(logRecord, policy)) {
                Log.i(TAG, "Policy matched: ${policy.id}")
                return PolicyMatchResult(
                    policyId = policy.id,
                    flushWindowMinutes = policy.actions.flushWindowMinutes
                )
            }
        }

        return null
    }

    /**
     * Checks if a log record matches a policy's conditions.
     */
    private fun matchesPolicy(logRecord: LogRecordData, policy: Policy): Boolean {
        val matchConditions = policy.match.attributes.map { (attrKey, condition) ->
            val attrValue = getAttributeValue(logRecord, attrKey)
            matchesCondition(attrValue, condition)
        }

        return when (policy.match.logicalOperator) {
            "and" -> matchConditions.all { it }
            "or" -> matchConditions.any { it }
            else -> false
        }
    }

    /**
     * Extracts attribute value from log record.
     */
    private fun getAttributeValue(logRecord: LogRecordData, key: String): Any? {
        return when (key) {
            "event.name" -> logRecord.body.asString()
            else -> logRecord.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(key))?.asString()
        }
    }

    /**
     * Checks if a value matches a condition.
     */
    private fun matchesCondition(value: Any?, condition: Condition): Boolean {
        if (value == null) return false

        return when {
            condition.equals != null -> value.toString() == condition.equals
            condition.gt != null -> (value as? Number)?.toDouble()?.let { it > condition.gt } ?: false
            condition.lt != null -> (value as? Number)?.toDouble()?.let { it < condition.lt } ?: false
            condition.gte != null -> (value as? Number)?.toDouble()?.let { it >= condition.gte } ?: false
            condition.lte != null -> (value as? Number)?.toDouble()?.let { it <= condition.lte } ?: false
            condition.contains != null -> value.toString().contains(condition.contains)
            condition.regex != null -> value.toString().matches(Regex(condition.regex))
            else -> false
        }
    }

    /**
     * Fetches policy configuration from the collector/gateway.
     */
    private fun fetchConfig() {
        scope.launch {
            try {
                val configUrl = "${collectorEndpoint.removeSuffix("/")}/config"
                val request = Request.Builder()
                    .url(configUrl)
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val config = parseConfig(body)
                        policyConfig.set(config)
                        Log.i(TAG, "Fetched policy config: ${config.policies.size} policies")
                    }
                } else {
                    Log.w(TAG, "Failed to fetch config: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching config", e)
            }
        }
    }

    /**
     * Parses JSON configuration into PolicyConfig.
     */
    private fun parseConfig(json: String): PolicyConfig {
        val jsonObj = JSONObject(json)
        val workflowsArray = jsonObj.optJSONArray("workflows") ?: JSONArray()

        val policies = mutableListOf<Policy>()

        for (i in 0 until workflowsArray.length()) {
            val workflowObj = workflowsArray.getJSONObject(i)

            // Parse trigger node
            val triggerNode = workflowObj.getJSONObject("nodes")
                .getJSONArray("trigger")
                .getJSONObject(0)

            val matchObj = triggerNode.getJSONObject("data").getJSONObject("match")
            val attributes = mutableMapOf<String, Condition>()

            val attrsObj = matchObj.optJSONObject("attributes")
            attrsObj?.keys()?.forEach { key ->
                val condObj = attrsObj.getJSONObject(key)
                attributes[key] = Condition(
                    equals = condObj.optString("equals").takeIf { it.isNotEmpty() },
                    gt = condObj.optDouble("gt").takeIf { !it.isNaN() },
                    lt = condObj.optDouble("lt").takeIf { !it.isNaN() },
                    gte = condObj.optDouble("gte").takeIf { !it.isNaN() },
                    lte = condObj.optDouble("lte").takeIf { !it.isNaN() },
                    contains = condObj.optString("contains").takeIf { it.isNotEmpty() },
                    regex = condObj.optString("regex").takeIf { it.isNotEmpty() }
                )
            }

            // Parse action node
            val actionNode = workflowObj.getJSONObject("nodes")
                .getJSONArray("action")
                .getJSONObject(0)

            val flushWindowMinutes = actionNode.getJSONObject("data")
                .optInt("flush_window_minutes", 2)

            policies.add(
                Policy(
                    id = workflowObj.getString("id"),
                    enabled = workflowObj.optBoolean("enabled", true),
                    match = Match(
                        logicalOperator = matchObj.optString("logical_operator", "and"),
                        attributes = attributes
                    ),
                    actions = Actions(
                        flushWindowMinutes = flushWindowMinutes
                    )
                )
            )
        }

        return PolicyConfig(policies)
    }

    /**
     * Shuts down the evaluator and releases resources.
     */
    fun shutdown() {
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

/**
 * Result of policy evaluation.
 */
data class PolicyMatchResult(
    val policyId: String,
    val flushWindowMinutes: Int
)

/**
 * Policy configuration container.
 */
data class PolicyConfig(
    val policies: List<Policy>
)

/**
 * Individual policy definition.
 */
data class Policy(
    val id: String,
    val enabled: Boolean,
    val match: Match,
    val actions: Actions
)

/**
 * Match conditions for a policy.
 */
data class Match(
    val logicalOperator: String, // "and" or "or"
    val attributes: Map<String, Condition>
)

/**
 * Condition for a single attribute.
 */
data class Condition(
    val equals: String? = null,
    val gt: Double? = null,
    val lt: Double? = null,
    val gte: Double? = null,
    val lte: Double? = null,
    val contains: String? = null,
    val regex: String? = null
)

/**
 * Actions to take when policy matches.
 */
data class Actions(
    val flushWindowMinutes: Int
)
