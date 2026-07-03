import Foundation
import Testing

/// Guards the Apple privacy manifests (`PrivacyInfo.xcprivacy`) shipped with
/// the SPM package. Apple rejects app submissions when a third-party SDK
/// uses "required reason" APIs without declaring them, so these tests pin:
///
/// 1. The manifest files exist for the targets that use required-reason APIs.
/// 2. Every required-reason API category the code actually uses is declared
///    with a valid reason code.
/// 3. The manifests declare no tracking (we never do ATT-style tracking).
/// 4. `Package.swift` registers the manifests as target resources — a
///    manifest that exists on disk but isn't a declared resource never
///    reaches the consuming app's bundle and is invisible to App Review.
///
/// The tests read the files from the repository (via `#filePath`) rather
/// than `Bundle.module` so they also validate the *source of truth* that
/// ships to consumers, independent of test-bundle resource processing.
@Suite("Privacy manifest (PrivacyInfo.xcprivacy)")
struct PrivacyManifestTests {

    /// Repo root, derived from this file's location:
    /// <root>/otel-ios-mobile/Tests/OTelMobileSDKTests/Privacy/PrivacyManifestTests.swift
    private static let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // Privacy/
        .deletingLastPathComponent()  // OTelMobileSDKTests/
        .deletingLastPathComponent()  // Tests/
        .deletingLastPathComponent()  // otel-ios-mobile/
        .deletingLastPathComponent()  // <root>

    private static let sdkManifestURL = repoRoot
        .appendingPathComponent("otel-ios-mobile/Sources/OTelMobileSDK/PrivacyInfo.xcprivacy")
    private static let coreManifestURL = repoRoot
        .appendingPathComponent("otel-ios-mobile/Sources/OTelMobileCore/PrivacyInfo.xcprivacy")

    private static func loadPlist(_ url: URL) throws -> [String: Any] {
        let data = try Data(contentsOf: url)
        let plist = try PropertyListSerialization.propertyList(from: data, format: nil)
        return try #require(plist as? [String: Any])
    }

    /// Accessed-API declarations keyed by category →  set of reason codes.
    private static func accessedApiReasons(in manifest: [String: Any]) throws -> [String: Set<String>] {
        let entries = try #require(
            manifest["NSPrivacyAccessedAPITypes"] as? [[String: Any]],
            "manifest must declare NSPrivacyAccessedAPITypes"
        )
        var result: [String: Set<String>] = [:]
        for entry in entries {
            let category = try #require(entry["NSPrivacyAccessedAPIType"] as? String)
            let reasons = try #require(entry["NSPrivacyAccessedAPITypeReasons"] as? [String])
            #expect(!reasons.isEmpty, "category \(category) must list at least one reason code")
            result[category] = Set(reasons)
        }
        return result
    }

    @Test("SDK manifest exists")
    func sdkManifestExists() {
        #expect(
            FileManager.default.fileExists(atPath: Self.sdkManifestURL.path),
            "PrivacyInfo.xcprivacy missing for OTelMobileSDK target"
        )
    }

    @Test("Core manifest exists")
    func coreManifestExists() {
        #expect(
            FileManager.default.fileExists(atPath: Self.coreManifestURL.path),
            "PrivacyInfo.xcprivacy missing for OTelMobileCore target"
        )
    }

    @Test("SDK manifest declares the required-reason APIs the SDK uses")
    func sdkManifestDeclaresAccessedApis() throws {
        let reasons = try Self.accessedApiReasons(in: Self.loadPlist(Self.sdkManifestURL))

        // UserDefaults: SessionManager / ConfigPoller / FleetAlertDeduplicator.
        // CA92.1 = access info from the same app/SDK only.
        #expect(reasons["NSPrivacyAccessedAPICategoryUserDefaults"]?.contains("CA92.1") == true)

        // Disk space: DeviceHealthMonitor / DeviceStatsCollector read
        // volumeAvailableCapacityForImportantUsage before writing buffers.
        // E174.1 = check whether there is sufficient disk space to write files.
        #expect(reasons["NSPrivacyAccessedAPICategoryDiskSpace"]?.contains("E174.1") == true)

        // System boot time: BootTracker (kern.boottime) reached via OTelMobileCore.
        // 35F9.1 = measure time elapsed between events inside the app.
        #expect(reasons["NSPrivacyAccessedAPICategorySystemBootTime"]?.contains("35F9.1") == true)
    }

    @Test("Core manifest declares system boot time (BootTracker)")
    func coreManifestDeclaresBootTime() throws {
        let reasons = try Self.accessedApiReasons(in: Self.loadPlist(Self.coreManifestURL))
        #expect(reasons["NSPrivacyAccessedAPICategorySystemBootTime"]?.contains("35F9.1") == true)
    }

    @Test("manifests declare no tracking and no tracking domains")
    func manifestsDeclareNoTracking() throws {
        for url in [Self.sdkManifestURL, Self.coreManifestURL] {
            let manifest = try Self.loadPlist(url)
            #expect(manifest["NSPrivacyTracking"] as? Bool == false,
                    "\(url.lastPathComponent) must set NSPrivacyTracking=false")
            let domains = manifest["NSPrivacyTrackingDomains"] as? [String] ?? []
            #expect(domains.isEmpty, "no tracking domains may be declared")
        }
    }

    @Test("SDK manifest declares collected data types, none linked or tracking")
    func sdkManifestCollectedData() throws {
        let manifest = try Self.loadPlist(Self.sdkManifestURL)
        let collected = try #require(
            manifest["NSPrivacyCollectedDataTypes"] as? [[String: Any]],
            "SDK manifest must declare NSPrivacyCollectedDataTypes"
        )
        let types = Set(collected.compactMap { $0["NSPrivacyCollectedDataType"] as? String })
        // The SDK exports crash reports, perf/vitals telemetry, a generated
        // device.id resource attribute, and diagnostic logs.
        #expect(types.contains("NSPrivacyCollectedDataTypeCrashData"))
        #expect(types.contains("NSPrivacyCollectedDataTypePerformanceData"))
        #expect(types.contains("NSPrivacyCollectedDataTypeDeviceID"))
        #expect(types.contains("NSPrivacyCollectedDataTypeOtherDiagnosticData"))

        for entry in collected {
            let type = entry["NSPrivacyCollectedDataType"] as? String ?? "?"
            #expect(entry["NSPrivacyCollectedDataTypeLinked"] as? Bool == false,
                    "\(type) must not be declared as linked to identity")
            #expect(entry["NSPrivacyCollectedDataTypeTracking"] as? Bool == false,
                    "\(type) must not be declared as used for tracking")
            let purposes = entry["NSPrivacyCollectedDataTypePurposes"] as? [String] ?? []
            #expect(!purposes.isEmpty, "\(type) must declare at least one purpose")
        }
    }

    @Test("Package.swift ships both manifests as target resources")
    func packageManifestRegistersResources() throws {
        let packageSwift = try String(
            contentsOf: Self.repoRoot.appendingPathComponent("Package.swift"),
            encoding: .utf8
        )
        // Both targets must carry a resources entry for the manifest;
        // .copy keeps the exact filename Apple's scanner looks for.
        let occurrences = packageSwift.components(
            separatedBy: ".copy(\"PrivacyInfo.xcprivacy\")"
        ).count - 1
        #expect(occurrences >= 2,
                "Package.swift must register PrivacyInfo.xcprivacy via .copy for OTelMobileCore and OTelMobileSDK")
    }
}
