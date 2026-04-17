import Foundation
import DequeModule

/// Bounded RAM ring buffer of `BufferedEvent`s. Actor-isolated so appends
/// from any thread are serialized. Three separate caps keep us a "good
/// citizen" on host apps:
///
/// - **Event count cap** (`capacity`) — hard upper bound on number of events
///   held. Oldest is evicted when full.
/// - **Total size cap** (`maxTotalBytes`) — approximate ceiling on cumulative
///   `sizeBytes`. Evicts oldest events until we're under budget before
///   accepting a new append. Prevents runaway memory if events grow large.
/// - **Per-event size cap** (`maxEventBytes`) — refuses to buffer single
///   events larger than this. Silently drops them and returns a synthetic
///   evicted marker so callers can record the drop.
///
/// Defaults are conservative (50k events hypothetical, but we honor the
/// caller's explicit `capacity`; bytes default to 10 MB / 256 KB). Adjust
/// via the full init for more aggressive limits.
public actor RAMEventBuffer {
    private var events: Deque<BufferedEvent>
    private var totalBytes: Int = 0
    private let capacity: Int
    private let maxTotalBytes: Int
    private let maxEventBytes: Int

    /// Count of events dropped because they exceeded `maxEventBytes`. Useful
    /// for test assertions and a future health-metric gauge.
    public private(set) var droppedOversizeCount: Int = 0

    public init(capacity: Int) {
        self.init(
            capacity: capacity,
            maxTotalBytes: 10 * 1024 * 1024,  // 10 MB default
            maxEventBytes: 256 * 1024          // 256 KB default
        )
    }

    public init(capacity: Int, maxTotalBytes: Int, maxEventBytes: Int) {
        self.capacity = capacity
        self.maxTotalBytes = maxTotalBytes
        self.maxEventBytes = maxEventBytes
        self.events = Deque()
    }

    public var count: Int { events.count }
    public var approximateBytes: Int { totalBytes }

    @discardableResult
    public func append(_ event: BufferedEvent) -> BufferedEvent? {
        // P2 safety: per-event size cap. Silently drop anything too large.
        // A rogue caller that tries to buffer megabyte payloads should NOT be
        // able to balloon app memory.
        if event.sizeBytes > maxEventBytes {
            droppedOversizeCount += 1
            return event
        }

        var evicted: BufferedEvent?

        // Capacity (count) cap — Android parity.
        if events.count >= capacity {
            let drop = events.removeFirst()
            totalBytes -= drop.sizeBytes
            evicted = drop
        }

        // Total bytes cap — additional defense. Evict until under budget.
        // On the OTel-native path `sizeBytes` may be zero (record-only), in
        // which case totalBytes stays small and this loop is a no-op.
        while totalBytes + event.sizeBytes > maxTotalBytes, !events.isEmpty {
            let drop = events.removeFirst()
            totalBytes -= drop.sizeBytes
            evicted = drop
        }

        events.append(event)
        totalBytes += event.sizeBytes
        return evicted
    }

    public func flush() -> [BufferedEvent] {
        let result = Array(events)
        events.removeAll()
        totalBytes = 0
        return result
    }

    public func flushWindow(lastMs: UInt64) -> [BufferedEvent] {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        let cutoff = now - lastMs
        let matching = events.filter { $0.timestampMs >= cutoff }
        events.removeAll { $0.timestampMs >= cutoff }
        totalBytes = events.reduce(0) { $0 + $1.sizeBytes }
        return Array(matching)
    }

    public func peek() -> [BufferedEvent] {
        Array(events)
    }
}
