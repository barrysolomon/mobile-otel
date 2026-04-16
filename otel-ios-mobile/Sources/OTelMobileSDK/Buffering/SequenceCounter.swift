import Foundation

/// Thread-safe monotonic counter for event sequence IDs.
/// Wraps around at UInt64.max (~1.8e19 — effectively unbounded).
public final class SequenceCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value: UInt64

    public init(start: UInt64 = 0) { self.value = start }

    public func next() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        value &+= 1
        return value
    }

    public func current() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        return value
    }
}
