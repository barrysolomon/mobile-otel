import Foundation

/// Verdict returned by `CrashLoopDetector.evaluateOnLaunch(threshold:)`.
public enum CrashLoopVerdict: Equatable, Sendable {
    case proceed
    case disabled
}

/// Crash-loop self-disable guard (SDK_SAFETY).
///
/// On every launch, BEFORE any SDK initialization, `evaluateOnLaunch`
/// inspects the crash marker `ErrorsInstrumentation` left behind if the
/// previous session died from an NSException or fatal signal:
///
/// - marker present → increment a persisted consecutive-crash counter. Once
///   the counter reaches the configured threshold the verdict is `.disabled`
///   and the SDK must not initialize for this launch.
/// - marker absent (previous session was clean) → reset the counter to zero.
///
/// On the `.disabled` path the crash marker is deleted: the SDK stays inert
/// for that launch, so nothing else would ever consume the marker, and a
/// stale one would keep the SDK disabled forever. Deleting it makes the next
/// launch count as clean, which resets the counter — the guard self-clears
/// after exactly one disabled launch unless crashes resume. On the `.proceed`
/// path the marker is left untouched so `emitAnyPendingCrash` can still emit
/// `app.crash`.
///
/// Mirrors Android `CrashLoopDetector` — zero platform drift.
public struct CrashLoopDetector {

    static let countKey = "io.dash0.mobile.crashLoopCount"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public var consecutiveCrashCount: Int {
        defaults.integer(forKey: Self.countKey)
    }

    public func evaluateOnLaunch(threshold: Int) -> CrashLoopVerdict {
        guard threshold > 0 else { return .proceed }
        guard let markerURL = ErrorsInstrumentation.crashMarkerURL() else {
            return .proceed
        }
        guard FileManager.default.fileExists(atPath: markerURL.path) else {
            defaults.set(0, forKey: Self.countKey)
            return .proceed
        }
        let count = consecutiveCrashCount + 1
        defaults.set(count, forKey: Self.countKey)
        if count >= threshold {
            try? FileManager.default.removeItem(at: markerURL)
            return .disabled
        }
        return .proceed
    }
}
