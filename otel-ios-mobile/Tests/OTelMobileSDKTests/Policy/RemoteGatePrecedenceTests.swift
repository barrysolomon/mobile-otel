import Testing
@testable import OTelMobileSDK

/// Fail-open & precedence coverage for the kill switch, driven through the
/// same parse → gate-push path `ConfigPoller.applyConfig` uses (via
/// `testHelperApplyToGate`). Behavioural parity with Android's gate-precedence
/// tests.
///
/// See `docs/design/remote-kill-switch.md` §Fail-open & precedence rules.
@Suite("RemoteGatePrecedence")
struct RemoteGatePrecedenceTests {

    @Test("applying a config with sdk.enabled=false disables the gate")
    func applyDisable() {
        let gate = RemoteGate()
        let applied = ConfigPoller.testHelperApplyToGate(
            jsonString: #"{ "version": 2, "sdk": { "enabled": false }, "workflows": [] }"#,
            gate: gate
        )
        #expect(applied)
        #expect(!gate.isEnabled)
    }

    @Test("fetch/parse failure keeps last-applied (gate not reset)")
    func failureKeepsLast() {
        let gate = RemoteGate()
        // First, a valid config disables the SDK.
        _ = ConfigPoller.testHelperApplyToGate(
            jsonString: #"{ "version": 2, "sdk": { "enabled": false, "sample_rate": 0.2 }, "workflows": [] }"#,
            gate: gate
        )
        #expect(!gate.isEnabled)
        #expect(gate.sampleRate == 0.2)

        // A malformed payload fails to parse → applyConfig is never reached →
        // helper returns false and the gate is left exactly as it was.
        let applied = ConfigPoller.testHelperApplyToGate(
            jsonString: "this is not json {{{",
            gate: gate
        )
        #expect(!applied)
        // Last-applied (disabled / 0.2) survives the failure.
        #expect(!gate.isEnabled)
        #expect(gate.sampleRate == 0.2)
    }

    @Test("wrong-version config fails to parse and keeps last-applied")
    func wrongVersionKeepsLast() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 0.1))
        // version != 2 ⇒ parseConfigV2 returns nil ⇒ not applied.
        let applied = ConfigPoller.testHelperApplyToGate(
            jsonString: #"{ "version": 1, "sdk": { "enabled": true }, "workflows": [] }"#,
            gate: gate
        )
        #expect(!applied)
        #expect(!gate.isEnabled)
        #expect(gate.sampleRate == 0.1)
    }

    @Test("absent sdk in a valid config re-opens a previously-disabled gate")
    func absentSdkReopens() {
        let gate = RemoteGate()
        // Disable first.
        _ = ConfigPoller.testHelperApplyToGate(
            jsonString: #"{ "version": 2, "sdk": { "enabled": false }, "workflows": [] }"#,
            gate: gate
        )
        #expect(!gate.isEnabled)

        // A valid config that OMITS the sdk block must re-enable (absence ==
        // "no restriction"), not leave the SDK disabled forever.
        let applied = ConfigPoller.testHelperApplyToGate(
            jsonString: #"{ "version": 2, "workflows": [] }"#,
            gate: gate
        )
        #expect(applied)
        #expect(gate.isEnabled)
        #expect(gate.sampleRate == 1.0)
    }

    @Test("explicit enabled=true re-opens a disabled gate")
    func explicitReenable() {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 0.0))
        let applied = ConfigPoller.testHelperApplyToGate(
            jsonString: #"{ "version": 2, "sdk": { "enabled": true, "sample_rate": 1.0 }, "workflows": [] }"#,
            gate: gate
        )
        #expect(applied)
        #expect(gate.isEnabled)
        #expect(gate.sampleRate == 1.0)
    }
}
