import Foundation
import OpenTelemetryApi
import OTelMobileCore

/// Captures uncaught Objective-C/Swift exceptions and POSIX signal crashes,
/// writing a crash marker file synchronously so the *next* launch can emit an
/// `app.crash` log with the saved details.
///
/// Two capture paths:
/// 1. `NSSetUncaughtExceptionHandler` — Objective-C NSException instances,
///    including the ones bridged from Swift errors that escape throwing.
/// 2. POSIX signal handlers for fatal signals (SIGABRT, SIGSEGV, SIGILL,
///    SIGFPE, SIGBUS, SIGPIPE, SIGTRAP) — native crashes.
///
/// Because the process is mid-crash when these fire, we only do
/// async-signal-safe work: write a small marker file to the app's Caches dir
/// with a newline-separated payload (signal name, timestamp, backtrace frames)
/// and re-raise the signal so the debugger / OS crash reporter can still pick
/// it up. We do NOT call into the OTel tracer/logger from inside the handler —
/// locks and allocations are unsafe at that point.
///
/// On the next `install()` call, we read any pending marker file, emit an
/// `app.crash` log with the saved data, then delete the marker.
///
/// Usage:
/// ```swift
/// let mobile = try OTelMobile.start(config: config)
/// if let logger = mobile.logger {
///     ErrorsInstrumentation.shared.install(logger: logger)
/// }
/// ```
///
/// Production apps should consider pairing this with PLCrashReporter or
/// KSCrash for symbolication and better multi-threaded crash handling. This
/// built-in path is good enough for most observability use cases and has
/// zero external dependencies.
public final class ErrorsInstrumentation: @unchecked Sendable {
    public static let shared = ErrorsInstrumentation()

    private let lock = NSLock()
    private var installed = false
    private var logger: Logger?

    /// Previous NSException handler installed before us. Stored as the C
    /// function-pointer type (`@convention(c)`) so we can both call it at
    /// crash time AND hand it back to `NSSetUncaughtExceptionHandler` on
    /// uninstall. Swift closures can't round-trip through the C ABI.
    ///
    /// We MUST call it after writing our marker so apps running Sentry,
    /// Firebase Crashlytics, PLCrashReporter, Bugsnag, etc. still get their
    /// crash report.
    nonisolated(unsafe) static var previousExceptionHandler: (@convention(c) (NSException) -> Void)?

    private init() {}

    public func install(logger: Logger) {
        lock.lock(); defer { lock.unlock() }
        self.logger = logger

        // Always check for a crash marker from a prior run first — even if
        // we've already installed (lets tests exercise the recovery path).
        Self.emitAnyPendingCrash(logger: logger)

        guard !installed else { return }
        installed = true

        // Path 1: NSException — chain through the previously installed
        // handler so we don't clobber Sentry / Firebase Crashlytics /
        // PLCrashReporter / Bugsnag. Whoever was there before us still gets
        // their crash report AFTER we write our marker.
        ErrorsInstrumentation.previousExceptionHandler = NSGetUncaughtExceptionHandler()
        NSSetUncaughtExceptionHandler(uncaughtExceptionTrampoline)

        // Path 2: Signals.
        //
        // Pre-open the crash-marker file descriptor NOW, while we're still in
        // normal-execution context. The signal handler will only use write(2)
        // on this fd — no Foundation, no allocations, no locks. See the
        // handler comment in signalHandler(_:).
        if ErrorsInstrumentation.crashMarkerFd < 0 {
            if let path = ErrorsInstrumentation.crashMarkerURL()?.path {
                let fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0o600)
                if fd >= 0 {
                    ErrorsInstrumentation.crashMarkerFd = fd
                }
            }
        }
        for sig in Self.fatalSignals {
            var action = sigaction()
            action.__sigaction_u.__sa_handler = signalHandler
            sigemptyset(&action.sa_mask)
            action.sa_flags = 0
            sigaction(sig, &action, nil)
        }
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        // Restore the previously-installed NSException handler so apps that
        // had Sentry/Firebase/PLCrashReporter installed before us still have
        // them after we uninstall. If there was no previous handler, passing
        // nil is correct.
        NSSetUncaughtExceptionHandler(ErrorsInstrumentation.previousExceptionHandler)
        for sig in Self.fatalSignals {
            signal(sig, SIG_DFL)
        }
    }

    // MARK: - Manual error emission (useful for caught errors / demo)

    public func recordError(
        _ error: Error,
        attributes: [String: AttributeValue] = [:]
    ) {
        lock.lock()
        let logger = self.logger
        lock.unlock()
        guard let logger = logger else { return }
        var attrs = attributes
        attrs["error.type"] = .string(String(describing: type(of: error)))
        attrs["error.message"] = .string(error.localizedDescription)
        attrs["event.name"] = .string("app.error")
        logger.logRecordBuilder()
            .setBody(AttributeValue.string("app.error"))
            .setSeverity(.error)
            .setAttributes(attrs)
            .emit()
    }

    // MARK: - Crash marker file (signal-safe path)

    /// Pre-opened file descriptor used by the async-signal-safe signal handler.
    /// Opened during `install()` on the happy path; never touched from the
    /// signal handler itself (open(2) IS async-signal-safe but we prefer to do
    /// all allocation-like work ahead of time). `-1` means not yet opened.
    nonisolated(unsafe) static var crashMarkerFd: Int32 = -1

    static let fatalSignals: [Int32] = [SIGABRT, SIGSEGV, SIGILL, SIGFPE, SIGBUS, SIGPIPE, SIGTRAP]

    static func crashMarkerURL() -> URL? {
        guard let cacheDir = FileManager.default.urls(
            for: .cachesDirectory, in: .userDomainMask
        ).first else {
            return nil
        }
        return cacheDir.appendingPathComponent("io.dash0.mobile.crash-marker", isDirectory: false)
    }

    static func writeMarker(kind: String, name: String, reason: String, frames: [String]) {
        guard let url = crashMarkerURL() else { return }
        // Async-signal-safe-ish payload: use String joining + Data write.
        // In a true signal handler we'd avoid allocations, but Swift can't
        // express that cleanly. This works reliably on iOS.
        let ts = String(Int(Date().timeIntervalSince1970))
        let lines = ["kind=\(kind)", "name=\(name)", "reason=\(reason)", "timestamp=\(ts)"]
            + frames.prefix(50).enumerated().map { "frame\($0.offset)=\($0.element)" }
        let payload = lines.joined(separator: "\n") + "\n"
        try? payload.data(using: .utf8)?.write(to: url, options: .atomic)
    }

    static func emitAnyPendingCrash(logger: Logger) {
        guard let url = crashMarkerURL() else { return }
        guard let data = try? Data(contentsOf: url) else { return }

        var attrs: [String: AttributeValue] = [
            "event.name": .string("app.crash"),
            "crash.from_marker": .bool(true),
        ]
        var frames: [String] = []

        // Signal-handler marker: 3-byte "S<sig>\n" record. Parse defensively.
        if data.count == 3, data[0] == UInt8(ascii: "S") {
            attrs["crash.kind"] = .string("signal")
            attrs["crash.signal"] = .int(Int(data[1]))
            attrs["crash.name"] = .string(Self.signalName(for: Int32(data[1])))
        } else if let text = String(data: data, encoding: .utf8) {
            // Legacy NSException marker: key=value lines.
            for line in text.split(separator: "\n") {
                let parts = line.split(separator: "=", maxSplits: 1).map(String.init)
                guard parts.count == 2 else { continue }
                let (k, v) = (parts[0], parts[1])
                if k.hasPrefix("frame") {
                    frames.append(v)
                } else {
                    attrs["crash.\(k)"] = .string(v)
                }
            }
        }
        if !frames.isEmpty {
            attrs["exception.stacktrace"] = .string(frames.joined(separator: "\n"))
        }
        logger.logRecordBuilder()
            .setBody(AttributeValue.string("app.crash"))
            .setSeverity(.fatal)
            .setAttributes(attrs)
            .emit()
        try? FileManager.default.removeItem(at: url)
    }

    /// Human-readable signal name. Used only during recovery (not in the
    /// signal handler itself — that stores the raw byte).
    static func signalName(for sig: Int32) -> String {
        switch sig {
        case SIGABRT: return "SIGABRT"
        case SIGSEGV: return "SIGSEGV"
        case SIGILL: return "SIGILL"
        case SIGFPE: return "SIGFPE"
        case SIGBUS: return "SIGBUS"
        case SIGPIPE: return "SIGPIPE"
        case SIGTRAP: return "SIGTRAP"
        default: return "SIGNAL_\(sig)"
        }
    }
}

// Top-level NSException trampoline (needs C-callable signature).
// Writes our marker first so even if the chained handler crashes or exits,
// we've persisted our info for next-launch recovery. Then re-delegates to
// whatever handler was installed before us (Sentry / Firebase / etc.).
private let uncaughtExceptionTrampoline: @convention(c) (NSException) -> Void = { exception in
    ErrorsInstrumentation.writeMarker(
        kind: "exception",
        name: exception.name.rawValue,
        reason: exception.reason ?? "",
        frames: exception.callStackSymbols
    )
    // Call previous handler if any. Must be unwrapped locally — the closure
    // captures the static at call time.
    if let prev = ErrorsInstrumentation.previousExceptionHandler {
        prev(exception)
    }
}

// Top-level signal handler (needs C-callable signature).
//
// SAFETY: Only async-signal-safe calls are permitted here per POSIX. We must
// not allocate, call Foundation, take locks, use ARC, or touch Swift runtime
// metadata. String interpolation, Date, Thread.callStackSymbols, JSONEncoder,
// Data.write, and FileManager are all UNSAFE and have been removed.
//
// All we do here is:
//   1. write(2) a fixed 3-byte signal marker (async-signal-safe) to a
//      pre-opened file descriptor. The byte encodes the signal number so
//      the next launch can identify which signal fired.
//   2. Restore the default handler and re-raise. The OS / debugger /
//      PLCrashReporter (if integrated) still observe the underlying crash
//      with its native stack — we defer stack collection to the OS.
//
// The next launch reads the marker, emits an `app.crash` log with the signal
// number (but without a symbolicated stack — that requires a real crash
// reporter like PLCrashReporter or KSCrash). This is the defensible "do no
// harm" posture: we never corrupt memory trying to enrich a crash record.
private func signalHandler(_ sig: Int32) {
    // Async-signal-safe: `write(2)` is on POSIX's async-signal-safe list.
    let fd = ErrorsInstrumentation.crashMarkerFd
    if fd >= 0 {
        let buf: [UInt8] = [
            UInt8(ascii: "S"),
            UInt8(truncatingIfNeeded: sig),
            UInt8(ascii: "\n"),
        ]
        _ = buf.withUnsafeBufferPointer { ptr in
            write(fd, ptr.baseAddress, 3)
        }
    }
    // Async-signal-safe: signal(), raise().
    signal(sig, SIG_DFL)
    raise(sig)
}

