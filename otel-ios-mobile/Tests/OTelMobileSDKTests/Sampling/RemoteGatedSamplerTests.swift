import Foundation
import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi
import OpenTelemetrySdk

/// Span-side gate coverage. The `RemoteGatedSampler` folds the shared
/// `RemoteGate` over an inner sampler with MIN/cap trace-id-ratio semantics —
/// matching Android's `DynamicSampler` fold (`minOf(localRate, gate.rate)`).
/// Behavioural parity with Android's `DynamicSamplerGateTest.kt`.
///
/// See `docs/design/remote-kill-switch.md`.
@Suite("RemoteGatedSampler")
struct RemoteGatedSamplerTests {

    /// TraceId whose `idLo` ratio is `ratio` (capped to avoid overflow at 1.0).
    private func traceId(ratio: Double) -> TraceId {
        let lo = UInt64(min(Double(UInt64.max) - 1, ratio * Double(UInt64.max)))
        return TraceId(idHi: 0, idLo: lo)
    }

    private func decide(_ sampler: Sampler, ratio: Double, name: String = "ui.tap") -> Decision {
        sampler.shouldSample(
            parentContext: nil,
            traceId: traceId(ratio: ratio),
            name: name,
            kind: .client,
            attributes: [:],
            parentLinks: []
        )
    }

    @Test("disabled gate drops every span without consulting inner")
    func disabledDropsAllSpans() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 1.0))
        // alwaysOn inner — proves the DROP comes from the gate, not the inner.
        let sampler = RemoteGatedSampler(inner: Samplers.alwaysOn, gate: gate)
        #expect(!decide(sampler, ratio: 0.0).isSampled)
        #expect(!decide(sampler, ratio: 0.5).isSampled)
        #expect(!decide(sampler, ratio: 0.99).isSampled)
        // page/startup spans are also dropped — a disabled SDK emits nothing.
        #expect(!decide(sampler, ratio: 0.0, name: "page.checkout").isSampled)
        #expect(!decide(sampler, ratio: 0.0, name: "app.startup").isSampled)
    }

    @Test("rate 1.0 is pure pass-through to inner sampler")
    func fullRatePassThrough() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 1.0))
        // Inner DynamicSampler at baseline 0.5: gate adds no restriction, so the
        // gated decision == the inner decision for every trace id.
        let inner = DynamicSampler(baselineSamplingRate: 0.5, highPrioritySamplingRate: 1.0)
        let gated = RemoteGatedSampler(inner: inner, gate: gate)
        for r in stride(from: 0.05, to: 1.0, by: 0.1) {
            let innerDecision = decide(inner, ratio: r).isSampled
            let gatedDecision = decide(gated, ratio: r).isSampled
            #expect(innerDecision == gatedDecision, "rate 1.0 must not alter inner decision at ratio \(r)")
        }
    }

    @Test("rate 0.0 drops every span")
    func zeroRateDropsAll() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.0))
        let sampler = RemoteGatedSampler(inner: Samplers.alwaysOn, gate: gate)
        #expect(!decide(sampler, ratio: 0.0).isSampled)
        #expect(!decide(sampler, ratio: 0.5).isSampled)
    }

    @Test("MIN-fold: effective rate is min(local, global)")
    func minFold() {
        // Inner alwaysOn (local == 1.0); global cap 0.3. Effective = min(1.0,0.3)=0.3.
        // A trace id at ratio 0.2 (< 0.3) is kept; at 0.4 (> 0.3) is dropped.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.3))
        let sampler = RemoteGatedSampler(inner: Samplers.alwaysOn, gate: gate)
        #expect(decide(sampler, ratio: 0.2).isSampled, "ratio below global cap kept")
        #expect(!decide(sampler, ratio: 0.4).isSampled, "ratio above global cap dropped")
    }

    @Test("MIN-fold: the tighter of local vs global wins (local tighter)")
    func minFoldLocalTighter() {
        // Inner traceIdRatio 0.2 (local), global cap 0.6. Effective = min = 0.2.
        // ratio 0.1 kept by both; ratio 0.4 dropped by the LOCAL sampler even
        // though it passes the looser global cap.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.6))
        let inner = Samplers.traceIdRatio(ratio: 0.2)
        let sampler = RemoteGatedSampler(inner: inner, gate: gate)
        #expect(decide(sampler, ratio: 0.1).isSampled)
        #expect(!decide(sampler, ratio: 0.4).isSampled, "local 0.2 cap drops it despite global 0.6")
    }

    @Test("MIN-fold: the tighter of local vs global wins (global tighter)")
    func minFoldGlobalTighter() {
        // Inner traceIdRatio 0.8 (local), global cap 0.3. Effective = min = 0.3.
        // ratio 0.5 passes the LOCAL 0.8 sampler but is dropped by the global cap.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.3))
        let inner = Samplers.traceIdRatio(ratio: 0.8)
        let sampler = RemoteGatedSampler(inner: inner, gate: gate)
        #expect(decide(sampler, ratio: 0.1).isSampled)
        #expect(!decide(sampler, ratio: 0.5).isSampled, "global 0.3 cap drops it despite local 0.8")
    }

    @Test("trace coherence: same trace id ⇒ same gate verdict every call")
    func traceCoherence() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.5))
        let sampler = RemoteGatedSampler(inner: Samplers.alwaysOn, gate: gate)
        let keptRatio = 0.25
        let droppedRatio = 0.75
        for _ in 0..<50 {
            #expect(decide(sampler, ratio: keptRatio).isSampled)
            #expect(!decide(sampler, ratio: droppedRatio).isSampled)
        }
    }
}
