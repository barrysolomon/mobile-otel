/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OTelMobileCore

/// Minimal `SessionProvider` that returns a single UUID for the app's lifetime.
///
/// Thin-slice implementation — a real `SessionManager` (with idle rotation,
/// app-lifecycle rotation, and persistence) lands in a later task.
public final class StaticSessionProvider: SessionProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var current: String

    public init(initial: String = UUID().uuidString) {
        self.current = initial
    }

    public var sessionId: String {
        lock.lock(); defer { lock.unlock() }
        return current
    }

    @discardableResult
    public func rotateSession() -> String {
        lock.lock(); defer { lock.unlock() }
        current = UUID().uuidString
        return current
    }
}
