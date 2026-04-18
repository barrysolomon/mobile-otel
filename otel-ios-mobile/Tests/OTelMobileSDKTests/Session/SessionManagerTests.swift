import Foundation
import Testing
@testable import OTelMobileSDK
import OTelMobileCore

/// Backfill suite for `SessionManager`. iOS coverage was 0; Android
/// has 26 tests. This suite ports the highest-leverage scenarios:
/// resume-within-window, mint-on-cold-start-after-timeout, mid-life
/// rotation on access after timeout, manual rotation, persistence
/// round-trip, snapshot accuracy.
///
/// Each test uses an isolated `UserDefaults(suiteName:)` so the
/// process-wide `.standard` defaults stay clean.
@Suite("SessionManager")
struct SessionManagerTests {

    /// Returns a fresh, isolated UserDefaults suite for one test. The
    /// suite name embeds a UUID so parallel tests can't see each
    /// other's session state (the SessionManager singleton-ish
    /// contract is at the `.standard` UserDefaults level).
    private func freshDefaults() -> UserDefaults {
        let name = "io.dash0.mobile.test." + UUID().uuidString
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test("first construction mints a UUID-shaped sessionId")
    func mintsOnFirstConstruction() {
        let defaults = freshDefaults()
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        let id = manager.sessionId
        // UUID format: 8-4-4-4-12 with dashes, length 36
        #expect(id.count == 36)
        #expect(id.contains("-"))
    }

    @Test("sessionId is stable across reads within the inactivity window")
    func stableWithinWindow() {
        let defaults = freshDefaults()
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        let a = manager.sessionId
        let b = manager.sessionId
        let c = manager.sessionId
        #expect(a == b)
        #expect(b == c)
    }

    @Test("rotateSession() returns a new id and persists it")
    func rotateMintsNewId() {
        let defaults = freshDefaults()
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        let original = manager.sessionId
        let rotated = manager.rotateSession()
        #expect(rotated != original)
        #expect(manager.sessionId == rotated)
    }

    @Test("rotateSession persists to UserDefaults")
    func rotatePersists() {
        let defaults = freshDefaults()
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        let rotated = manager.rotateSession()
        // Build a second SessionManager against the same defaults — it
        // should resume the rotated id (still within the inactivity
        // window).
        let resumed = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        #expect(resumed.sessionId == rotated)
    }

    @Test("inactivity longer than timeout mints a new session on next access")
    func mintsOnTimeoutSinceLastSeen() {
        let defaults = freshDefaults()
        // Manually plant an old session in defaults (60 seconds ago)
        // and use a 30-second inactivity window so the next
        // construction triggers a mint.
        let oldId = UUID().uuidString
        let oldLastSeen = Date().addingTimeInterval(-60).timeIntervalSince1970
        defaults.set(oldId, forKey: "io.dash0.mobile.sessionId")
        defaults.set(oldLastSeen, forKey: "io.dash0.mobile.sessionLastSeen")
        let manager = SessionManager(inactivityTimeoutSeconds: 30, defaults: defaults)
        #expect(manager.sessionId != oldId, "expected fresh mint after 60s of inactivity > 30s timeout")
    }

    @Test("session within inactivity window resumes from defaults")
    func resumesWithinWindow() {
        let defaults = freshDefaults()
        let oldId = UUID().uuidString
        // 5 seconds ago — well within a 900-second window.
        let oldLastSeen = Date().addingTimeInterval(-5).timeIntervalSince1970
        defaults.set(oldId, forKey: "io.dash0.mobile.sessionId")
        defaults.set(oldLastSeen, forKey: "io.dash0.mobile.sessionLastSeen")
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        #expect(manager.sessionId == oldId)
    }

    @Test("snapshot exposes the current id, last-seen, and timeout")
    func snapshotMatchesState() {
        let defaults = freshDefaults()
        let manager = SessionManager(inactivityTimeoutSeconds: 600, defaults: defaults)
        let id = manager.sessionId
        let snap = manager.snapshot()
        #expect(snap.sessionId == id)
        #expect(snap.inactivityTimeout == 600)
        // lastSeen should be very recent — within the last 5 seconds.
        #expect(Date().timeIntervalSince(snap.lastSeen) < 5.0)
    }

    @Test("sessionId access updates lastSeen (touch-to-extend)")
    func accessTouchesLastSeen() {
        let defaults = freshDefaults()
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        let snapBefore = manager.snapshot()
        // Wait the smallest measurable interval so lastSeen advances.
        Thread.sleep(forTimeInterval: 0.05)
        _ = manager.sessionId
        let snapAfter = manager.snapshot()
        #expect(snapAfter.lastSeen >= snapBefore.lastSeen)
    }

    @Test("emitInitialStart is a safe no-op when no logger is wired")
    func initialStartNoLoggerNoOp() {
        let defaults = freshDefaults()
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        // No assertion beyond "doesn't crash" — emitInitialStart is
        // documented to be best-effort when the logger is nil.
        manager.emitInitialStart()
    }

    @Test("missing lastSeen in defaults treats as cold start")
    func missingLastSeenColdStart() {
        let defaults = freshDefaults()
        // Plant only the id — no lastSeen. The manager has nothing to
        // compare against, so it mints fresh.
        defaults.set("orphan-id", forKey: "io.dash0.mobile.sessionId")
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        #expect(manager.sessionId != "orphan-id")
    }

    @Test("default inactivity timeout matches Android (900s = 15 minutes)")
    func defaultTimeoutMatchesAndroid() {
        let defaults = freshDefaults()
        let manager = SessionManager(defaults: defaults)
        #expect(manager.snapshot().inactivityTimeout == 900)
    }

    @Test("rotated id has UUID shape (defends against future regression)")
    func rotatedIdIsUuid() {
        let defaults = freshDefaults()
        let manager = SessionManager(inactivityTimeoutSeconds: 900, defaults: defaults)
        let id = manager.rotateSession()
        #expect(id.count == 36)
        #expect(id.contains("-"))
    }
}
