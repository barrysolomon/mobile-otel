/// Configures the maximum disk budget consumed during offline periods.
///
/// Mirrors Android's `io.opentelemetry.android.mobile.config.OfflineBudgetConfig`.
/// When the device is offline and events are spilling to disk, this config
/// bounds the total disk consumption and controls which events are evicted
/// when the budget is exceeded.
public struct OfflineBudgetConfig: Sendable {
    /// Maximum bytes the disk buffer may consume during offline periods.
    /// Default 10 MB — aggressive enough to survive hours of offline without
    /// filling the device, small enough to avoid angering storage-conscious users.
    public let maxOfflineDiskBytes: Int

    /// Strategy for selecting which events to evict when the budget is exceeded.
    public let evictionStrategy: EvictionStrategy

    /// Master switch. When false, no offline budget enforcement occurs.
    public let enabled: Bool

    public init(
        maxOfflineDiskBytes: Int = 10 * 1024 * 1024,
        evictionStrategy: EvictionStrategy = .oldestFirst,
        enabled: Bool = true
    ) {
        precondition(maxOfflineDiskBytes > 0, "maxOfflineDiskBytes must be positive")
        self.maxOfflineDiskBytes = maxOfflineDiskBytes
        self.evictionStrategy = evictionStrategy
        self.enabled = enabled
    }

    public static let `default` = OfflineBudgetConfig()
    public static let disabled = OfflineBudgetConfig(enabled: false)
}

public enum EvictionStrategy: String, Codable, Sendable {
    case oldestFirst
    case lowestSeverityFirst
}
