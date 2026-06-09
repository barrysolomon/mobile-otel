/*
 * Dash0MobileModule — React Native bridge for @dash0/mobile-react-native.
 *
 * Responsibilities (kept deliberately thin):
 *   1. Decode JS payloads (ReadableMap / ReadableArray) into native types.
 *   2. Dispatch on the payload `kind` field.
 *   3. Forward to a BridgeCallSink (production: OTelMobileCallSink).
 *
 * All buffering / policy / export / crash recovery lives in the existing
 * io.opentelemetry.android.mobile.OTelMobile SDK — not here.
 */
package com.dash0.mobile.reactnative

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType

class Dash0MobileModule internal constructor(
    reactContext: ReactApplicationContext,
    private val sink: BridgeCallSink,
) : ReactContextBaseJavaModule(reactContext) {

    constructor(reactContext: ReactApplicationContext) :
        this(reactContext, OTelMobileCallSink(reactContext.applicationContext))

    override fun getName(): String = NAME

    @ReactMethod
    fun start(config: ReadableMap, promise: Promise) {
        try {
            sink.start(
                StartConfig(
                    serviceName = config.getString("serviceName")
                        ?: error("serviceName is required"),
                    serviceVersion = config.getStringOrNull("serviceVersion"),
                    endpoint = config.getString("endpoint")
                        ?: error("endpoint is required"),
                    authToken = config.getStringOrNull("authToken"),
                    dataset = config.getStringOrNull("dataset"),
                    extraResourceAttributes = config
                        .getMapOrNull("extraResourceAttributes")
                        ?.toStringMap(),
                    nativeAutoCapture = config
                        .getArrayOrNull("nativeAutoCapture")
                        ?.toStringList()
                        ?: emptyList(),
                ),
            )
            promise.resolve(null)
        } catch (t: Throwable) {
            promise.reject("Dash0Mobile.start", t)
        }
    }

    @ReactMethod
    fun emitBatch(payloads: ReadableArray, promise: Promise) {
        // Isolate each payload: a single malformed entry (e.g. wrong-typed
        // severity/value) must not reject the whole batch, or JS retries the
        // entire batch forever and never makes progress. Drop the bad one,
        // keep going.
        for (i in 0 until payloads.size()) {
            try {
                val p = payloads.getMap(i) ?: continue
                dispatch(p)
            } catch (t: Throwable) {
                // Best-effort: skip the offending payload and continue.
                continue
            }
        }
        promise.resolve(null)
    }

    @ReactMethod
    fun flushWindow(minutes: Double, promise: Promise) {
        try {
            sink.flushWindow(minutes.toInt())
            promise.resolve(null)
        } catch (t: Throwable) {
            promise.reject("Dash0Mobile.flushWindow", t)
        }
    }

    @ReactMethod
    fun shutdown(promise: Promise) {
        try {
            sink.shutdown()
            promise.resolve(null)
        } catch (t: Throwable) {
            promise.reject("Dash0Mobile.shutdown", t)
        }
    }

    private fun dispatch(p: ReadableMap) {
        val kind = p.getString("kind") ?: return
        val attrs = p.getMapOrNull("attributes")?.toAttributeMap() ?: emptyMap()
        when (kind) {
            "log" -> {
                val severity = p.getIntOrDefault("severity", 9)
                sink.emitLog(
                    name = p.getString("name") ?: return,
                    severity = severity,
                    attributes = attrs,
                    timeUnixNano = p.getStringAsLong("timeUnixNano"),
                )
                // FATAL-severity logs (OTel semconv 21..24) are the
                // crash path. JS-side bypasses the 50ms debounce via
                // emitSync, but the payload still sits in
                // MobileLogRecordProcessor's RAM buffer waiting for the
                // periodic flush. Eagerly drain BEFORE the next payload
                // in the batch so the FATAL has a chance to reach disk
                // (and from there OTLP) even if the next payload's
                // dispatch path or the JS reporter terminates the
                // process. Mirrors iOS dispatcher commit `39bd258`.
                if (severity >= 21) {
                    sink.forceFlush()
                }
            }
            "spanStart" -> sink.startSpan(
                spanId = p.getString("spanId") ?: return,
                parentSpanId = p.getStringOrNull("parentSpanId"),
                name = p.getString("name") ?: return,
                spanKind = p.getString("spanKind") ?: "INTERNAL",
                attributes = attrs,
                startTimeUnixNano = p.getStringAsLong("startTimeUnixNano"),
            )
            "spanEnd" -> sink.endSpan(
                spanId = p.getString("spanId") ?: return,
                status = p.getString("status") ?: "UNSET",
                statusMessage = p.getStringOrNull("statusMessage"),
                attributes = attrs,
                endTimeUnixNano = p.getStringAsLong("endTimeUnixNano"),
            )
            "metric" -> sink.recordMetric(
                name = p.getString("name") ?: return,
                instrumentType = p.getString("instrumentType") ?: "counter",
                value = p.getDoubleOrDefault("value", 0.0),
                attributes = attrs,
                timeUnixNano = p.getStringAsLong("timeUnixNano"),
            )
            else -> Unit // unknown kinds are silently dropped (forward-compat)
        }
    }

    companion object {
        const val NAME = "Dash0Mobile"
    }
}

// ─── ReadableMap helpers ─────────────────────────────────────────────────────

private fun ReadableMap.getStringOrNull(key: String): String? =
    if (hasKey(key) && !isNull(key)) getString(key) else null

private fun ReadableMap.getMapOrNull(key: String): ReadableMap? =
    if (hasKey(key) && !isNull(key)) getMap(key) else null

private fun ReadableMap.getArrayOrNull(key: String): ReadableArray? =
    if (hasKey(key) && !isNull(key)) getArray(key) else null

private fun ReadableArray.toStringList(): List<String> {
    val out = ArrayList<String>(size())
    for (i in 0 until size()) {
        if (getType(i) == ReadableType.String) {
            getString(i)?.let { out.add(it) }
        }
    }
    return out
}

private fun ReadableMap.getStringAsLong(key: String): Long =
    getStringOrNull(key)?.toLongOrNull() ?: 0L

// Type-guarded numeric reads. ReadableMap.getInt/getDouble throw if the JS
// value isn't actually a number (e.g. a stringified severity). Check the
// declared type first and fall back to the default so one malformed payload
// can't blow up dispatch.
private fun ReadableMap.getIntOrDefault(key: String, default: Int): Int =
    if (hasKey(key) && getType(key) == ReadableType.Number) getInt(key) else default

private fun ReadableMap.getDoubleOrDefault(key: String, default: Double): Double =
    if (hasKey(key) && getType(key) == ReadableType.Number) getDouble(key) else default

private fun ReadableMap.toStringMap(): Map<String, String> {
    val iter = keySetIterator()
    val out = LinkedHashMap<String, String>()
    while (iter.hasNextKey()) {
        val k = iter.nextKey()
        if (getType(k) == ReadableType.String) {
            getString(k)?.let { out[k] = it }
        }
    }
    return out
}

private fun ReadableMap.toAttributeMap(): Map<String, Any?> {
    val iter = keySetIterator()
    val out = LinkedHashMap<String, Any?>()
    while (iter.hasNextKey()) {
        val k = iter.nextKey()
        out[k] = when (getType(k)) {
            ReadableType.Null -> null
            ReadableType.Boolean -> getBoolean(k)
            ReadableType.Number -> getDouble(k)
            ReadableType.String -> getString(k)
            else -> null // RN bridge doesn't send nested Map/Array as attribute values
        }
    }
    return out
}
