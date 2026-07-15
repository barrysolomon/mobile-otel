/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Observability surface for the SDK's own export pipeline. Consumers
/// (debug widget overlays, dashboards, CI smoke checks) register a
/// listener and react to each transition without having to subclass the
/// exporter. Mirrors Android's `ExportStatus` sealed class line-for-line
/// so a customer who wires a single listener implementation against both
/// platforms gets the same event stream.
///
/// `eventCount` is the number of records that were in the failing batch —
/// useful for "events lost" counters. `attempt` is 1-indexed; `delayMs`
/// is the *upcoming* sleep before the next attempt.
public enum ExportStatus: Sendable, Equatable {
    case success(eventCount: Int)
    case failed(reason: String, eventCount: Int, attempt: Int)
    /// Surfaced separately from `failed` because callers usually want to
    /// react differently — auth failure means no amount of retrying will
    /// help, the user must rotate the token.
    case authError(reason: String, eventCount: Int)
    case retrying(attempt: Int, maxAttempts: Int, delayMs: Int)
}

/// Receiver for `ExportStatus` events. Implemented as a class-bound
/// protocol so the manager can do identity-based removal (`===`).
public protocol ExportStatusListener: AnyObject {
    func onExportStatus(_ status: ExportStatus)
}

/// Process-wide registry of `ExportStatusListener`s. Pure event-driven
/// fan-out — stores no status, holds no buffer of past events. Listeners
/// register early (e.g. in the app delegate) and receive every transition
/// reported by every exporter for the rest of the process lifetime.
///
/// Thread safety: snapshot-then-iterate. The lock guards the listeners
/// array; the listener callback runs OUTSIDE the lock so a slow listener
/// can never block other notifications. Mirrors Android's pattern.
public final class ExportStatusManager: @unchecked Sendable {
    /// Process-wide instance app code uses by default. Tests construct
    /// their own instances and inject them into `RetryableExporter` so
    /// parallel suites don't cross-contaminate each other's listener
    /// transcripts.
    public static let shared = ExportStatusManager()

    private let lock = NSLock()
    private var listeners: [ExportStatusListener] = []

    public init() {}

    public func addListener(_ listener: ExportStatusListener) {
        lock.lock(); defer { lock.unlock() }
        // No dedupe — Android allows the same listener to register twice
        // and receive each event twice. Match the contract.
        listeners.append(listener)
    }

    public func removeListener(_ listener: ExportStatusListener) {
        lock.lock(); defer { lock.unlock() }
        listeners.removeAll { $0 === listener }
    }

    public func clearListeners() {
        lock.lock(); defer { lock.unlock() }
        listeners.removeAll()
    }

    /// Public so `RetryableExporter` (in the SDK target) and any user-
    /// supplied exporter can publish status. Snapshot-then-iterate so a
    /// listener that calls `removeListener` from inside its callback
    /// doesn't mutate the array we're iterating.
    public func notify(_ status: ExportStatus) {
        lock.lock()
        let snapshot = listeners
        lock.unlock()
        for listener in snapshot {
            listener.onExportStatus(status)
        }
    }
}
