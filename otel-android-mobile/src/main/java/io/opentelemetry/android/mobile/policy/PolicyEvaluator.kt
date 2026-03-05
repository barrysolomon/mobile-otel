/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import android.content.Context
import android.util.Log
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.context.ContextSnapshot
import io.opentelemetry.android.mobile.context.ContextSnapshotProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Evaluates export policies to determine when to flush events.
 *
 * The PolicyEvaluator:
 * 1. Fetches policy configurations from the collector/gateway
 * 2. Evaluates each log record against active policies
 * 3. Evaluates geo and device context (privacy-safe)
 * 4. Returns flush instructions when policies match
 *
 * **Policy Structure (Extended with Geo/Device):**
 * ```json
 * {
 *   "id": "ui-freeze-us-only",
 *   "enabled": true,
 *   "match": {
 *     "logical_operator": "and",
 *     "attributes": {
 *       "event.name": {"equals": "ui.freeze"},
 *       "duration_ms": {"gt": 2000.0}
 *     },
 *     "geo": {
 *       "country": ["US"],
 *       "timezone": ["America/wildcard"]
 *     },
 *     "device": {
 *       "network": ["cellular"],
 *       "battery": ["normal", "charging"]
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
 * 3. Get current device/geo context (ContextSnapshot)
 * 4. Apply match conditions (attributes, geo, device)
 * 5. Combine with logical operator (and/or)
 * 6. Return flush action if matched
 *
 * Thread Safety: Uses atomic reference for policy config
 * Config Refresh: Polls for updates every 5 minutes (configurable)
 *
 * @property context Android application context
 * @property config Mobile configuration
 * @property collectorEndpoint Base URL for configuration endpoint
 */
class PolicyEvaluator(
    private val context: Context,
    private val config: MobileConfig,
    private val collectorEndpoint: String = config.collectorEndpoint,
    private val configPollIntervalSeconds: Long = config.configPollIntervalSeconds
) {
    private val TAG = "PolicyEvaluator"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Current policy configuration (thread-safe)
    private val policyConfig = AtomicReference<PolicyConfig?>(null)

    // Built-in fallback policies used when no remote config has been fetched yet.
    // These cover the most critical event types to ensure flush happens even without config.
    private val defaultPolicies = PolicyConfig(
        listOf(
            Policy(
                id = "ui-freeze-detector",
                enabled = true,
                match = Match(
                    logicalOperator = "and",
                    attributes = mapOf("event.name" to Condition(equals = "ui.freeze"))
                ),
                actions = Actions(flushWindowMinutes = 2)
            ),
            Policy(
                id = "crash-recovery",
                enabled = true,
                match = Match(
                    logicalOperator = "and",
                    attributes = mapOf("event.name" to Condition(equals = "app.crash"))
                ),
                actions = Actions(flushWindowMinutes = 5)
            )
        )
    )

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
        val policyConf = policyConfig.get() ?: defaultPolicies

        // Get current device/geo context
        val contextSnapshot = ContextSnapshotProvider.getSnapshot(context, config)

        for (policy in policyConf.policies) {
            if (!policy.enabled) continue

            if (matchesPolicy(logRecord, contextSnapshot, policy)) {
                Log.i(TAG, "Policy matched: ${policy.id}")
                return PolicyMatchResult(
                    policyId = policy.id,
                    flushWindowMinutes = policy.actions.flushWindowMinutes,
                    contextSnapshot = contextSnapshot
                )
            }
        }

        return null
    }

    /**
     * Checks if a log record matches a policy's conditions.
     *
     * Evaluates three dimensions:
     * 1. Attribute conditions (existing)
     * 2. Geo conditions (new)
     * 3. Device conditions (new)
     *
     * Combined using the policy's logical operator (and/or).
     */
    private fun matchesPolicy(
        logRecord: LogRecordData,
        contextSnapshot: ContextSnapshot,
        policy: Policy
    ): Boolean {
        // 1. Check attribute conditions
        val attributeMatch = if (policy.match.attributes.isNotEmpty()) {
            val conditions = policy.match.attributes.map { (attrKey, condition) ->
                val attrValue = getAttributeValue(logRecord, attrKey)
                matchesCondition(attrValue, condition)
            }
            conditions.all { it }  // Attributes always use AND logic internally
        } else {
            true  // No attribute constraints = always match
        }

        // 2. Check geo conditions
        val geoMatch = matchGeo(contextSnapshot, policy.match.geo)

        // 3. Check device conditions
        val deviceMatch = matchDevice(contextSnapshot, policy.match.device)

        // 4. Combine with logical operator
        return when (policy.match.logicalOperator) {
            "and" -> attributeMatch && geoMatch && deviceMatch
            "or" -> attributeMatch || geoMatch || deviceMatch
            else -> false
        }
    }

    /**
     * Extracts attribute value from log record.
     */
    private fun getAttributeValue(logRecord: LogRecordData, key: String): Any? {
        return when (key) {
            "event.name" -> logRecord.body.asString()
            else -> {
                val attr = logRecord.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(key))
                attr?.toString()
            }
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
     * Checks if context matches geo conditions.
     *
     * Supports:
     * - Country list matching (exact)
     * - Region list matching (exact)
     * - Timezone glob matching (e.g., "America/wildcard")
     * - Locale list matching (exact)
     */
    private fun matchGeo(context: ContextSnapshot, geo: GeoMatch?): Boolean {
        if (geo == null) return true  // No geo constraint = always match

        var matches = true

        // Country list match (e.g., ["US", "CA"])
        if (geo.country != null && geo.country.isNotEmpty()) {
            matches = matches && context.country in geo.country
        }

        // Region list match (e.g., ["CA", "NY"])
        if (geo.region != null && geo.region.isNotEmpty()) {
            matches = matches && context.region in geo.region
        }

        // Timezone glob match (e.g., ["America/wildcard", "US/wildcard"])
        if (geo.timezone != null && geo.timezone.isNotEmpty()) {
            val timezoneMatches = geo.timezone.any { pattern ->
                matchGlob(context.timezone, pattern)
            }
            matches = matches && timezoneMatches
        }

        // Locale list match (e.g., ["en-US", "es-ES"])
        if (geo.locale != null && geo.locale.isNotEmpty()) {
            matches = matches && context.locale in geo.locale
        }

        return matches
    }

    /**
     * Checks if context matches device conditions.
     *
     * Supports:
     * - Network type list matching
     * - Battery state list matching
     * - Device class list matching
     * - Build channel list matching
     * - OS version range matching
     * - App version list matching (string comparison)
     */
    private fun matchDevice(context: ContextSnapshot, device: DeviceMatch?): Boolean {
        if (device == null) return true  // No device constraint = always match

        var matches = true

        // Network type list match (e.g., ["wifi", "cellular"])
        if (device.network != null && device.network.isNotEmpty()) {
            matches = matches && context.networkType in device.network
        }

        // Battery state list match (e.g., ["normal", "charging"])
        if (device.battery != null && device.battery.isNotEmpty()) {
            matches = matches && context.batteryState in device.battery
        }

        // Device class list match (e.g., ["phone"])
        if (device.deviceClass != null && device.deviceClass.isNotEmpty()) {
            matches = matches && context.deviceClass in device.deviceClass
        }

        // Build channel list match (e.g., ["beta", "internal"])
        if (device.buildChannel != null && device.buildChannel.isNotEmpty()) {
            matches = matches && context.buildChannel in device.buildChannel
        }

        // OS version range match (e.g., minSdkInt >= 26)
        if (device.osVersionMin != null) {
            matches = matches && context.osVersion >= device.osVersionMin
        }
        if (device.osVersionMax != null) {
            matches = matches && context.osVersion <= device.osVersionMax
        }

        // App version list match (e.g., ["1.2.3", "1.2.4"])
        if (device.appVersion != null && device.appVersion.isNotEmpty()) {
            matches = matches && context.appVersion in device.appVersion
        }

        return matches
    }

    /**
     * Simple glob pattern matching.
     *
     * Supports:
     * - "America/wildcard" matches "America/Los_Angeles", "America/New_York", etc.
     * - "US/wildcard" matches "US/Pacific", "US/Eastern", etc.
     * - Exact match if no glob
     */
    private fun matchGlob(value: String, pattern: String): Boolean {
        if (pattern.endsWith("/*")) {
            val prefix = pattern.removeSuffix("/*")
            return value.startsWith(prefix + "/")
        }
        return value == pattern
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

            // Parse attribute conditions (existing)
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

            // Parse geo conditions (new)
            val geoMatch = matchObj.optJSONObject("geo")?.let { geoObj ->
                GeoMatch(
                    country = geoObj.optJSONArray("country")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    region = geoObj.optJSONArray("region")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    timezone = geoObj.optJSONArray("timezone")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    locale = geoObj.optJSONArray("locale")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    }
                )
            }

            // Parse device conditions (new)
            val deviceMatch = matchObj.optJSONObject("device")?.let { deviceObj ->
                DeviceMatch(
                    network = deviceObj.optJSONArray("network")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    battery = deviceObj.optJSONArray("battery")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    deviceClass = deviceObj.optJSONArray("deviceClass")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    buildChannel = deviceObj.optJSONArray("buildChannel")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    },
                    osVersionMin = deviceObj.optInt("osVersionMin").takeIf { it > 0 },
                    osVersionMax = deviceObj.optInt("osVersionMax").takeIf { it > 0 },
                    appVersion = deviceObj.optJSONArray("appVersion")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    }
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
                        attributes = attributes,
                        geo = geoMatch,
                        device = deviceMatch
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
    val flushWindowMinutes: Int,
    val contextSnapshot: ContextSnapshot  // NEW: Include context for attribute enrichment
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
    val attributes: Map<String, Condition>,
    val geo: GeoMatch? = null,  // NEW: Geo-based matching
    val device: DeviceMatch? = null  // NEW: Device-based matching
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
 * Geo-based match conditions (privacy-safe, coarse only).
 */
data class GeoMatch(
    val country: List<String>? = null,      // ISO 3166-1 alpha-2 (e.g., ["US", "CA"])
    val region: List<String>? = null,       // Best-effort region (e.g., ["CA", "NY"])
    val timezone: List<String>? = null,     // IANA timezone with glob support (e.g., ["America/wildcard"])
    val locale: List<String>? = null        // BCP-47 locale (e.g., ["en-US", "es-ES"])
)

/**
 * Device-based match conditions (non-PII only).
 */
data class DeviceMatch(
    val network: List<String>? = null,      // wifi/cellular/offline/unknown
    val battery: List<String>? = null,      // charging/low/normal/unknown
    val deviceClass: List<String>? = null,  // phone/tablet/unknown
    val buildChannel: List<String>? = null, // prod/beta/internal/unknown
    val osVersionMin: Int? = null,          // Minimum SDK_INT
    val osVersionMax: Int? = null,          // Maximum SDK_INT
    val appVersion: List<String>? = null    // Specific app versions (string match)
)

/**
 * Actions to take when policy matches.
 */
data class Actions(
    val flushWindowMinutes: Int
)
