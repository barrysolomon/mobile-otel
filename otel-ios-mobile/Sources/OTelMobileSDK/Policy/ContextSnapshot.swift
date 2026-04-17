import Foundation

// MARK: - ContextSnapshot (placeholder)
//
// Minimal port of Android's `io.opentelemetry.android.mobile.context.ContextSnapshot`.
// Intentionally a *data-only* struct with every field optional so the iOS
// evaluator can accept it today even though the iOS-side collector (geo
// lookup, battery / network probes, locale introspection, etc.) has not yet
// been ported. Once we port `ContextSnapshotProvider` for iOS, geo/device
// matchers will hydrate these fields; until then callers pass `nil` and the
// evaluator ignores the geo/device dimensions entirely.
//
// Contract: every field is optional, and the struct is Sendable so it can
// cross actor boundaries cleanly (the evaluator is an actor).
public struct ContextSnapshot: Sendable, Equatable {
    public let countryCode: String?
    public let region: String?
    public let timezone: String?
    public let localeId: String?
    /// One of: "wifi", "cellular", "offline", "unknown".
    public let networkType: String?
    /// One of: "charging", "low", "normal", "unknown".
    public let batteryState: String?
    /// One of: "phone", "tablet", "unknown".
    public let deviceClass: String?
    /// One of: "prod", "beta", "internal", "unknown".
    public let buildChannel: String?
    /// Major OS version as Int (e.g. iOS 17.4 -> 17). Matches Android's
    /// `osVersionInt` for parity with its device matcher.
    public let osVersionInt: Int?
    public let appVersion: String?

    public init(
        countryCode: String? = nil,
        region: String? = nil,
        timezone: String? = nil,
        localeId: String? = nil,
        networkType: String? = nil,
        batteryState: String? = nil,
        deviceClass: String? = nil,
        buildChannel: String? = nil,
        osVersionInt: Int? = nil,
        appVersion: String? = nil
    ) {
        self.countryCode = countryCode
        self.region = region
        self.timezone = timezone
        self.localeId = localeId
        self.networkType = networkType
        self.batteryState = batteryState
        self.deviceClass = deviceClass
        self.buildChannel = buildChannel
        self.osVersionInt = osVersionInt
        self.appVersion = appVersion
    }
}
