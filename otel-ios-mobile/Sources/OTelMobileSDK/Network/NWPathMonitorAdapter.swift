import Foundation
import Network

/// NF-011: Bridges Apple's `NWPathMonitor` to a `NetworkAvailabilityWatcher`.
///
/// On every path update, examines `.status`: `.satisfied` → `watcher.onAvailable()`;
/// everything else → `watcher.onLost()`. The watcher applies the LOST →
/// AVAILABLE transition filter so only genuine offline→online edges produce
/// a `.restored` event downstream.
///
/// Apple's documentation considers `.satisfied` "network is available";
/// `.unsatisfied` and `.requiresConnection` both mean "not currently available."
/// We collapse the latter two into "lost" — a customer-visible flush wouldn't
/// distinguish them anyway.
///
/// Lifecycle: callers create the adapter, then `start()` to begin observing.
/// Stop is implicit via deinit (cancels the monitor) but `stop()` is provided
/// for explicit shutdown in tests.
///
/// See: docs/epics/NETWORK_RESTORED_FLUSH_EPIC.md (NF-011).
public final class NWPathMonitorAdapter: @unchecked Sendable {

    private let monitor: NWPathMonitor
    private let watcher: NetworkAvailabilityWatcher
    private let queue: DispatchQueue

    public init(
        watcher: NetworkAvailabilityWatcher,
        queue: DispatchQueue = DispatchQueue(label: "io.dash0.mobile.NWPathMonitorAdapter", qos: .utility)
    ) {
        self.watcher = watcher
        self.queue = queue
        self.monitor = NWPathMonitor()
    }

    /// Begin observing path updates. Idempotent — `NWPathMonitor.start` is
    /// safe to call multiple times but `pathUpdateHandler` is replaced on
    /// each call, so we set it once here.
    public func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            if path.status == .satisfied {
                self.watcher.onAvailable()
            } else {
                self.watcher.onLost()
            }
        }
        monitor.start(queue: queue)
    }

    public func stop() {
        monitor.cancel()
    }

    deinit {
        monitor.cancel()
    }
}
