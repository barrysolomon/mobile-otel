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

    // MARK: - PiiScrubber-routed wiring (scrubUrls = true)

    @Test("default has scrubUrls + scrubPathSegments enabled")
    func defaultEnablesPiiScrub() {
        #expect(NetworkConfig.default.scrubUrls == true)
        #expect(NetworkConfig.default.scrubPathSegments == true)
    }

    @Test("PiiScrubber path collapses UUID + numeric segments")
    func scrubViaPiiScrubberCollapsesPath() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/users/12345/orders/550e8400-e29b-41d4-a716-446655440000",
            stripQuery: true,
            scrubPathSegments: true
        )
        #expect(result.contains("{id}"))
        #expect(result.contains("{uuid}"))
        #expect(!result.contains("12345"))
        #expect(!result.contains("550e8400"))
    }

    @Test("PiiScrubber path redacts sensitive query keys when query allowed")
    func scrubViaPiiScrubberRedactsSensitiveQueryKey() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/users?page=2&token=secret",
            stripQuery: false,
            scrubPathSegments: false
        )
        #expect(result.contains("page=2"))
        #expect(result.contains("[REDACTED]"))
        #expect(!result.contains("secret"))
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
