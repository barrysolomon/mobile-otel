import Testing
@testable import OTelMobileSDK

/// Parse coverage for the root `sdk` block (remote kill switch + global
/// sampling). Behavioural parity with Android's `SdkConfigParseTest.kt`.
///
/// See `docs/design/remote-kill-switch.md` (Wire contract + Fail-open rules).
@Suite("SdkConfigParse")
struct SdkConfigParseTests {

    @Test("present sdk block parses enabled + sample_rate")
    func presentBlock() {
        let json = """
        { "version": 2, "sdk": { "enabled": false, "sample_rate": 0.25 }, "workflows": [] }
        """
        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        let sdk = config?.sdkConfig
        #expect(sdk != nil)
        #expect(sdk?.enabled == false)
        #expect(sdk?.sampleRate == 0.25)
    }

    @Test("absent sdk block ⇒ nil sdkConfig (caller treats as default / no restriction)")
    func absentBlock() {
        let json = """
        { "version": 2, "workflows": [] }
        """
        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        #expect(config?.sdkConfig == nil)
    }

    @Test("malformed sdk (not an object) ⇒ nil sdkConfig, never crash")
    func malformedNotObject() {
        let json = """
        { "version": 2, "sdk": "disable everything", "workflows": [] }
        """
        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        #expect(config?.sdkConfig == nil)
    }

    @Test("wrong-type fields fall back to per-field defaults")
    func wrongTypeFields() {
        // enabled is a string, sample_rate is a string — org.json-style
        // coercion: "false" → false (NSString.boolValue), "0.5" → 0.5.
        let json = """
        { "version": 2, "sdk": { "enabled": "false", "sample_rate": "0.5" }, "workflows": [] }
        """
        let config = PolicyParser.parseV2(json)
        let sdk = config?.sdkConfig
        #expect(sdk?.enabled == false)
        #expect(sdk?.sampleRate == 0.5)
    }

    @Test("uncoercible wrong-type fields keep defaults")
    func uncoercibleFields() {
        // enabled is an object, sample_rate is an array — neither coerces,
        // so each field stays at its default (true / 1.0).
        let json = """
        { "version": 2, "sdk": { "enabled": {"x": 1}, "sample_rate": [1,2] }, "workflows": [] }
        """
        let config = PolicyParser.parseV2(json)
        let sdk = config?.sdkConfig
        #expect(sdk?.enabled == true)
        #expect(sdk?.sampleRate == 1.0)
    }

    @Test("numeric enabled:0 is NOT a boolean ⇒ stays ENABLED (default), matching Android")
    func numericEnabledZeroStaysEnabled() {
        // org.json `optBoolean` does not coerce numbers, so `"enabled": 0`
        // leaves the field at its default of `true`. iOS must match: a numeric
        // NSNumber (CFNumber, not CFBoolean) is not a boolean and is ignored.
        // Regression guard: previously `as? Bool` bridged `0` → false and
        // wrongly DISABLED the SDK while Android stayed enabled.
        let json = """
        { "version": 2, "sdk": { "enabled": 0 }, "workflows": [] }
        """
        let sdk = PolicyParser.parseV2(json)?.sdkConfig
        #expect(sdk?.enabled == true)
    }

    @Test("numeric enabled:1 is NOT a boolean ⇒ stays at default (true), matching Android")
    func numericEnabledOneStaysDefault() {
        // Symmetric to the `0` case: a numeric `1` is not a boolean per
        // org.json, so the field keeps its default rather than coercing to true.
        let json = """
        { "version": 2, "sdk": { "enabled": 1 }, "workflows": [] }
        """
        let sdk = PolicyParser.parseV2(json)?.sdkConfig
        #expect(sdk?.enabled == true)
    }

    @Test("genuine boolean enabled:true/false is honoured")
    func genuineBooleanHonoured() {
        // Sanity: a real JSON boolean still works after the CFBoolean guard.
        let disabled = PolicyParser.parseV2(
            #"{ "version": 2, "sdk": { "enabled": false }, "workflows": [] }"#
        )?.sdkConfig
        #expect(disabled?.enabled == false)
        let enabled = PolicyParser.parseV2(
            #"{ "version": 2, "sdk": { "enabled": true }, "workflows": [] }"#
        )?.sdkConfig
        #expect(enabled?.enabled == true)
    }

    @Test("unrecognized enabled string keeps default (true), matching org.json")
    func unrecognizedEnabledStringKeepsDefault() {
        // org.json only coerces "true"/"false"; any other string falls back to
        // the default. (The old `NSString.boolValue` wrongly treated "yes"/"1"
        // as true.)
        let json = """
        { "version": 2, "sdk": { "enabled": "nope" }, "workflows": [] }
        """
        let sdk = PolicyParser.parseV2(json)?.sdkConfig
        #expect(sdk?.enabled == true)
    }

    @Test("sample_rate above 1.0 is clamped to 1.0")
    func sampleRateClampHigh() {
        let json = """
        { "version": 2, "sdk": { "enabled": true, "sample_rate": 4.2 }, "workflows": [] }
        """
        let sdk = PolicyParser.parseV2(json)?.sdkConfig
        #expect(sdk?.sampleRate == 1.0)
    }

    @Test("sample_rate below 0.0 is clamped to 0.0")
    func sampleRateClampLow() {
        let json = """
        { "version": 2, "sdk": { "enabled": true, "sample_rate": -3.0 }, "workflows": [] }
        """
        let sdk = PolicyParser.parseV2(json)?.sdkConfig
        #expect(sdk?.sampleRate == 0.0)
    }

    @Test("partial sdk block (only enabled) keeps rate default 1.0")
    func partialOnlyEnabled() {
        let json = """
        { "version": 2, "sdk": { "enabled": false }, "workflows": [] }
        """
        let sdk = PolicyParser.parseV2(json)?.sdkConfig
        #expect(sdk?.enabled == false)
        #expect(sdk?.sampleRate == 1.0)
    }

    @Test("partial sdk block (only sample_rate) keeps enabled default true")
    func partialOnlyRate() {
        let json = """
        { "version": 2, "sdk": { "sample_rate": 0.1 }, "workflows": [] }
        """
        let sdk = PolicyParser.parseV2(json)?.sdkConfig
        #expect(sdk?.enabled == true)
        #expect(sdk?.sampleRate == 0.1)
    }

    @Test("sdk block coexists with workflows")
    func sdkWithWorkflows() {
        let json = """
        {
          "version": 2,
          "sdk": { "enabled": true, "sample_rate": 0.5 },
          "workflows": [{
            "id": "crash-handler",
            "enabled": true,
            "states": [{
              "id": "default",
              "matchers": [{"type": "crash", "config": {}}],
              "on_match": { "actions": [{"type": "flush_buffer", "config": {"minutes": 5}}] }
            }]
          }]
        }
        """
        let config = PolicyParser.parseV2(json)
        #expect(config?.policies.count == 1)
        #expect(config?.sdkConfig?.sampleRate == 0.5)
    }
}
