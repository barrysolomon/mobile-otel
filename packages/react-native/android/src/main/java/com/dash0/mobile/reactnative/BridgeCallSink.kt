/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
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
    fun startSpan(spanId: String, parentSpanId: String?, name: String, spanKind: String, attributes: Map<String, Any?>, startTimeUnixNano: Long)
    fun endSpan(spanId: String, status: String, statusMessage: String?, attributes: Map<String, Any?>, endTimeUnixNano: Long)
    fun recordMetric(name: String, instrumentType: String, value: Double, attributes: Map<String, Any?>, timeUnixNano: Long)
    fun flushWindow(minutes: Int)
    fun shutdown()

    /**
     * Synchronously drain every buffered telemetry record through the
     * underlying SDK exporter, persisting any in-flight records to disk
     * on export failure. Called by `Dash0MobileModule.dispatch`
     * immediately after dispatching a FATAL-severity (severity ≥ 21)
     * log emit, before continuing to the next payload in the batch.
     *
     * Mirrors the iOS [`BridgeCallSink.forceFlush`](../../../../../../../ios/BridgeCallSink.swift)
     * contract introduced in commit `39bd258`. Default no-op so test
     * fakes and lightweight non-RN consumers inherit safe behavior;
     * production sinks override.
     *
     * Kept as a default method so existing implementations (test
     * fakes from earlier RN-bridge work) compile without needing to
     * be touched. Production [`OTelMobileCallSink.forceFlush`]
     * overrides this to call [io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor.forceFlush].
     */
    fun forceFlush() = Unit
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
    /**
     * Native-only auto-capture capability tokens the JS caller explicitly
     * opted into. Default empty = no native auto-capture (RN apps get
     * network/errors/lifecycle coverage from the JS-side shims).
     *
     * Supported tokens: "network", "errors", "lifecycle", "tap", "scroll",
     * "textInput", "screen", "freeze", "vitals", "deviceStats".
     *
     * Today the Android MobileConfig's auto-capture is driven separately by
     * the host app's `OTelMobileBuilder` — these tokens are accepted here
     * for cross-platform bridge parity but not yet consumed. When the
     * Android SDK gains a unified `autoCaptureOptions` on MobileConfig, the
     * sink will translate them the same way the iOS sink does today.
     */
    val nativeAutoCapture: List<String> = emptyList(),
    /**
     * Trace sampling strategy from the JS caller, mapped onto the native
     * [io.opentelemetry.android.mobile.sampling.SamplingConfig].
     *
     * The RN bridge defaults this to [SamplingStrategy.ALWAYS_ON] when the
     * JS caller omits `sampling`, rather than inheriting the native SDK's
     * `dynamic(0.1)` default. RN manual spans are root spans with arbitrary
     * names, so a 10% baseline silently drops ~90% of a user's first span
     * (Loper finding #4). Null here means "caller said nothing" — the sink
     * leaves the native default untouched, but in practice the JS bridge
     * always sends a value.
     */
    val sampling: BridgeSamplingConfig? = null,
    /**
     * Base URL of the mobile-otel gateway serving `/config?dsl_version=2`.
     * Enables the native RemoteGate kill switch + policy polling for RN apps
     * whose `endpoint` points at plain OTLP ingest (no /config route). Null
     * (default) leaves the native SDK polling `endpoint` as before.
     */
    val gatewayEndpoint: String? = null,
    /**
     * Whether the native SDK should poll for remote policy config. Null
     * (default) keeps the native SDK's own default; `false` disables remote
     * config entirely (Android `remoteConfigEnabled`, iOS `enablePolicyPolling`).
     */
    val enablePolicyPolling: Boolean? = null,
    /** Poll interval in seconds. Null = native SDK default (300). */
    val configPollIntervalSeconds: Long? = null,
)

/**
 * Bridge-side mirror of the JS `SamplingConfig`. Decoded from the RN
 * `start()` payload and translated to the native SDK's `SamplingConfig` in
 * [OTelMobileCallSink.start].
 */
data class BridgeSamplingConfig(
    val strategy: SamplingStrategy,
    /** Baseline rate for [SamplingStrategy.DYNAMIC]. Null = native default. */
    val normalRate: Double? = null,
    /** High-priority rate for [SamplingStrategy.DYNAMIC]. Null = native default. */
    val highPriorityRate: Double? = null,
)

enum class SamplingStrategy {
    ALWAYS_ON,
    ALWAYS_OFF,
    DYNAMIC,
    ;

    companion object {
        /** Maps the JS `strategy` string; unknown values fall back to ALWAYS_ON (RN default). */
        fun fromToken(raw: String?): SamplingStrategy = when (raw) {
            "always_off" -> ALWAYS_OFF
            "dynamic" -> DYNAMIC
            else -> ALWAYS_ON
        }
    }
}
