import Foundation

/// Internal test helpers for `PolicyParser`. Shipped with the SDK module so that
/// test files can invoke the parser without importing `Foundation` directly —
/// Swift Testing's `_Testing_Foundation` cross-import overlay ships incomplete
/// with the macOS Command Line Tools. See `DSLv2ModelsTestSupport.swift` for the
/// same pattern.

public extension PolicyParser {
    /// Convenience alias for tests — same as `parseConfigV2(jsonString:)` but
    /// with a shorter, argumentless-label name.
    static func parseV2(_ json: String) -> PolicyConfig? {
        parseConfigV2(jsonString: json)
    }
}

public extension PolicyMatchResult {
    static let none: PolicyMatchResult? = nil
}
