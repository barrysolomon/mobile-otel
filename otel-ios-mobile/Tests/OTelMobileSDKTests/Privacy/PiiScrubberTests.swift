import Testing
@testable import OTelMobileCore

/// Behavioural-parity port of Android's `PiiScrubberTest.kt` — 40 tests
/// in 8 groups (A: scrubUrl × 8, B: scrubDeepLink × 2, C: scrubExceptionMessage
/// × 8, D: scrubStackTrace × 4, E: scrubText × 4, F: containsPii × 5,
/// G: isValidAttributeKey × 6, H: scrubAttributes × 3). Same input strings,
/// same expected tokens. If a customer dashboard counts `[EMAIL]` markers
/// across both platforms, they should match.
@Suite("PiiScrubber")
struct PiiScrubberTests {

    // MARK: - Group A: scrubUrl (8 tests)

    @Test("scrubUrl removes all query params by default")
    func a1_scrubUrlRemovesQueryParams() {
        let result = PiiScrubber.scrubUrl("https://api.example.com/users?token=abc123&page=2")
        #expect(!result.contains("?"))
        #expect(!result.contains("token"))
        #expect(!result.contains("abc123"))
    }

    @Test("scrubUrl with allowQueryParams keeps non-sensitive and redacts sensitive")
    func a2_scrubUrlAllowsNonSensitive() {
        let result = PiiScrubber.scrubUrl(
            "https://api.example.com/users?page=2&token=secret",
            allowQueryParams: true
        )
        #expect(result.contains("page=2"))
        #expect(!result.contains("secret"))
        #expect(result.contains("[REDACTED]"))
    }

    @Test("scrubUrl replaces UUID in path with {uuid}")
    func a3_scrubUrlReplacesUuid() {
        let result = PiiScrubber.scrubUrl(
            "https://api.example.com/items/550e8400-e29b-41d4-a716-446655440000/details"
        )
        #expect(result.contains("{uuid}"))
        #expect(!result.contains("550e8400"))
    }

    @Test("scrubUrl replaces numeric path id with {id}")
    func a4_scrubUrlReplacesNumericId() {
        let result = PiiScrubber.scrubUrl("https://api.example.com/users/12345/profile")
        #expect(result.contains("{id}"))
        #expect(!result.contains("12345"))
    }

    @Test("scrubUrl with scrubPathSegments=false leaves path untouched")
    func a5_scrubUrlScrubPathSegmentsFalse() {
        let result = PiiScrubber.scrubUrl(
            "https://api.example.com/users/12345",
            scrubPathSegments: false
        )
        #expect(result.contains("12345"))
    }

    @Test("scrubUrl preserves scheme and host")
    func a6_scrubUrlPreservesSchemeHost() {
        let result = PiiScrubber.scrubUrl("https://api.example.com/path?q=1")
        #expect(result.hasPrefix("https://api.example.com"))
    }

    @Test("scrubUrl returns [INVALID_URL] for empty input")
    func a7_scrubUrlInvalid() {
        // iOS's URLComponents is more permissive than Android's
        // android.net.Uri — most malformed strings get percent-encoded
        // and accepted. Empty string is the one input that genuinely
        // returns nil from `URLComponents.init`. Same observable
        // contract as Android: "unparseable input → [INVALID_URL]".
        #expect(PiiScrubber.scrubUrl("") == "[INVALID_URL]")
    }

    @Test("scrubUrl handles URL with no query params unchanged in scheme/host")
    func a8_scrubUrlNoQueryParams() {
        let result = PiiScrubber.scrubUrl("https://api.example.com/health")
        #expect(result == "https://api.example.com/health")
    }

    // MARK: - Group B: scrubDeepLink (2 tests)

    @Test("scrubDeepLink removes query params by default")
    func b1_scrubDeepLinkRemovesParams() {
        let result = PiiScrubber.scrubDeepLink("myapp://profile/123?session=abc&ref=email")
        #expect(!result.contains("session"))
        #expect(!result.contains("abc"))
    }

    @Test("scrubDeepLink with allowQueryParams keeps non-sensitive and redacts sensitive")
    func b2_scrubDeepLinkAllowQueryParams() {
        let result = PiiScrubber.scrubDeepLink(
            "myapp://action?ref=newsletter&token=xyz",
            allowQueryParams: true
        )
        #expect(result.contains("ref=newsletter"))
        #expect(!result.contains("xyz"))
    }

    // MARK: - Group C: scrubExceptionMessage (8 tests)

    @Test("scrubExceptionMessage replaces email")
    func c1_email() {
        let result = PiiScrubber.scrubExceptionMessage("Failed for user alice@example.com")
        #expect(result.contains("[EMAIL]"))
        #expect(!result.contains("alice@example.com"))
    }

    @Test("scrubExceptionMessage replaces phone")
    func c2_phone() {
        let result = PiiScrubber.scrubExceptionMessage("Call (555) 123-4567 on retry")
        #expect(result.contains("[PHONE]"))
    }

    @Test("scrubExceptionMessage replaces credit card")
    func c3_creditCard() {
        let result = PiiScrubber.scrubExceptionMessage("Card 4111 1111 1111 1111 declined")
        #expect(result.contains("[CREDIT_CARD]"))
        #expect(!result.contains("4111"))
    }

    @Test("scrubExceptionMessage replaces SSN")
    func c4_ssn() {
        let result = PiiScrubber.scrubExceptionMessage("SSN 123-45-6789 mismatched")
        #expect(result.contains("[SSN]"))
    }

    @Test("scrubExceptionMessage replaces iOS app-container path")
    func c5_iosContainerPath() {
        let result = PiiScrubber.scrubExceptionMessage(
            "File not found at /var/mobile/Containers/Data/Application/ABC12345-DEAD-BEEF-CAFE-123456789012/Documents/x.dat"
        )
        #expect(result.contains("{app-container}/"))
        #expect(!result.contains("ABC12345"))
    }

    @Test("scrubExceptionMessage returns empty for nil input")
    func c6_nilReturnsEmpty() {
        let result = PiiScrubber.scrubExceptionMessage(nil)
        #expect(result == "")
    }

    @Test("scrubExceptionMessage passes clean text through")
    func c7_cleanText() {
        let result = PiiScrubber.scrubExceptionMessage("Cart total exceeded limit")
        #expect(result == "Cart total exceeded limit")
    }

    @Test("scrubExceptionMessage handles multiple PII types in one message")
    func c8_multiplePii() {
        let result = PiiScrubber.scrubExceptionMessage(
            "User foo@bar.com SSN 111-22-3333 phone 555-123-4567 failed"
        )
        #expect(result.contains("[EMAIL]"))
        #expect(result.contains("[SSN]"))
        #expect(result.contains("[PHONE]"))
    }

    // MARK: - Group D: scrubStackTrace (4 tests)

    @Test("scrubStackTrace limits depth to maxDepth")
    func d1_maxDepth() {
        let frames = (0..<100).map { "0  Astro 0x\($0)  Frame\($0)" }
        let result = PiiScrubber.scrubStackTrace(frames, maxDepth: 5)
        let lines = result.split(separator: "\n").filter { !$0.isEmpty }
        #expect(lines.count == 5)
    }

    @Test("scrubStackTrace default depth is 50")
    func d2_defaultDepth() {
        let frames = (0..<100).map { "Frame\($0)" }
        let result = PiiScrubber.scrubStackTrace(frames)
        let lines = result.split(separator: "\n").filter { !$0.isEmpty }
        #expect(lines.count == 50)
    }

    @Test("scrubStackTrace replaces iOS container path in frames")
    func d3_containerPathInFrames() {
        let frames = ["0  /var/mobile/Containers/Data/Application/ABC12345-DEAD-BEEF-CAFE-123456789012/Frameworks/X"]
        let result = PiiScrubber.scrubStackTrace(frames)
        #expect(result.contains("{app-container}/"))
        #expect(!result.contains("ABC12345"))
    }

    @Test("scrubStackTrace returns empty string for empty array")
    func d4_emptyArray() {
        let result = PiiScrubber.scrubStackTrace([])
        #expect(result == "")
    }

    // MARK: - Group E: scrubText (4 tests)

    @Test("scrubText replaces email")
    func e1_email() {
        let result = PiiScrubber.scrubText("Email me at user@host.com please")
        #expect(result.contains("[EMAIL]"))
    }

    @Test("scrubText replaces credit card")
    func e2_creditCard() {
        let result = PiiScrubber.scrubText("Card 4111-1111-1111-1111 was used")
        #expect(result.contains("[CREDIT_CARD]"))
    }

    @Test("scrubText replaces SSN")
    func e3_ssn() {
        let result = PiiScrubber.scrubText("SSN: 987-65-4321")
        #expect(result.contains("[SSN]"))
    }

    @Test("scrubText does not modify text without PII")
    func e4_cleanPassthrough() {
        let result = PiiScrubber.scrubText("Order #1234 shipped in 3 business days")
        #expect(result == "Order #1234 shipped in 3 business days")
    }

    // MARK: - Group F: containsPii (5 tests)

    @Test("containsPii detects email")
    func f1_email() {
        #expect(PiiScrubber.containsPii("send to alice@example.com"))
    }

    @Test("containsPii detects credit card")
    func f2_creditCard() {
        #expect(PiiScrubber.containsPii("card 4111 1111 1111 1111"))
    }

    @Test("containsPii detects SSN")
    func f3_ssn() {
        #expect(PiiScrubber.containsPii("ssn 123-45-6789"))
    }

    @Test("containsPii returns false for clean text")
    func f4_cleanText() {
        #expect(!PiiScrubber.containsPii("Cart total exceeded the daily limit"))
    }

    @Test("containsPii returns false for empty string")
    func f5_empty() {
        #expect(!PiiScrubber.containsPii(""))
    }

    // MARK: - Group G: isValidAttributeKey (6 tests)

    @Test("isValidAttributeKey accepts dotted lowercase")
    func g1_dottedLowercase() {
        #expect(PiiScrubber.isValidAttributeKey("http.request.method"))
        #expect(PiiScrubber.isValidAttributeKey("user_id"))
        #expect(PiiScrubber.isValidAttributeKey("device-model"))
    }

    @Test("isValidAttributeKey rejects uppercase")
    func g2_rejectsUppercase() {
        #expect(!PiiScrubber.isValidAttributeKey("HTTP.method"))
    }

    @Test("isValidAttributeKey rejects leading digit")
    func g3_rejectsLeadingDigit() {
        #expect(!PiiScrubber.isValidAttributeKey("1foo"))
    }

    @Test("isValidAttributeKey rejects spaces")
    func g4_rejectsSpaces() {
        #expect(!PiiScrubber.isValidAttributeKey("foo bar"))
    }

    @Test("isValidAttributeKey rejects empty string")
    func g5_rejectsEmpty() {
        #expect(!PiiScrubber.isValidAttributeKey(""))
    }

    @Test("isValidAttributeKey rejects email-like keys")
    func g6_rejectsEmailLike() {
        #expect(!PiiScrubber.isValidAttributeKey("user@host"))
    }

    // MARK: - Group H: scrubAttributes (3 tests)

    @Test("scrubAttributes scrubs string values containing PII")
    func h1_scrubsStrings() {
        let input: [String: Any] = ["message": "alice@example.com complained", "code": 42]
        let out = PiiScrubber.scrubAttributes(input)
        if let msg = out["message"] as? String {
            #expect(msg.contains("[EMAIL]"))
            #expect(!msg.contains("alice"))
        } else {
            Issue.record("'message' attribute missing or not a string")
        }
    }

    @Test("scrubAttributes passes through non-string values unchanged")
    func h2_passesThroughNonStrings() {
        let input: [String: Any] = ["count": Int64(42), "ratio": 0.75, "ok": true]
        let out = PiiScrubber.scrubAttributes(input)
        #expect((out["count"] as? Int64) == 42)
        #expect((out["ratio"] as? Double) == 0.75)
        #expect((out["ok"] as? Bool) == true)
    }

    @Test("scrubAttributes returns empty for empty input")
    func h3_emptyInput() {
        let out = PiiScrubber.scrubAttributes([:])
        #expect(out.isEmpty)
    }
}
