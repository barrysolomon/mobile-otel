import Foundation
import OpenTelemetryApi
import OTelMobileCore
#if canImport(UIKit)
import UIKit
#endif

/// Detects main-thread freezes (Android "ANR" / iOS "app hang").
///
/// Algorithm: a `DispatchSourceTimer` on a background utility queue posts a
/// zero-cost ack block onto the main queue every `pingIntervalMs`. If the
/// main queue hasn't drained the previous ack within `thresholdMs` when the
/// next tick fires, we emit `ui.freeze` with the observed freeze duration.
///
/// SAFETY:
/// - The watchdog never runs on the main thread.
/// - The main-queue ack is `async` (non-blocking); it can't itself freeze
///   the thread it observes.
/// - Emitting a freeze event is O(1) — no file I/O, no network.
public final class FreezeInstrumentation: @unchecked Sendable {
    public static let shared = FreezeInstrumentation()

    private let lock = NSLock()
    private var installed = false
    private var logger: Logger?
    private var timer: DispatchSourceTimer?
    private var lastPingSentAt: Date = Date()
    private var alive = true
    private var thresholdMs: Int = 250

    private init() {}

    public func install(
        logger: Logger,
        tracer: Tracer? = nil,
        thresholdMs: Int = 250,
        pingIntervalMs: Int = 500
    ) {
        lock.lock(); defer { lock.unlock() }
        guard !installed else { return }
        installed = true
        self.logger = logger
        self.thresholdMs = thresholdMs

        let queue = DispatchQueue(label: "io.dash0.mobile.FreezeWatchdog", qos: .utility)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(pingIntervalMs),
                       repeating: .milliseconds(pingIntervalMs))
        timer.setEventHandler { [weak self] in
            self?.tick()
        }
        timer.resume()
        self.timer = timer
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        timer?.cancel()
        timer = nil
    }

    // MARK: - Watchdog tick

    private func tick() {
        lock.lock()
        let stillAlive = alive
        let lastPing = lastPingSentAt
        alive = false
        lastPingSentAt = Date()
        let threshold = thresholdMs
        let logger = self.logger
        lock.unlock()

        if !stillAlive {
            let elapsedMs = Int(Date().timeIntervalSince(lastPing) * 1000)
            if elapsedMs >= threshold, let logger = logger {
                logger.logRecordBuilder()
                    .setBody(AttributeValue.string("ui.freeze"))
                    .setSeverity(.warn)
                    .setAttributes([
                        "event.name": .string("ui.freeze"),
                        "duration_ms": .int(elapsedMs),
                    ])
                    .emit()
            }
        }

        DispatchQueue.main.async { [weak self] in
            self?.acknowledge()
        }
    }

    private func acknowledge() {
        lock.lock()
        alive = true
        lock.unlock()
    }
}
