/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

/// A `Sampler` decorator that folds the remote kill-switch + global sampling
/// gate (`RemoteGate`) into an inner sampler's decision. This is the span-side
/// choke point for `docs/design/remote-kill-switch.md`, analogous to the
/// log-side gate in `MobileLogRecordProcessor.onEmit`.
///
/// It wraps whatever sampler `SamplerFactory.createSampler(_:)` produces (the
/// default `DynamicSampler`, a static ratio sampler, parent-based, etc.) so
/// the gate composes with the configured sampling strategy rather than
/// replacing it.
///
/// ## Decision composition (spec §Semantics — MIN/cap fold)
///
/// - `enabled = false` ⇒ **DROP** every span unconditionally. The inner
///   sampler is not consulted.
/// - `sample_rate < 1.0` ⇒ the global rate is folded as a **deterministic
///   trace-id-ratio cap** keyed on the span's `traceId`. The span survives
///   iff `ratio(traceId) < sample_rate` AND the inner sampler keeps it.
///   Because the inner `DynamicSampler` / `traceIdRatio` sampler applies the
///   *same* `ratio(traceId) < localRate` test, two threshold tests on the one
///   shared ratio compose as `ratio < min(localRate, sample_rate)` — i.e.
///   `effective = min(local, global)`, matching the Android `DynamicSampler`
///   fold (`minOf(localRate, gate.sampleRate)`) for cross-platform
///   consistency. Keying on the trace id also keeps every span in a trace
///   coherent — no orphaned children from a per-span coin flip.
/// - `enabled = true, sample_rate = 1.0` ⇒ pure pass-through to the inner
///   sampler (the default; zero behavioural change).
///
/// When the gate vetoes a span, the inner sampler is still consulted only when
/// the gate defers — so on the hot path a globally-disabled SDK short-circuits
/// without touching the inner sampler at all.
public final class RemoteGatedSampler: Sampler, @unchecked Sendable {
    private let inner: Sampler
    private let gate: RemoteGate

    public init(inner: Sampler, gate: RemoteGate) {
        self.inner = inner
        self.gate = gate
    }

    public func shouldSample(
        parentContext: SpanContext?,
        traceId: TraceId,
        name: String,
        kind: SpanKind,
        attributes: [String: AttributeValue],
        parentLinks: [SpanData.Link]
    ) -> Decision {
        switch gate.spanGate(traceIdLo: traceId.idLo) {
        case .drop:
            // SDK remotely disabled or globally capped out for this trace id.
            // Drop without consulting the inner sampler.
            return GatedDecision(isSampled: false, attributes: [:])
        case .deferToInner:
            return inner.shouldSample(
                parentContext: parentContext,
                traceId: traceId,
                name: name,
                kind: kind,
                attributes: attributes,
                parentLinks: parentLinks
            )
        }
    }

    public var description: String {
        "RemoteGatedSampler{inner=\(inner.description)}"
    }
}

/// Concrete `Decision` returned by `RemoteGatedSampler` when the gate drops a
/// span. File-scope (not nested) for the same reason as `DynamicDecision`:
/// OTel-Swift's `Decision` is a protocol.
private struct GatedDecision: Decision {
    let isSampled: Bool
    let attributes: [String: AttributeValue]
}
