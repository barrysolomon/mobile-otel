import Testing
@testable import OTelMobileSDK

/// Tests for `BuildIdReader` — symbolication Phase 1
/// (docs/design/symbolication.md). The Mach-O `LC_UUID` of the main
/// executable is the key that matches a crash to its dSYM, so the reader
/// must return a stable, well-formed UUID on every Apple platform the SDK
/// compiles for (the test process itself is a Mach-O executable, so this
/// exercises the real parser — no fixtures).
///
/// No `import Foundation` here (CLT `_Testing_Foundation` gap — see
/// otel-ios-mobile/CLAUDE.md); UUID shape is validated with stdlib only.
@Suite("BuildIdReader")
struct BuildIdReaderTests {
    /// Canonical 8-4-4-4-12 lowercase hex form — how dSYM stores are
    /// conventionally keyed (debug-id convention).
    static func isCanonicalLowercaseUUID(_ s: String) -> Bool {
        let groups = s.split(separator: "-", omittingEmptySubsequences: false)
        let lengths = groups.map { $0.count }
        guard lengths == [8, 4, 4, 4, 12] else { return false }
        let hex = Set("0123456789abcdef")
        return groups.joined().allSatisfy { hex.contains($0) }
    }

    @Test("main executable UUID is a well-formed lowercase UUID")
    func uuidIsWellFormed() throws {
        let buildId = try #require(BuildIdReader.mainExecutableUUID())
        #expect(Self.isCanonicalLowercaseUUID(buildId), "not a canonical lowercase UUID: \(buildId)")
    }

    @Test("main executable UUID is stable across calls")
    func uuidIsStable() {
        #expect(BuildIdReader.mainExecutableUUID() == BuildIdReader.mainExecutableUUID())
    }
}
