import Foundation
#if canImport(UIKit)
import UIKit
#endif
#if canImport(Network)
import Network
#endif

/// Populates `ContextSnapshot` from live iOS device signals.
///
/// Android parity: mirrors `io.opentelemetry.android.mobile.context.ContextSnapshotProvider`.
/// The provider reads battery / network / locale / timezone / device class
/// / OS version / app version lazily on demand, caches the result with a
/// short TTL (default 10 s) so consecutive policy evaluations don't pay
/// the system-API cost, and refreshes when the cache expires.
///
/// Thread-safety: reads are guarded by an NSLock. Network-type tracking
/// runs on a dedicated background queue via `NWPathMonitor` and updates
/// the cached value atomically.
///
/// Every field on the returned `ContextSnapshot` is optional. If an API is
/// unavailable on the current simulator/device or throws, the field stays
/// nil — the policy evaluator simply doesn't match on that dimension.
public final class ContextSnapshotProvider: @unchecked Sendable {
    private let ttl: TimeInterval
    private let lock = NSLock()
    private var cached: ContextSnapshot?
    private var cachedAt: Date = .distantPast
    private var latestNetworkType: String = "unknown"

    #if canImport(Network)
    private let pathMonitor: NWPathMonitor
    private let pathQueue: DispatchQueue
    #endif

    public init(ttlSeconds: TimeInterval = 10) {
        self.ttl = ttlSeconds
        #if canImport(Network)
        self.pathMonitor = NWPathMonitor()
        self.pathQueue = DispatchQueue(
            label: "io.dash0.mobile.ContextSnapshotProvider.path", qos: .utility
        )
        self.pathMonitor.pathUpdateHandler = { [weak self] path in
            self?.updateNetworkType(from: path)
        }
        self.pathMonitor.start(queue: self.pathQueue)
        #endif
        // Enable battery monitoring so `UIDevice.batteryLevel` / `batteryState`
        // return real values instead of `-1` / `.unknown`.
        #if canImport(UIKit) && !os(watchOS)
        DispatchQueue.main.async {
            UIDevice.current.isBatteryMonitoringEnabled = true
        }
        #endif
    }

    deinit {
        #if canImport(Network)
        pathMonitor.cancel()
        #endif
    }

    /// Return a fresh snapshot (or a cached one if the last capture is still
    /// within the TTL window).
    public func currentSnapshot() -> ContextSnapshot {
        lock.lock(); defer { lock.unlock() }
        if let cached = cached, Date().timeIntervalSince(cachedAt) < ttl {
            return cached
        }
        let snapshot = Self.buildSnapshot(networkType: latestNetworkType)
        cached = snapshot
        cachedAt = Date()
        return snapshot
    }

    /// Force re-read on the next call regardless of TTL.
    public func invalidate() {
        lock.lock(); defer { lock.unlock() }
        cached = nil
    }

    // MARK: - Builders

    private static func buildSnapshot(networkType: String) -> ContextSnapshot {
        ContextSnapshot(
            countryCode: countryCode(),
            region: regionCode(),
            timezone: TimeZone.current.identifier,
            localeId: Locale.current.identifier,
            networkType: networkType,
            batteryState: batteryState(),
            deviceClass: deviceClass(),
            buildChannel: buildChannel(),
            osVersionInt: osMajorVersion(),
            appVersion: appVersion()
        )
    }

    private static func countryCode() -> String? {
        if #available(iOS 16.0, macOS 13.0, *) {
            return Locale.current.region?.identifier
        }
        return Locale.current.regionCode
    }

    /// Regions on iOS without sub-national state granularity default to the
    /// same value as country code. Kept as a distinct getter so Android
    /// matchers that query `region` still resolve.
    private static func regionCode() -> String? { countryCode() }

    /// One of `"charging" | "low" | "normal" | "unknown"`. Matches the
    /// Android contract.
    private static func batteryState() -> String? {
        #if canImport(UIKit) && !os(watchOS)
        guard Thread.isMainThread else {
            // UIDevice must be read on the main thread. If we're off-main,
            // hop synchronously — the cost is a single UI-thread ping that
            // happens at most once per TTL window.
            return DispatchQueue.main.sync { batteryState() }
        }
        let state = UIDevice.current.batteryState
        let level = UIDevice.current.batteryLevel
        switch state {
        case .charging, .full: return "charging"
        case .unplugged:
            // iOS reports `-1` when monitoring is off, between `0.0` and
            // `1.0` otherwise. Treat <= 20% as "low" to mirror Android's
            // `BatteryManager.BATTERY_STATUS_LOW` threshold.
            if level >= 0 && level <= 0.20 { return "low" }
            return "normal"
        case .unknown: return "unknown"
        @unknown default: return "unknown"
        }
        #else
        return "unknown"
        #endif
    }

    /// One of `"phone" | "tablet" | "unknown"`. Android uses
    /// `Configuration.smallestScreenWidthDp`; iOS has `userInterfaceIdiom`
    /// which is a cleaner signal for phone-vs-tablet.
    private static func deviceClass() -> String? {
        #if canImport(UIKit) && !os(watchOS)
        guard Thread.isMainThread else {
            return DispatchQueue.main.sync { deviceClass() }
        }
        switch UIDevice.current.userInterfaceIdiom {
        case .phone: return "phone"
        case .pad: return "tablet"
        default: return "unknown"
        }
        #else
        return "unknown"
        #endif
    }

    /// One of `"prod" | "beta" | "internal" | "unknown"`. iOS doesn't have
    /// a first-class release-channel API; we infer from the build config.
    /// Apps can override by setting `DASH0_BUILD_CHANNEL` in their Info.plist.
    private static func buildChannel() -> String? {
        if let override = Bundle.main.object(forInfoDictionaryKey: "DASH0_BUILD_CHANNEL") as? String,
           !override.isEmpty {
            return override.lowercased()
        }
        #if DEBUG
        return "internal"
        #else
        // Without sandbox-receipt inspection we can't distinguish beta
        // (TestFlight) from prod release at runtime reliably. Default to
        // "prod" for non-DEBUG; customers override via Info.plist if needed.
        return "prod"
        #endif
    }

    private static func osMajorVersion() -> Int? {
        ProcessInfo.processInfo.operatingSystemVersion.majorVersion
    }

    private static func appVersion() -> String? {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
    }

    #if canImport(Network)
    private func updateNetworkType(from path: NWPath) {
        let type: String
        if path.status != .satisfied {
            type = "offline"
        } else if path.usesInterfaceType(.wifi) {
            type = "wifi"
        } else if path.usesInterfaceType(.cellular) {
            type = "cellular"
        } else if path.usesInterfaceType(.wiredEthernet) {
            // Rare on iOS but real on USB-tethered simulator hosts.
            type = "wifi"
        } else {
            type = "unknown"
        }
        lock.lock()
        latestNetworkType = type
        // Network flip invalidates any cached snapshot so the next evaluate
        // sees the fresh type instead of waiting for the TTL to expire.
        cached = nil
        lock.unlock()
    }
    #endif
}
