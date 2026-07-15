/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

public protocol SessionProvider: Sendable {
    var sessionId: String { get }
    func rotateSession() -> String
}
