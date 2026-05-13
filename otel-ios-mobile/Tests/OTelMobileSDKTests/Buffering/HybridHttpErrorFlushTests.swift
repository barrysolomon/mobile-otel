import Foundation
import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi
import OpenTelemetrySdk

/// Reproduces the 2026-05-12 iOS HYBRID gap end-to-end at the SDK layer:
/// a real `http.error` log emitted through the production OTel exporter path
/// must reach the exporter when a matching DSL policy is loaded. Mirrors the
/// Schedulr iPhone 17 simulator repro (real 503, real log, exportMode=hybrid)
/// without the simulator or backend dependency.
///
/// The previous `PolicyPipelineIntegrationTests` exercise the *legacy*
/// `BufferedEventExporter` path — they do not cover the OTel
/// `LogRecordExporter` branch in `MobileLogRecordProcessor.exportBuffered`,
/// which is what production wires up via `OTelMobile.start`.
@Suite("HybridHttpErrorFlush")
struct HybridHttpErrorFlushTests {

    /// `LogRecordExporter` stub that records every batch it receives so tests
    /// can assert which records reached the OTel-layer exporter (the same
    /// layer `OTLPHttpLogExporter` lives at in production).
    final class RecordingLogExporter: LogRecordExporter, @unchecked Sendable {
        private let lock = NSLock()
        private var _batches: [[ReadableLogRecord]] = []

        var batches: [[ReadableLogRecord]] {
            lock.lock(); defer { lock.unlock() }
            return _batches
        }

        var allRecords: [ReadableLogRecord] {
            batches.flatMap { $0 }
        }

        func export(logRecords: [ReadableLogRecord], explicitTimeout: TimeInterval?) -> ExportResult {
            lock.lock(); defer { lock.unlock() }
            _batches.append(logRecords)
            return .success
        }

        func shutdown(explicitTimeout: TimeInterval?) {}

        func forceFlush(explicitTimeout: TimeInterval?) -> ExportResult { .success }
    }

    /// Emits a `http.error` log via the real `onEmit` path on a processor
    /// wired the way `OTelMobile.start` wires it for HYBRID mode
    /// (OTel `LogRecordExporter`, seeded `http-error-detector` policy, no
    /// continuous-flush timer). The recording exporter MUST see the record —
    /// without that, HYBRID is silently broken even though the policy
    /// matcher matches.
    @Test("HYBRID flushes http.error through OTel exporter when policy matches")
    func hybridFlushesHttpErrorThroughOtelExporter() async throws {
        let recorder = RecordingLogExporter()
        let evaluator = PolicyEvaluator(policies: [
            Policy(
                id: "http-error-detector",
                enabled: true,
                match: Match(
                    logicalOperator: "and",
                    attributes: ["event.name": Condition(equals: "http.error")]
                ),
                actions: Actions(flushWindowMinutes: 2)
            )
        ])
        let processor = MobileLogRecordProcessor(
            buffer: RAMEventBuffer(capacity: 100),
            otelExporter: recorder,
            sessionProvider: StaticSessionProvider(),
            policyEvaluator: evaluator
        )

        // Mirror OTelURLProtocol.urlSession(_:didReceive:completionHandler:)
        // exactly: body = "http.error", severity = .error, attributes carry
        // event.name plus the http.* tags.
        let attrs: [String: AttributeValue] = [
            "event.name": .string("http.error"),
            "http.response.status_code": .int(503),
            "http.request.method": .string("GET"),
            "url.full": .string("http://localhost:3001/api/force-500/run-1"),
        ]
        let record = ReadableLogRecord(
            resource: Resource(),
            instrumentationScopeInfo: InstrumentationScopeInfo(name: "io.dash0.mobile"),
            timestamp: Date(),
            severity: .error,
            body: .string("http.error"),
            attributes: attrs
        )

        processor.onEmit(logRecord: record)

        // The policy path is: append → evaluate → flushWindow → export. All
        // four hops are async; poll the recorder rather than fixed-sleeping.
        let deadline = Date().addingTimeInterval(2.0)
        var received = recorder.allRecords
        while received.isEmpty && Date() < deadline {
            try await Task.sleep(nanoseconds: 25_000_000) // 25ms
            received = recorder.allRecords
        }

        #expect(received.count >= 1,
                "policy-triggered HYBRID flush should hand the http.error record to the OTel exporter")
        let landed = received.first
        #expect(landed?.attributes["event.name"]?.description.contains("http.error") == true,
                "exporter should see event.name=http.error so Dash0 can route the record")
    }

    /// Regression guard: a non-matching event must NOT trigger an OTel-path
    /// export. Without this, a future change that wires "flush on every
    /// emit" into HYBRID would silently turn it back into CONTINUOUS.
    @Test("HYBRID does not flush when no policy matches")
    func hybridDoesNotFlushWithoutPolicyMatch() async throws {
        let recorder = RecordingLogExporter()
        let evaluator = PolicyEvaluator(policies: [
            Policy(
                id: "http-error-detector",
                enabled: true,
                match: Match(
                    logicalOperator: "and",
                    attributes: ["event.name": Condition(equals: "http.error")]
                ),
                actions: Actions(flushWindowMinutes: 2)
            )
        ])
        let processor = MobileLogRecordProcessor(
            buffer: RAMEventBuffer(capacity: 100),
            otelExporter: recorder,
            sessionProvider: StaticSessionProvider(),
            policyEvaluator: evaluator
        )

        let record = ReadableLogRecord(
            resource: Resource(),
            instrumentationScopeInfo: InstrumentationScopeInfo(name: "io.dash0.mobile"),
            timestamp: Date(),
            severity: .info,
            body: .string("ui.tap"),
            attributes: ["event.name": .string("ui.tap")]
        )

        processor.onEmit(logRecord: record)
        try await Task.sleep(nanoseconds: 200_000_000) // 200ms — give any async path time
        #expect(recorder.allRecords.isEmpty,
                "non-matching event should sit in the RAM buffer; no OTel flush")
    }
}
