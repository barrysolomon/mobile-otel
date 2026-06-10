import Testing
@testable import OTelMobileSDK

/// Unit coverage for the remote kill-switch + global-sampling holder.
/// Behavioural parity with Android's `RemoteGateTest.kt`.
///
/// See `docs/design/remote-kill-switch.md`.
@Suite("RemoteGate")
struct RemoteGateTests {

    // MARK: - Construction / defaults

    @Test("fresh gate is fail-open (enabled, full rate)")
    func freshGateOpen() {
        let gate = RemoteGate()
        #expect(gate.isEnabled)
        #expect(gate.sampleRate == 1.0)
        #expect(gate.shouldEmitLog())
    }

    @Test("SDKRemoteConfig.default is enabled / 1.0")
    func defaultConfig() {
        #expect(SDKRemoteConfig.default.enabled)
        #expect(SDKRemoteConfig.default.sampleRate == 1.0)
    }

    // MARK: - enabled toggle / log allow-drop semantics

    @Test("disabled gate drops all logs")
    func disabledDropsLogs() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 1.0))
        #expect(!gate.isEnabled)
        for _ in 0..<100 { #expect(!gate.shouldEmitLog()) }
    }

    @Test("enabled gate at rate 1.0 keeps all logs")
    func enabledKeepsAll() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 1.0))
        for _ in 0..<100 { #expect(gate.shouldEmitLog()) }
    }

    @Test("rate 0.0 drops all logs even when enabled")
    func rateZeroDropsAll() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.0))
        for _ in 0..<100 { #expect(!gate.shouldEmitLog()) }
    }

    @Test("rate 0.5 keeps roughly half over a large N")
    func rateHalfApproxHalf() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.5))
        let n = 20_000
        var kept = 0
        for _ in 0..<n where gate.shouldEmitLog() { kept += 1 }
        let fraction = Double(kept) / Double(n)
        // Generous tolerance — this is a statistical assertion, not exact.
        #expect(fraction > 0.45 && fraction < 0.55, "kept fraction \(fraction) not ~0.5")
    }

    // MARK: - update / apply(nil) re-open

    @Test("update flips enabled then back open")
    func updateFlips() {
        let gate = RemoteGate()
        gate.update(SDKRemoteConfig(enabled: false, sampleRate: 1.0))
        #expect(!gate.isEnabled)
        #expect(!gate.shouldEmitLog())
        // Re-open by pushing the default (the absent-sdk mapping).
        gate.update(.default)
        #expect(gate.isEnabled)
        #expect(gate.shouldEmitLog())
    }

    @Test("snapshot is internally consistent")
    func snapshotConsistent() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 0.25))
        let snap = gate.current
        #expect(!snap.enabled)
        #expect(snap.sampleRate == 0.25)
    }

    // MARK: - clamping in the value type

    @Test("SDKRemoteConfig clamps out-of-range rate")
    func configClamps() {
        #expect(SDKRemoteConfig(sampleRate: 2.5).sampleRate == 1.0)
        #expect(SDKRemoteConfig(sampleRate: -0.5).sampleRate == 0.0)
        #expect(SDKRemoteConfig(sampleRate: 0.3).sampleRate == 0.3)
    }

    @Test("SDKRemoteConfig maps NaN rate to 1.0")
    func configNaN() {
        #expect(SDKRemoteConfig(sampleRate: Double.nan).sampleRate == 1.0)
    }

    // MARK: - span gate: MIN-fold trace-id-ratio semantics

    @Test("disabled gate drops every span regardless of trace id")
    func spanGateDisabledDrops() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 1.0))
        #expect(gate.spanGate(traceIdLo: 0) == .drop)
        #expect(gate.spanGate(traceIdLo: UInt64.max / 2) == .drop)
        #expect(gate.spanGate(traceIdLo: UInt64.max) == .drop)
    }

    @Test("rate 1.0 defers every span to inner sampler")
    func spanGateFullDefers() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 1.0))
        #expect(gate.spanGate(traceIdLo: 0) == .deferToInner)
        #expect(gate.spanGate(traceIdLo: UInt64.max) == .deferToInner)
    }

    @Test("rate 0.0 drops every span")
    func spanGateZeroDrops() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.0))
        #expect(gate.spanGate(traceIdLo: 0) == .drop)
        #expect(gate.spanGate(traceIdLo: UInt64.max) == .drop)
    }

    @Test("span gate is a deterministic trace-id-ratio cap")
    func spanGateDeterministicRatio() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.5))
        // ratio = traceIdLo / UInt64.max. A trace id just below the 0.5
        // threshold defers; one just above drops. Same input → same output,
        // every time (trace coherence).
        let lowLo = UInt64(0.25 * Double(UInt64.max))
        let highLo = UInt64(0.75 * Double(UInt64.max))
        #expect(gate.spanGate(traceIdLo: lowLo) == .deferToInner)
        #expect(gate.spanGate(traceIdLo: highLo) == .drop)
        // Determinism: repeated calls never flip.
        for _ in 0..<50 {
            #expect(gate.spanGate(traceIdLo: lowLo) == .deferToInner)
            #expect(gate.spanGate(traceIdLo: highLo) == .drop)
        }
    }
}
