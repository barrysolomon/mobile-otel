import Foundation

/// SDK-internal in-memory `BufferedEventExporter` that captures exported
/// events for inspection. Used by SDK tests that need `BufferedEvent`-level
/// introspection (seqId, payload). Production code wires the OTel path; new
/// tests prefer the OTel-native `RecordingLogExporter` pattern shown in
/// `HybridHttpErrorFlushTests`.
///
/// Lock-protected class (not an actor): `BufferedEventExporter.export` is a
/// synchronous requirement so the drain surface never needs a
/// cooperative-executor slot (issue #66).
internal final class CapturingExporter: BufferedEventExporter, @unchecked Sendable {
    private let lock = NSLock()
    private var _events: [BufferedEvent] = []
    private var _exportCallCount: Int = 0

    init() {}

    func export(_ events: [BufferedEvent]) -> BufferExportResult {
        lock.lock(); defer { lock.unlock() }
        _events.append(contentsOf: events)
        _exportCallCount += 1
        return .success
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        _events.removeAll()
        _exportCallCount = 0
    }

    var events: [BufferedEvent] {
        lock.lock(); defer { lock.unlock() }
        return _events
    }

    var exportCallCount: Int {
        lock.lock(); defer { lock.unlock() }
        return _exportCallCount
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return _events.count
    }
}
