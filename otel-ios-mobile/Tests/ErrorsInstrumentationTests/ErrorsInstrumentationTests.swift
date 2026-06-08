import Foundation
import Testing
@testable import ErrorsInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Backfill suite covering the `recordError` path, install idempotency,
/// and helper utilities that `CrashRecoveryTests` doesn't exercise.
/// Together with `CrashRecoveryTests` this brings iOS Errors coverage
/// to ~20 tests (Android sits at 65; remaining gap is mostly platform-
/// specific paths like Coroutine error capture that don't apply here).
///
/// `.serialized` because `ErrorsInstrumentation.shared` and the on-disk
/// crash-marker file are process-wide singletons.
@Suite("ErrorsInstrumentation", .serialized, .isolatedCrashMarker)
struct ErrorsInstrumentationTests {

    // MARK: - recordError

    private struct TestError: Error {
        let message: String
        var localizedDescription: String { message }
    }

    private struct CustomError: LocalizedError {
        let reason: String
        var errorDescription: String? { reason }
    }

    @Test("recordError emits an app.error log at .error severity")
    func recordErrorEmits() {
        ErrorsInstrumentation.removeMarkerForTesting()
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.shared.install(logger: logger)
        ErrorsInstrumentation.shared.recordError(TestError(message: "boom"))
        let errors = cap.records.filter { $0.body == "app.error" }
        #expect(errors.count == 1)
        #expect(errors.first?.severity == .error)
    }

    @Test("recordError sets error.type from runtime type")
    func recordErrorSetsType() {
        ErrorsInstrumentation.removeMarkerForTesting()
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.shared.install(logger: logger)
        ErrorsInstrumentation.shared.recordError(TestError(message: "x"))
        guard let err = cap.records.first(where: { $0.body == "app.error" }) else {
            Issue.record("no app.error log captured")
            return
        }
        if case .string(let typeName)? = err.attributes["error.type"] {
            #expect(typeName.contains("TestError"))
        } else {
            Issue.record("error.type missing or wrong type")
        }
    }

    @Test("recordError scrubs PII from error.message")
    func recordErrorScrubsPii() {
        ErrorsInstrumentation.removeMarkerForTesting()
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.shared.install(logger: logger)
        ErrorsInstrumentation.shared.recordError(
            CustomError(reason: "auth failed for alice@example.com")
        )
        guard let err = cap.records.first(where: { $0.body == "app.error" }) else {
            Issue.record("no app.error log captured")
            return
        }
        if case .string(let msg)? = err.attributes["error.message"] {
            #expect(msg.contains("[EMAIL]"))
            #expect(!msg.contains("alice@example.com"))
        } else {
            Issue.record("error.message missing or wrong type")
        }
    }

    @Test("recordError preserves caller-supplied custom attributes")
    func recordErrorPassesThroughAttributes() {
        ErrorsInstrumentation.removeMarkerForTesting()
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.shared.install(logger: logger)
        ErrorsInstrumentation.shared.recordError(
            TestError(message: "x"),
            attributes: ["screen.name": .string("Cart"), "retry.count": .int(3)]
        )
        guard let err = cap.records.first(where: { $0.body == "app.error" }) else {
            Issue.record("no app.error log captured")
            return
        }
        #expect(err.attributes["screen.name"] == .string("Cart"))
        #expect(err.attributes["retry.count"] == .int(3))
    }

    @Test("recordError tags event.name = app.error for downstream filtering")
    func recordErrorTagsEventName() {
        ErrorsInstrumentation.removeMarkerForTesting()
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.shared.install(logger: logger)
        ErrorsInstrumentation.shared.recordError(TestError(message: "x"))
        guard let err = cap.records.first(where: { $0.body == "app.error" }) else {
            Issue.record("no app.error log captured")
            return
        }
        #expect(err.attributes["event.name"] == .string("app.error"))
    }

    @Test("recordError without install is a safe no-op")
    func recordErrorNoLoggerNoOp() {
        ErrorsInstrumentation.removeMarkerForTesting()
        ErrorsInstrumentation.shared.uninstall()
        // Just exercising that this doesn't crash.
        ErrorsInstrumentation.shared.recordError(TestError(message: "x"))
    }

    // MARK: - install / uninstall

    @Test("install is idempotent — second call is a no-op")
    func installIsIdempotent() {
        ErrorsInstrumentation.removeMarkerForTesting()
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.shared.install(logger: logger)
        ErrorsInstrumentation.shared.install(logger: logger)
        // No assertion on internal state; the contract is "doesn't
        // crash, doesn't double-register signal handlers". A double
        // install would re-set fatalSignals which is fine on iOS but
        // would break the previousExceptionHandler chain.
    }

    @Test("install scans for a pending marker and emits app.crash on found")
    func installScansForMarker() {
        ErrorsInstrumentation.removeMarkerForTesting()
        ErrorsInstrumentation.writeMarker(
            kind: "NSException",
            name: "TestException",
            reason: "preinstall",
            frames: ["0  Astro 0x1 main"]
        )
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.shared.install(logger: logger)
        // install() always invokes emitAnyPendingCrash up-front.
        let crashes = cap.records.filter { $0.body == "app.crash" }
        #expect(crashes.count == 1)
    }

    // MARK: - signalName

    @Test("signalName maps every fatal signal to its POSIX mnemonic")
    func signalNameMnemonics() {
        // Reading these straight off ErrorsInstrumentation's static
        // array lets us pick up any future signal additions without
        // updating the test.
        let expected: [Int32: String] = [
            SIGABRT: "SIGABRT", SIGSEGV: "SIGSEGV", SIGILL: "SIGILL",
            SIGFPE: "SIGFPE", SIGBUS: "SIGBUS", SIGPIPE: "SIGPIPE",
            SIGTRAP: "SIGTRAP",
        ]
        for sig in ErrorsInstrumentation.fatalSignals {
            let name = ErrorsInstrumentation.signalName(for: sig)
            if let want = expected[sig] {
                #expect(name == want, "signal \(sig): expected \(want), got \(name)")
            } else {
                #expect(name.hasPrefix("SIGNAL_"), "unmapped signal \(sig) should fall through to numeric form")
            }
        }
    }

    @Test("signalName falls back to SIGNAL_<n> for unknown numbers")
    func signalNameFallback() {
        #expect(ErrorsInstrumentation.signalName(for: 999) == "SIGNAL_999")
    }

    @Test("fatalSignals covers the standard POSIX fatal set")
    func fatalSignalsCoverage() {
        let signals = ErrorsInstrumentation.fatalSignals
        #expect(signals.contains(SIGSEGV), "SIGSEGV must be covered (most common iOS crash)")
        #expect(signals.contains(SIGABRT), "SIGABRT must be covered (assertions, NSException bridging)")
        #expect(signals.contains(SIGILL))
        #expect(signals.contains(SIGFPE))
        #expect(signals.contains(SIGBUS))
        #expect(signals.contains(SIGTRAP))
    }

    // MARK: - crashMarkerURL

    @Test("crashMarkerURL points to a writable Caches subpath")
    func crashMarkerURLIsWritable() {
        guard let url = ErrorsInstrumentation.crashMarkerURL() else {
            Issue.record("crashMarkerURL returned nil — Caches dir unavailable")
            return
        }
        // Path component should match the bundle id we emit at
        // signal time. Lock the literal so a future rename doesn't
        // silently invalidate every customer's recovered crash.
        #expect(url.lastPathComponent == "io.dash0.mobile.crash-marker")
        // Caches dir should exist (parent must exist for write to succeed).
        #expect(FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
    }

    // MARK: - emitAnyPendingCrash edge cases

    @Test("emitAnyPendingCrash handles empty marker file as no-op")
    func recoveryHandlesEmptyMarker() {
        ErrorsInstrumentation.removeMarkerForTesting()
        // Write a 0-byte marker.
        ErrorsInstrumentation.writeRawMarkerForTesting(bytes: [])
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.emitAnyPendingCrash(logger: logger)
        // Empty file: still emits an app.crash log because the file
        // existed (the SDK can't tell empty from "we crashed and
        // wrote nothing" — better to surface than swallow). Marker
        // gets deleted on the way out.
        guard let url = ErrorsInstrumentation.crashMarkerURL() else { return }
        #expect(!ErrorsInstrumentation.fileExistsForTesting(at: url))
    }

    @Test("emitAnyPendingCrash handles malformed lines (no = separator)")
    func recoveryHandlesMalformed() {
        ErrorsInstrumentation.removeMarkerForTesting()
        // Lines without `=` should be skipped; the kind/name/reason
        // attrs simply won't appear. Doesn't crash.
        let payload = "garbage line one\nanother\n".data(using: .utf8) ?? Data()
        ErrorsInstrumentation.writeRawMarkerForTesting(bytes: Array(payload))
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.emitAnyPendingCrash(logger: logger)
        #expect(cap.records.contains { $0.body == "app.crash" })
    }
}
