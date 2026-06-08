import Testing
@testable import ErrorsInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Integration-style coverage for the crash-marker → recovery → emission
/// path. Mirrors the intent of Android's `BufferCrashPathTest` /
/// `validate-us063-crash-flush.sh`. Runs entirely in-process — no real
/// crash needed — by injecting a known marker payload, calling
/// `emitAnyPendingCrash`, and asserting the captured log shape.
/// `.serialized` because every test in this suite writes to and reads
/// from the on-disk crash marker file
/// (`~/Library/Caches/io.dash0.mobile.crash-marker`) — a process-wide
/// singleton. Without serialization Swift Testing's parallel execution
/// would race writes against reads and tests would observe each other's
/// markers.
@Suite("CrashRecovery", .serialized, .isolatedCrashMarker)
struct CrashRecoveryTests {
    @Test("NSException-style marker round-trips through emitAnyPendingCrash")
    func nsExceptionMarker() throws {
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)

        // Write a marker payload like the NSException handler would.
        ErrorsInstrumentation.writeMarker(
            kind: "NSException",
            name: "NSInvalidArgumentException",
            reason: "test reason",
            frames: ["0  Astro 0x1 -[Foo bar]", "1  Astro 0x2 main"]
        )

        // Recovery scan — what `OTelMobile.start(config:)` does on launch.
        ErrorsInstrumentation.emitAnyPendingCrash(logger: logger)

        let records = cap.records
        #expect(records.count == 1, "expected exactly one app.crash log, got \(records.count)")
        guard let crash = records.first else { return }
        #expect(crash.body == "app.crash")
        #expect(crash.severity == .fatal)
        #expect(crash.attributes["crash.from_marker"] == .bool(true))
        #expect(crash.attributes["crash.kind"] == .string("NSException"))
        #expect(crash.attributes["crash.name"] == .string("NSInvalidArgumentException"))
        #expect(crash.attributes["crash.reason"] == .string("test reason"))
        // Stacktrace attribute carries the frames joined by newline.
        if case let .string(stack)? = crash.attributes["exception.stacktrace"] {
            #expect(stack.contains("[Foo bar]"))
            #expect(stack.contains("main"))
        } else {
            Issue.record("exception.stacktrace attribute missing or wrong type")
        }
    }

    @Test("marker file is deleted after recovery emits the log")
    func markerDeleted() throws {
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)

        ErrorsInstrumentation.writeMarker(
            kind: "TestKind", name: "TestName", reason: "TestReason", frames: []
        )

        guard let url = ErrorsInstrumentation.crashMarkerURL() else {
            Issue.record("crashMarkerURL returned nil — caches dir unavailable?")
            return
        }
        let pathExistsBefore = ErrorsInstrumentation.fileExistsForTesting(at: url)
        #expect(pathExistsBefore, "marker file should exist after writeMarker")

        ErrorsInstrumentation.emitAnyPendingCrash(logger: logger)

        let pathExistsAfter = ErrorsInstrumentation.fileExistsForTesting(at: url)
        #expect(!pathExistsAfter, "marker file should be deleted after recovery")
    }

    @Test("second recovery scan emits nothing when no marker is present")
    func cleanRecoveryIsNoop() throws {
        // Belt-and-braces: ensure no marker from a prior test bleeds through.
        ErrorsInstrumentation.removeMarkerForTesting()

        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        ErrorsInstrumentation.emitAnyPendingCrash(logger: logger)
        #expect(cap.records.isEmpty, "expected no logs, got \(cap.records.count)")
    }

    @Test("recovery scrubs PII from reason + iOS container path from frames")
    func recoveryRedactsPii() throws {
        // Belt-and-braces between tests — the marker file is a process-wide
        // singleton and a prior test could have left one behind.
        ErrorsInstrumentation.removeMarkerForTesting()

        let cap = LogCapture()
        let logger = makeLogger(processor: cap)

        // Reason embeds an email; one frame embeds the iOS app-container
        // UUID path. Both must be redacted on the read path so the
        // emitted `app.crash` log never carries raw PII to Dash0.
        ErrorsInstrumentation.writeMarker(
            kind: "NSException",
            name: "NSInvalidArgumentException",
            reason: "validation failed for alice@example.com",
            frames: [
                "0  Astro 0x1 -[Foo bar]",
                "1  Astro 0x2 /var/mobile/Containers/Data/Application/ABC12345-DEAD-BEEF-CAFE-123456789012/Frameworks/X",
            ]
        )

        ErrorsInstrumentation.emitAnyPendingCrash(logger: logger)

        let records = cap.records
        #expect(records.count == 1)
        guard let crash = records.first else { return }

        if case let .string(reason)? = crash.attributes["crash.reason"] {
            #expect(reason.contains("[EMAIL]"))
            #expect(!reason.contains("alice@example.com"))
        } else {
            Issue.record("crash.reason missing or wrong type")
        }

        if case let .string(stack)? = crash.attributes["exception.stacktrace"] {
            #expect(stack.contains("{app-container}/"))
            #expect(!stack.contains("ABC12345"))
            #expect(stack.contains("[Foo bar]"))
        } else {
            Issue.record("exception.stacktrace missing or wrong type")
        }
    }

    @Test("signal-handler 3-byte marker decodes signal kind + number")
    func signalMarker() throws {
        let cap = LogCapture()
        let logger = makeLogger(processor: cap)
        // Inject the exact 3-byte payload the async-signal-safe handler
        // writes: 'S' + signal-number + '\n'. SIGSEGV = 11 on Darwin.
        ErrorsInstrumentation.writeRawMarkerForTesting(bytes: [0x53, 11, 0x0a])
        ErrorsInstrumentation.emitAnyPendingCrash(logger: logger)
        let records = cap.records
        #expect(records.count == 1)
        guard let crash = records.first else { return }
        #expect(crash.attributes["crash.kind"] == .string("signal"))
        #expect(crash.attributes["crash.signal"] == .int(11))
        #expect(crash.attributes["crash.name"] == .string("SIGSEGV"))
    }
}
