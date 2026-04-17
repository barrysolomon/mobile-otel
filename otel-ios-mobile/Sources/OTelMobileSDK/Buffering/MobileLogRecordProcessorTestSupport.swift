import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Internal test helpers. Lives in the SDK module so test files can exercise
/// the processor without importing Foundation directly (Swift Testing's
/// `_Testing_Foundation` overlay is shipped incomplete in macOS Command Line
/// Tools). See `BufferedEventTestSupport.swift` for precedent.
extension MobileLogRecordProcessor {
    /// Emits a synthetic log record through the real `onEmit` path. Returns
    /// once the detached buffer-append task has completed so tests can
    /// deterministically observe state afterwards.
    func emitForTesting(
        body: String = "msg",
        severity: Severity? = .info,
        attributes: [String: AttributeValue] = [:]
    ) async {
        let record = ReadableLogRecord(
            resource: Resource(),
            instrumentationScopeInfo: InstrumentationScopeInfo(name: "test"),
            timestamp: Date(),
            severity: severity,
            body: .string(body),
            attributes: attributes
        )
        onEmit(logRecord: record)
        // Yield several times so the detached append task observed in
        // `onEmit` has a chance to run before the caller reads the buffer.
        for _ in 0..<20 { await Task.yield() }
        // Policy evaluation is a deeper async chain (evaluator actor +
        // flushWindow). Give it a real wall-clock tick to settle.
        try? await Task.sleep(nanoseconds: 50_000_000)
    }
}
