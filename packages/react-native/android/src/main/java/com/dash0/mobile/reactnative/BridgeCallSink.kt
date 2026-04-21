/*
 * Seam between the RN bridge and the native OTel Mobile SDK.
 *
 * The production implementation (`OTelMobileCallSink`) forwards calls into
 * `io.opentelemetry.android.mobile.OTelMobile`. Tests provide a fake so we
 * can assert forwarding behavior without standing up Android's Application,
 * emulator, or the full OTel SDK.
 */
package com.dash0.mobile.reactnative

interface BridgeCallSink {
    fun start(config: StartConfig)
    fun emitLog(name: String, severity: Int, attributes: Map<String, Any?>, timeUnixNano: Long)
    fun startSpan(spanId: String, name: String, spanKind: String, attributes: Map<String, Any?>, startTimeUnixNano: Long)
    fun endSpan(spanId: String, status: String, statusMessage: String?, attributes: Map<String, Any?>, endTimeUnixNano: Long)
    fun recordMetric(name: String, instrumentType: String, value: Double, attributes: Map<String, Any?>, timeUnixNano: Long)
    fun flushWindow(minutes: Int)
    fun shutdown()
}

data class StartConfig(
    val serviceName: String,
    val serviceVersion: String?,
    val endpoint: String,
    val authToken: String?,
    val dataset: String?,
    /**
     * Extra resource attributes supplied by the JS caller. The RN bridge
     * populates `telemetry.distro.name` / `telemetry.distro.version` by
     * default; apps can add their own keys through `Dash0Mobile.start`.
     */
    val extraResourceAttributes: Map<String, String>? = null,
)
