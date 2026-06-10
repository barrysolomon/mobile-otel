import Foundation

/// Test-support helpers that let test files manipulate the crash-marker
/// file without importing Foundation directly (the `_Testing_Foundation`
/// CLT overlay is incomplete; tests in this repo route every Foundation
/// call through SDK-side helpers).
public extension ErrorsInstrumentation {
    /// Whether the marker file exists at the given URL. For tests that
    /// need to assert pre-/post-recovery file state.
    static func fileExistsForTesting(at url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    /// Best-effort marker delete. Tests call this in setUp to ensure no
    /// stale marker from a prior crash bleeds across runs. Also resets the
    /// recordError rate-limit + dedup throttle so dedup state from a prior
    /// test (the shared singleton persists across the serialized suite) can't
    /// suppress this test's first error.
    static func removeMarkerForTesting() {
        if let url = crashMarkerURL() {
            try? FileManager.default.removeItem(at: url)
        }
        shared.resetThrottleForTesting()
    }

    /// Write an arbitrary byte payload as the marker file. Used by the
    /// signal-handler shape test where we need exactly the 3-byte
    /// `'S' + sig + '\n'` payload that the async-signal-safe handler
    /// emits — there's no public API on `ErrorsInstrumentation` to
    /// produce that without actually crashing.
    static func writeRawMarkerForTesting(bytes: [UInt8]) {
        guard let url = crashMarkerURL() else { return }
        try? Data(bytes).write(to: url, options: .atomic)
    }
}
