/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OTelMobileCore
import OpenTelemetryApi
#if canImport(UIKit)
import UIKit
#endif

/// Full session manager — persistent UUID with inactivity-timeout rotation
/// and UserDefaults storage. Mirrors Android's `SessionManager` behavior.
///
/// Rotation rules:
/// - First call after construction: if we have a persisted session within the
///   inactivity window, resume it. Otherwise mint a new session.
/// - Every access to `sessionId` updates `lastSeen`, so active use keeps the
///   session alive.
/// - If inactivity exceeds `inactivityTimeoutSeconds` between two accesses,
///   the next access mints a new session (old session is considered ended).
///
/// UserDefaults keys:
/// - `io.dash0.mobile.sessionId` — UUID string
/// - `io.dash0.mobile.sessionLastSeen` — `Date` via timeIntervalSince1970
///
/// Session lifecycle events:
/// - `session.start` log emitted when a new session is minted
/// - `session.end` log emitted when the previous session times out and we
///   rotate (best-effort — we only know the old session ended when the next
///   access happens AFTER the timeout)
///
/// SAFETY: all state access goes through NSLock. Emission of session.start/
/// session.end requires a Logger — when one isn't configured we just rotate
/// silently. UserDefaults reads/writes are thread-safe by contract.
public final class SessionManager: SessionProvider, @unchecked Sendable {
    private static let sessionIdKey = "io.dash0.mobile.sessionId"
    private static let lastSeenKey = "io.dash0.mobile.sessionLastSeen"

    private let lock = NSLock()
    private let inactivityTimeout: TimeInterval
    private let defaults: UserDefaults
    private let logger: Logger?
    private var currentId: String
    private var lastSeen: Date

    public init(
        inactivityTimeoutSeconds: TimeInterval = 900,  // 15 min, matches Android
        defaults: UserDefaults = .standard,
        logger: Logger? = nil
    ) {
        self.inactivityTimeout = inactivityTimeoutSeconds
        self.defaults = defaults
        self.logger = logger

        // Recover from UserDefaults if within inactivity window. Otherwise mint.
        let persistedId = defaults.string(forKey: Self.sessionIdKey)
        let persistedLastSeen: Date? = {
            let ts = defaults.double(forKey: Self.lastSeenKey)
            return ts > 0 ? Date(timeIntervalSince1970: ts) : nil
        }()

        let now = Date()
        if let id = persistedId, let lastSeen = persistedLastSeen,
           now.timeIntervalSince(lastSeen) < inactivityTimeoutSeconds {
            // Resume.
            self.currentId = id
            self.lastSeen = lastSeen
        } else {
            // Mint. We can't emit session.start here because the logger may
            // not be installed yet (chicken-and-egg with OTelMobile.start);
            // callers that want that emission should call `emitInitialStart()`
            // after they've wired up a logger.
            self.currentId = UUID().uuidString
            self.lastSeen = now
            defaults.set(currentId, forKey: Self.sessionIdKey)
            defaults.set(now.timeIntervalSince1970, forKey: Self.lastSeenKey)
        }
    }

    // MARK: - SessionProvider conformance

    public var sessionId: String {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        if now.timeIntervalSince(lastSeen) >= inactivityTimeout {
            // Rotate.
            let oldId = currentId
            let newId = UUID().uuidString
            currentId = newId
            lastSeen = now
            defaults.set(newId, forKey: Self.sessionIdKey)
            defaults.set(now.timeIntervalSince1970, forKey: Self.lastSeenKey)
            emitSessionEnd(oldId: oldId)
            emitSessionStart(newId: newId)
        } else {
            // Touch.
            lastSeen = now
            defaults.set(now.timeIntervalSince1970, forKey: Self.lastSeenKey)
        }
        return currentId
    }

    @discardableResult
    public func rotateSession() -> String {
        lock.lock(); defer { lock.unlock() }
        let oldId = currentId
        let newId = UUID().uuidString
        currentId = newId
        lastSeen = Date()
        defaults.set(newId, forKey: Self.sessionIdKey)
        defaults.set(lastSeen.timeIntervalSince1970, forKey: Self.lastSeenKey)
        emitSessionEnd(oldId: oldId)
        emitSessionStart(newId: newId)
        return newId
    }

    // MARK: - Emission helpers (called while lock is held — safe because
    // Logger.logRecordBuilder() is lock-free and non-blocking)

    private func emitSessionStart(newId: String) {
        logger?.logRecordBuilder()
            .setBody(AttributeValue.string("session.start"))
            .setSeverity(.info)
            .setAttributes([
                "event.name": .string("session.start"),
                "session.id": .string(newId),
            ])
            .emit()
    }

    private func emitSessionEnd(oldId: String) {
        logger?.logRecordBuilder()
            .setBody(AttributeValue.string("session.end"))
            .setSeverity(.info)
            .setAttributes([
                "event.name": .string("session.end"),
                "session.id": .string(oldId),
            ])
            .emit()
    }

    // MARK: - Public helpers

    /// Emit a `session.start` for the current session. Intended to be called
    /// ONCE after the logger is wired up in `OTelMobile.start(config:)`.
    public func emitInitialStart() {
        lock.lock()
        let id = currentId
        lock.unlock()
        emitSessionStart(newId: id)
    }

    /// Read-only snapshot of internal state, for diagnostics.
    public func snapshot() -> (sessionId: String, lastSeen: Date, inactivityTimeout: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        return (currentId, lastSeen, inactivityTimeout)
    }
}
