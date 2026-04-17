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
}
