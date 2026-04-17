import Testing
@testable import NetworkInstrumentation

@Suite("NetworkConfig")
struct NetworkConfigTests {
    @Test("default strips query strings")
    func defaultStripsQuery() {
        #expect(NetworkConfig.default.stripQueryStrings == true)
    }

    @Test("default captures common response headers")
    func defaultCaptureCommonResponseHeaders() {
        #expect(NetworkConfig.default.capturedResponseHeaders.contains("content-type"))
    }

    @Test("default error threshold is 500")
    func defaultErrorThreshold500() {
        #expect(NetworkConfig.default.errorStatusThreshold == 500)
    }

    @Test("default does not capture request headers or propagate context")
    func conservativeDefaults() {
        #expect(NetworkConfig.default.capturedRequestHeaders.isEmpty)
        #expect(NetworkConfig.default.propagateTraceContext == false)
    }

    @Test("host lists are normalized to lowercase")
    func lowercaseHosts() {
        let config = NetworkConfig(
            ignoredHosts: ["Analytics.Example.com"],
            allowedHosts: ["Dash0.COM"]
        )
        #expect(config.ignoredHosts.contains("analytics.example.com"))
        #expect(config.allowedHosts.contains("dash0.com"))
    }

    @Test("scrubForTesting strips query when requested")
    func scrubStripsQuery() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/users?token=secret",
            stripQuery: true
        )
        #expect(result == "https://api.example.com/users")
    }

    @Test("scrubForTesting preserves query when stripping disabled")
    func scrubKeepsQuery() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/users?token=secret",
            stripQuery: false
        )
        #expect(result == "https://api.example.com/users?token=secret")
    }

    @Test("hostPassesFilter allowlist blocks non-allowed hosts")
    func allowlistBlocks() {
        let config = NetworkConfig(allowedHosts: ["dash0.com"])
        #expect(NetworkInstrumentation.hostPassesFilter(host: "dash0.com", config: config))
        #expect(!NetworkInstrumentation.hostPassesFilter(host: "evil.com", config: config))
    }

    @Test("hostPassesFilter ignoredHosts blocks listed hosts")
    func ignoredBlocks() {
        let config = NetworkConfig(ignoredHosts: ["analytics.example.com"])
        #expect(!NetworkInstrumentation.hostPassesFilter(host: "Analytics.Example.com", config: config))
        #expect(NetworkInstrumentation.hostPassesFilter(host: "dash0.com", config: config))
    }
}
