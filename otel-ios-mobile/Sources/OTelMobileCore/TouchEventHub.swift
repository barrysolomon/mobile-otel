/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif

public protocol TouchEventListener: AnyObject {
    func onTouchEvent(_ event: TouchEventHub.Event)
}

public final class TouchEventHub: @unchecked Sendable {
    public struct Event {
        public let type: EventType
        public let timestamp: Date
        public let x: CGFloat
        public let y: CGFloat
        public let viewDescription: String?

        public enum EventType {
            case touchDown, touchUp, touchMoved
        }

        public init(type: EventType, timestamp: Date, x: CGFloat, y: CGFloat, viewDescription: String?) {
            self.type = type; self.timestamp = timestamp
            self.x = x; self.y = y; self.viewDescription = viewDescription
        }
    }

    private let lock = NSLock()
    private var listeners: [String: TouchEventListener] = [:]

    public init() {}

    public func addListener(id: String, listener: TouchEventListener) {
        lock.lock(); defer { lock.unlock() }
        listeners[id] = listener
    }

    public func removeListener(id: String) {
        lock.lock(); defer { lock.unlock() }
        listeners.removeValue(forKey: id)
    }

    public func dispatch(_ event: Event) {
        lock.lock()
        let snapshot = Array(listeners.values)
        lock.unlock()
        for listener in snapshot { listener.onTouchEvent(event) }
    }
}
