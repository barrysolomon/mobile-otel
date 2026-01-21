package io.opentelemetry.android.mobile.export

import android.content.Context
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.context.ContextSnapshot
import io.opentelemetry.android.mobile.context.ContextSnapshotProvider
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter

/**
 * Wrapping exporter that enriches log records with context attributes.
 *
 * This exporter wraps another exporter and optionally adds geo/device context
 * attributes to log records before exporting them.
 *
 * **Usage**:
 * ```kotlin
 * val baseExporter = OtlpGrpcLogRecordExporter.builder()
 *     .setEndpoint(config.collectorEndpoint)
 *     .build()
 *
 * val enrichingExporter = EnrichingLogRecordExporter(
 *     context = androidContext,
 *     config = config,
 *     delegate = baseExporter
 * )
 * ```
 *
 * **Attribute Enrichment**:
 * - Only happens if `config.attachContextAttributes == true`
 * - Adds `geo.*`, `device.*`, `app.*` attributes
 * - Preserves all original attributes
 *
 * @property context Android application context
 * @property config Mobile configuration
 * @property delegate Underlying exporter to forward records to
 */
class EnrichingLogRecordExporter(
    private val context: Context,
    private val config: MobileConfig,
    private val delegate: LogRecordExporter
) : LogRecordExporter {

    private var policyMatchId: String? = null

    /**
     * Sets the policy match ID for the next export batch.
     *
     * This is called by MobileLogRecordProcessor when a policy triggers a flush.
     */
    fun setPolicyMatch(policyId: String?) {
        this.policyMatchId = policyId
    }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        // If enrichment is disabled and no policy match, just delegate
        if (!config.attachContextAttributes && policyMatchId == null) {
            return delegate.export(logs)
        }

        // Get current context snapshot
        val contextSnapshot = ContextSnapshotProvider.getSnapshot(context, config)

        // Enrich log records
        val enrichedLogs = logs.map { log ->
            enrichLogRecord(log, contextSnapshot, policyMatchId)
        }

        // Reset policy match for next batch
        val currentPolicyId = policyMatchId
        policyMatchId = null

        // Export enriched records
        return delegate.export(enrichedLogs)
    }

    /**
     * Enriches a single log record with context attributes.
     */
    private fun enrichLogRecord(
        log: LogRecordData,
        context: ContextSnapshot,
        policyId: String?
    ): LogRecordData {
        val enrichedAttributes = AttributeEnricher.enrich(
            original = log.attributes,
            context = context,
            config = config,
            policyId = policyId
        )

        // Create new LogRecordData with enriched attributes
        return LogRecordDataImpl(
            resource = log.resource,
            instrumentationScopeInfo = log.instrumentationScopeInfo,
            timestampEpochNanos = log.timestampEpochNanos,
            observedTimestampEpochNanos = log.observedTimestampEpochNanos,
            spanContext = log.spanContext,
            severity = log.severity,
            severityText = log.severityText,
            body = log.body,
            attributes = enrichedAttributes,
            totalAttributeCount = enrichedAttributes.size()
        )
    }

    override fun flush(): CompletableResultCode {
        return delegate.flush()
    }

    override fun shutdown(): CompletableResultCode {
        return delegate.shutdown()
    }
}

/**
 * Implementation of LogRecordData with enriched attributes.
 *
 * This is a simple data class that implements LogRecordData to hold enriched log records.
 */
private data class LogRecordDataImpl(
    private val resource: io.opentelemetry.sdk.resources.Resource,
    private val instrumentationScopeInfo: io.opentelemetry.sdk.common.InstrumentationScopeInfo,
    private val timestampEpochNanos: Long,
    private val observedTimestampEpochNanos: Long,
    private val spanContext: io.opentelemetry.api.trace.SpanContext,
    private val severity: io.opentelemetry.api.logs.Severity,
    private val severityText: String?,
    private val body: Body,
    private val attributes: io.opentelemetry.api.common.Attributes,
    private val totalAttributeCount: Int
) : LogRecordData {
    override fun getResource() = resource
    override fun getInstrumentationScopeInfo() = instrumentationScopeInfo
    override fun getTimestampEpochNanos() = timestampEpochNanos
    override fun getObservedTimestampEpochNanos() = observedTimestampEpochNanos
    override fun getSpanContext() = spanContext
    override fun getSeverity() = severity
    override fun getSeverityText() = severityText
    override fun getBody() = body
    override fun getAttributes() = attributes
    override fun getTotalAttributeCount() = totalAttributeCount
}
