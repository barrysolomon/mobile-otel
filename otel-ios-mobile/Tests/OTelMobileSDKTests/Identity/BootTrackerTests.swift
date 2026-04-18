import Testing
@testable import OTelMobileCore

/// Behavioural-parity coverage for the iOS `BootTracker`. Mirrors the
/// intent of the Android tests (which simply assert "id is non-empty
/// and stable") — the underlying source is different (`/proc` vs
/// `sysctl`) so we can't compare values across platforms, only contract.
@Suite("BootTracker")
struct BootTrackerTests {
    @Test("currentBootId is non-empty")
    func nonEmpty() {
        #expect(!BootTracker.currentBootId.isEmpty)
    }

    @Test("currentBootId is stable across repeated reads")
    func stable() {
        let a = BootTracker.currentBootId
        let b = BootTracker.currentBootId
        #expect(a == b)
    }

    @Test("readBootId returns the same value as the cached currentBootId")
    func readMatchesCached() {
        // sysctl read should succeed on iOS Simulator + macOS host. If
        // it ever returns nil here, the platform sandbox shifted —
        // `currentBootId` would have used the UUID fallback in that
        // case, and this assertion catches the regression.
        guard let live = BootTracker.readBootId() else {
            Issue.record("sysctl readBootId returned nil — fallback path now active")
            return
        }
        #expect(live == BootTracker.currentBootId)
    }

    @Test("repeated readBootId calls return identical strings")
    func readIsIdempotent() {
        let first = BootTracker.readBootId()
        let second = BootTracker.readBootId()
        #expect(first == second)
    }
}
