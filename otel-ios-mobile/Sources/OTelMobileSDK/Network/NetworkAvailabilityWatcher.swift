import Foundation

/// Listener invoked by `NetworkAvailabilityWatcher` on a genuine network transition.
/// Mirrors the Android `NetworkAvailabilityWatcher.Listener` functional interface.
public protocol NetworkAvailabilityListener: AnyObject {
    func onTransition(_ transition: NetworkAvailabilityWatcher.Transition)
}

/// Tracks network availability transitions and notifies listeners only on
/// **genuine** LOST → AVAILABLE transitions.
///
/// Why: `NWPathMonitor.pathUpdateHandler` fires for many non-transition
/// reasons (Wi-Fi handoff, DNS resolution, monitor start). A naive "every
/// path update → flush" wiring would trigger flushes constantly. This
/// watcher applies a small state machine so only the offline→online edge
/// produces a `.restored` event.
///
/// `NWPathMonitor` plumbing (the actual path-update bridge) is intentionally
/// **not** in this class — it arrives in a follow-up so the state-machine
/// logic stays unit-testable on the Foundation-only toolchain (no Network
/// framework dependency in tests).
///
/// See: docs/epics/NETWORK_RESTORED_FLUSH_EPIC.md (NF-008/NF-009).
public final class NetworkAvailabilityWatcher: @unchecked Sendable {

    /// Discrete network states the watcher tracks.
    private enum State {
        case unknown, lost, available
    }

    /// Transitions surfaced to listeners. Only `.restored` today; reserved
    /// enum for future events (e.g. `.degraded`, `.expensive`).
    public enum Transition: Sendable, Equatable {
        case restored
    }

    private let lock = NSLock()
    private var state: State = .unknown
    private var listeners: [WeakBox] = []

    public init() {}

    public func addListener(_ listener: NetworkAvailabilityListener) {
        lock.lock(); defer { lock.unlock() }
        // Prune any tombstoned weak refs while we hold the lock.
        listeners.removeAll { $0.value == nil }
        listeners.append(WeakBox(listener))
    }

    public func removeListener(_ listener: NetworkAvailabilityListener) {
        lock.lock(); defer { lock.unlock() }
        listeners.removeAll { $0.value === listener || $0.value == nil }
    }

    /// Called by the network-system adapter (NWPathMonitor) when a path
    /// becomes satisfied. Emits `.restored` **only** if the previous state
    /// was `.lost`.
    public func onAvailable() {
        let shouldNotify: Bool = {
            lock.lock(); defer { lock.unlock() }
            let wasLost = (state == .lost)
            state = .available
            return wasLost
        }()
        if shouldNotify {
            notifyAll(.restored)
        }
    }

    /// Called by the network-system adapter when the path becomes unsatisfied.
    public func onLost() {
        lock.lock(); defer { lock.unlock() }
        state = .lost
    }

    private func notifyAll(_ transition: Transition) {
        // Snapshot listeners under lock, fire callbacks outside it to avoid
        // re-entrant deadlocks if a listener calls back into the watcher.
        let snapshot: [NetworkAvailabilityListener] = {
            lock.lock(); defer { lock.unlock() }
            return listeners.compactMap { $0.value }
        }()
        for listener in snapshot {
            listener.onTransition(transition)
        }
    }

    /// Holds a weak ref so listeners that go out of scope are auto-collected.
    private final class WeakBox {
        weak var value: NetworkAvailabilityListener?
        init(_ value: NetworkAvailabilityListener) { self.value = value }
    }
}
