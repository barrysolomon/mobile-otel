/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import OpenTelemetryApi

/// Controls what gets buffered when the device is offline.
///
/// Mirrors Android's `io.opentelemetry.android.mobile.config.OfflinePolicy`.
/// When the processor detects no network connectivity, this policy determines
/// which events pass through to the buffer versus being silently dropped.
public enum OfflinePolicy: String, Codable, Sendable {
    case bufferAll
    case errorOnly
    case warnAndAbove
    case dropAll
}

extension OfflinePolicy {
    /// Minimum severity a log record must have to be buffered when offline.
    /// Returns `nil` for `bufferAll` (no filter) and `dropAll` (handled separately).
    public var minBufferSeverity: Severity? {
        switch self {
        case .bufferAll: return nil
        case .errorOnly: return .error
        case .warnAndAbove: return .warn
        case .dropAll: return nil
        }
    }

    /// When true, all events are dropped regardless of severity.
    public var dropsAll: Bool { self == .dropAll }
}
