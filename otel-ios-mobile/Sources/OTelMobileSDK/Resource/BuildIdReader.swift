/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import MachO

/// Symbolication Phase 1 (docs/design/symbolication.md): reads the Mach-O
/// `LC_UUID` of the main executable at runtime. Apple's linker writes this
/// UUID into every binary and the matching dSYM carries the same one, so
/// stamping it on the resource makes every crash/error matchable to the
/// dSYM that symbolicates it — no build-time stamping needed (unlike
/// Android, where the R8 mapping id is not runtime-readable).
enum BuildIdReader {
    /// Cached at first access — the executable image cannot change for the
    /// lifetime of the process, and resource construction runs on the
    /// startup path where the 50 ms budget applies.
    private static let cachedUUID: String? = readMainExecutableUUID()

    /// The main executable's `LC_UUID` in canonical lowercase 8-4-4-4-12
    /// form (debug-id convention), or nil if the image has no UUID load
    /// command (never the case for App Store / Xcode builds).
    static func mainExecutableUUID() -> String? { cachedUUID }

    private static func readMainExecutableUUID() -> String? {
        for i in 0..<_dyld_image_count() {
            guard let header = _dyld_get_image_header(i),
                  header.pointee.filetype == UInt32(MH_EXECUTE) else { continue }
            return uuid(of: header)
        }
        return nil
    }

    /// Walks the load commands after the Mach header looking for `LC_UUID`.
    /// Reading `filetype`/`ncmds` through `mach_header` is safe for 64-bit
    /// images too — `mach_header_64` shares the leading field layout and
    /// only appends a trailing `reserved` word; only the header *size*
    /// (where the load commands start) differs.
    private static func uuid(of header: UnsafePointer<mach_header>) -> String? {
        let is64 = header.pointee.magic == MH_MAGIC_64
        let headerSize = is64 ? MemoryLayout<mach_header_64>.size : MemoryLayout<mach_header>.size
        var cursor = UnsafeRawPointer(header).advanced(by: headerSize)
        for _ in 0..<header.pointee.ncmds {
            let command = cursor.assumingMemoryBound(to: load_command.self).pointee
            if command.cmd == UInt32(LC_UUID) {
                let uuidCommand = cursor.assumingMemoryBound(to: uuid_command.self).pointee
                return UUID(uuid: uuidCommand.uuid).uuidString.lowercased()
            }
            // A zero cmdsize means a malformed header — bail rather than spin.
            guard command.cmdsize > 0 else { return nil }
            cursor = cursor.advanced(by: Int(command.cmdsize))
        }
        return nil
    }
}
