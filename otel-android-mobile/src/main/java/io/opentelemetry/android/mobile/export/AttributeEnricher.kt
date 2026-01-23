package io.opentelemetry.android.mobile.export

import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.context.ContextSnapshot
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.sdk.logs.data.LogRecordData

/**
 * Enriches log records with context attributes.
 *
 * When `config.attachContextAttributes` is enabled, this adds geo and device
 * context attributes to exported log records.
 *
 * **Attribute Namespace**:
 * All added attributes use namespaced keys to avoid collisions:
 * - `geo.*` - Geographic context (country, timezone, etc.)
 * - `device.*` - Device context (network, battery, etc.)
 * - `app.*` - Application context (version, build channel)
 * - `policy.*` - Policy matching metadata
 *
 * **Privacy**: All attributes are non-PII, coarse location only.
 */
object AttributeEnricher {

    /**
     * Creates an enriched attributes object with context data.
     *
     * Adds context attributes to the original log record attributes if enabled.
     *
     * @param original Original attributes from log record
     * @param context Device/geo context snapshot
     * @param config Mobile configuration
     * @param policyId Optional policy ID if policy matched
     * @return Enriched attributes
     */
    fun enrich(
        original: Attributes,
        context: ContextSnapshot,
        config: MobileConfig,
        policyId: String? = null
    ): Attributes {
        if (!config.attachContextAttributes && policyId == null) {
            return original  // No enrichment needed
        }

        val builder = Attributes.builder()

        // Copy original attributes
        original.forEach { key, value ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is String -> builder.put(key as AttributeKey<String>, value)
                is Long -> builder.put(key as AttributeKey<Long>, value)
                is Double -> builder.put(key as AttributeKey<Double>, value)
                is Boolean -> builder.put(key as AttributeKey<Boolean>, value)
                is List<*> -> {
                    // Handle array types
                    if (value.isNotEmpty()) {
                        when (value.first()) {
                            is String -> builder.put(key as AttributeKey<List<String>>, value as List<String>)
                            is Long -> builder.put(key as AttributeKey<List<Long>>, value as List<Long>)
                            is Double -> builder.put(key as AttributeKey<List<Double>>, value as List<Double>)
                            is Boolean -> builder.put(key as AttributeKey<List<Boolean>>, value as List<Boolean>)
                        }
                    }
                }
            }
        }

        // Add context attributes if enabled
        if (config.attachContextAttributes) {
            addContextAttributes(builder, context)
        }

        // Add policy match metadata if policy triggered
        if (policyId != null) {
            builder.put("policy.match_id", policyId)
            builder.put("policy.matched", true)
        }

        return builder.build()
    }

    /**
     * Adds context attributes to the builder.
     */
    private fun addContextAttributes(builder: AttributesBuilder, context: ContextSnapshot) {
        // Geo attributes (coarse, privacy-safe)
        builder.put("geo.country", context.country)
        if (context.region != null) {
            builder.put("geo.region", context.region)
        }
        builder.put("geo.timezone", context.timezone)
        builder.put("device.locale", context.locale)

        // Device attributes (non-PII)
        builder.put("app.version", context.appVersion)
        builder.put("os.version", context.osVersion.toLong())
        builder.put("device.class", context.deviceClass)
        builder.put("device.network", context.networkType)
        builder.put("device.battery", context.batteryState)
        builder.put("app.build_channel", context.buildChannel)

        // User demographics (if available)
        if (context.deviceType != null) {
            builder.put("device.type", context.deviceType)
        }
        if (context.userRegion != null) {
            builder.put("user.region", context.userRegion)
        }
        if (context.ageGroup != null) {
            builder.put("user.age_group", context.ageGroup)
        }
        if (context.tier != null) {
            builder.put("user.tier", context.tier)
        }
    }
}
