/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Coalesces identical error events within a configurable time window.
///
/// When multiple identical errors occur (same `exception.type` + `exception.message`),
/// only the first occurrence passes through. On drain, a `coalesced.count`
/// represents the total occurrences.
///
/// Mirrors Android's `io.opentelemetry.android.mobile.buffering.ErrorCoalescer`.
/// Thread-safe via an `NSLock`-guarded dictionary (not an actor, because
/// `tryCoalesce` is called from the synchronous `onEmit` path).
public final class ErrorCoalescer: @unchecked Sendable {
    public struct CoalescedEntry: Sendable {
        public let firstRecord: ReadableLogRecord
        public let firstSeenMs: UInt64
        public var count: Int
    }

    private let windowMs: UInt64
    private let minSeverity: Severity
    private var active: [String: CoalescedEntry] = [:]
    private let lock = NSLock()

    public init(windowMs: UInt64 = 60_000, minSeverity: Severity = .error) {
        self.windowMs = windowMs
        self.minSeverity = minSeverity
    }

    /// Returns `true` if this record was coalesced (suppressed). `false` means
    /// it's the first occurrence or not eligible for coalescing.
    public func tryCoalesce(_ record: ReadableLogRecord) -> Bool {
        guard let sev = record.severity, sev.rawValue >= minSeverity.rawValue else {
            return false
        }
        guard let key = coalescingKey(record) else { return false }

        let nowMs = Self.currentMs()
        lock.lock()
        defer { lock.unlock() }

        pruneExpired(nowMs)

        if var existing = active[key], (nowMs - existing.firstSeenMs) < windowMs {
            existing.count += 1
            active[key] = existing
            return true
        }

        active[key] = CoalescedEntry(
            firstRecord: record,
            firstSeenMs: nowMs,
            count: 1
        )
        return false
    }

    /// Returns entries with count > 1, clearing them from active tracking.
    public func drainCoalesced() -> [CoalescedEntry] {
        let nowMs = Self.currentMs()
        lock.lock()
        defer { lock.unlock() }

        var result: [CoalescedEntry] = []
        var toRemove: [String] = []

        for (key, entry) in active {
            if entry.count > 1 || (nowMs - entry.firstSeenMs) >= windowMs {
                if entry.count > 1 {
                    result.append(entry)
                }
                toRemove.append(key)
            }
        }
        for key in toRemove { active.removeValue(forKey: key) }
        return result
    }

    public func getCount(for record: ReadableLogRecord) -> Int {
        guard let key = coalescingKey(record) else { return 0 }
        lock.lock()
        defer { lock.unlock() }
        return active[key]?.count ?? 0
    }

    public var activeGroupCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return active.count
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        active.removeAll()
    }

    // MARK: - Private

    /// Build the dedup key for a record. Order of precedence:
    ///   1. Exception tuple (`exception.type|exception.message`) when present.
    ///      Genuine crash duplicates (same exception in tight loop) collapse.
    ///   2. Structured signal with distinguishing attrs: `http.error` keys on
    ///      `http.response.status_code|url.full` so two different 4xx requests
    ///      do NOT collapse. Documented in docs/contracts/error-coalescer.md.
    ///   3. Any other record with `event.name` set: NOT coalesced. Structured
    ///      events are intentional signals, not error noise — returning nil
    ///      bypasses the coalescer entirely. This is the 2026-05-12 footgun fix.
    ///   4. Raw body fallback (`body|<body>`): preserves the old behaviour for
    ///      genuine error storms with no event.name (legacy uncaught exceptions
    ///      that emit a body but no structured attrs).
    private func coalescingKey(_ record: ReadableLogRecord) -> String? {
        let exType = record.attributes["exception.type"]
        let exMsg = record.attributes["exception.message"]
        if let exType = exType {
            let typeStr = Self.stringValue(exType)
            let msgStr = exMsg.map { Self.stringValue($0) } ?? ""
            return "\(typeStr)|\(msgStr)"
        }

        if let eventName = record.attributes["event.name"] {
            let eventNameStr = Self.stringValue(eventName)
            if eventNameStr == "http.error" {
                let status = record.attributes["http.response.status_code"]
                    .map { Self.stringValue($0) } ?? ""
                let url = record.attributes["url.full"]
                    .map { Self.stringValue($0) } ?? ""
                return "http.error|\(status)|\(url)"
            }
            // Other structured signals (event.name set, no exception): not
            // coalesced. Two ui.tap events with different x/y are not
            // duplicates; the previous body|<body> behaviour collapsed them.
            return nil
        }

        if let body = record.body {
            let bodyStr = Self.stringValue(body)
            if !bodyStr.isEmpty {
                return "body|\(bodyStr)"
            }
        }
        return nil
    }

    private static func stringValue(_ value: AttributeValue) -> String {
        switch value {
        case .string(let s): return s
        default: return "\(value)"
        }
    }

    private func pruneExpired(_ nowMs: UInt64) {
        active = active.filter { (nowMs - $0.value.firstSeenMs) < windowMs }
    }

    static func currentMs() -> UInt64 {
        UInt64(Date().timeIntervalSince1970 * 1000)
    }
}
