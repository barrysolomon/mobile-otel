/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Thread-safe holder for the remote kill-switch + global sampling state
/// `(enabled, sampleRate)` delivered over the wire via the `sdk` block of the
/// DSL v2 config (see `docs/design/remote-kill-switch.md`).
///
/// This is the single source of truth consulted at the two telemetry emission
/// choke points:
///
/// 1. **Logs** — `MobileLogRecordProcessor.onEmit` consults `shouldEmitLog()`
///    *before* any buffering / coalescing work, so a disabled SDK does no work.
/// 2. **Spans** — `RemoteGatedSampler` folds the gate into the trace sampler's
///    `shouldSample` decision.
///
/// `ConfigPoller.applyConfig` pushes parsed `SDKRemoteConfig` here on every
/// successful poll.
///
/// ## Concurrency
///
/// The log choke point (`onEmit`) is **synchronous** per the OTel
/// `LogRecordProcessor` contract, and the span sampler's `shouldSample` is
/// likewise synchronous and on a hot path. Both must read the gate state
/// without `await`-ing — so the gate is a lock-guarded `final class`, **not**
/// an actor. An `os_unfair_lock` guards a single small struct; reads copy the
/// value out under the lock and release immediately. No allocation occurs on
/// the read path, and the per-event sampling RNG is non-biased
/// (`SystemRandomNumberGenerator` via `Double.random(in:)`).
///
/// `os_unfair_lock` is used rather than `NSLock` because the read path is
/// uncontended in the common case (one writer, the poll thread; many readers,
/// the emit threads) and `os_unfair_lock`'s acquire/release is the cheapest
/// primitive available that is still correct under contention.
public final class RemoteGate: @unchecked Sendable {
    private var unfairLock = os_unfair_lock_s()
    private var state: SDKRemoteConfig

    /// Create a gate seeded with `initial` state. Defaults to the
    /// fail-open default (`enabled = true, sampleRate = 1.0`) so a gate that
    /// is never fed config behaves as a no-op — the SDK runs normally.
    public init(initial: SDKRemoteConfig = .default) {
        self.state = initial
    }

    /// Replace the live state. Called by `ConfigPoller.applyConfig` after a
    /// successful parse. Absent `sdk` block ⇒ caller passes `.default`
    /// (re-enable / no restriction); a transient fetch failure never reaches
    /// here, so the last-applied value is preserved automatically.
    public func update(_ config: SDKRemoteConfig) {
        os_unfair_lock_lock(&unfairLock)
        state = config
        os_unfair_lock_unlock(&unfairLock)
    }

    /// Current state snapshot. Cheap copy under the lock.
    public var current: SDKRemoteConfig {
        os_unfair_lock_lock(&unfairLock)
        let snapshot = state
        os_unfair_lock_unlock(&unfairLock)
        return snapshot
    }

    /// Whether the SDK is currently enabled. Convenience read for the
    /// self-telemetry gauge path.
    public var isEnabled: Bool {
        current.enabled
    }

    /// Currently-applied global sample rate, clamped to `[0, 1]`.
    public var sampleRate: Double {
        current.sampleRate
    }

    /// Synchronous, allocation-free decision for the **log** choke point.
    /// Returns `false` (drop) when the SDK is remotely disabled, or
    /// probabilistically when `sampleRate < 1`. Returns `true` (keep)
    /// in normal operation.
    ///
    /// Reads the gate state exactly once, then draws a single non-biased
    /// random sample only when a probabilistic decision is actually required.
    public func shouldEmitLog() -> Bool {
        let snapshot = current
        if !snapshot.enabled { return false }
        return Self.sampleDecision(rate: snapshot.sampleRate)
    }

    /// Folds the gate into a span sampling decision for a span identified by
    /// `traceIdLo` (the low 64 bits of its trace id).
    ///
    /// Semantics (spec §Semantics; matches the Android `DynamicSampler`
    /// MIN/cap fold for cross-platform consistency):
    ///
    /// - `!enabled` ⇒ `.drop` unconditionally — the inner sampler is not
    ///   consulted.
    /// - `sampleRate >= 1.0` (the default / "no restriction") ⇒ `.deferToInner`
    ///   — pure pass-through, zero behavioural change.
    /// - `sampleRate <= 0.0` ⇒ `.drop`.
    /// - otherwise ⇒ a **deterministic trace-id-ratio** test at the global
    ///   rate: `.deferToInner` when `ratio(traceIdLo) < sampleRate`, else
    ///   `.drop`.
    ///
    /// The deterministic ratio is the key to MIN-fold semantics. The inner
    /// sampler (`DynamicSampler` / `traceIdRatio`) applies the *same*
    /// `ratio(traceIdLo) < localRate` threshold test. Two threshold tests on
    /// the one shared ratio compose as intersection — a span survives iff
    /// `ratio < localRate AND ratio < globalRate`, i.e. `ratio < min(local,
    /// global)`. That is exactly `effective = min(localRate, globalRate)`,
    /// and because both decisions key off the same trace id, every span in a
    /// trace gets the same gate verdict (no orphaned children). A random
    /// per-span draw would instead yield the *product* of the rates and break
    /// trace coherence.
    public func spanGate(traceIdLo: UInt64) -> SpanGateDecision {
        let snapshot = current
        if !snapshot.enabled { return .drop }
        let rate = snapshot.sampleRate
        if rate >= 1.0 { return .deferToInner }
        if rate <= 0.0 { return .drop }
        return Self.traceIdRatio(traceIdLo) < rate ? .deferToInner : .drop
    }

    /// Non-biased per-event sampling decision for the **log** path. `rate >= 1`
    /// always keeps, `rate <= 0` always drops, otherwise draw a uniform sample.
    /// Logs have no trace id to key on, so a per-event RNG draw is the correct
    /// (and only) choice here.
    private static func sampleDecision(rate: Double) -> Bool {
        if rate >= 1.0 { return true }
        if rate <= 0.0 { return false }
        return Double.random(in: 0..<1) < rate
    }

    /// Maps the low 64 bits of a trace id to a `[0, 1)` ratio. Mirrors
    /// `DynamicSampler.shouldSampleTraceId` (and Android's `shouldSampleTraceId`)
    /// so the gate's threshold and the inner sampler's threshold operate on the
    /// identical value — the precondition for MIN-fold composition.
    static func traceIdRatio(_ traceIdLo: UInt64) -> Double {
        Double(traceIdLo) / Double(UInt64.max)
    }

    /// Outcome of folding the gate into a span decision.
    public enum SpanGateDecision: Sendable, Equatable {
        /// SDK disabled or globally sampled out — drop the span unconditionally.
        case drop
        /// Gate imposes no restriction — let the inner sampler decide.
        case deferToInner
    }
}
