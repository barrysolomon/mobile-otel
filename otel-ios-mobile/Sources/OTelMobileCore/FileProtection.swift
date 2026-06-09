import Foundation

/// At-rest protection helpers for the SDK's on-disk stores (SQLite buffers,
/// crash markers). On-device telemetry can carry PII, so persisted files must
/// be encrypted-at-rest and kept out of iTunes/iCloud backups.
///
/// All helpers are crash-safe: they LOG and CONTINUE on failure rather than
/// throwing, so a sandbox/filesystem quirk can never take down the host app.
public enum FileProtectionHelper {

    /// Apply `.completeUntilFirstUserAuthentication` data protection to a file.
    /// This class keeps the file encrypted until the first unlock after boot,
    /// which is the right tradeoff for a background SDK that must write before
    /// the user unlocks (vs `.complete`, which would deny access in that
    /// window). No-op on platforms without NSFileProtection.
    public static func applyProtection(toFile url: URL) {
        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do {
            try FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: url.path
            )
        } catch {
            NSLog("[Dash0] file protection failed for \(url.lastPathComponent): \(error)")
        }
        #endif
    }

    /// Apply data protection to a directory so newly created children (e.g.
    /// SQLite WAL/SHM sidecar files) inherit it, and exclude the directory
    /// from iCloud/iTunes backups so PII never leaves the device in a backup.
    public static func protectDirectory(_ url: URL) {
        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        applyProtection(toFile: url)
        #endif
        excludeFromBackup(url)
    }

    /// Mark a file/directory as excluded from backups via URLResourceValues.
    public static func excludeFromBackup(_ url: URL) {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            NSLog("[Dash0] backup-exclusion failed for \(url.lastPathComponent): \(error)")
        }
    }
}
