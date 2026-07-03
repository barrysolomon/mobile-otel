/*
 * RN-012 unit tests for Dash0MobileModule.
 *
 * These tests feed ReadableMap/ReadableArray fixtures into the module and
 * assert the corresponding BridgeCallSink methods are called with the right
 * arguments. A fake sink is used so we don't need the real OTelMobile SDK,
 * an Android emulator, or any RN runtime.
 *
 * The cross-bridge contract is the only thing under test here — the OTel
 * wiring is verified separately in the Android SDK's own test suite.
 */
package com.dash0.mobile.reactnative

import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class Dash0MobileModuleTest {

    private lateinit var sink: RecordingSink
    private lateinit var module: Dash0MobileModule

    @Before
    fun setUp() {
        sink = RecordingSink()
        val ctx = mock(ReactApplicationContext::class.java)
        module = Dash0MobileModule(ctx, sink)
    }

    // ── start ────────────────────────────────────────────────────────────

    @Test
    fun start_forwards_required_and_optional_fields() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "otel-rn-astronomy-shop",
            "endpoint", "https://ingress/v1/logs",
            "serviceVersion", "1.2.3",
            "authToken", "tok",
            "dataset", "otel-mobile",
        )
        val promise = RecordingPromise()

        module.start(cfg, promise)

        assertEquals(1, sink.starts.size)
        val got = sink.starts[0]
        assertEquals("otel-rn-astronomy-shop", got.serviceName)
        assertEquals("https://ingress/v1/logs", got.endpoint)
        assertEquals("1.2.3", got.serviceVersion)
        assertEquals("tok", got.authToken)
        assertEquals("otel-mobile", got.dataset)
        assertTrue(promise.resolved)
    }

    @Test
    fun start_forwards_nativeAutoCapture_tokens() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "s",
            "endpoint", "https://e",
            "nativeAutoCapture", JavaOnlyArray.of("vitals", "deviceStats"),
        )
        val promise = RecordingPromise()
        module.start(cfg, promise)
        assertEquals(listOf("vitals", "deviceStats"), sink.starts[0].nativeAutoCapture)
    }

    @Test
    fun start_defaults_nativeAutoCapture_to_empty_when_absent() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "s",
            "endpoint", "https://e",
        )
        val promise = RecordingPromise()
        module.start(cfg, promise)
        assertTrue(sink.starts[0].nativeAutoCapture.isEmpty())
    }

    // ── start: gateway / config polling (N7) ─────────────────────────────

    @Test
    fun start_forwards_gateway_and_config_polling_options() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "s",
            "endpoint", "https://ingress.example/v1/logs",
            "gatewayEndpoint", "https://gateway.example:8080",
            "enablePolicyPolling", true,
            "configPollIntervalSeconds", 60,
        )
        val promise = RecordingPromise()
        module.start(cfg, promise)
        val got = sink.starts[0]
        assertEquals("https://gateway.example:8080", got.gatewayEndpoint)
        assertEquals(true, got.enablePolicyPolling)
        assertEquals(60L, got.configPollIntervalSeconds)
    }

    @Test
    fun start_defaults_gateway_and_polling_options_to_null_when_absent() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "s",
            "endpoint", "https://e",
        )
        val promise = RecordingPromise()
        module.start(cfg, promise)
        val got = sink.starts[0]
        assertNull(got.gatewayEndpoint)
        assertNull(got.enablePolicyPolling)
        assertNull(got.configPollIntervalSeconds)
    }

    // ── start: sampling (Loper finding #4) ───────────────────────────────

    @Test
    fun start_decodes_alwaysOff_sampling() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "s",
            "endpoint", "https://e",
            "sampling", JavaOnlyMap.of("strategy", "always_off"),
        )
        module.start(cfg, RecordingPromise())
        assertEquals(
            BridgeSamplingConfig(strategy = SamplingStrategy.ALWAYS_OFF),
            sink.starts[0].sampling,
        )
    }

    @Test
    fun start_decodes_dynamic_sampling_with_rates() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "s",
            "endpoint", "https://e",
            "sampling", JavaOnlyMap.of(
                "strategy", "dynamic",
                "normalRate", 0.1,
                "highPriorityRate", 1.0,
            ),
        )
        module.start(cfg, RecordingPromise())
        assertEquals(
            BridgeSamplingConfig(
                strategy = SamplingStrategy.DYNAMIC,
                normalRate = 0.1,
                highPriorityRate = 1.0,
            ),
            sink.starts[0].sampling,
        )
    }

    @Test
    fun start_decodes_alwaysOn_sampling() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "s",
            "endpoint", "https://e",
            "sampling", JavaOnlyMap.of("strategy", "always_on"),
        )
        module.start(cfg, RecordingPromise())
        assertEquals(SamplingStrategy.ALWAYS_ON, sink.starts[0].sampling?.strategy)
    }

    @Test
    fun start_sampling_null_when_absent() {
        // The JS bridge always sends `sampling`, but a missing field decodes
        // to null (the sink then applies the RN ALWAYS_ON default).
        val cfg = JavaOnlyMap.of("serviceName", "s", "endpoint", "https://e")
        module.start(cfg, RecordingPromise())
        assertNull(sink.starts[0].sampling)
    }

    @Test
    fun start_unknown_sampling_strategy_falls_back_to_alwaysOn() {
        val cfg = JavaOnlyMap.of(
            "serviceName", "s",
            "endpoint", "https://e",
            "sampling", JavaOnlyMap.of("strategy", "martian"),
        )
        module.start(cfg, RecordingPromise())
        assertEquals(SamplingStrategy.ALWAYS_ON, sink.starts[0].sampling?.strategy)
    }

    @Test
    fun start_rejects_when_serviceName_missing() {
        val cfg = JavaOnlyMap.of("endpoint", "e")
        val promise = RecordingPromise()
        module.start(cfg, promise)
        assertTrue(promise.rejected)
        assertEquals(0, sink.starts.size)
    }

    // ── emitBatch: log ───────────────────────────────────────────────────

    @Test
    fun emitBatch_dispatches_log_payload() {
        val payload = JavaOnlyMap.of(
            "kind", "log",
            "name", "cart.add_item",
            "severity", 9.0,
            "timeUnixNano", "1713600000000000000",
            "attributes", JavaOnlyMap.of(
                "shop.item_id", "abc",
                "qty", 2.0,
                "urgent", true,
            ),
        )
        val batch = JavaOnlyArray.of(payload)
        val promise = RecordingPromise()

        module.emitBatch(batch, promise)

        assertEquals(1, sink.logs.size)
        val log = sink.logs[0]
        assertEquals("cart.add_item", log.name)
        assertEquals(9, log.severity)
        assertEquals(1713600000000000000L, log.timeUnixNano)
        assertEquals("abc", log.attributes["shop.item_id"])
        assertEquals(2.0, log.attributes["qty"] as Double, 0.0)
        assertEquals(true, log.attributes["urgent"])
        assertTrue(promise.resolved)
    }

    // ── emitBatch: span pair ─────────────────────────────────────────────

    @Test
    fun emitBatch_dispatches_spanStart_and_spanEnd_with_matching_spanId() {
        val spanStart = JavaOnlyMap.of(
            "kind", "spanStart",
            "spanId", "abc123def456789a",
            "name", "checkout",
            "spanKind", "INTERNAL",
            "startTimeUnixNano", "1713600000000000000",
            "attributes", JavaOnlyMap.of("shop.cart_size", 3.0),
        )
        val spanEnd = JavaOnlyMap.of(
            "kind", "spanEnd",
            "spanId", "abc123def456789a",
            "status", "OK",
            "endTimeUnixNano", "1713600000050000000",
            "attributes", JavaOnlyMap.of("http.response.status_code", 200.0),
        )
        val batch = JavaOnlyArray.of(spanStart, spanEnd)

        module.emitBatch(batch, RecordingPromise())

        assertEquals(1, sink.spanStarts.size)
        assertEquals(1, sink.spanEnds.size)
        assertEquals(sink.spanStarts[0].spanId, sink.spanEnds[0].spanId)
        assertEquals("checkout", sink.spanStarts[0].name)
        assertEquals("OK", sink.spanEnds[0].status)
    }

    @Test
    fun emitBatch_spanEnd_with_ERROR_carries_statusMessage() {
        val spanEnd = JavaOnlyMap.of(
            "kind", "spanEnd",
            "spanId", "aaaaaaaaaaaaaaaa",
            "status", "ERROR",
            "statusMessage", "nope",
            "endTimeUnixNano", "0",
            "attributes", JavaOnlyMap.of(),
        )
        module.emitBatch(JavaOnlyArray.of(spanEnd), RecordingPromise())
        assertEquals("ERROR", sink.spanEnds[0].status)
        assertEquals("nope", sink.spanEnds[0].statusMessage)
    }

    // ── emitBatch: metric ────────────────────────────────────────────────

    @Test
    fun emitBatch_dispatches_metric_for_counter_histogram_gauge() {
        val types = listOf("counter", "histogram", "gauge")
        val items = types.map { t ->
            JavaOnlyMap.of(
                "kind", "metric",
                "name", "shop.x",
                "instrumentType", t,
                "value", 42.5,
                "timeUnixNano", "0",
                "attributes", JavaOnlyMap.of(),
            )
        }
        val batch = JavaOnlyArray.of(*items.toTypedArray())

        module.emitBatch(batch, RecordingPromise())

        assertEquals(3, sink.metrics.size)
        assertEquals(types, sink.metrics.map { it.instrumentType })
        assertEquals(42.5, sink.metrics[0].value, 0.0)
    }

    // ── emitBatch: forward-compat ────────────────────────────────────────

    @Test
    fun emitBatch_silently_drops_unknown_kind() {
        val weird = JavaOnlyMap.of("kind", "martian", "name", "x")
        val promise = RecordingPromise()
        module.emitBatch(JavaOnlyArray.of(weird), promise)
        assertEquals(0, sink.logs.size + sink.spanStarts.size + sink.spanEnds.size + sink.metrics.size)
        assertTrue(promise.resolved)
    }

    // ── flushWindow / shutdown ───────────────────────────────────────────

    @Test
    fun flushWindow_forwards_minutes_as_int() {
        val promise = RecordingPromise()
        module.flushWindow(5.0, promise)
        assertEquals(listOf(5), sink.flushMinutes)
        assertTrue(promise.resolved)
    }

    @Test
    fun shutdown_forwards_and_resolves() {
        val promise = RecordingPromise()
        module.shutdown(promise)
        assertEquals(1, sink.shutdowns)
        assertTrue(promise.resolved)
    }

    @Test
    fun getName_returns_Dash0Mobile() {
        assertEquals("Dash0Mobile", module.name)
    }

    // ── FATAL-severity forceFlush hook ───────────────────────────────────

    @Test
    fun emitLog_fatalSeverity_triggersForceFlush() {
        val payload = JavaOnlyMap.of(
            "kind", "log",
            "name", "app.error",
            "severity", 21.0,
            "attributes", JavaOnlyMap.of(),
            "timeUnixNano", "1700000000000000000",
        )
        module.emitBatch(JavaOnlyArray.of(payload), RecordingPromise())

        assertEquals(1, sink.logs.size)
        assertEquals(1, sink.forceFlushes)
    }

    @Test
    fun emitLog_belowFatal_doesNotTriggerForceFlush() {
        // 17 = ERROR in OTel semconv. Should NOT flush.
        val payload = JavaOnlyMap.of(
            "kind", "log",
            "name", "app.error",
            "severity", 17.0,
            "attributes", JavaOnlyMap.of(),
            "timeUnixNano", "1700000000000000000",
        )
        module.emitBatch(JavaOnlyArray.of(payload), RecordingPromise())

        assertEquals(1, sink.logs.size)
        assertEquals(0, sink.forceFlushes)
    }

    @Test
    fun emitLog_fatalSeverity_flushOrderingIsPostEmitPrePeer() {
        // FATAL log followed by another payload in the same batch. The
        // dispatcher must emit the FATAL, force-flush, THEN dispatch
        // the next payload — never the other way around.
        val fatal = JavaOnlyMap.of(
            "kind", "log",
            "name", "app.error",
            "severity", 21.0,
            "attributes", JavaOnlyMap.of(),
            "timeUnixNano", "1700000000000000000",
        )
        val context = JavaOnlyMap.of(
            "kind", "log",
            "name", "app.error.context",
            "severity", 9.0,
            "attributes", JavaOnlyMap.of(),
            "timeUnixNano", "1700000000000000001",
        )
        module.emitBatch(JavaOnlyArray.of(fatal, context), RecordingPromise())

        assertEquals(
            listOf(
                "emitLog(app.error,21)",
                "forceFlush",
                "emitLog(app.error.context,9)",
            ),
            sink.actionLog,
        )
    }

    @Test
    fun emitLog_multipleFatalsInBatch_eachTriggersFlush() {
        // Two FATALs in a row — each gets its own flush. Wasteful but
        // safer than batching: a flush after the first FATAL might
        // succeed-then-die before reaching the second; flushing after
        // each ensures both have a chance to reach disk independently.
        val a = JavaOnlyMap.of(
            "kind", "log", "name", "a", "severity", 21.0,
            "attributes", JavaOnlyMap.of(), "timeUnixNano", "1",
        )
        val b = JavaOnlyMap.of(
            "kind", "log", "name", "b", "severity", 22.0,
            "attributes", JavaOnlyMap.of(), "timeUnixNano", "2",
        )
        module.emitBatch(JavaOnlyArray.of(a, b), RecordingPromise())

        assertEquals(2, sink.forceFlushes)
    }

    @Test
    fun emitLog_fatalSeverityRangeBoundary() {
        // OTel semconv: FATAL severity range is 21..24. Anything ≥ 21
        // is FATAL. The dispatcher's threshold (≥ 21) covers all.
        val fatal22 = JavaOnlyMap.of(
            "kind", "log", "name", "fatal2", "severity", 22.0,
            "attributes", JavaOnlyMap.of(), "timeUnixNano", "1",
        )
        val fatal23 = JavaOnlyMap.of(
            "kind", "log", "name", "fatal3", "severity", 23.0,
            "attributes", JavaOnlyMap.of(), "timeUnixNano", "2",
        )
        val fatal24 = JavaOnlyMap.of(
            "kind", "log", "name", "fatal4", "severity", 24.0,
            "attributes", JavaOnlyMap.of(), "timeUnixNano", "3",
        )
        val warn = JavaOnlyMap.of(
            "kind", "log", "name", "warn", "severity", 13.0,
            "attributes", JavaOnlyMap.of(), "timeUnixNano", "4",
        )
        module.emitBatch(
            JavaOnlyArray.of(fatal22, fatal23, fatal24, warn),
            RecordingPromise(),
        )

        assertEquals(3, sink.forceFlushes)
    }
}

// ─── test doubles ────────────────────────────────────────────────────────

private data class LogCall(val name: String, val severity: Int, val attributes: Map<String, Any?>, val timeUnixNano: Long)
private data class SpanStartCall(val spanId: String, val parentSpanId: String?, val name: String, val spanKind: String, val attributes: Map<String, Any?>, val startTimeUnixNano: Long)
private data class SpanEndCall(val spanId: String, val status: String, val statusMessage: String?, val attributes: Map<String, Any?>, val endTimeUnixNano: Long)
private data class MetricCall(val name: String, val instrumentType: String, val value: Double, val attributes: Map<String, Any?>, val timeUnixNano: Long)

private class RecordingSink : BridgeCallSink {
    val starts = mutableListOf<StartConfig>()
    val logs = mutableListOf<LogCall>()
    val spanStarts = mutableListOf<SpanStartCall>()
    val spanEnds = mutableListOf<SpanEndCall>()
    val metrics = mutableListOf<MetricCall>()
    val flushMinutes = mutableListOf<Int>()
    var shutdowns = 0
    /**
     * Order of `(action, payloadId)` so the ordering tests can assert
     * forceFlush runs AFTER the FATAL emit but BEFORE the next payload
     * — the dispatcher contract is "drain the buffer for the FATAL
     * before the next payload could clobber it or the process dies."
     */
    val actionLog = mutableListOf<String>()
    var forceFlushes = 0

    override fun start(config: StartConfig) {
        starts += config
        actionLog += "start"
    }
    override fun emitLog(name: String, severity: Int, attributes: Map<String, Any?>, timeUnixNano: Long) {
        logs += LogCall(name, severity, attributes, timeUnixNano)
        actionLog += "emitLog($name,$severity)"
    }
    override fun startSpan(spanId: String, parentSpanId: String?, name: String, spanKind: String, attributes: Map<String, Any?>, startTimeUnixNano: Long) {
        spanStarts += SpanStartCall(spanId, parentSpanId, name, spanKind, attributes, startTimeUnixNano)
        actionLog += "startSpan($name)"
    }
    override fun endSpan(spanId: String, status: String, statusMessage: String?, attributes: Map<String, Any?>, endTimeUnixNano: Long) {
        spanEnds += SpanEndCall(spanId, status, statusMessage, attributes, endTimeUnixNano)
        actionLog += "endSpan($spanId)"
    }
    override fun recordMetric(name: String, instrumentType: String, value: Double, attributes: Map<String, Any?>, timeUnixNano: Long) {
        metrics += MetricCall(name, instrumentType, value, attributes, timeUnixNano)
        actionLog += "recordMetric($name)"
    }
    override fun flushWindow(minutes: Int) {
        flushMinutes += minutes
        actionLog += "flushWindow($minutes)"
    }
    override fun shutdown() {
        shutdowns += 1
        actionLog += "shutdown"
    }
    override fun forceFlush() {
        forceFlushes += 1
        actionLog += "forceFlush"
    }
}

// Mirrors the react-android 0.76.0 Kotlin `Promise` interface exactly. That
// interface declares `code` and `userInfo` as NON-null (it is Promise.kt, not
// the older Java Promise where everything was a nullable platform type), so the
// override signatures must use non-null `String` / `WritableMap` or Kotlin
// reports "overrides nothing".
private class RecordingPromise : Promise {
    var resolved = false
    var rejected = false
    override fun resolve(value: Any?) { resolved = true }
    override fun reject(code: String, message: String?) { rejected = true }
    override fun reject(code: String, throwable: Throwable?) { rejected = true }
    override fun reject(code: String, message: String?, throwable: Throwable?) { rejected = true }
    override fun reject(throwable: Throwable) { rejected = true }
    override fun reject(throwable: Throwable, userInfo: com.facebook.react.bridge.WritableMap) { rejected = true }
    override fun reject(code: String, userInfo: com.facebook.react.bridge.WritableMap) { rejected = true }
    override fun reject(code: String, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap) { rejected = true }
    override fun reject(code: String, message: String?, userInfo: com.facebook.react.bridge.WritableMap) { rejected = true }
    // The 4-arg overload is declared with all-nullable params in 0.76's Promise.kt.
    override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: com.facebook.react.bridge.WritableMap?) { rejected = true }
    @Suppress("DEPRECATION")
    override fun reject(message: String) { rejected = true }
}
