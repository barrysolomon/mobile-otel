import Testing
@testable import OTelMobileCore

/// Behavioural-parity port of Android's `ExportStatusManagerTest`. Same
/// 7 cases — listener add/remove/clear, multi-listener fan-out, removal-
/// during-callback safety, every status variant, no-listener no-op.
///
/// Each test constructs its own `ExportStatusManager()` instead of
/// reaching into `.shared`, so Swift Testing's default parallel
/// execution can't race listener-add/clear across tests.
@Suite("ExportStatusManager")
struct ExportStatusManagerTests {

    /// Test-only listener that records every status it receives.
    final class CapturingListener: ExportStatusListener {
        // `nonisolated(unsafe)` because Swift Testing parallelises tests
        // and this stays single-threaded per-instance — each test makes
        // its own listener and never shares it.
        var received: [ExportStatus] = []
        func onExportStatus(_ status: ExportStatus) {
            received.append(status)
        }
    }

    /// Listener that removes itself the moment it gets a callback —
    /// exercises the snapshot-then-iterate guarantee.
    final class SelfRemovingListener: ExportStatusListener {
        let manager: ExportStatusManager
        var callCount = 0
        init(_ manager: ExportStatusManager) { self.manager = manager }
        func onExportStatus(_ status: ExportStatus) {
            callCount += 1
            manager.removeListener(self)
        }
    }

    @Test("addListener delivers subsequent notify")
    func addAndNotify() {
        let mgr = ExportStatusManager()
        mgr.clearListeners()
        let listener = CapturingListener()
        mgr.addListener(listener)
        mgr.notify(.success(eventCount: 5))
        #expect(listener.received == [.success(eventCount: 5)])
        mgr.clearListeners()
    }

    @Test("removeListener stops further notifies")
    func removeListener() {
        let mgr = ExportStatusManager()
        mgr.clearListeners()
        let listener = CapturingListener()
        mgr.addListener(listener)
        mgr.notify(.success(eventCount: 1))
        mgr.removeListener(listener)
        mgr.notify(.success(eventCount: 2))
        #expect(listener.received.count == 1)
        mgr.clearListeners()
    }

    @Test("clearListeners drops every registration")
    func clearListeners() {
        let mgr = ExportStatusManager()
        mgr.clearListeners()
        let a = CapturingListener()
        let b = CapturingListener()
        mgr.addListener(a)
        mgr.addListener(b)
        mgr.clearListeners()
        mgr.notify(.success(eventCount: 99))
        #expect(a.received.isEmpty)
        #expect(b.received.isEmpty)
    }

    @Test("notify fans out to every listener in registration order")
    func multipleListeners() {
        let mgr = ExportStatusManager()
        mgr.clearListeners()
        let listeners = (0..<3).map { _ in CapturingListener() }
        for l in listeners { mgr.addListener(l) }
        mgr.notify(.success(eventCount: 7))
        for l in listeners {
            #expect(l.received == [.success(eventCount: 7)])
        }
        mgr.clearListeners()
    }

    @Test("listener removing itself mid-callback doesn't break iteration")
    func selfRemovalSafe() {
        let mgr = ExportStatusManager()
        mgr.clearListeners()
        let removable = SelfRemovingListener(mgr)
        let observer = CapturingListener()
        mgr.addListener(removable)
        mgr.addListener(observer)
        mgr.notify(.success(eventCount: 1))
        // Both still get the first callback (snapshot-then-iterate).
        #expect(removable.callCount == 1)
        #expect(observer.received.count == 1)
        // Now `removable` is gone — the second notify reaches `observer`
        // only.
        mgr.notify(.success(eventCount: 2))
        #expect(removable.callCount == 1)
        #expect(observer.received.count == 2)
        mgr.clearListeners()
    }

    @Test("every ExportStatus variant round-trips through notify")
    func allVariantsDelivered() {
        let mgr = ExportStatusManager()
        mgr.clearListeners()
        let listener = CapturingListener()
        mgr.addListener(listener)
        let events: [ExportStatus] = [
            .success(eventCount: 10),
            .retrying(attempt: 1, maxAttempts: 3, delayMs: 1000),
            .failed(reason: "boom", eventCount: 10, attempt: 4),
            .authError(reason: "401 Unauthorized", eventCount: 10),
        ]
        for e in events { mgr.notify(e) }
        #expect(listener.received == events)
        mgr.clearListeners()
    }

    @Test("notify with no listeners is a safe no-op")
    func noListenersNoOp() {
        let mgr = ExportStatusManager()
        mgr.clearListeners()
        // Just exercising that this doesn't crash. Swift Testing has no
        // built-in "no exception thrown" assertion — the implicit one is
        // "the test reaches the end".
        mgr.notify(.success(eventCount: 0))
    }
}
