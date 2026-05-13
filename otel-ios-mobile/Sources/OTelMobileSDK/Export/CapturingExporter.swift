import Foundation

/// SDK-internal in-memory `BufferedEventExporter` that captures exported
/// events for inspection. Used by SDK tests that need `BufferedEvent`-level
/// introspection (seqId, payload). Production code wires the OTel path; new
/// tests prefer the OTel-native `RecordingLogExporter` pattern shown in
/// `HybridHttpErrorFlushTests`.
internal actor CapturingExporter: BufferedEventExporter {
    private(set) var events: [BufferedEvent] = []
    private(set) var exportCallCount: Int = 0

    init() {}

    func export(_ events: [BufferedEvent]) async -> BufferExportResult {
        self.events.append(contentsOf: events)
        exportCallCount += 1
        return .success
    }

    func reset() {
        events.removeAll()
        exportCallCount = 0
    }

    var count: Int { events.count }
}
