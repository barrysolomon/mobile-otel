import Foundation
import Testing
@testable import NetworkInstrumentation

/// Backfill suite for `OTelURLProtocol`. Targets `canInit`'s host-
/// filtering, scheme-checking, and recursion-guard logic plus the
/// scrub helpers exposed via `NetworkInstrumentationTestSupport`.
///
/// Doesn't drive a full URLSession round-trip — that lives in the
/// scenario validation scripts. The unit-level surface is the gating
/// logic and the URL scrubbing pipeline; both have shipped behaviour
/// that customers depend on for privacy and host-based opt-out.
@Suite("OTelURLProtocol")
struct OTelURLProtocolTests {
    private static let helloHost = "api.example.com"

    // MARK: - hostPassesFilter (canInit equivalent for tests)

    @Test("hostPassesFilter accepts everything when both lists are empty")
    func emptyListsAcceptEverything() {
        let config = NetworkConfig()
        #expect(NetworkInstrumentation.hostPassesFilter(host: Self.helloHost, config: config))
        #expect(NetworkInstrumentation.hostPassesFilter(host: "evil.com", config: config))
    }

    @Test("hostPassesFilter respects ignoredHosts")
    func ignoredHostsDrop() {
        let config = NetworkConfig(ignoredHosts: ["analytics.example.com"])
        #expect(NetworkInstrumentation.hostPassesFilter(host: Self.helloHost, config: config))
        #expect(!NetworkInstrumentation.hostPassesFilter(
            host: "analytics.example.com",
            config: config
        ))
    }

    @Test("hostPassesFilter respects allowedHosts allowlist")
    func allowedHostsAllowlist() {
        let config = NetworkConfig(allowedHosts: ["dash0.com"])
        #expect(NetworkInstrumentation.hostPassesFilter(host: "dash0.com", config: config))
        #expect(!NetworkInstrumentation.hostPassesFilter(host: "evil.com", config: config))
    }

    @Test("hostPassesFilter is case-insensitive on host")
    func hostMatchIsCaseInsensitive() {
        let config = NetworkConfig(ignoredHosts: ["ANALYTICS.example.com"])
        #expect(!NetworkInstrumentation.hostPassesFilter(
            host: "analytics.example.com",
            config: config
        ))
        #expect(!NetworkInstrumentation.hostPassesFilter(
            host: "Analytics.Example.COM",
            config: config
        ))
    }

    @Test("ignoredHosts wins over allowedHosts when both contain the host")
    func ignoredBeatsAllowed() {
        let config = NetworkConfig(
            ignoredHosts: ["analytics.example.com"],
            allowedHosts: ["analytics.example.com"]
        )
        #expect(!NetworkInstrumentation.hostPassesFilter(
            host: "analytics.example.com",
            config: config
        ))
    }

    // MARK: - scrubUrlString — legacy strip-only path (scrubUrls = false)

    @Test("legacy strip path drops the entire query string")
    func legacyStripDropsQuery() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/users?token=secret&page=2",
            stripQuery: true
        )
        #expect(result == "https://api.example.com/users")
    }

    @Test("legacy strip path keeps the URL untouched when stripQuery is false")
    func legacyStripKeepsQuery() {
        let url = "https://api.example.com/users?token=secret&page=2"
        let result = NetworkInstrumentation.scrubForTesting(urlString: url, stripQuery: false)
        #expect(result == url)
    }

    @Test("legacy strip path preserves URL fragment")
    func legacyStripPreservesFragment() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/users#section",
            stripQuery: true
        )
        #expect(result.contains("#section"))
    }

    // MARK: - scrubUrlString — PiiScrubber-routed path (scrubUrls = true)

    @Test("PiiScrubber path collapses UUID + numeric path segments")
    func piiPathCollapsesSegments() {
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

    @Test("PiiScrubber path leaves segments intact when scrubPathSegments=false")
    func piiPathKeepsSegmentsWhenDisabled() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/users/12345",
            stripQuery: true,
            scrubPathSegments: false
        )
        #expect(result.contains("12345"))
    }

    @Test("PiiScrubber path redacts sensitive query keys when query allowed")
    func piiPathRedactsSensitiveKeys() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/users?page=2&token=secret",
            stripQuery: false,
            scrubPathSegments: false
        )
        #expect(result.contains("page=2"))
        #expect(result.contains("[REDACTED]"))
        #expect(!result.contains("secret"))
    }

    @Test("PiiScrubber path keeps non-sensitive query intact")
    func piiPathKeepsNonSensitiveQuery() {
        let result = NetworkInstrumentation.scrubForTesting(
            urlString: "https://api.example.com/search?q=opentelemetry&page=2",
            stripQuery: false,
            scrubPathSegments: false
        )
        #expect(result.contains("q=opentelemetry"))
        #expect(result.contains("page=2"))
    }

    // MARK: - sensitive header floor

    @Test("sensitiveHeaders set always includes auth + cookie variants")
    func sensitiveHeadersFloor() {
        let s = OTelURLProtocol.sensitiveHeaders
        #expect(s.contains("authorization"))
        #expect(s.contains("cookie"))
        #expect(s.contains("set-cookie"))
        #expect(s.contains("proxy-authorization"))
        #expect(s.contains("x-api-key"))
    }
}
