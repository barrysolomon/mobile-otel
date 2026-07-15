/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Stable per-boot identifier for correlating monotonic timestamps across
/// process restarts within the same device boot. Direct port of Android's
/// `BootTracker` — same semantics, iOS-specific source.
///
/// Why it matters: `mach_absolute_time` (the iOS analog of Android's
/// `SystemClock.elapsedRealtime`) resets at every boot. So a flush window
/// that spans a real reboot is meaningless. A buffered crash marker
/// written in boot A and read in boot B should not be treated as a "just
/// crashed" event — the user explicitly powered down. The boot id lets
/// the recovery path detect that mismatch.
///
/// iOS source: `sysctlbyname("kern.boottime", ...)` returns a `timeval`
/// (boot wall-clock seconds + microseconds). The same kernel boot will
/// always report identical values; a reboot updates them. We derive a
/// stable hex string from that timestamp so the id formats identically
/// to Android's UUID-shaped boot_id.
///
/// Fallback: if the sysctl fails (sandbox / future iOS lockdown), fall
/// back to a per-process UUID so callers always get a non-nil id. In
/// that degenerate case the boot-id correlation is reduced to "did the
/// same process write and read this marker" — strictly weaker than
/// "same kernel boot" but still meaningful.
public enum BootTracker {

    /// Lazily-resolved per-boot identifier. Cached on first access; will
    /// not change for the lifetime of the process even if the underlying
    /// sysctl is queried again.
    public static let currentBootId: String = readBootId() ?? UUID().uuidString

    /// Re-reads the kernel boot time on every call. Public for tests
    /// that want to confirm the cached `currentBootId` matches a live
    /// read (and stays stable across reads).
    public static func readBootId() -> String? {
        var bootTime = timeval()
        var size = MemoryLayout<timeval>.stride
        let result = sysctlbyname("kern.boottime", &bootTime, &size, nil, 0)
        guard result == 0 else { return nil }
        // Encode "<seconds>-<microseconds>" as a stable hex string. We
        // don't return the raw `tv_sec.tv_usec` because callers compare
        // boot ids as opaque strings; a hex encoding keeps them
        // length-bounded and free of locale issues.
        let composed = "\(bootTime.tv_sec)-\(bootTime.tv_usec)"
        return composed.data(using: .utf8)?.map { String(format: "%02x", $0) }.joined()
    }
}
