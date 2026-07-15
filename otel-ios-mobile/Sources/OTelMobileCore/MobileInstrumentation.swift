/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

public protocol MobileInstrumentation: AnyObject {
    var id: String { get }
    var isAutoCapture: Bool { get }
    func install(context: InstrumentationContext)
    func uninstall()
}
