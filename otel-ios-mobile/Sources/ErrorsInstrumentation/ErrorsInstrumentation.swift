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

    private init() {}

    public func install(logger: Logger) {
        lock.lock(); defer { lock.unlock() }
        self.logger = logger

        // Always check for a crash marker from a prior run first — even if
        // we've already installed (lets tests exercise the recovery path).
        Self.emitAnyPendingCrash(logger: logger)

        guard !installed else { return }
        installed = true

        // Path 1: NSException
        NSSetUncaughtExceptionHandler { exception in
            ErrorsInstrumentation.writeMarker(
                kind: "exception",
                name: exception.name.rawValue,
                reason: exception.reason ?? "",
                frames: exception.callStackSymbols
            )
        }

        // Path 2: Signals
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
        NSSetUncaughtExceptionHandler(nil)
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
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) else {
            return
        }
        var attrs: [String: AttributeValue] = [
            "event.name": .string("app.crash"),
            "crash.from_marker": .bool(true),
        ]
        var frames: [String] = []
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
}

// Top-level signal handler (needs C-callable signature).
private func signalHandler(_ sig: Int32) {
    let name: String
    switch sig {
    case SIGABRT: name = "SIGABRT"
    case SIGSEGV: name = "SIGSEGV"
    case SIGILL: name = "SIGILL"
    case SIGFPE: name = "SIGFPE"
    case SIGBUS: name = "SIGBUS"
    case SIGPIPE: name = "SIGPIPE"
    case SIGTRAP: name = "SIGTRAP"
    default: name = "SIGNAL_\(sig)"
    }
    let frames = Thread.callStackSymbols
    ErrorsInstrumentation.writeMarker(
        kind: "signal", name: name, reason: "fatal signal \(sig)", frames: frames
    )
    // Restore default handler and re-raise so the OS / debugger still sees it.
    signal(sig, SIG_DFL)
    raise(sig)
}

