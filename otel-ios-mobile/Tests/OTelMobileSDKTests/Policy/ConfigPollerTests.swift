import Testing
@testable import OTelMobileSDK

@Suite("ConfigPoller")
struct ConfigPollerTests {
    @Test("appendQuery adds param when absent")
    func appendAdds() {
        let url = ConfigPoller.testHelperAppendQuery(
            urlString: "https://gateway.dash0.com/config",
            key: "dsl_version",
            value: "2"
        )
        #expect(url.contains("dsl_version=2"))
    }

    @Test("appendQuery replaces existing param")
    func appendReplaces() {
        let url = ConfigPoller.testHelperAppendQuery(
            urlString: "https://gateway.dash0.com/config?dsl_version=1",
            key: "dsl_version",
            value: "2"
        )
        // Exactly one dsl_version=, and it's 2
        let count = url.components(separatedBy: "dsl_version=").count - 1
        #expect(count == 1)
        #expect(url.contains("dsl_version=2"))
        #expect(!url.contains("dsl_version=1"))
    }

    @Test("appendQuery preserves other params")
    func appendPreserves() {
        let url = ConfigPoller.testHelperAppendQuery(
            urlString: "https://gateway.dash0.com/config?foo=bar",
            key: "dsl_version",
            value: "2"
        )
        #expect(url.contains("foo=bar"))
        #expect(url.contains("dsl_version=2"))
    }

    @Test("empty endpoint throws PollerError.invalidEndpoint")
    func emptyEndpoint() async {
        // URL(string: "") returns nil — guaranteed invalid on every Swift
        // version. Newer URL impls (iOS 17+) are too permissive about
        // garbage-string inputs, so we use the empty-string base case.
        do {
            _ = try ConfigPoller.testHelperBuild(endpoint: "")
            Issue.record("Expected throw for empty endpoint")
        } catch {
            // Expected.
        }
    }
}
