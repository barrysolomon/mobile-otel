/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import io.opentelemetry.android.mobile.instrumentation.Incubating
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe single source of truth for the remote kill switch and global
 * head-sampling override delivered via the `sdk` block of the remote config.
 *
 * The gate is consulted on the hot path at the two telemetry emission choke
 * points — [io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor.onEmit]
 * for logs and [io.opentelemetry.android.mobile.sampling.DynamicSampler.shouldSample]
 * for spans. Both choke points share the **same** gate instance, which is wired
 * once at SDK init in [io.opentelemetry.android.mobile.MobileLoggerProvider].
 *
 * **State model.** State is held as a single immutable [GateState] behind one
 * `@Volatile` reference, so a hot-path read is a single volatile load with no
 * lock and no allocation. Writes (from config apply) publish a fresh immutable
 * snapshot, so a reader always sees an internally-consistent `(enabled, rate)`
 * pair — never a torn mix of an old `enabled` with a new `sampleRate`.
 *
 * **Fail-open.** A freshly-constructed gate is `enabled = true, sampleRate = 1.0`
 * (no restriction). A transient config-fetch failure must leave the prior state
 * untouched: callers simply do not call [apply] on failure. An applied config
 * whose `sdk` block is absent re-enables the SDK (absence == "no restriction"),
 * which is why [apply] maps a `null` [SdkConfig] back to the open defaults.
 *
 * @see SdkConfig for the parsed, clamped wire representation.
 */
@Incubating
class RemoteGate {

    /**
     * Immutable snapshot of the gate's two control values. Published atomically
     * via the enclosing [state] reference so readers never observe a torn pair.
     *
     * @property enabled `false` hard-drops all new telemetry at both choke points.
     * @property sampleRate global head-sampling fraction in `[0.0, 1.0]`.
     */
    data class GateState(
        val enabled: Boolean,
        val sampleRate: Double
    )

    private val state = AtomicReference(GateState(enabled = true, sampleRate = 1.0))

    /** Whether new telemetry is currently permitted. Single volatile read. */
    val enabled: Boolean
        get() = state.get().enabled

    /** Current global head-sampling fraction in `[0.0, 1.0]`. Single volatile read. */
    val sampleRate: Double
        get() = state.get().sampleRate

    /** Current immutable snapshot of both control values. */
    fun snapshot(): GateState = state.get()

    /**
     * Applies a parsed [SdkConfig] to the gate.
     *
     * Per the fail-open contract, a `null` [config] (the `sdk` block was absent
     * from an otherwise-valid remote config) resets the gate to the open
     * defaults (`enabled = true, sampleRate = 1.0`). Callers must **not** invoke
     * this on a fetch/parse failure — skipping the call is what preserves the
     * last-applied state across transient errors.
     *
     * @param config the parsed `sdk` block, or `null` when it was absent.
     */
    fun apply(config: SdkConfig?) {
        state.set(
            if (config == null) {
                GateState(enabled = true, sampleRate = 1.0)
            } else {
                GateState(enabled = config.enabled, sampleRate = config.clampedSampleRate)
            }
        )
    }

    /**
     * Hot-path decision for a single event: should it be permitted to flow?
     *
     * Returns `false` when the SDK is disabled, or — when `sampleRate < 1.0` —
     * with probability `1 - sampleRate`, using a non-biased per-thread RNG so
     * the draw allocates nothing and never contends across threads. A
     * `sampleRate` of exactly `1.0` short-circuits the draw entirely.
     *
     * Intended for the log choke point. The span choke point folds [sampleRate]
     * into the sampler's deterministic trace-ID ratio instead (see
     * [io.opentelemetry.android.mobile.sampling.DynamicSampler]).
     *
     * @return `true` to keep the event, `false` to drop it.
     */
    fun allowEvent(): Boolean {
        val current = state.get()
        if (!current.enabled) return false
        val rate = current.sampleRate
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false
        return ThreadLocalRandom.current().nextDouble() < rate
    }
}
