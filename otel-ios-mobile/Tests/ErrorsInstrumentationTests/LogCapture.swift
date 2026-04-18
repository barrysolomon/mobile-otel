import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

/// In-memory `LogRecordProcessor` that captures every emitted log so a
/// test can assert on body / severity / attributes. Mirrors the iOS
/// SDK's own `CapturingExporter` pattern but at the processor layer so
/// we exercise the standard OTel `LoggerProvider` wiring rather than
/// faking the `Logger`.
final class LogCapture: LogRecordProcessor, @unchecked Sendable {
    struct CapturedRecord {
        let body: String
        let severity: Severity
        let attributes: [String: AttributeValue]
    }

    private let lock = NSLock()
    private var captured: [CapturedRecord] = []

    var records: [CapturedRecord] {
        lock.lock(); defer { lock.unlock() }
        return captured
    }

    func onEmit(logRecord: ReadableLogRecord) {
        let bodyString: String
        switch logRecord.body {
        case let .string(s)?: bodyString = s
        default: bodyString = ""
        }
        var attrs: [String: AttributeValue] = [:]
        for (k, v) in logRecord.attributes {
            attrs[k] = v
        }
        let record = CapturedRecord(
            body: bodyString,
            severity: logRecord.severity ?? .info,
            attributes: attrs
        )
        lock.lock()
        captured.append(record)
        lock.unlock()
    }

    func forceFlush(explicitTimeout: TimeInterval?) -> ExportResult { .success }

    func shutdown(explicitTimeout: TimeInterval?) -> ExportResult { .success }
}

/// Build a `Logger` whose records flow into the supplied `LogCapture`.
func makeLogger(processor: LogCapture) -> Logger {
    let provider = LoggerProviderBuilder()
        .with(processors: [processor])
        .build()
    return provider.get(instrumentationScopeName: "io.dash0.mobile.test")
}
