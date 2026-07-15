/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
// Note: PrivacyConfig lives in OTelMobileCore (not OTelMobileSDK/Config) because
// InstrumentationContext references it. Keeping it in Core avoids a cyclic module dep.
import Foundation

public struct PrivacyConfig: Sendable {
    public let scrubPii: Bool
    public let captureLocation: Bool
    public let bucketCoordinates: Bool
    public let redactTextOnScreenshots: Bool

    public static let `default` = PrivacyConfig(scrubPii: true, captureLocation: false, bucketCoordinates: true, redactTextOnScreenshots: false)
    public static let minimal = PrivacyConfig(scrubPii: false, captureLocation: false, bucketCoordinates: false, redactTextOnScreenshots: false)
    public static let production = PrivacyConfig(scrubPii: true, captureLocation: false, bucketCoordinates: true, redactTextOnScreenshots: true)
    public static let debug = PrivacyConfig(scrubPii: false, captureLocation: true, bucketCoordinates: false, redactTextOnScreenshots: false)

    public init(scrubPii: Bool, captureLocation: Bool, bucketCoordinates: Bool, redactTextOnScreenshots: Bool) {
        self.scrubPii = scrubPii
        self.captureLocation = captureLocation
        self.bucketCoordinates = bucketCoordinates
        self.redactTextOnScreenshots = redactTextOnScreenshots
    }
}
