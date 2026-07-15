/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Internal factory helpers used by tests. These live in the SDK module so test
/// files can construct `BufferedEvent` instances without importing Foundation
/// directly (Swift Testing's `_Testing_Foundation` cross-import overlay is
/// shipped incomplete in the macOS Command Line Tools; importing Foundation
/// from a test file fails to resolve the overlay).
extension BufferedEvent {
    /// Creates a `BufferedEvent` with string payload and optional explicit timestamp.
    /// If `timestampMs` is nil, the current wall-clock time is used.
    static func makeForTesting(
        sequenceId: UInt64,
        timestampMs: UInt64? = nil,
        sessionId: String = "test-session",
        payload: String = ""
    ) -> BufferedEvent {
        BufferedEvent(
            sequenceId: sequenceId,
            timestampMs: timestampMs ?? UInt64(Date().timeIntervalSince1970 * 1000),
            sessionId: sessionId,
            eventData: Data(payload.utf8)
        )
    }

    /// Returns the current wall-clock time in milliseconds. Exposed for tests
    /// that cannot import Foundation directly.
    static func currentTimestampMs() -> UInt64 {
        UInt64(Date().timeIntervalSince1970 * 1000)
    }
}
