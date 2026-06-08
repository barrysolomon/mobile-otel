/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import android.content.Context
import android.util.Log
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.instrumentation.Incubating
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
@Incubating
class PolicyEvaluator(
    private val context: Context,
    private val config: MobileConfig,
    private val collectorEndpoint: String = config.collectorEndpoint,
    private val configPollIntervalSeconds: Long = config.configPollIntervalSeconds,
    httpClient: OkHttpClient? = null,
) {
    private val TAG = "PolicyEvaluator"

    // Regex cache to avoid recompilation on every evaluation. LRU-bounded to prevent unbounded growth.
    private val regexCache = object : LinkedHashMap<String, Regex?>(MAX_REGEX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>): Boolean =
            size > MAX_REGEX_CACHE
    }

    // SR-008: prefer an injected OkHttpClient so callers can share the SDK's
    // connection pool / dispatcher across components. Construct a private one
    // only when nothing was injected (preserves the old default behaviour for
    // existing call sites).
    private val httpClient: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @androidx.annotation.VisibleForTesting
    internal fun getHttpClientForTest(): OkHttpClient = this.httpClient

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
            ),
            Policy(
                id = "http-error-detector",
                enabled = true,
                match = Match(
                    logicalOperator = "and",
                    attributes = mapOf("event.name" to Condition(equals = "http.error"))
                ),
                actions = Actions(flushWindowMinutes = 2)
            )
        )
    )

    // Coroutine scope for background tasks
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        if (config.remoteConfigEnabled) {
            // Initial config fetch
            fetchConfig()

            // Schedule periodic config refresh
            scope.launch {
                while (isActive) {
                    delay(configPollIntervalSeconds * 1000)
                    fetchConfig()
                }
            }
        } else {
            Log.i(TAG, "Remote policy config polling disabled; using built-in default policies")
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
        // 1. Check attribute conditions.
        // A policy with NO attribute constraints AND no geo/device constraints would match every
        // single log record and trigger a flush on each one.  Require at least one constraint
        // dimension to be non-empty; otherwise treat the policy as inactive (no match).
        val hasAnyConstraint = policy.match.attributes.isNotEmpty() ||
            policy.match.geo != null ||
            policy.match.device != null
        if (!hasAnyConstraint) {
            Log.w(TAG, "Policy '${policy.id}' has no match constraints — skipping to avoid matching every event")
            return false
        }

        val attributeMatch = if (policy.match.attributes.isNotEmpty()) {
            val conditions = policy.match.attributes.map { (attrKey, condition) ->
                val attrValue = getAttributeValue(logRecord, attrKey)
                matchesCondition(attrValue, condition)
            }
            conditions.all { it }  // Attributes always use AND logic internally
        } else {
            true  // No attribute constraints but geo/device constraints exist — attribute side passes
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
     * Tries all 4 AttributeKey types (string, long, double, boolean) to support
     * numeric conditions from v2 matchers (SR-018).
     */
    private fun getAttributeValue(logRecord: LogRecordData, key: String): Any? {
        // Try attributes first (all 4 types), then fall back to built-in fields
        val fromAttrs = logRecord.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(key))
            ?: logRecord.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey(key))
            ?: logRecord.attributes.get(io.opentelemetry.api.common.AttributeKey.doubleKey(key))
            ?: logRecord.attributes.get(io.opentelemetry.api.common.AttributeKey.booleanKey(key))
        if (fromAttrs != null) return fromAttrs

        // Built-in LogRecordData fields
        return when (key) {
            "event.name", "body" -> logRecord.body.asString()
            "severity" -> logRecord.severity.name
            else -> null
        }
    }

    /**
     * Checks if a value matches a condition.
     */
    private fun matchesCondition(value: Any?, condition: Condition): Boolean {
        if (value == null) return false

        return when {
            condition.equals != null -> value.toString() == condition.equals
            condition.notEquals != null -> value.toString() != condition.notEquals
            condition.gt != null -> (value as? Number)?.toDouble()?.let { it > condition.gt } ?: false
            condition.lt != null -> (value as? Number)?.toDouble()?.let { it < condition.lt } ?: false
            condition.gte != null -> (value as? Number)?.toDouble()?.let { it >= condition.gte } ?: false
            condition.lte != null -> (value as? Number)?.toDouble()?.let { it <= condition.lte } ?: false
            condition.contains != null -> value.toString().contains(condition.contains)
            condition.regex != null -> matchRegexSafe(value.toString(), condition.regex)
            else -> false
        }
    }

    /**
     * Safely matches a value against a regex pattern with ReDoS protection.
     *
     * - Rejects patterns longer than MAX_REGEX_LENGTH to limit complexity
     * - Caches compiled Regex objects to avoid repeated compilation
     * - Catches PatternSyntaxException for malformed patterns
     */
    private fun matchRegexSafe(value: String, pattern: String): Boolean {
        if (pattern.length > MAX_REGEX_LENGTH) {
            Log.w(TAG, "Regex pattern too long (${pattern.length} > $MAX_REGEX_LENGTH), rejecting")
            return false
        }

        val regex = synchronized(regexCache) {
            regexCache.getOrPut(pattern) {
                try {
                    Regex(pattern)
                } catch (e: java.util.regex.PatternSyntaxException) {
                    Log.w(TAG, "Invalid regex pattern: $pattern", e)
                    null
                }
            }
        } ?: return false

        return try {
            value.matches(regex)
        } catch (e: Exception) {
            Log.w(TAG, "Regex evaluation failed for pattern: $pattern", e)
            false
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
     * Requests DSL v2 (state-machine format) by default, with automatic
     * fallback to v1 or legacy format via [parseConfigAny].
     */
    private fun fetchConfig() {
        scope.launch {
            try {
                val configUrl = "${collectorEndpoint.removeSuffix("/")}/config?dsl_version=2"
                val request = Request.Builder()
                    .url(configUrl)
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val config = parseConfigAny(body)
                        if (config != null) {
                            policyConfig.set(config)
                            Log.i(TAG, "Fetched policy config: ${config.policies.size} policies")
                        } else {
                            Log.w(TAG, "Failed to parse config response")
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to fetch config: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching config", e)
            }
        }
    }

    // Legacy parseConfig (nodes.trigger/action format) removed 2026-04-14.
    // Replaced by companion parseConfigV1Compiler/parseConfigV2/parseConfigAny.

    /**
     * Shuts down the evaluator and releases resources.
     */
    fun shutdown() {
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    companion object {
        private const val TAG_STATIC = "PolicyEvaluator"

        /** Maximum number of policies allowed from remote config. */
        internal const val MAX_POLICIES = 100
        /** Maximum number of attribute conditions per policy. */
        internal const val MAX_CONDITIONS_PER_POLICY = 50
        /** Maximum regex pattern length to prevent ReDoS. */
        internal const val MAX_REGEX_LENGTH = 200
        /** Maximum number of cached compiled regex patterns. */
        private const val MAX_REGEX_CACHE = 64
        /** Minimum flush window in minutes. */
        internal const val MIN_FLUSH_WINDOW_MINUTES = 1
        /** Maximum flush window in minutes (24 hours). */
        internal const val MAX_FLUSH_WINDOW_MINUTES = 1440

        /**
         * Auto-detect config version and parse accordingly.
         * Supports: v2 (FSM), v1 compiler output (trigger/actions), legacy (nodes.trigger/action).
         */
        fun parseConfigAny(jsonString: String): PolicyConfig? {
            return try {
                val root = JSONObject(jsonString)
                val version = root.optInt("version", 0)

                when {
                    version == 2 -> parseConfigV2(jsonString)
                    else -> parseConfigV1Compiler(jsonString)
                }
            } catch (e: Exception) {
                Log.e(TAG_STATIC, "Failed to auto-detect config format", e)
                null
            }
        }

        /**
         * Parse DSL v2 (state-machine format) into PolicyConfig.
         * Maps v2 matcher types to attribute-based conditions that the existing
         * evaluation engine understands.
         */
        fun parseConfigV2(jsonString: String): PolicyConfig? {
            return try {
                val root = JSONObject(jsonString)
                val version = root.optInt("version", 1)
                if (version != 2) return null

                val workflowsArray = root.optJSONArray("workflows")
                    ?: return PolicyConfig(emptyList())
                val policies = mutableListOf<Policy>()

                for (i in 0 until minOf(workflowsArray.length(), MAX_POLICIES)) {
                    val workflow = workflowsArray.getJSONObject(i)
                    val workflowId = workflow.optString("id", "workflow-$i")
                    val enabled = workflow.optBoolean("enabled", true)
                    val states = workflow.optJSONArray("states") ?: continue

                    for (s in 0 until states.length()) {
                        val state = states.getJSONObject(s)
                        val stateId = state.optString("id", "state-$s")
                        val matchers = state.optJSONArray("matchers") ?: continue
                        val onMatch = state.optJSONObject("on_match") ?: continue
                        val actions = onMatch.optJSONArray("actions") ?: continue

                        val flushMinutes = extractFlushMinutesV2(actions)

                        for (m in 0 until matchers.length()) {
                            val matcher = matchers.getJSONObject(m)
                            val match = matcherToMatch(matcher)
                            if (match != null) {
                                val policyId = if (matchers.length() == 1 && states.length() == 1)
                                    workflowId
                                else "$workflowId/$stateId/$m"
                                policies.add(Policy(
                                    id = policyId,
                                    enabled = enabled,
                                    match = match,
                                    actions = Actions(flushWindowMinutes = flushMinutes)
                                ))
                            }
                        }
                    }
                }

                PolicyConfig(policies)
            } catch (e: Exception) {
                Log.e(TAG_STATIC, "Failed to parse v2 config", e)
                null
            }
        }

        /**
         * Parse DSL v1 compiler output format:
         * {version: 1, workflows: [{id, enabled, trigger: {any/all: [{event, where?}]}, actions: [{type, minutes}]}]}
         */
        fun parseConfigV1Compiler(jsonString: String): PolicyConfig? {
            return try {
                val root = JSONObject(jsonString)
                val workflowsArray = root.optJSONArray("workflows")
                    ?: return PolicyConfig(emptyList())
                val policies = mutableListOf<Policy>()

                for (i in 0 until minOf(workflowsArray.length(), MAX_POLICIES)) {
                    val workflow = workflowsArray.getJSONObject(i)
                    val id = workflow.optString("id", "workflow-$i")
                    val enabled = workflow.optBoolean("enabled", true)
                    val trigger = workflow.optJSONObject("trigger") ?: continue
                    val actionsArray = workflow.optJSONArray("actions") ?: continue

                    // Extract flush window from actions array
                    var flushMinutes = 2
                    for (a in 0 until actionsArray.length()) {
                        val action = actionsArray.getJSONObject(a)
                        if (action.optString("type") == "flush_window") {
                            flushMinutes = action.optInt("minutes", 2)
                                .coerceIn(MIN_FLUSH_WINDOW_MINUTES, MAX_FLUSH_WINDOW_MINUTES)
                            break
                        }
                    }

                    // Parse trigger conditions (any → or, all → and)
                    val hasAll = trigger.has("all")
                    val conditionsKey = if (hasAll) "all" else "any"
                    val conditions = trigger.optJSONArray(conditionsKey) ?: continue

                    for (c in 0 until conditions.length()) {
                        val cond = conditions.getJSONObject(c)
                        val eventName = cond.optString("event", "")
                        val attributes = mutableMapOf<String, Condition>()

                        if (eventName.isNotEmpty()) {
                            attributes["event.name"] = Condition(equals = eventName)
                        }

                        // Parse where clauses into additional attribute conditions
                        val where = cond.optJSONArray("where")
                        if (where != null) {
                            for (w in 0 until minOf(where.length(), MAX_CONDITIONS_PER_POLICY)) {
                                val pred = where.getJSONObject(w)
                                val attr = pred.optString("attr", "")
                                val op = pred.optString("op", "==")
                                val value = pred.opt("value")
                                if (attr.isNotEmpty() && value != null) {
                                    attributes[attr] = predicateToCondition(op, value)
                                }
                            }
                        }

                        if (attributes.isNotEmpty()) {
                            val policyId = if (conditions.length() == 1) id else "$id/$c"
                            policies.add(Policy(
                                id = policyId,
                                enabled = enabled,
                                match = Match(logicalOperator = "and", attributes = attributes),
                                actions = Actions(flushWindowMinutes = flushMinutes)
                            ))
                        }
                    }
                }

                PolicyConfig(policies)
            } catch (e: Exception) {
                Log.e(TAG_STATIC, "Failed to parse v1 compiler config", e)
                null
            }
        }

        /**
         * Map a v2 matcher type to the internal Match model.
         * Translates typed matchers (crash, ui_freeze, etc.) into attribute conditions.
         */
        private fun matcherToMatch(matcher: JSONObject): Match? {
            val type = matcher.optString("type", "")
            val config = matcher.optJSONObject("config") ?: JSONObject()
            val where = matcher.optJSONArray("where")

            val attributes = mutableMapOf<String, Condition>()

            when (type) {
                "crash" -> attributes["event.name"] = Condition(equals = "app.crash")
                "ui_freeze" -> {
                    attributes["event.name"] = Condition(equals = "ui.freeze")
                    val durationMs = config.optDouble("duration_ms", 0.0)
                    if (durationMs > 0) attributes["duration_ms"] = Condition(gt = durationMs)
                }
                "event_match" -> {
                    val eventName = config.optString("event_name", "")
                    if (eventName.isNotEmpty()) attributes["event.name"] = Condition(equals = eventName)
                }
                "log_severity" -> {
                    // Map min_severity to a numeric gte comparison for proper "at or above" semantics
                    val minSeverity = config.optString("min_severity", "")
                    if (minSeverity.isNotEmpty()) {
                        val severityLevel = severityToLevel(minSeverity)
                        if (severityLevel > 0) attributes["severity_number"] = Condition(gte = severityLevel.toDouble())
                        else attributes["severity"] = Condition(equals = minSeverity)
                    }
                    val bodyContains = config.optString("body_contains", "")
                    if (bodyContains.isNotEmpty()) attributes["body"] = Condition(contains = bodyContains)
                }
                "http_match" -> {
                    attributes["event.name"] = Condition(equals = "http.error")
                    val statusMin = config.optInt("status_min", 0)
                    if (statusMin > 0) attributes["http.status_code"] = Condition(gte = statusMin.toDouble())
                }
                "exception_pattern" -> {
                    attributes["event.name"] = Condition(equals = "app.crash")
                    val exType = config.optString("exception_type", "")
                    if (exType.isNotEmpty()) attributes["exception.type"] = Condition(contains = exType)
                    val msgPattern = config.optString("message_pattern", "")
                    if (msgPattern.isNotEmpty()) attributes["exception.message"] = Condition(regex = msgPattern)
                }
                "metric_threshold" -> {
                    val metricName = config.optString("metric_name", "")
                    if (metricName.isNotEmpty()) attributes["event.name"] = Condition(equals = metricName)
                    val op = config.optString("operator", "gt")
                    val threshold = config.optDouble("threshold", Double.NaN)
                    if (!threshold.isNaN()) {
                        attributes["value"] = when (op) {
                            "gt" -> Condition(gt = threshold)
                            "lt" -> Condition(lt = threshold)
                            "gte" -> Condition(gte = threshold)
                            "lte" -> Condition(lte = threshold)
                            else -> Condition(gt = threshold)
                        }
                    }
                }
                "slow_operation" -> {
                    val opName = config.optString("operation_name", "")
                    if (opName.isNotEmpty()) attributes["event.name"] = Condition(equals = opName)
                    val thresholdMs = config.optDouble("threshold_ms", 0.0)
                    if (thresholdMs > 0) attributes["duration_ms"] = Condition(gt = thresholdMs)
                }
                "frame_drop" -> {
                    attributes["event.name"] = Condition(equals = "ui.jank")
                    val dropped = config.optDouble("dropped_frames", 0.0)
                    if (dropped > 0) attributes["dropped_frames"] = Condition(gt = dropped)
                }
                "network_loss" -> attributes["event.name"] = Condition(equals = "network.loss")
                "network_restored" -> attributes["event.name"] = Condition(equals = "network.restored")
                "slow_request" -> {
                    attributes["event.name"] = Condition(equals = "http.request")
                    val thresholdMs = config.optDouble("threshold_ms", 0.0)
                    if (thresholdMs > 0) attributes["duration_ms"] = Condition(gt = thresholdMs)
                }
                "low_memory" -> {
                    attributes["event.name"] = Condition(equals = "device.low_memory")
                    val availMb = config.optDouble("available_mb", 0.0)
                    if (availMb > 0) attributes["available_mb"] = Condition(lt = availMb)
                }
                "battery_drain" -> {
                    attributes["event.name"] = Condition(equals = "device.battery_drain")
                    val rate = config.optDouble("drain_rate_perc_per_min", 0.0)
                    if (rate > 0) attributes["drain_rate"] = Condition(gt = rate)
                }
                "thermal_throttle" -> attributes["event.name"] = Condition(equals = "device.thermal_throttle")
                "storage_low" -> {
                    attributes["event.name"] = Condition(equals = "device.storage_low")
                    val availMb = config.optDouble("available_mb", 0.0)
                    if (availMb > 0) attributes["available_mb"] = Condition(lt = availMb)
                }
                "predictive_risk" -> {
                    attributes["event.name"] = Condition(equals = "prediction.high_risk_alert")
                    val minScore = config.optDouble("min_score", 0.0)
                    if (minScore > 0) attributes["risk_score"] = Condition(gte = minScore)
                }
                "anr" -> attributes["event.name"] = Condition(equals = "app.anr")
                "app_lifecycle" -> {
                    val event = config.optString("event", "")
                    attributes["event.name"] = Condition(equals = if (event.isNotEmpty()) event else "app.lifecycle")
                }
                "resource_snapshot" -> {
                    val metricName = config.optString("metric_name", "")
                    if (metricName.isNotEmpty()) attributes["event.name"] = Condition(equals = metricName)
                    else attributes["event.name"] = Condition(equals = "resource.snapshot")
                }
                "field_presence" -> {
                    val field = config.optString("field", "")
                    if (field.isNotEmpty()) attributes[field] = Condition(regex = ".+")
                }
                "field_absence" -> {
                    // Field absence can't be expressed as a positive match — skip
                    // The policy will rely on other matchers in the same state
                    return null
                }
                "timeout" -> return null // State-machine transition, not a flush trigger
                else -> {
                    Log.w(TAG_STATIC, "Unknown v2 matcher type: $type, using as event name")
                    attributes["event.name"] = Condition(equals = type)
                }
            }

            // Apply where-clause predicates
            if (where != null) {
                for (w in 0 until minOf(where.length(), MAX_CONDITIONS_PER_POLICY)) {
                    val predicate = where.getJSONObject(w)
                    val attr = predicate.optString("attr", "")
                    val op = predicate.optString("op", "==")
                    val value = predicate.opt("value")
                    if (attr.isNotEmpty() && value != null) {
                        attributes[attr] = predicateToCondition(op, value)
                    }
                }
            }

            if (attributes.isEmpty()) return null

            return Match(logicalOperator = "and", attributes = attributes)
        }

        /** Map OTel severity name to numeric level for gte comparison. */
        private fun severityToLevel(name: String): Int = when (name.uppercase()) {
            "TRACE" -> 1; "DEBUG" -> 5; "INFO" -> 9
            "WARN" -> 13; "ERROR" -> 17; "FATAL" -> 21
            else -> 0
        }

        /** Convert a where-clause predicate {op, value} into a Condition. */
        private fun predicateToCondition(op: String, value: Any): Condition {
            val numValue = (value as? Number)?.toDouble()
            val strValue = value.toString()

            return when (op) {
                "==", "equals" -> Condition(equals = strValue)
                "!=", "not_equals" -> Condition(notEquals = strValue)
                ">", "gt" -> Condition(gt = numValue)
                "<", "lt" -> Condition(lt = numValue)
                ">=", "gte" -> Condition(gte = numValue)
                "<=", "lte" -> Condition(lte = numValue)
                "contains" -> Condition(contains = strValue)
                "regex" -> Condition(regex = strValue)
                else -> Condition(equals = strValue)
            }
        }

        /** Extract flush_buffer minutes from v2 actions array. Default 2 min. */
        private fun extractFlushMinutesV2(actions: JSONArray): Int {
            for (a in 0 until actions.length()) {
                val action = actions.getJSONObject(a)
                if (action.optString("type") == "flush_buffer") {
                    val actionConfig = action.optJSONObject("config") ?: continue
                    val minutes = actionConfig.optInt("minutes", 2)
                    return minutes.coerceIn(MIN_FLUSH_WINDOW_MINUTES, MAX_FLUSH_WINDOW_MINUTES)
                }
            }
            return 2 // default
        }
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
    val notEquals: String? = null,
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
