import Foundation

public final class RateLimiter: @unchecked Sendable {
    private let maxPerWindow: Int
    private let windowSeconds: TimeInterval
    private let lock = NSLock()
    private var timestamps: [Date] = []

    public init(maxPerWindow: Int, windowSeconds: TimeInterval = 60) {
        self.maxPerWindow = maxPerWindow
        self.windowSeconds = windowSeconds
    }

    public func tryAcquire() -> Bool {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        let cutoff = now.addingTimeInterval(-windowSeconds)
        timestamps.removeAll { $0 < cutoff }
        if timestamps.count >= maxPerWindow { return false }
        timestamps.append(now)
        return true
    }

    public func reset() {
        lock.lock(); defer { lock.unlock() }
        timestamps.removeAll()
    }
}
