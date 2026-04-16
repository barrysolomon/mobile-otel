import Foundation

/// In-memory `BufferedEventExporter` that captures exported events for
/// inspection. Useful for tests, local demos, and smoke tests. Lives in the
/// SDK target (not tests) so downstream consumers can depend on it directly.
public actor CapturingExporter: BufferedEventExporter {
    public private(set) var events: [BufferedEvent] = []
    public private(set) var exportCallCount: Int = 0

    public init() {}

    public func export(_ events: [BufferedEvent]) async -> BufferExportResult {
        self.events.append(contentsOf: events)
        exportCallCount += 1
        return .success
    }

    public func reset() {
        events.removeAll()
        exportCallCount = 0
    }

    public var count: Int { events.count }
}
