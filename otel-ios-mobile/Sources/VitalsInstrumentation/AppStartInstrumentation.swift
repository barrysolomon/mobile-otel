import Foundation
import OpenTelemetryApi
import OTelMobileCore
#if canImport(UIKit)
import UIKit
#endif

/// Span-emitting app start instrumentation. Direct port of Android's
/// `AppStartInstrumentation`, with iOS-native timing sources.
///
/// Emits three spans (matching Android's mobile semantic conventions —
/// still `@Incubating` upstream):
/// - `app.startup` — root span covering the entire startup window. The
///   name is intentionally what `DynamicSampler` watches as
///   high-priority (alongside `page.*`), so the trace stays in the
///   sampled set even at low baseline rates.
/// - `app.start.cold` — process boot → first frame drawn. Only emitted
///   on the first launch of a process.
/// - `app.start.warm` — background → foreground transition. One per
///   transition.
///
/// Companion to `VitalsInstrumentation`, which still emits the legacy
/// `app.start` log event with `start_duration_ms`. Both ship in
/// parallel so existing dashboards keep working.
///
/// **iOS process-start time**: read via
/// `sysctl(CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid())`. The
/// `kp_proc.p_starttime` field is a `timeval` set by the kernel at
/// `fork(2)` — strictly earlier than any code we can run. Mirrors
/// Android's `Process.getStartElapsedRealtime()`. Falls back to
/// `install()` time if the sysctl fails (sandbox).
public final class AppStartInstrumentation: @unchecked Sendable {
    public static let shared = AppStartInstrumentation()

    private let lock = NSLock()
    private var installed = false
    private var tracer: Tracer?
    private var processStartTime: Date = Date()
    private var coldStartMeasured = false
    private var lastBackgroundTime: Date?

    private init() {}

    /// Installs lifecycle observers and starts the cold-start span.
    /// Should be called as early as possible in app launch (matches
    /// Android's `Application.onCreate()` advice).
    public func install(tracer: Tracer) {
        lock.lock(); defer { lock.unlock() }
        guard !installed else { return }
        installed = true
        self.tracer = tracer
        self.processStartTime = Self.readProcessStartTime() ?? Date()

        // Emit cold start on the next main-loop tick — by then UIKit's
        // first frame will have rendered. We can't observe the frame
        // draw directly without a view-controller hook, so the next-
        // tick proxy is the same approximation Android's TTID makes.
        DispatchQueue.main.async { [weak self] in
            self?.emitColdStart()
        }

        #if canImport(UIKit) && os(iOS)
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(onDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(onDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        #endif
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        #if canImport(UIKit) && os(iOS)
        NotificationCenter.default.removeObserver(self)
        #endif
    }

    /// Test-only: drops every piece of mutable state so the next
    /// `install()` can re-fire the cold-start span. Production callers
    /// should never need this — cold start is one-shot per process.
    public func resetForTesting() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        tracer = nil
        coldStartMeasured = false
        lastBackgroundTime = nil
        processStartTime = Date()
        #if canImport(UIKit) && os(iOS)
        NotificationCenter.default.removeObserver(self)
        #endif
    }

    /// Test-only: wire a tracer without going through `install()` (which
    /// schedules a deferred `emitColdStart` on the main queue and races
    /// the test's explicit emit calls). Setting this does NOT install
    /// lifecycle observers — tests don't need them.
    public var tracerForTesting: Tracer? {
        get {
            lock.lock(); defer { lock.unlock() }
            return tracer
        }
        set {
            lock.lock(); defer { lock.unlock() }
            tracer = newValue
        }
    }

    // MARK: - Span emission

    /// Internal-public so tests can drive the cold-start span without
    /// waiting for the deferred main-queue tick. Called once per
    /// process — re-entry is a no-op.
    public func emitColdStart() {
        lock.lock()
        let alreadyMeasured = coldStartMeasured
        let tracer = self.tracer
        let start = self.processStartTime
        coldStartMeasured = true
        lock.unlock()
        guard !alreadyMeasured, let tracer = tracer else { return }

        let now = Date()
        let durationMs = Int(now.timeIntervalSince(start) * 1000)

        // `app.startup` — the high-priority root span. Emitted first
        // so any child spans created during startup nest under it.
        let startupSpan = tracer.spanBuilder(spanName: "app.startup")
            .setStartTime(time: start)
            .startSpan()
        startupSpan.setAttribute(key: "mobile.app.start.type", value: "cold")
        startupSpan.setAttribute(key: "mobile.app.start.duration_ms", value: durationMs)
        startupSpan.end(time: now)

        // `app.start.cold` — Android-parity span name.
        let coldSpan = tracer.spanBuilder(spanName: "app.start.cold")
            .setStartTime(time: start)
            .startSpan()
        coldSpan.setAttribute(key: "mobile.app.start.type", value: "cold")
        coldSpan.setAttribute(key: "mobile.app.start.duration_ms", value: durationMs)
        coldSpan.setAttribute(
            key: "mobile.app.start.process_start_time",
            value: AttributeValue.int(Int(start.timeIntervalSince1970 * 1000))
        )
        coldSpan.end(time: now)
    }

    /// Internal-public for tests — same shape as Android's warm-start
    /// path. `backgroundTime` is the moment the app last entered
    /// background; `now` is the moment it became active again.
    public func emitWarmStart(backgroundTime: Date, now: Date = Date()) {
        lock.lock()
        let tracer = self.tracer
        lock.unlock()
        guard let tracer = tracer else { return }
        let durationMs = Int(now.timeIntervalSince(backgroundTime) * 1000)
        guard durationMs >= 0 else { return }

        let warmSpan = tracer.spanBuilder(spanName: "app.start.warm")
            .setStartTime(time: backgroundTime)
            .startSpan()
        warmSpan.setAttribute(key: "mobile.app.start.type", value: "warm")
        warmSpan.setAttribute(key: "mobile.app.start.duration_ms", value: durationMs)
        warmSpan.end(time: now)
    }

    // MARK: - Lifecycle

    #if canImport(UIKit) && os(iOS)
    @objc private func onDidEnterBackground() {
        lock.lock(); defer { lock.unlock() }
        lastBackgroundTime = Date()
    }

    @objc private func onDidBecomeActive() {
        lock.lock()
        let backgroundTime = lastBackgroundTime
        lastBackgroundTime = nil
        lock.unlock()
        guard let backgroundTime = backgroundTime else { return }
        emitWarmStart(backgroundTime: backgroundTime)
    }
    #endif

    // MARK: - Process start time

    /// Read the kernel-recorded process start timestamp. Returns nil
    /// when the sysctl is unavailable (some sandboxed contexts), in
    /// which case callers fall back to install-time approximation.
    static func readProcessStartTime() -> Date? {
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        var size = MemoryLayout<kinfo_proc>.stride
        var info = kinfo_proc()
        let result = mib.withUnsafeMutableBufferPointer { mibPtr -> Int32 in
            guard let base = mibPtr.baseAddress else { return -1 }
            return sysctl(base, UInt32(mibPtr.count), &info, &size, nil, 0)
        }
        guard result == 0 else { return nil }
        let tv = info.kp_proc.p_starttime
        let seconds = TimeInterval(tv.tv_sec)
        let microseconds = TimeInterval(tv.tv_usec) / 1_000_000.0
        return Date(timeIntervalSince1970: seconds + microseconds)
    }
}
