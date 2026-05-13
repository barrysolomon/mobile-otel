import Foundation
import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi
import OpenTelemetrySdk

/// Reproduces the UAT 2026-05-13 finding for iPhone 17 cells 2, 4, 6 at the
/// SDK layer. The cell scenario:
///   1. Recovery launch reads the crash marker and emits an `app.crash` log
///      with `event.name="app.crash"`, `severity=.fatal`.
///   2. The seeded `crash-recovery` policy matches `event.name=app.crash` →
///      `flushWindow(5)`.
///   3. The flush drains RAM+disk → OTel exporter → Dash0.
///
/// The assertion: the OTel exporter MUST receive the record. UAT observed
/// zero records in Dash0 for CONT and COND modes; this test pins the SDK
/// guarantee — if the SDK-layer test passes for every mode, the UAT failure
/// is a launch-pipeline timing artifact, not an SDK bug.
@Suite("IOSCrashRecoveryFlush")
struct IOSCrashRecoveryFlushTests {

    /// OTel-native `LogRecordExporter` stub. Same shape as
    /// `HybridHttpErrorFlushTests.RecordingLogExporter` — kept fileprivate to
    /// avoid cross-suite coupling.
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

    /// Builds the production crash-recovery policy. Kept verbatim from
    /// `OTelMobile.start:269` — when the seeded policy shape changes,
    /// production AND this test update together.
    private func crashRecoveryPolicy() -> Policy {
        Policy(
            id: "crash-recovery",
            enabled: true,
            match: Match(
                logicalOperator: "and",
                attributes: ["event.name": Condition(equals: "app.crash")]
            ),
            actions: Actions(flushWindowMinutes: 5)
        )
    }

    /// Builds the `app.crash` log the way `ErrorsInstrumentation.emitAnyPendingCrash`
    /// builds it: body + severity + event.name + crash.* attributes. Includes
    /// the UAT cell_id stamping that `extraResourceAttributes` adds on the
    /// recovery launch.
    private func makeAppCrashRecord(cellId: String) -> ReadableLogRecord {
        let attrs: [String: AttributeValue] = [
            "event.name": .string("app.crash"),
            "crash.from_marker": .bool(true),
            "crash.kind": .string("signal"),
            "crash.signal": .int(11),
            "crash.name": .string("SIGSEGV"),
            "dash0.test.cell_id": .string(cellId),
        ]
        return ReadableLogRecord(
            resource: Resource(),
            instrumentationScopeInfo: InstrumentationScopeInfo(name: "io.dash0.mobile"),
            timestamp: Date(),
            severity: .fatal,
            body: .string("app.crash"),
            attributes: attrs
        )
    }

    @Test("CONT recovery: app.crash exports through OTel exporter")
    func continuousRecoveryExports() async throws {
        try await assertCrashExports(modeNote: "CONT")
    }

    @Test("COND recovery: app.crash exports through OTel exporter")
    func conditionalRecoveryExports() async throws {
        try await assertCrashExports(modeNote: "COND")
    }

    @Test("HYBRID recovery: app.crash exports through OTel exporter (regression guard for cells 10/12)")
    func hybridRecoveryExports() async throws {
        try await assertCrashExports(modeNote: "HYBRID")
    }

    /// Common assertion body. The processor doesn't carry an export-mode
    /// label — the export-mode behaviour difference is in `OTelMobile.start`
    /// wiring (CONT enables `startContinuousFlush`, HYBRID/COND don't). At
    /// the buffer-processor layer the policy-triggered `flushWindow` path is
    /// mode-agnostic. If this passes for every `modeNote`, the SDK
    /// guarantee holds and the UAT gap is in the launch pipeline.
    private func assertCrashExports(modeNote: String) async throws {
        let recorder = RecordingLogExporter()
        let evaluator = PolicyEvaluator(policies: [crashRecoveryPolicy()])
        let processor = MobileLogRecordProcessor(
            buffer: RAMEventBuffer(capacity: 100),
            otelExporter: recorder,
            sessionProvider: StaticSessionProvider(),
            policyEvaluator: evaluator,
            extraRecordAttributes: ["dash0.test.cell_id": "uat-cell-2"]
        )

        let cellId = "uat-cell-2"
        let crash = makeAppCrashRecord(cellId: cellId)
        processor.onEmit(logRecord: crash)

        // Bounded poll loop — append → policy eval → flushWindow → export
        // are all async hops; production assertion has 40s grace, we want
        // sub-second here.
        let deadline = Date().addingTimeInterval(2.0)
        var received = recorder.allRecords
        while received.isEmpty && Date() < deadline {
            try await Task.sleep(nanoseconds: 25_000_000)
            received = recorder.allRecords
        }

        #expect(!received.isEmpty,
                "\(modeNote): the OTel exporter must receive the app.crash record after policy-triggered flush")
        let landed = received.first
        let bodyMatches = landed?.body.flatMap { value -> Bool? in
            if case .string(let s) = value { return s == "app.crash" }
            return nil
        } ?? false
        #expect(bodyMatches, "\(modeNote): exported record body should be 'app.crash'")
    }

    /// Negative-control: a non-crash record at .info severity must NOT
    /// trigger the crash-recovery policy.
    @Test("non-crash record does not trigger crash-recovery flush")
    func nonCrashSkipsFlush() async throws {
        let recorder = RecordingLogExporter()
        let evaluator = PolicyEvaluator(policies: [crashRecoveryPolicy()])
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
        try await Task.sleep(nanoseconds: 200_000_000) // 200ms

        #expect(recorder.allRecords.isEmpty,
                "non-crash record should not trigger crash-recovery flush")
    }
}
