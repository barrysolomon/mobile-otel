import Foundation
import OTelMobileSDK
import OTelMobileCore
import ScreenshotInstrumentation
import WireframeInstrumentation

/// Reads credentials from bundled `otel-config.json` (or the committed
/// `.template` fallback) and boots OTelMobile. Mirrors the Starter demo's
/// `OTelMobileBootstrap` so both demos share the same config shape.
enum ShopBootstrap {
    struct DemoConfig: Decodable {
        let serviceName: String
        let serviceVersion: String
        let endpoint: String
        let authToken: String
        let dataset: String
        let incubating: IncubatingConfig?

        enum CodingKeys: String, CodingKey {
            case serviceName = "service_name"
            case serviceVersion = "service_version"
            case endpoint
            case authToken = "auth_token"
            case dataset
            case incubating
        }
    }

    /// Mirrors Android's `incubating` section in otel-config.json. All fields
    /// optional so the demo runs unchanged when the block is absent.
    struct IncubatingConfig: Decodable {
        let screenshot: ScreenshotJSON?
        let wireframe: WireframeJSON?
    }

    struct ScreenshotJSON: Decodable {
        let enabled: Bool?
        let captureOnScreenView: Bool?
        let captureOnError: Bool?
        let captureOnPolicyMatch: Bool?
    }

    struct WireframeJSON: Decodable {
        let enabled: Bool?
        let captureOnScreenView: Bool?
        let captureOnTap: Bool?
        let captureOnError: Bool?
        let captureOnPolicyMatch: Bool?
        let dedupeByContentHash: Bool?
    }

    struct BootResult {
        let mobile: OTelMobile?
        let config: DemoConfig?
        let status: String
    }

    static func start() -> BootResult {
        guard let demo = loadConfig() else {
            return BootResult(mobile: nil, config: nil,
                              status: "No otel-config.json — demo runs offline")
        }
        if demo.authToken.contains("YOUR_") || demo.endpoint.contains("YOUR-") {
            return BootResult(mobile: nil, config: demo,
                              status: "otel-config has placeholders — fill in Dash0 creds")
        }
        // Under XCUITest, every SDK auto-install is a potential interferer
        // with XCTest's XPC-based accessibility bridge: NetworkInstrumentation's
        // `URLProtocol` swizzle intercepts URLSession, VitalsInstrumentation's
        // `CADisplayLink` fires on main, signal handlers from ErrorsInstrumentation
        // can race with the XCTest process. Disable every auto-capture module
        // while UI-driven tests are running; only enable whatever the test
        // explicitly opts back in via additional launch args. The telemetry
        // we care about for cross-platform parity flows through ShopTelemetry
        // (cart, checkout, product-view) which is wired into the user-code
        // path, not auto-instrumentation.
        let underUITest = CommandLine.arguments.contains("-DASH0_UI_TEST")
        let autoCapture: AutoCaptureOptions = underUITest ? .none : .all

        let exportMode = Self.exportModeFromArgs()
        let cellId = Self.launchArg("-DASH0_CELL_ID")
        var extraAttrs: [String: String] = [:]
        if let mode = Self.launchArg("-DASH0_EXPORT_MODE") {
            extraAttrs["dash0.test.export_mode"] = mode
        }
        if let cid = cellId, !cid.isEmpty {
            extraAttrs["dash0.test.cell_id"] = cid
        }

        // Build capture configs from the optional `incubating` JSON block.
        // Absent block → default `ScreenshotConfig()` / `WireframeConfig()`.
        let screenshotJSON = demo.incubating?.screenshot
        let screenshotConfig = ScreenshotConfig(
            enabled: screenshotJSON?.enabled ?? true,
            captureOnScreenView: screenshotJSON?.captureOnScreenView ?? false,
            captureOnError: screenshotJSON?.captureOnError ?? true,
            captureOnPolicyMatch: screenshotJSON?.captureOnPolicyMatch ?? true
        )
        let wireframeJSON = demo.incubating?.wireframe
        let wireframeConfig = WireframeConfig(
            enabled: wireframeJSON?.enabled ?? true,
            captureOnScreenView: wireframeJSON?.captureOnScreenView ?? true,
            captureOnTap: wireframeJSON?.captureOnTap ?? false,
            captureOnError: wireframeJSON?.captureOnError ?? true,
            captureOnPolicyMatch: wireframeJSON?.captureOnPolicyMatch ?? true,
            dedupeByContentHash: wireframeJSON?.dedupeByContentHash ?? true
        )

        let config = MobileConfig(
            serviceName: demo.serviceName,
            serviceVersion: demo.serviceVersion,
            endpoint: demo.endpoint,
            authToken: demo.authToken,
            exportMode: exportMode,
            autoCaptureOptions: autoCapture,
            extraHeaders: ["Dash0-Dataset": demo.dataset],
            samplingConfig: .alwaysOn(),
            extraResourceAttributes: extraAttrs,
            screenshotConfig: screenshotConfig,
            wireframeConfig: wireframeConfig
        )
        // Initialise the disk buffer synchronously via a semaphore.
        // Required because @StateObject / RootState.init is sync, and
        // the demo wants crash-safety + offline-drain parity with Android.
        // 2s timeout — if sqlite init takes longer than that something is
        // badly wrong and we fall back to RAM-only.
        let diskBuffer: DiskLogBuffer? = {
            let sem = DispatchSemaphore(value: 0)
            nonisolated(unsafe) var result: DiskLogBuffer? = nil
            Task {
                result = try? await DiskLogBuffer()
                sem.signal()
            }
            _ = sem.wait(timeout: .now() + 2.0)
            return result
        }()

        do {
            let mobile = try OTelMobile.start(config: config, diskBuffer: diskBuffer)
            let diskSuffix = diskBuffer == nil ? " (no disk buffer)" : " (disk buffer active)"
            return BootResult(mobile: mobile, config: demo,
                              status: "SDK started → \(demo.dataset) on \(demo.endpoint)\(diskSuffix)")
        } catch {
            return BootResult(mobile: nil, config: demo,
                              status: "SDK start failed: \(error)")
        }
    }

    static func launchArg(_ flag: String) -> String? {
        let args = CommandLine.arguments
        guard let idx = args.firstIndex(of: flag), idx + 1 < args.count else { return nil }
        return args[idx + 1]
    }

    static func exportModeFromArgs() -> ExportMode {
        guard let raw = launchArg("-DASH0_EXPORT_MODE") else { return .conditional }
        switch raw.lowercased() {
        case "cont", "continuous": return .continuous
        case "cond", "conditional": return .conditional
        case "hyb", "hybrid": return .hybrid
        default: return .conditional
        }
    }

    static func loadConfig() -> DemoConfig? {
        let candidates: [(String, String)] = [
            ("otel-config", "json"),
            ("otel-config.json", "template"),
        ]
        for (name, ext) in candidates {
            guard let url = Bundle.main.url(forResource: name, withExtension: ext),
                  let data = try? Data(contentsOf: url) else { continue }
            if let cfg = try? JSONDecoder().decode(DemoConfig.self, from: data) {
                return cfg
            }
        }
        return nil
    }
}
