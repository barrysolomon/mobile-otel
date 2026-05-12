import Testing
@testable import OTelMobileSDK

/// NF-008: iOS parity of the Android `NetworkAvailabilityWatcherTest`.
/// The watcher emits a `Restored` transition only on a genuine
/// LOST → AVAILABLE edge. Spurious `onAvailable` callbacks (Wi-Fi handoff,
/// path validation, callback registration) must be filtered out.
///
/// `NWPathMonitor` plumbing arrives in a follow-up; this test exercises
/// only the pure state machine so it doesn't depend on the network stack.
///
/// See: docs/epics/NETWORK_RESTORED_FLUSH_EPIC.md
@Suite("NetworkAvailabilityWatcher")
struct NetworkAvailabilityWatcherTests {

    private final class RecordingListener: NetworkAvailabilityListener, @unchecked Sendable {
        var events: [NetworkAvailabilityWatcher.Transition] = []
        func onTransition(_ transition: NetworkAvailabilityWatcher.Transition) {
            events.append(transition)
        }
    }

    @Test("LOST then AVAILABLE emits one Restored transition")
    func lostThenAvailableEmitsRestored() {
        let watcher = NetworkAvailabilityWatcher()
        let listener = RecordingListener()
        watcher.addListener(listener)

        // First onAvailable from UNKNOWN must NOT emit Restored
        watcher.onAvailable()
        #expect(listener.events.isEmpty)

        watcher.onLost()
        watcher.onAvailable()

        #expect(listener.events.count == 1)
        #expect(listener.events.first == .restored)
    }

    @Test("back-to-back AVAILABLE calls emit no transitions")
    func backToBackAvailableEmitsNothing() {
        let watcher = NetworkAvailabilityWatcher()
        let listener = RecordingListener()
        watcher.addListener(listener)

        watcher.onAvailable()
        watcher.onAvailable()
        watcher.onAvailable()

        #expect(listener.events.isEmpty)
    }

    @Test("back-to-back LOST then AVAILABLE emits one Restored")
    func backToBackLostEmitsOneRestored() {
        let watcher = NetworkAvailabilityWatcher()
        let listener = RecordingListener()
        watcher.addListener(listener)

        watcher.onLost()
        watcher.onLost()
        watcher.onAvailable()

        #expect(listener.events.count == 1)
    }

    @Test("multiple LOST/AVAILABLE cycles each emit one Restored")
    func multipleCyclesEmitOneEach() {
        let watcher = NetworkAvailabilityWatcher()
        let listener = RecordingListener()
        watcher.addListener(listener)

        watcher.onLost(); watcher.onAvailable()
        watcher.onLost(); watcher.onAvailable()
        watcher.onLost(); watcher.onAvailable()

        #expect(listener.events.count == 3)
        #expect(listener.events.allSatisfy { $0 == .restored })
    }

    @Test("removed listener stops receiving events")
    func removedListenerNoLongerReceives() {
        let watcher = NetworkAvailabilityWatcher()
        let listener = RecordingListener()
        watcher.addListener(listener)

        watcher.onLost(); watcher.onAvailable()
        #expect(listener.events.count == 1)

        watcher.removeListener(listener)
        watcher.onLost(); watcher.onAvailable()
        #expect(listener.events.count == 1)
    }
}
