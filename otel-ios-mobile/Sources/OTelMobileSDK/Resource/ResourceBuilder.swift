/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk
#if canImport(UIKit)
import UIKit
#endif

/// Builds a shared OpenTelemetry `Resource` describing the mobile runtime that
/// emits telemetry. The result is passed to all three providers (logger,
/// tracer, meter) so every signal carries a consistent set of device /
/// service / SDK attributes.
///
/// Attribute keys follow the OpenTelemetry semantic conventions
/// (service.*, telemetry.sdk.*, os.*, device.*).
///
/// Note: this lives in `OTelMobileSDK` (not `OTelMobileCore`) because the
/// `Resource` type ships in `OpenTelemetrySdk`, which is an SDK-target
/// dependency only. Moving it to Core would force Core to depend on the full
/// SDK — unwanted coupling.
public enum ResourceBuilder {
    /// SDK version constant — bump on release. Kept in lockstep with the npm
    /// package.json version + native gradle sdkVersionName; the publish gate
    /// (scripts/ci/check-version-parity.sh) fails the release on drift.
    public static let sdkVersion = "1.0.0"

    /// Build a `Resource` with iOS-identifying attributes plus the caller's
    /// service metadata and any extra attributes they want to merge on top.
    /// Extras override defaults on key collision.
    public static func buildMobileResource(
        serviceName: String,
        serviceVersion: String,
        extraAttributes: [String: String] = [:]
    ) -> Resource {
        var attrs: [String: AttributeValue] = [
            "service.name": .string(serviceName),
            "service.version": .string(serviceVersion),
            "telemetry.sdk.name": .string("io.dash0.mobile"),
            "telemetry.sdk.version": .string(sdkVersion),
            "telemetry.sdk.language": .string("swift"),
            // Dash0 resource-type classifier. Without this, Dash0 reads
            // `telemetry.sdk.language=swift` and surfaces iOS data under its
            // Website / browser category. Setting the classifier explicitly
            // routes the resource to the Mobile category in the Dash0 UI.
            // Android does the same from its ResourceBuilder — this keeps
            // both platforms side-by-side under one resource type.
            "dash0.resource.type": .string("mobile"),
        ]

        // OS / device attributes — populated per-platform.
        #if os(iOS)
        attrs["os.type"] = .string("darwin")
        attrs["os.name"] = .string("iOS")
        #if canImport(UIKit)
        attrs["os.version"] = .string(UIDevice.current.systemVersion)
        attrs["device.manufacturer"] = .string("Apple")
        attrs["device.model.name"] = .string(UIDevice.current.model)
        attrs["device.model.identifier"] = .string(machineIdentifier())
        if let vendorId = UIDevice.current.identifierForVendor?.uuidString {
            attrs["device.id"] = .string(vendorId)
        }
        #endif
        #elseif os(macOS)
        attrs["os.type"] = .string("darwin")
        attrs["os.name"] = .string("macOS")
        attrs["os.version"] = .string(ProcessInfo.processInfo.operatingSystemVersionString)
        attrs["device.manufacturer"] = .string("Apple")
        #endif

        // Symbolication Phase 1 (docs/design/symbolication.md): the main
        // executable's Mach-O LC_UUID matches the dSYM that symbolicates
        // this build's crashes, so every signal carries it. Android emits
        // the same key from its build-time manifest stamp.
        if let buildId = BuildIdReader.mainExecutableUUID() {
            attrs["app.build.id"] = .string(buildId)
        }

        // User-provided extras override any defaults we may have set above.
        for (key, value) in extraAttributes {
            attrs[key] = .string(value)
        }
        return Resource(attributes: attrs)
    }

    /// Returns the hardware machine identifier (e.g. `iPhone17,3`). On the
    /// simulator `uname.machine` is the host arch (`arm64` / `x86_64`), so we
    /// prefer the `SIMULATOR_MODEL_IDENTIFIER` env var — which the simulator
    /// runtime exposes as the device being emulated — and tag it so consumers
    /// can tell the two apart.
    private static func machineIdentifier() -> String {
        #if targetEnvironment(simulator)
        if let simModel = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return simModel + " (Simulator)"
        }
        #endif
        var systemInfo = utsname()
        uname(&systemInfo)
        let machineMirror = Mirror(reflecting: systemInfo.machine)
        let identifier = machineMirror.children.reduce("") { identifier, element in
            guard let value = element.value as? Int8, value != 0 else { return identifier }
            return identifier + String(UnicodeScalar(UInt8(value)))
        }
        return identifier
    }
}
