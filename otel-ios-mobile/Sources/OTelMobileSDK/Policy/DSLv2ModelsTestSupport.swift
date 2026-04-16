import Foundation

/// Internal test helpers for DSL v2 model decoding. These live in the SDK
/// module so test files can invoke `JSONDecoder` without importing `Foundation`
/// directly — Swift Testing's `_Testing_Foundation` cross-import overlay is
/// shipped incomplete with the macOS Command Line Tools, so importing
/// Foundation from a test file fails to resolve. See
/// `BufferedEventTestSupport.swift` / `MobileLogRecordProcessorTestSupport.swift`
/// for the same pattern.

public extension DSLMatcher {
    static func decode(fromJsonString json: String) throws -> DSLMatcher {
        try JSONDecoder().decode(DSLMatcher.self, from: Data(json.utf8))
    }
}

public extension DSLAction {
    static func decode(fromJsonString json: String) throws -> DSLAction {
        try JSONDecoder().decode(DSLAction.self, from: Data(json.utf8))
    }
}

public extension DSLWorkflow {
    static func decode(fromJsonString json: String) throws -> DSLWorkflow {
        try JSONDecoder().decode(DSLWorkflow.self, from: Data(json.utf8))
    }
}

public extension DSLConfigV2 {
    static func decode(fromJsonString json: String) throws -> DSLConfigV2 {
        try JSONDecoder().decode(DSLConfigV2.self, from: Data(json.utf8))
    }
}
