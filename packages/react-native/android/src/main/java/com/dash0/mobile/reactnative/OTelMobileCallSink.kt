/*
 * Production BridgeCallSink. Forwards RN bridge calls into the existing
 * io.opentelemetry.android.mobile.OTelMobile SDK.
 *
 * Kept in a separate file from Dash0MobileModule so the module's unit tests
 * can run under pure JVM (JUnit) without needing to stand up OTelMobile.
 */
package com.dash0.mobile.reactnative

import android.content.Context
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.sampling.SamplingConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode

internal class OTelMobileCallSink(
    private val appContext: Context,
) : BridgeCallSink {

    private val liveSpans = HashMap<String, io.opentelemetry.api.trace.Span>()

    // Async gauges must be registered exactly ONCE per metric name. Calling
    // buildWithCallback on every event leaks a new never-closed observable +
    // a retained closure each time. We register one observable gauge per name
    // and have its callback report the latest value/attributes stored here.
    private val gaugeRegistered = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val gaugeLatest =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Attributes>>()

    override fun start(config: StartConfig) {
        val app = appContext.applicationContext as android.app.Application
        // Build optional auth + dataset headers. Dash0's OTLP/HTTP ingress
        // requires Bearer auth + the Dash0-Dataset routing header.
        val headers = buildMap<String, String> {
            config.authToken?.takeIf { it.isNotBlank() }?.let {
                put("Authorization", "Bearer $it")
            }
            config.dataset?.takeIf { it.isNotBlank() }?.let {
                put("Dash0-Dataset", it)
            }
        }.takeIf { it.isNotEmpty() }

        OTelMobile.start(
            app,
            MobileConfig(
                serviceName = config.serviceName,
                serviceVersion = config.serviceVersion ?: "unknown",
                collectorEndpoint = config.endpoint,
                headers = headers,
                // RN consumers don't have the same battery concerns as native —
                // default to CONTINUOUS so traces + metrics flow in reasonable
                // time. CONDITIONAL buffers spans for up to 1 hour, which is
                // surprising behavior for a JS dev who just called startSpan().
                exportMode = io.opentelemetry.android.mobile.config.ExportMode.CONTINUOUS,
                // RN sampling default is ALWAYS_ON (Loper finding #4): RN manual
                // spans are root spans with arbitrary names, so the native SDK's
                // dynamic(0.1) default would silently drop ~90% of a user's first
                // span. The JS bridge sends always_on unless the caller opts into
                // sampling; rate-limiting for RN belongs in the collector.
                samplingConfig = samplingConfigOf(config.sampling),
                extraResourceAttributes = config.extraResourceAttributes,
            ),
        )
    }

    private fun samplingConfigOf(sampling: BridgeSamplingConfig?): SamplingConfig =
        when (sampling?.strategy) {
            null,
            SamplingStrategy.ALWAYS_ON -> SamplingConfig.alwaysOn()
            SamplingStrategy.ALWAYS_OFF -> SamplingConfig.alwaysOff()
            SamplingStrategy.DYNAMIC -> SamplingConfig.dynamic(
                normalRate = sampling.normalRate ?: 0.05,
                highPriorityRate = sampling.highPriorityRate ?: 1.0,
            )
        }

    override fun emitLog(
        name: String,
        severity: Int,
        attributes: Map<String, Any?>,
        timeUnixNano: Long,
    ) {
        OTelMobile.getLogger(SCOPE)
            .logRecordBuilder()
            .setBody(name)
            .setSeverity(severityOf(severity))
            .setAllAttributes(attributes.toOtelAttributes())
            .emit()
    }

    override fun startSpan(
        spanId: String,
        parentSpanId: String?,
        name: String,
        spanKind: String,
        attributes: Map<String, Any?>,
        startTimeUnixNano: Long,
    ) {
        val parent = if (parentSpanId != null) synchronized(liveSpans) { liveSpans[parentSpanId] } else null
        val builder = OTelMobile.getTracer(SCOPE)
            .spanBuilder(name)
            .setSpanKind(spanKindOf(spanKind))
            .setAllAttributes(attributes.toOtelAttributes())
        // Propagate parent context so Dash0 renders the waterfall instead
        // of 14 orphan spans per checkout. `setParent` with an active span
        // is Context.current()-free and works regardless of thread.
        if (parent != null) {
            builder.setParent(io.opentelemetry.context.Context.current().with(parent))
        }
        val span = builder.startSpan()
        synchronized(liveSpans) { liveSpans[spanId] = span }
    }

    override fun endSpan(
        spanId: String,
        status: String,
        statusMessage: String?,
        attributes: Map<String, Any?>,
        endTimeUnixNano: Long,
    ) {
        val span = synchronized(liveSpans) { liveSpans.remove(spanId) } ?: return
        span.setAllAttributes(attributes.toOtelAttributes())
        when (status) {
            "OK" -> span.setStatus(StatusCode.OK)
            "ERROR" -> span.setStatus(StatusCode.ERROR, statusMessage ?: "")
            else -> Unit
        }
        span.end()
    }

    override fun recordMetric(
        name: String,
        instrumentType: String,
        value: Double,
        attributes: Map<String, Any?>,
        timeUnixNano: Long,
    ) {
        val meter = OTelMobile.getMeter(SCOPE)
        val otelAttrs = attributes.toOtelAttributes()
        when (instrumentType) {
            "counter" -> meter.counterBuilder(name).build().add(value.toLong(), otelAttrs)
            "histogram" -> meter.histogramBuilder(name).build().record(value, otelAttrs)
            "gauge" -> {
                // Stash the latest reading, then register the observable gauge
                // ONCE per name. The callback re-reads from gaugeLatest on each
                // collection so we don't leak a closure/instrument per event.
                gaugeLatest[name] = value to otelAttrs
                gaugeRegistered.computeIfAbsent(name) { gaugeName ->
                    meter.gaugeBuilder(gaugeName).buildWithCallback { obs ->
                        gaugeLatest[gaugeName]?.let { (v, a) -> obs.record(v, a) }
                    }
                    true
                }
            }
            else -> Unit
        }
    }

    override fun flushWindow(minutes: Int) {
        try {
            OTelMobile.getLoggerProvider().getMobileProcessor().flushWindow(minutes)
        } catch (_: Throwable) {
            // Bridge contract: flush failures must not surface as JS promise
            // rejections. The SDK's own retry/disk-buffer path handles
            // recovery; the RN caller just wants best-effort flush.
        }
    }

    override fun shutdown() {
        OTelMobile.stop()
    }

    /**
     * Synchronous drain through the OTLP exporter, persisting any
     * in-flight records to disk on export failure. Invoked by
     * [Dash0MobileModule.dispatch] after a FATAL-severity (severity ≥ 21)
     * log emit so the crash payload lands in Dash0 even when the JS
     * fatal reporter terminates the process before the periodic flush
     * timer fires.
     *
     * Mirrors iOS commit `39bd258` (library-level FATAL forceFlush).
     */
    override fun forceFlush() {
        try {
            OTelMobile.getLoggerProvider().getMobileProcessor().forceFlush()
        } catch (_: Throwable) {
            // Best-effort: a forceFlush failure on the FATAL path must
            // never throw out of the dispatcher (we'd lose the rest of
            // the batch). The SDK's own retry/disk-buffer handles any
            // export failure under the hood.
        }
    }

    companion object {
        private const val SCOPE = "io.dash0.mobile.reactnative"

        private fun severityOf(n: Int): Severity = when (n) {
            1 -> Severity.TRACE
            5 -> Severity.DEBUG
            9 -> Severity.INFO
            13 -> Severity.WARN
            17 -> Severity.ERROR
            21 -> Severity.FATAL
            else -> Severity.INFO
        }

        private fun spanKindOf(s: String): SpanKind = when (s) {
            "CLIENT" -> SpanKind.CLIENT
            "SERVER" -> SpanKind.SERVER
            "PRODUCER" -> SpanKind.PRODUCER
            "CONSUMER" -> SpanKind.CONSUMER
            else -> SpanKind.INTERNAL
        }

        private fun Map<String, Any?>.toOtelAttributes(): Attributes {
            val b: AttributesBuilder = Attributes.builder()
            for ((k, v) in this) {
                when (v) {
                    is String -> b.put(AttributeKey.stringKey(k), v)
                    is Boolean -> b.put(AttributeKey.booleanKey(k), v)
                    is Double -> {
                        // Route integer-valued doubles to long keys so downstream
                        // consumers see `qty=2`, not `qty=2.0`. OTel attribute
                        // conventions use long for whole-number counts.
                        if (v == v.toLong().toDouble()) {
                            b.put(AttributeKey.longKey(k), v.toLong())
                        } else {
                            b.put(AttributeKey.doubleKey(k), v)
                        }
                    }
                    is Long -> b.put(AttributeKey.longKey(k), v)
                    is Int -> b.put(AttributeKey.longKey(k), v.toLong())
                    null -> Unit
                    else -> b.put(AttributeKey.stringKey(k), v.toString())
                }
            }
            return b.build()
        }
    }
}
