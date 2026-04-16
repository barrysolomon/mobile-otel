import Foundation
import DequeModule

public actor RAMEventBuffer {
    private var events: Deque<BufferedEvent>
    private let capacity: Int

    public init(capacity: Int) {
        self.capacity = capacity
        self.events = Deque()
    }

    public var count: Int { events.count }

    @discardableResult
    public func append(_ event: BufferedEvent) -> BufferedEvent? {
        if events.count >= capacity {
            let evicted = events.removeFirst()
            events.append(event)
            return evicted
        }
        events.append(event)
        return nil
    }

    public func flush() -> [BufferedEvent] {
        let result = Array(events)
        events.removeAll()
        return result
    }

    public func flushWindow(lastMs: UInt64) -> [BufferedEvent] {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        let cutoff = now - lastMs
        let matching = events.filter { $0.timestampMs >= cutoff }
        events.removeAll { $0.timestampMs >= cutoff }
        return Array(matching)
    }

    public func peek() -> [BufferedEvent] {
        Array(events)
    }
}
