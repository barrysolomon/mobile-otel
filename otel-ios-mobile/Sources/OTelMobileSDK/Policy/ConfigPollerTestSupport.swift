import Foundation

/// Test-only helpers that avoid importing Foundation directly in the test
/// files (Swift Testing + Command Line Tools constraint — see
/// BufferedEventTestSupport.swift for the precedent).
public extension ConfigPoller {
    /// Wraps the private URL query helper for test assertions without
    /// requiring the test file to import Foundation.
    static func testHelperAppendQuery(urlString: String, key: String, value: String) -> String {
        guard let url = URL(string: urlString) else { return urlString }
        return Self.appendQuery(url, key: key, value: value).absoluteString
    }

    /// Attempts to construct a poller; tests use this to verify that invalid
    /// endpoints throw without importing Foundation.
    static func testHelperBuild(endpoint: String) throws -> ConfigPoller {
        try ConfigPoller(
            gatewayEndpoint: endpoint,
            authToken: nil,
            evaluator: PolicyEvaluator()
        )
    }

    /// Drives the same parse → gate-push path `applyConfig` uses for a
    /// successful poll, synchronously and without a network round-trip, so
    /// tests can assert the kill-switch precedence rules deterministically.
    ///
    /// Mirrors `applyConfig`'s gate semantics exactly: a config that parses
    /// but omits the `sdk` block maps to `SDKRemoteConfig.default`
    /// (re-enable / no restriction); a config that fails to parse leaves the
    /// gate untouched (returns `false`, modelling the keep-last fail-open
    /// behaviour where `applyConfig` is never reached).
    @discardableResult
    static func testHelperApplyToGate(jsonString: String, gate: RemoteGate) -> Bool {
        guard let config = PolicyParser.parseConfigV2(jsonString: jsonString) else {
            return false
        }
        gate.update(config.sdkConfig ?? .default)
        return true
    }

    /// Builds a poller wired to the given `gate` and an isolated, in-memory
    /// `UserDefaults` suite, so warm-start ordering tests don't import
    /// Foundation and don't touch the shared `standard` domain. The endpoint
    /// is a syntactically-valid URL that is never actually fetched — tests
    /// drive `testApply(...)` directly.
    static func testHelperBuildWithGate(gate: RemoteGate, suiteName: String) -> ConfigPoller {
        let defaults = UserDefaults(suiteName: suiteName) ?? .standard
        defaults.removePersistentDomain(forName: suiteName)
        // Force-unwrap is safe: the endpoint is a constant valid URL.
        return try! ConfigPoller(
            gatewayEndpoint: "https://gateway.example.com/config",
            authToken: nil,
            evaluator: PolicyEvaluator(),
            remoteGate: gate,
            defaults: defaults
        )
    }
}
