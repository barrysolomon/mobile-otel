# iOS SDK Sprint 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the foundation iOS SDK that initializes, buffers events, evaluates DSL v2 policies, and exports telemetry via OTLP/gRPC, with 6 Tier 1 instrumentation modules.

**Architecture:** "Shared Core, Native Shell" — domain logic (buffer, policy, DSL) uses Swift actors with matching type names to Android. Platform integration (swizzling, UIKit hooks) is isolated in `Platform/`. Each instrumentation module is a separate SPM target.

**Tech Stack:** Swift 5.9+, iOS 15+ (optimized for 16+), opentelemetry-swift 2.1.1+ + opentelemetry-swift-core 2.1.0+ (upstream split in 2.x), swift-collections 1.1+, raw sqlite3 C API, Swift Testing (`@Test`/`#expect`; XCTest also acceptable under Xcode but Command Line Tools ships only Swift Testing).

**Build without Xcode:** Package targets `[.iOS(.v15), .macOS(.v13)]` so `swift build`/`swift test` work on macOS with Command Line Tools only. A `run-tests.sh` wrapper sets rpath for `Testing.framework`; plain `swift test` works once a full Xcode install is active.

**Spec:** `docs/superpowers/specs/2026-04-08-ios-sdk-port-design.md`

**Key OTel Swift SDK imports:**
```
import OpenTelemetryApi       // Tracer, Logger, Meter protocols
import OpenTelemetrySdk       // TracerProviderSdk, LoggerProviderSdk, Resource, LogRecordProcessor, LogRecordExporter
import OpenTelemetryProtocolGrpc  // OtlpTraceExporter, OtlpLogExporter
```

---

## Task Dependency Graph

```
Task 1 (SPM scaffold)
  ├── Task 2 (core types)
  │     ├── Task 3 (privacy)
  │     ├── Task 4 (DSL v2 models)
  │     ├── Task 5 (session manager)
  │     └── Task 6 (context snapshot)
  ├── Task 7 (rate limiter)
  ├── Task 8 (secure storage)
  ├── Task 9 (RAM buffer)
  │     └── Task 10 (disk buffer)
  │           └── Task 11 (MobileLogRecordProcessor)
  │                 └── Task 12 (export pipeline)
  │                       └── Task 13 (policy evaluator)
  │                             └── Task 14 (config poller)
  ├── Task 15 (platform layer)
  │     ├── Task 16 (lifecycle instrumentation)
  │     ├── Task 17 (screen instrumentation)
  │     ├── Task 18 (network instrumentation)
  │     ├── Task 19 (errors instrumentation)
  │     ├── Task 20 (vitals instrumentation)
  │     └── Task 21 (freeze instrumentation)
  └── Task 22 (OTelMobile public API — wires everything)
        └── Task 23 (recovery tracker)
              └── Task 24 (demo starter app)
                    └── Task 25 (gateway changes)
                          └── Task 26 (CI script)
```

---

## File Map

### New Package: `otel-ios-mobile/`

**SPM Root:**
- `otel-ios-mobile/Package.swift` — SPM manifest

**OTelMobileCore target** (protocols, no UIKit):
- `Sources/OTelMobileCore/MobileInstrumentation.swift` — protocol
- `Sources/OTelMobileCore/InstrumentationContext.swift` — context struct
- `Sources/OTelMobileCore/InstrumentationRegistry.swift` — module registry
- `Sources/OTelMobileCore/TouchEventHub.swift` — event fan-out
- `Sources/OTelMobileCore/RateLimiter.swift` — rolling-window limiter
- `Sources/OTelMobileCore/SessionProvider.swift` — protocol

**OTelMobileSDK target** (main SDK):
- `Sources/OTelMobileSDK/OTelMobile.swift` — public API facade
- `Sources/OTelMobileSDK/OTelMobileBuilder.swift` — builder
- `Sources/OTelMobileSDK/MobileSDK.swift` — actor coordinator
- `Sources/OTelMobileSDK/Config/MobileConfig.swift` — config types
- `Sources/OTelMobileSDK/Config/ExportMode.swift` — enum
- `Sources/OTelMobileSDK/Config/PrivacyConfig.swift` — presets
- `Sources/OTelMobileSDK/Config/BufferConfig.swift` — buffer settings
- `Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift` — swizzle toggles
- `Sources/OTelMobileSDK/Session/SessionManager.swift` — session lifecycle
- `Sources/OTelMobileSDK/Privacy/PiiScrubber.swift` — regex scrubbing
- `Sources/OTelMobileSDK/Privacy/CoordinateBucketer.swift` — tap quantization
- `Sources/OTelMobileSDK/Context/ContextSnapshot.swift` — device context
- `Sources/OTelMobileSDK/Buffering/BufferedEvent.swift` — event model
- `Sources/OTelMobileSDK/Buffering/RAMEventBuffer.swift` — actor
- `Sources/OTelMobileSDK/Buffering/DiskEventBuffer.swift` — sqlite3
- `Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift` — orchestrator
- `Sources/OTelMobileSDK/Buffering/RecoveryTracker.swift` — crash recovery
- `Sources/OTelMobileSDK/Export/EnrichingLogRecordExporter.swift` — attribute enrichment
- `Sources/OTelMobileSDK/Export/RetryableExporter.swift` — retry logic
- `Sources/OTelMobileSDK/Policy/DSLv2Models.swift` — Codable structs/enums
- `Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift` — actor FSM
- `Sources/OTelMobileSDK/Policy/ConfigPoller.swift` — HTTP polling
- `Sources/OTelMobileSDK/Security/SecureStorage.swift` — Keychain wrapper
- `Sources/OTelMobileSDK/Platform/SwizzleManager.swift` — centralized swizzle
- `Sources/OTelMobileSDK/Platform/UIWindowEventForwarder.swift` — sendEvent hook
- `Sources/OTelMobileSDK/Platform/ViewControllerTracker.swift` — VC lifecycle
- `Sources/OTelMobileSDK/Platform/MainThreadWatchdog.swift` — freeze detection

**Instrumentation targets** (one per module):
- `Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift`
- `Sources/ScreenInstrumentation/ScreenInstrumentation.swift`
- `Sources/NetworkInstrumentation/NetworkInstrumentation.swift`
- `Sources/NetworkInstrumentation/OTelURLProtocol.swift`
- `Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift`
- `Sources/ErrorsInstrumentation/SignalHandler.swift`
- `Sources/VitalsInstrumentation/VitalsInstrumentation.swift`
- `Sources/FreezeInstrumentation/FreezeInstrumentation.swift`

**Tests:**
- `Tests/OTelMobileSDKTests/Privacy/PiiScrubberTests.swift`
- `Tests/OTelMobileSDKTests/Privacy/CoordinateBucketerTests.swift`
- `Tests/OTelMobileSDKTests/Policy/DSLv2ModelsTests.swift`
- `Tests/OTelMobileSDKTests/Policy/PolicyEvaluatorTests.swift`
- `Tests/OTelMobileSDKTests/Buffering/RAMEventBufferTests.swift`
- `Tests/OTelMobileSDKTests/Buffering/DiskEventBufferTests.swift`
- `Tests/OTelMobileSDKTests/Buffering/MobileLogRecordProcessorTests.swift`
- `Tests/OTelMobileSDKTests/Export/RetryableExporterTests.swift`
- `Tests/OTelMobileSDKTests/Session/SessionManagerTests.swift`
- `Tests/OTelMobileSDKTests/Config/MobileConfigTests.swift`
- `Tests/OTelMobileSDKTests/Security/SecureStorageTests.swift`
- `Tests/OTelMobileSDKTests/Core/RateLimiterTests.swift`

### Demo Starter: `examples/demo-app-ios-starter/`
- `examples/demo-app-ios-starter/StarterApp/StarterApp.swift`
- `examples/demo-app-ios-starter/StarterApp/ContentView.swift`
- `examples/demo-app-ios-starter/StarterApp/Assets.xcassets/`
- `examples/demo-app-ios-starter/StarterApp.xcodeproj/`
- `examples/demo-app-ios-starter/otel-config.json.template`
- `examples/demo-app-ios-starter/README.md`

### Gateway Changes: `mobile-otel-control-plane/`
- Modify: `gateway/internal/db/db.go` — add platform column migration
- Modify: `gateway/internal/handlers/handlers.go` — platform in registration/heartbeat/config filter

### CI:
- `scripts/test/run-ios-tests.sh`
- Modify: `run-tests.sh` — add `--ios` and `--all` flags

---

## Task 1: SPM Package Scaffold

**Files:**
- Create: `otel-ios-mobile/Package.swift`

- [ ] **Step 1: Create directory structure**

```bash
cd /Users/barrysolomon/Projects/Dash0/Mobile\ Observability/mobile-otel
mkdir -p otel-ios-mobile/Sources/{OTelMobileCore,OTelMobileSDK/{Config,Session,Privacy,Context,Buffering,Export,Policy,Security,Platform},LifecycleInstrumentation,ScreenInstrumentation,NetworkInstrumentation,ErrorsInstrumentation,VitalsInstrumentation,FreezeInstrumentation}
mkdir -p otel-ios-mobile/Tests/{OTelMobileSDKTests/{Privacy,Policy,Buffering,Export,Session,Config,Security,Core},OTelMobilePlatformTests}
```

- [ ] **Step 2: Write Package.swift**

```swift
// otel-ios-mobile/Package.swift
// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "OTelMobile",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "OTelMobileCore", targets: ["OTelMobileCore"]),
        .library(name: "OTelMobileSDK", targets: ["OTelMobileSDK"]),
        .library(name: "LifecycleInstrumentation", targets: ["LifecycleInstrumentation"]),
        .library(name: "ScreenInstrumentation", targets: ["ScreenInstrumentation"]),
        .library(name: "NetworkInstrumentation", targets: ["NetworkInstrumentation"]),
        .library(name: "ErrorsInstrumentation", targets: ["ErrorsInstrumentation"]),
        .library(name: "VitalsInstrumentation", targets: ["VitalsInstrumentation"]),
        .library(name: "FreezeInstrumentation", targets: ["FreezeInstrumentation"]),
    ],
    dependencies: [
        .package(url: "https://github.com/open-telemetry/opentelemetry-swift.git", from: "2.1.1"),
        .package(url: "https://github.com/apple/swift-collections.git", from: "1.1.0"),
    ],
    targets: [
        // Core protocols — no UIKit dependency
        .target(
            name: "OTelMobileCore",
            dependencies: [
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift"),
            ]
        ),
        // Main SDK
        .target(
            name: "OTelMobileSDK",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift"),
                .product(name: "OpenTelemetryProtocolExporter", package: "opentelemetry-swift"),
                .product(name: "DequeModule", package: "swift-collections"),
            ]
        ),
        // Instrumentation modules — each depends only on Core
        .target(name: "LifecycleInstrumentation", dependencies: ["OTelMobileCore"]),
        .target(name: "ScreenInstrumentation", dependencies: ["OTelMobileCore"]),
        .target(name: "NetworkInstrumentation", dependencies: ["OTelMobileCore"]),
        .target(name: "ErrorsInstrumentation", dependencies: ["OTelMobileCore"]),
        .target(name: "VitalsInstrumentation", dependencies: ["OTelMobileCore"]),
        .target(name: "FreezeInstrumentation", dependencies: ["OTelMobileCore"]),
        // Tests — pure logic (swift test compatible)
        .testTarget(
            name: "OTelMobileSDKTests",
            dependencies: ["OTelMobileSDK"]
        ),
    ]
)
```

- [ ] **Step 3: Create minimal placeholder files so SPM resolves**

Each `Sources/` subdirectory needs at least one `.swift` file. Create a placeholder in each:

```swift
// Sources/OTelMobileCore/MobileInstrumentation.swift
// Placeholder — will be implemented in Task 2
public protocol MobileInstrumentation {
    var id: String { get }
}
```

Repeat for each target with a minimal placeholder (empty enum, empty struct, etc.).

- [ ] **Step 4: Verify SPM resolves**

Run: `cd otel-ios-mobile && swift package resolve`
Expected: Dependencies resolve without error.

- [ ] **Step 5: Verify build**

Run: `cd otel-ios-mobile && swift build`
Expected: Build succeeds.

- [ ] **Step 6: Commit**

```bash
git add otel-ios-mobile/
git commit -m "feat(ios): scaffold SPM package with all targets and dependencies"
```

---

## Task 2: Core Types & Protocols

**Files:**
- Create: `Sources/OTelMobileCore/MobileInstrumentation.swift`
- Create: `Sources/OTelMobileCore/InstrumentationContext.swift`
- Create: `Sources/OTelMobileCore/InstrumentationRegistry.swift`
- Create: `Sources/OTelMobileCore/TouchEventHub.swift`
- Create: `Sources/OTelMobileCore/SessionProvider.swift`
- Create: `Sources/OTelMobileSDK/Config/MobileConfig.swift`
- Create: `Sources/OTelMobileSDK/Config/ExportMode.swift`
- Create: `Sources/OTelMobileSDK/Config/PrivacyConfig.swift`
- Create: `Sources/OTelMobileSDK/Config/BufferConfig.swift`
- Create: `Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift`
- Test: `Tests/OTelMobileSDKTests/Config/MobileConfigTests.swift`

- [ ] **Step 1: Write MobileInstrumentation protocol**

```swift
// Sources/OTelMobileCore/MobileInstrumentation.swift
import Foundation

public protocol MobileInstrumentation: AnyObject {
    var id: String { get }
    var isAutoCapture: Bool { get }
    func install(context: InstrumentationContext)
    func uninstall()
}
```

- [ ] **Step 2: Write SessionProvider protocol**

```swift
// Sources/OTelMobileCore/SessionProvider.swift
import Foundation

public protocol SessionProvider: Sendable {
    var sessionId: String { get }
    func rotateSession() -> String
}
```

- [ ] **Step 3: Write InstrumentationContext**

```swift
// Sources/OTelMobileCore/InstrumentationContext.swift
import Foundation
import OpenTelemetryApi

public struct InstrumentationContext {
    public let tracer: Tracer
    public let logger: Logger
    public let meter: Meter
    public let sessionProvider: SessionProvider
    public let eventHub: TouchEventHub
    public let privacyConfig: PrivacyConfig

    public init(
        tracer: Tracer,
        logger: Logger,
        meter: Meter,
        sessionProvider: SessionProvider,
        eventHub: TouchEventHub,
        privacyConfig: PrivacyConfig
    ) {
        self.tracer = tracer
        self.logger = logger
        self.meter = meter
        self.sessionProvider = sessionProvider
        self.eventHub = eventHub
        self.privacyConfig = privacyConfig
    }
}
```

- [ ] **Step 4: Write InstrumentationRegistry**

```swift
// Sources/OTelMobileCore/InstrumentationRegistry.swift
import Foundation

public final class InstrumentationRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var modules: [String: MobileInstrumentation] = [:]

    public init() {}

    public func register(_ module: MobileInstrumentation) {
        lock.lock()
        defer { lock.unlock() }
        modules[module.id] = module
    }

    public func installAll(context: InstrumentationContext) {
        lock.lock()
        let snapshot = Array(modules.values)
        lock.unlock()
        for module in snapshot {
            module.install(context: context)
        }
    }

    public func uninstallAll() {
        lock.lock()
        let snapshot = Array(modules.values)
        lock.unlock()
        for module in snapshot {
            module.uninstall()
        }
    }

    public func module(forId id: String) -> MobileInstrumentation? {
        lock.lock()
        defer { lock.unlock() }
        return modules[id]
    }

    public var allModules: [MobileInstrumentation] {
        lock.lock()
        defer { lock.unlock() }
        return Array(modules.values)
    }
}
```

- [ ] **Step 5: Write TouchEventHub**

```swift
// Sources/OTelMobileCore/TouchEventHub.swift
import Foundation

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
            case touchDown
            case touchUp
            case touchMoved
        }

        public init(type: EventType, timestamp: Date, x: CGFloat, y: CGFloat, viewDescription: String?) {
            self.type = type
            self.timestamp = timestamp
            self.x = x
            self.y = y
            self.viewDescription = viewDescription
        }
    }

    private let lock = NSLock()
    private var listeners: [String: TouchEventListener] = [:]

    public init() {}

    public func addListener(id: String, listener: TouchEventListener) {
        lock.lock()
        defer { lock.unlock() }
        listeners[id] = listener
    }

    public func removeListener(id: String) {
        lock.lock()
        defer { lock.unlock() }
        listeners.removeValue(forKey: id)
    }

    public func dispatch(_ event: Event) {
        lock.lock()
        let snapshot = Array(listeners.values)
        lock.unlock()
        for listener in snapshot {
            listener.onTouchEvent(event)
        }
    }
}
```

- [ ] **Step 6: Write config types**

```swift
// Sources/OTelMobileSDK/Config/ExportMode.swift
public enum ExportMode: String, Codable, Sendable {
    case conditional
    case continuous
    case hybrid
}

// Sources/OTelMobileSDK/Config/BufferConfig.swift
public struct BufferConfig: Sendable {
    public let ramEvents: Int
    public let diskMb: Int
    public let retentionHours: Int

    public static let `default` = BufferConfig(ramEvents: 5000, diskMb: 50, retentionHours: 24)

    public init(ramEvents: Int, diskMb: Int, retentionHours: Int) {
        self.ramEvents = ramEvents
        self.diskMb = diskMb
        self.retentionHours = retentionHours
    }
}

// Sources/OTelMobileSDK/Config/PrivacyConfig.swift
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

// Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift
public struct AutoCaptureOptions: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }

    public static let tap = AutoCaptureOptions(rawValue: 1 << 0)
    public static let scroll = AutoCaptureOptions(rawValue: 1 << 1)
    public static let lifecycle = AutoCaptureOptions(rawValue: 1 << 2)
    public static let screen = AutoCaptureOptions(rawValue: 1 << 3)
    public static let network = AutoCaptureOptions(rawValue: 1 << 4)
    public static let errors = AutoCaptureOptions(rawValue: 1 << 5)
    public static let freeze = AutoCaptureOptions(rawValue: 1 << 6)
    public static let vitals = AutoCaptureOptions(rawValue: 1 << 7)
    public static let textInput = AutoCaptureOptions(rawValue: 1 << 8)
    public static let screenshot = AutoCaptureOptions(rawValue: 1 << 9)
    public static let wireframe = AutoCaptureOptions(rawValue: 1 << 10)

    public static let all: AutoCaptureOptions = [.tap, .scroll, .lifecycle, .screen, .network, .errors, .freeze, .vitals, .textInput, .screenshot, .wireframe]
    public static let none: AutoCaptureOptions = []
}

// Sources/OTelMobileSDK/Config/MobileConfig.swift
public struct MobileConfig: Sendable {
    public let serviceName: String
    public let serviceVersion: String
    public let endpoint: String
    public let authToken: String?
    public let exportMode: ExportMode
    public let bufferConfig: BufferConfig
    public let privacyConfig: PrivacyConfig
    public let autoCaptureOptions: AutoCaptureOptions
    public let pollingIntervalSeconds: Int

    public init(
        serviceName: String,
        serviceVersion: String = "1.0.0",
        endpoint: String,
        authToken: String? = nil,
        exportMode: ExportMode = .conditional,
        bufferConfig: BufferConfig = .default,
        privacyConfig: PrivacyConfig = .default,
        autoCaptureOptions: AutoCaptureOptions = .all,
        pollingIntervalSeconds: Int = 300
    ) {
        self.serviceName = serviceName
        self.serviceVersion = serviceVersion
        self.endpoint = endpoint
        self.authToken = authToken
        self.exportMode = exportMode
        self.bufferConfig = bufferConfig
        self.privacyConfig = privacyConfig
        self.autoCaptureOptions = autoCaptureOptions
        self.pollingIntervalSeconds = pollingIntervalSeconds
    }
}
```

- [ ] **Step 7: Write config tests**

```swift
// Tests/OTelMobileSDKTests/Config/MobileConfigTests.swift
import XCTest
@testable import OTelMobileSDK

final class MobileConfigTests: XCTestCase {
    func testDefaultBufferConfig() {
        let config = BufferConfig.default
        XCTAssertEqual(config.ramEvents, 5000)
        XCTAssertEqual(config.diskMb, 50)
        XCTAssertEqual(config.retentionHours, 24)
    }

    func testPrivacyPresets() {
        XCTAssertTrue(PrivacyConfig.production.scrubPii)
        XCTAssertFalse(PrivacyConfig.production.captureLocation)
        XCTAssertTrue(PrivacyConfig.production.redactTextOnScreenshots)

        XCTAssertFalse(PrivacyConfig.debug.scrubPii)
        XCTAssertTrue(PrivacyConfig.debug.captureLocation)

        XCTAssertFalse(PrivacyConfig.minimal.scrubPii)
        XCTAssertFalse(PrivacyConfig.minimal.bucketCoordinates)
    }

    func testAutoCaptureAll() {
        let all = AutoCaptureOptions.all
        XCTAssertTrue(all.contains(.tap))
        XCTAssertTrue(all.contains(.network))
        XCTAssertTrue(all.contains(.errors))
        XCTAssertTrue(all.contains(.freeze))
    }

    func testAutoCaptureCustom() {
        let custom: AutoCaptureOptions = [.tap, .network]
        XCTAssertTrue(custom.contains(.tap))
        XCTAssertTrue(custom.contains(.network))
        XCTAssertFalse(custom.contains(.scroll))
    }

    func testMobileConfigDefaults() {
        let config = MobileConfig(serviceName: "test-app", endpoint: "https://collector:4317")
        XCTAssertEqual(config.exportMode, .conditional)
        XCTAssertEqual(config.pollingIntervalSeconds, 300)
        XCTAssertNil(config.authToken)
    }
}
```

- [ ] **Step 8: Run tests**

Run: `cd otel-ios-mobile && swift test --filter MobileConfigTests`
Expected: All tests pass.

- [ ] **Step 9: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileCore/ otel-ios-mobile/Sources/OTelMobileSDK/Config/ otel-ios-mobile/Tests/
git commit -m "feat(ios): core protocols, config types, and InstrumentationContext"
```

---

## Task 3: Privacy — PiiScrubber & CoordinateBucketer

**Files:**
- Create: `Sources/OTelMobileSDK/Privacy/PiiScrubber.swift`
- Create: `Sources/OTelMobileSDK/Privacy/CoordinateBucketer.swift`
- Test: `Tests/OTelMobileSDKTests/Privacy/PiiScrubberTests.swift`
- Test: `Tests/OTelMobileSDKTests/Privacy/CoordinateBucketerTests.swift`

- [ ] **Step 1: Write PiiScrubber tests**

```swift
// Tests/OTelMobileSDKTests/Privacy/PiiScrubberTests.swift
import XCTest
@testable import OTelMobileSDK

final class PiiScrubberTests: XCTestCase {
    let scrubber = PiiScrubber()

    func testEmailRedaction() {
        let result = scrubber.scrub("Contact user@example.com for help")
        XCTAssertEqual(result, "Contact [REDACTED] for help")
    }

    func testPhoneRedaction() {
        let result = scrubber.scrub("Call 555-123-4567 now")
        XCTAssertEqual(result, "Call [REDACTED] now")
    }

    func testSsnRedaction() {
        let result = scrubber.scrub("SSN: 123-45-6789")
        XCTAssertEqual(result, "SSN: [REDACTED]")
    }

    func testMultiplePatterns() {
        let result = scrubber.scrub("Email user@test.com phone 555-123-4567")
        XCTAssertFalse(result.contains("user@test.com"))
        XCTAssertFalse(result.contains("555-123-4567"))
    }

    func testNoMatchPassesThrough() {
        let input = "No PII here, just regular text"
        XCTAssertEqual(scrubber.scrub(input), input)
    }

    func testEmptyString() {
        XCTAssertEqual(scrubber.scrub(""), "")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd otel-ios-mobile && swift test --filter PiiScrubberTests`
Expected: FAIL — `PiiScrubber` not defined.

- [ ] **Step 3: Implement PiiScrubber**

```swift
// Sources/OTelMobileSDK/Privacy/PiiScrubber.swift
import Foundation

public struct PiiScrubber: Sendable {
    private static let patterns: [(name: String, pattern: String)] = [
        ("email", "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
        ("phone", "\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b"),
        ("ssn", "\\b\\d{3}-\\d{2}-\\d{4}\\b"),
    ]

    private let regexes: [(name: String, regex: NSRegularExpression)]

    public init() {
        self.regexes = Self.patterns.compactMap { entry in
            guard let regex = try? NSRegularExpression(pattern: entry.pattern, options: []) else { return nil }
            return (entry.name, regex)
        }
    }

    public func scrub(_ text: String) -> String {
        var result = text
        for (_, regex) in regexes {
            result = regex.stringByReplacingMatches(
                in: result,
                options: [],
                range: NSRange(result.startIndex..., in: result),
                withTemplate: "[REDACTED]"
            )
        }
        return result
    }
}
```

- [ ] **Step 4: Run PiiScrubber tests**

Run: `cd otel-ios-mobile && swift test --filter PiiScrubberTests`
Expected: All pass.

- [ ] **Step 5: Write CoordinateBucketer tests**

```swift
// Tests/OTelMobileSDKTests/Privacy/CoordinateBucketerTests.swift
import XCTest
@testable import OTelMobileSDK

final class CoordinateBucketerTests: XCTestCase {
    let bucketer = CoordinateBucketer()

    func testBucketsToGrid() {
        let result = bucketer.bucket(x: 123.0, y: 267.0)
        XCTAssertEqual(result.x, 100.0)  // floor(123/50)*50
        XCTAssertEqual(result.y, 250.0)  // floor(267/50)*50
    }

    func testZeroPassesThrough() {
        let result = bucketer.bucket(x: 0, y: 0)
        XCTAssertEqual(result.x, 0.0)
        XCTAssertEqual(result.y, 0.0)
    }

    func testExactGridBoundary() {
        let result = bucketer.bucket(x: 50.0, y: 100.0)
        XCTAssertEqual(result.x, 50.0)
        XCTAssertEqual(result.y, 100.0)
    }

    func testCustomGridSize() {
        let bucketer = CoordinateBucketer(gridSize: 100.0)
        let result = bucketer.bucket(x: 149.0, y: 250.0)
        XCTAssertEqual(result.x, 100.0)
        XCTAssertEqual(result.y, 200.0)
    }
}
```

- [ ] **Step 6: Implement CoordinateBucketer**

```swift
// Sources/OTelMobileSDK/Privacy/CoordinateBucketer.swift
import Foundation

public struct CoordinateBucketer: Sendable {
    public let gridSize: CGFloat

    public init(gridSize: CGFloat = 50.0) {
        self.gridSize = gridSize
    }

    public struct BucketedPoint: Equatable, Sendable {
        public let x: CGFloat
        public let y: CGFloat
    }

    public func bucket(x: CGFloat, y: CGFloat) -> BucketedPoint {
        BucketedPoint(
            x: (x / gridSize).rounded(.down) * gridSize,
            y: (y / gridSize).rounded(.down) * gridSize
        )
    }
}
```

- [ ] **Step 7: Run all privacy tests**

Run: `cd otel-ios-mobile && swift test --filter "PiiScrubberTests|CoordinateBucketerTests"`
Expected: All pass.

- [ ] **Step 8: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Privacy/ otel-ios-mobile/Tests/OTelMobileSDKTests/Privacy/
git commit -m "feat(ios): PiiScrubber and CoordinateBucketer with tests"
```

---

## Task 4: DSL v2 Models

**Files:**
- Create: `Sources/OTelMobileSDK/Policy/DSLv2Models.swift`
- Test: `Tests/OTelMobileSDKTests/Policy/DSLv2ModelsTests.swift`

This is the core contract between the iOS SDK and the control plane. All 31 matcher types and 15 action types must be parseable.

- [ ] **Step 1: Write DSL v2 model parsing tests**

```swift
// Tests/OTelMobileSDKTests/Policy/DSLv2ModelsTests.swift
import XCTest
@testable import OTelMobileSDK

final class DSLv2ModelsTests: XCTestCase {

    func testDecodeCrashMatcher() throws {
        let json = """
        {"type": "crash"}
        """.data(using: .utf8)!
        let matcher = try JSONDecoder().decode(DSLMatcher.self, from: json)
        guard case .crash = matcher else { XCTFail("Expected crash matcher"); return }
    }

    func testDecodeHttpMatcher() throws {
        let json = """
        {"type": "http_match", "status_min": 500, "method": "POST"}
        """.data(using: .utf8)!
        let matcher = try JSONDecoder().decode(DSLMatcher.self, from: json)
        guard case .httpMatch(let statusMin, _, let method) = matcher else {
            XCTFail("Expected http_match"); return
        }
        XCTAssertEqual(statusMin, 500)
        XCTAssertEqual(method, "POST")
    }

    func testDecodeCompoundMatcher() throws {
        let json = """
        {"type": "compound", "combine": "all", "children": [{"type": "crash"}, {"type": "low_memory", "available_mb": 50}]}
        """.data(using: .utf8)!
        let matcher = try JSONDecoder().decode(DSLMatcher.self, from: json)
        guard case .compound(let mode, let children) = matcher else {
            XCTFail("Expected compound"); return
        }
        XCTAssertEqual(mode, .all)
        XCTAssertEqual(children.count, 2)
    }

    func testDecodeFlushBufferAction() throws {
        let json = """
        {"type": "flush_buffer", "minutes": 5, "scope": "session"}
        """.data(using: .utf8)!
        let action = try JSONDecoder().decode(DSLAction.self, from: json)
        guard case .flushBuffer(let minutes, let scope) = action else {
            XCTFail("Expected flush_buffer"); return
        }
        XCTAssertEqual(minutes, 5)
        XCTAssertEqual(scope, .session)
    }

    func testDecodeFullWorkflow() throws {
        let json = """
        {
            "id": "crash-handler",
            "name": "Crash Handler",
            "enabled": true,
            "priority": 1,
            "initial_state": "watching",
            "states": [{
                "id": "watching",
                "matchers": [{"type": "crash"}],
                "on_match": {
                    "actions": [{"type": "flush_buffer", "minutes": 5, "scope": "session"}],
                    "transition_to": "done"
                }
            }]
        }
        """.data(using: .utf8)!
        let workflow = try JSONDecoder().decode(DSLWorkflow.self, from: json)
        XCTAssertEqual(workflow.id, "crash-handler")
        XCTAssertEqual(workflow.initialState, "watching")
        XCTAssertEqual(workflow.states.count, 1)
        XCTAssertEqual(workflow.states[0].matchers.count, 1)
    }

    func testDecodeFullConfig() throws {
        let json = """
        {
            "version": 2,
            "buffer_config": {"ram_events": 3000, "disk_mb": 25, "retention_hours": 12, "strategy": "overwrite_oldest"},
            "workflows": []
        }
        """.data(using: .utf8)!
        let config = try JSONDecoder().decode(DSLConfigV2.self, from: json)
        XCTAssertEqual(config.version, 2)
        XCTAssertEqual(config.bufferConfig.ramEvents, 3000)
    }

    func testDecodeFleetMatcherDoesNotFail() throws {
        // Fleet matchers are server-side only but must parse without error
        let json = """
        {"type": "fleet_threshold"}
        """.data(using: .utf8)!
        let matcher = try JSONDecoder().decode(DSLMatcher.self, from: json)
        guard case .fleetThreshold = matcher else { XCTFail("Expected fleet_threshold"); return }
    }

    func testDecodeUnknownMatcherThrows() {
        let json = """
        {"type": "totally_unknown_type_xyz"}
        """.data(using: .utf8)!
        XCTAssertThrowsError(try JSONDecoder().decode(DSLMatcher.self, from: json))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd otel-ios-mobile && swift test --filter DSLv2ModelsTests`
Expected: FAIL — types not defined.

- [ ] **Step 3: Implement DSLv2Models**

```swift
// Sources/OTelMobileSDK/Policy/DSLv2Models.swift
import Foundation

// MARK: - Top-Level Config

public struct DSLConfigV2: Codable, Sendable {
    public let version: Int
    public let bufferConfig: DSLBufferConfig
    public let targeting: DSLTargeting?
    public let workflows: [DSLWorkflow]

    enum CodingKeys: String, CodingKey {
        case version
        case bufferConfig = "buffer_config"
        case targeting
        case workflows
    }
}

public struct DSLBufferConfig: Codable, Sendable {
    public let ramEvents: Int
    public let diskMb: Int
    public let retentionHours: Int
    public let strategy: String?

    enum CodingKeys: String, CodingKey {
        case ramEvents = "ram_events"
        case diskMb = "disk_mb"
        case retentionHours = "retention_hours"
        case strategy
    }
}

public struct DSLTargeting: Codable, Sendable {
    public let platform: String?
    public let appVersionRange: String?
    public let osVersionRange: String?
    public let deviceModels: [String]?
    public let deviceGroup: String?
    public let customAttributes: [String: String]?

    enum CodingKeys: String, CodingKey {
        case platform
        case appVersionRange = "app_version_range"
        case osVersionRange = "os_version_range"
        case deviceModels = "device_models"
        case deviceGroup = "device_group"
        case customAttributes = "custom_attributes"
    }
}

// MARK: - Workflow

public struct DSLWorkflow: Codable, Sendable {
    public let id: String
    public let name: String
    public let enabled: Bool
    public let priority: Int
    public let initialState: String
    public let states: [DSLWorkflowState]

    enum CodingKeys: String, CodingKey {
        case id, name, enabled, priority
        case initialState = "initial_state"
        case states
    }
}

public struct DSLWorkflowState: Codable, Sendable {
    public let id: String
    public let matchers: [DSLMatcher]
    public let onMatch: DSLMatchAction?
    public let onTimeout: DSLTimeoutAction?

    enum CodingKeys: String, CodingKey {
        case id, matchers
        case onMatch = "on_match"
        case onTimeout = "on_timeout"
    }
}

public struct DSLMatchAction: Codable, Sendable {
    public let actions: [DSLAction]
    public let transitionTo: String?

    enum CodingKeys: String, CodingKey {
        case actions
        case transitionTo = "transition_to"
    }
}

public struct DSLTimeoutAction: Codable, Sendable {
    public let durationMs: Int
    public let actions: [DSLAction]
    public let transitionTo: String?

    enum CodingKeys: String, CodingKey {
        case durationMs = "duration_ms"
        case actions
        case transitionTo = "transition_to"
    }
}

// MARK: - Combine Mode

public enum DSLCombineMode: String, Codable, Sendable {
    case any
    case all
}

// MARK: - Flush Scope

public enum DSLFlushScope: String, Codable, Sendable {
    case session
    case device
}

// MARK: - Matcher (31 types + compound)

public enum DSLMatcher: Codable, Sendable {
    // 21 core matchers (evaluated on-device)
    case eventMatch(eventName: String)
    case logSeverity(minSeverity: String, bodyContains: String?)
    case metricThreshold(metricName: String, op: String, threshold: Double)
    case httpMatch(statusMin: Int?, routeContains: String?, method: String?)
    case crash
    case exceptionPattern(exceptionType: String, messagePattern: String?)
    case uiFreeze(durationMs: Int)
    case slowOperation(operationName: String, thresholdMs: Int)
    case frameDrop(droppedFrames: Int, windowMs: Int)
    case networkLoss(consecutiveFailures: Int?)
    case lowMemory(availableMb: Int)
    case batteryDrain(drainRatePercPerMin: Double)
    case thermalThrottle(minLevel: Int)
    case storageLow(availableMb: Int)
    case predictiveRisk(riskType: String, minScore: Double)
    case anr
    case appLifecycle(event: String)
    case resourceSnapshot(metric: String, op: String, threshold: Double)
    case fieldPresence(fieldName: String)
    case fieldAbsence(fieldName: String)
    case timeout(durationMs: Int)
    // Compound
    case compound(combine: DSLCombineMode, children: [DSLMatcher])
    // 7 fleet matchers (server-side only, parsed but not evaluated locally)
    case fleetThreshold
    case fleetRate
    case fleetAbsence
    case fleetCorrelation
    case fleetAnomaly
    case fleetPrediction
    case fleetRootCause
    // 3 backend matchers (server-side only)
    case backendHealth
    case backendDeploy
    case backendCapacity

    // Custom Codable to handle "type" discriminator
    enum CodingKeys: String, CodingKey {
        case type, combine, children
        case eventName = "event_name"
        case minSeverity = "min_severity"
        case bodyContains = "body_contains"
        case metricName = "metric_name"
        case op = "operator"
        case threshold
        case statusMin = "status_min"
        case routeContains = "route_contains"
        case method
        case exceptionType = "exception_type"
        case messagePattern = "message_pattern"
        case durationMs = "duration_ms"
        case operationName = "operation_name"
        case thresholdMs = "threshold_ms"
        case droppedFrames = "dropped_frames"
        case windowMs = "window_ms"
        case consecutiveFailures = "consecutive_failures"
        case availableMb = "available_mb"
        case drainRatePercPerMin = "drain_rate_perc_per_min"
        case minLevel = "min_level"
        case riskType = "risk_type"
        case minScore = "min_score"
        case event
        case metric
        case fieldName = "field_name"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)

        switch type {
        case "event_match":
            self = .eventMatch(eventName: try container.decode(String.self, forKey: .eventName))
        case "log_severity":
            self = .logSeverity(
                minSeverity: try container.decode(String.self, forKey: .minSeverity),
                bodyContains: try container.decodeIfPresent(String.self, forKey: .bodyContains)
            )
        case "metric_threshold":
            self = .metricThreshold(
                metricName: try container.decode(String.self, forKey: .metricName),
                op: try container.decode(String.self, forKey: .op),
                threshold: try container.decode(Double.self, forKey: .threshold)
            )
        case "http_match":
            self = .httpMatch(
                statusMin: try container.decodeIfPresent(Int.self, forKey: .statusMin),
                routeContains: try container.decodeIfPresent(String.self, forKey: .routeContains),
                method: try container.decodeIfPresent(String.self, forKey: .method)
            )
        case "crash": self = .crash
        case "exception_pattern":
            self = .exceptionPattern(
                exceptionType: try container.decode(String.self, forKey: .exceptionType),
                messagePattern: try container.decodeIfPresent(String.self, forKey: .messagePattern)
            )
        case "ui_freeze":
            self = .uiFreeze(durationMs: try container.decode(Int.self, forKey: .durationMs))
        case "slow_operation":
            self = .slowOperation(
                operationName: try container.decode(String.self, forKey: .operationName),
                thresholdMs: try container.decode(Int.self, forKey: .thresholdMs)
            )
        case "frame_drop":
            self = .frameDrop(
                droppedFrames: try container.decode(Int.self, forKey: .droppedFrames),
                windowMs: try container.decode(Int.self, forKey: .windowMs)
            )
        case "network_loss":
            self = .networkLoss(consecutiveFailures: try container.decodeIfPresent(Int.self, forKey: .consecutiveFailures))
        case "low_memory":
            self = .lowMemory(availableMb: try container.decode(Int.self, forKey: .availableMb))
        case "battery_drain":
            self = .batteryDrain(drainRatePercPerMin: try container.decode(Double.self, forKey: .drainRatePercPerMin))
        case "thermal_throttle":
            self = .thermalThrottle(minLevel: try container.decode(Int.self, forKey: .minLevel))
        case "storage_low":
            self = .storageLow(availableMb: try container.decode(Int.self, forKey: .availableMb))
        case "predictive_risk":
            self = .predictiveRisk(
                riskType: try container.decode(String.self, forKey: .riskType),
                minScore: try container.decode(Double.self, forKey: .minScore)
            )
        case "anr": self = .anr
        case "app_lifecycle":
            self = .appLifecycle(event: try container.decode(String.self, forKey: .event))
        case "resource_snapshot":
            self = .resourceSnapshot(
                metric: try container.decode(String.self, forKey: .metric),
                op: try container.decode(String.self, forKey: .op),
                threshold: try container.decode(Double.self, forKey: .threshold)
            )
        case "field_presence":
            self = .fieldPresence(fieldName: try container.decode(String.self, forKey: .fieldName))
        case "field_absence":
            self = .fieldAbsence(fieldName: try container.decode(String.self, forKey: .fieldName))
        case "timeout":
            self = .timeout(durationMs: try container.decode(Int.self, forKey: .durationMs))
        case "compound":
            self = .compound(
                combine: try container.decode(DSLCombineMode.self, forKey: .combine),
                children: try container.decode([DSLMatcher].self, forKey: .children)
            )
        case "fleet_threshold": self = .fleetThreshold
        case "fleet_rate": self = .fleetRate
        case "fleet_absence": self = .fleetAbsence
        case "fleet_correlation": self = .fleetCorrelation
        case "fleet_anomaly": self = .fleetAnomaly
        case "fleet_prediction": self = .fleetPrediction
        case "fleet_root_cause": self = .fleetRootCause
        case "backend_health": self = .backendHealth
        case "backend_deploy": self = .backendDeploy
        case "backend_capacity": self = .backendCapacity
        default:
            throw DecodingError.dataCorrupted(
                .init(codingPath: [CodingKeys.type], debugDescription: "Unknown matcher type: \(type)")
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        // Encoding support for round-trip testing; abbreviated for plan brevity
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .crash: try container.encode("crash", forKey: .type)
        case .anr: try container.encode("anr", forKey: .type)
        // ... remaining cases follow same pattern
        default: break
        }
    }
}

// MARK: - Action (15 types)

public enum DSLAction: Codable, Sendable {
    // 10 core actions
    case flushBuffer(minutes: Int, scope: DSLFlushScope)
    case recordSession(maxDurationMinutes: Int)
    case emitMetric(metricName: String, metricType: String)
    case createFunnel(funnelName: String, steps: [String])
    case createSankey(sankeyName: String)
    case takeScreenshot(quality: String?, redactText: Bool?)
    case annotate(triggerId: String, reason: String)
    case setSampling(rate: Double, durationMinutes: Int?)
    case adjustBuffer(parameter: String, value: Int, durationMinutes: Int?)
    case sendAlert(severity: String, message: String)
    // 5 fleet actions
    case fleetFlush
    case fleetSetSampling
    case fleetAdjustConfig
    case fleetScreenshot
    case fleetClientCircuitBreak

    enum CodingKeys: String, CodingKey {
        case type, minutes, scope
        case maxDurationMinutes = "max_duration_minutes"
        case metricName = "metric_name"
        case metricType = "metric_type"
        case funnelName = "funnel_name"
        case steps
        case sankeyName = "sankey_name"
        case quality
        case redactText = "redact_text"
        case triggerId = "trigger_id"
        case reason, rate
        case durationMinutes = "duration_minutes"
        case parameter, value, severity, message
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)

        switch type {
        case "flush_buffer":
            self = .flushBuffer(
                minutes: try container.decode(Int.self, forKey: .minutes),
                scope: try container.decode(DSLFlushScope.self, forKey: .scope)
            )
        case "record_session":
            self = .recordSession(maxDurationMinutes: try container.decode(Int.self, forKey: .maxDurationMinutes))
        case "emit_metric":
            self = .emitMetric(
                metricName: try container.decode(String.self, forKey: .metricName),
                metricType: try container.decode(String.self, forKey: .metricType)
            )
        case "create_funnel":
            self = .createFunnel(
                funnelName: try container.decode(String.self, forKey: .funnelName),
                steps: try container.decode([String].self, forKey: .steps)
            )
        case "create_sankey":
            self = .createSankey(sankeyName: try container.decode(String.self, forKey: .sankeyName))
        case "take_screenshot":
            self = .takeScreenshot(
                quality: try container.decodeIfPresent(String.self, forKey: .quality),
                redactText: try container.decodeIfPresent(Bool.self, forKey: .redactText)
            )
        case "annotate":
            self = .annotate(
                triggerId: try container.decode(String.self, forKey: .triggerId),
                reason: try container.decode(String.self, forKey: .reason)
            )
        case "set_sampling":
            self = .setSampling(
                rate: try container.decode(Double.self, forKey: .rate),
                durationMinutes: try container.decodeIfPresent(Int.self, forKey: .durationMinutes)
            )
        case "adjust_buffer":
            self = .adjustBuffer(
                parameter: try container.decode(String.self, forKey: .parameter),
                value: try container.decode(Int.self, forKey: .value),
                durationMinutes: try container.decodeIfPresent(Int.self, forKey: .durationMinutes)
            )
        case "send_alert":
            self = .sendAlert(
                severity: try container.decode(String.self, forKey: .severity),
                message: try container.decode(String.self, forKey: .message)
            )
        case "fleet_flush": self = .fleetFlush
        case "fleet_set_sampling": self = .fleetSetSampling
        case "fleet_adjust_config": self = .fleetAdjustConfig
        case "fleet_screenshot": self = .fleetScreenshot
        case "fleet_client_circuit_break": self = .fleetClientCircuitBreak
        default:
            throw DecodingError.dataCorrupted(
                .init(codingPath: [CodingKeys.type], debugDescription: "Unknown action type: \(type)")
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .flushBuffer(let minutes, let scope):
            try container.encode("flush_buffer", forKey: .type)
            try container.encode(minutes, forKey: .minutes)
            try container.encode(scope, forKey: .scope)
        // ... remaining cases follow same pattern
        default: break
        }
    }
}
```

- [ ] **Step 4: Run DSL model tests**

Run: `cd otel-ios-mobile && swift test --filter DSLv2ModelsTests`
Expected: All pass.

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Policy/DSLv2Models.swift otel-ios-mobile/Tests/OTelMobileSDKTests/Policy/DSLv2ModelsTests.swift
git commit -m "feat(ios): DSL v2 Codable models — 31 matchers, 15 actions, full config"
```

---

## Task 5: RateLimiter

**Files:**
- Create: `Sources/OTelMobileCore/RateLimiter.swift`
- Test: `Tests/OTelMobileSDKTests/Core/RateLimiterTests.swift`

- [ ] **Step 1: Write tests**

```swift
// Tests/OTelMobileSDKTests/Core/RateLimiterTests.swift
import XCTest
@testable import OTelMobileCore

final class RateLimiterTests: XCTestCase {
    func testAllowsWithinLimit() {
        let limiter = RateLimiter(maxPerWindow: 3, windowMs: 60_000)
        XCTAssertTrue(limiter.tryAcquire())
        XCTAssertTrue(limiter.tryAcquire())
        XCTAssertTrue(limiter.tryAcquire())
    }

    func testRejectsOverLimit() {
        let limiter = RateLimiter(maxPerWindow: 2, windowMs: 60_000)
        XCTAssertTrue(limiter.tryAcquire())
        XCTAssertTrue(limiter.tryAcquire())
        XCTAssertFalse(limiter.tryAcquire())
    }

    func testResetsAfterWindow() {
        let limiter = RateLimiter(maxPerWindow: 1, windowMs: 50) // 50ms window
        XCTAssertTrue(limiter.tryAcquire())
        XCTAssertFalse(limiter.tryAcquire())
        Thread.sleep(forTimeInterval: 0.06) // wait for window to pass
        XCTAssertTrue(limiter.tryAcquire())
    }
}
```

- [ ] **Step 2: Implement RateLimiter**

```swift
// Sources/OTelMobileCore/RateLimiter.swift
import Foundation

public final class RateLimiter: @unchecked Sendable {
    private let maxPerWindow: Int
    private let windowMs: Int
    private let lock = NSLock()
    private var timestamps: [UInt64] = []

    public init(maxPerWindow: Int, windowMs: Int) {
        self.maxPerWindow = maxPerWindow
        self.windowMs = windowMs
    }

    public func tryAcquire() -> Bool {
        lock.lock()
        defer { lock.unlock() }

        let now = DispatchTime.now().uptimeNanoseconds / 1_000_000 // ms
        let windowStart = now - UInt64(windowMs)

        timestamps.removeAll { $0 < windowStart }

        if timestamps.count >= maxPerWindow {
            return false
        }
        timestamps.append(now)
        return true
    }

    public func reset() {
        lock.lock()
        defer { lock.unlock() }
        timestamps.removeAll()
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd otel-ios-mobile && swift test --filter RateLimiterTests`
Expected: All pass.

- [ ] **Step 4: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileCore/RateLimiter.swift otel-ios-mobile/Tests/OTelMobileSDKTests/Core/
git commit -m "feat(ios): RateLimiter — rolling-window rate limiter for instrumentation modules"
```

---

## Task 6: Session Manager

**Files:**
- Create: `Sources/OTelMobileSDK/Session/SessionManager.swift`
- Test: `Tests/OTelMobileSDKTests/Session/SessionManagerTests.swift`

- [ ] **Step 1: Write tests**

```swift
// Tests/OTelMobileSDKTests/Session/SessionManagerTests.swift
import XCTest
@testable import OTelMobileSDK
@testable import OTelMobileCore

final class SessionManagerTests: XCTestCase {
    func testGeneratesSessionId() {
        let manager = SessionManager()
        let sessionId = manager.sessionId
        XCTAssertFalse(sessionId.isEmpty)
        XCTAssertEqual(sessionId.count, 36) // UUID format
    }

    func testSessionIdStableAcrossCalls() {
        let manager = SessionManager()
        let id1 = manager.sessionId
        let id2 = manager.sessionId
        XCTAssertEqual(id1, id2)
    }

    func testRotateSessionGeneratesNewId() {
        let manager = SessionManager()
        let oldId = manager.sessionId
        let newId = manager.rotateSession()
        XCTAssertNotEqual(oldId, newId)
        XCTAssertEqual(manager.sessionId, newId)
    }

    func testConformsToSessionProvider() {
        let manager = SessionManager()
        let provider: SessionProvider = manager
        XCTAssertFalse(provider.sessionId.isEmpty)
    }
}
```

- [ ] **Step 2: Implement SessionManager**

```swift
// Sources/OTelMobileSDK/Session/SessionManager.swift
import Foundation
import OTelMobileCore

public final class SessionManager: SessionProvider, @unchecked Sendable {
    private let lock = NSLock()
    private var _sessionId: String

    public init() {
        self._sessionId = UUID().uuidString
    }

    public var sessionId: String {
        lock.lock()
        defer { lock.unlock() }
        return _sessionId
    }

    @discardableResult
    public func rotateSession() -> String {
        lock.lock()
        defer { lock.unlock() }
        _sessionId = UUID().uuidString
        return _sessionId
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd otel-ios-mobile && swift test --filter SessionManagerTests`
Expected: All pass.

- [ ] **Step 4: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Session/ otel-ios-mobile/Tests/OTelMobileSDKTests/Session/
git commit -m "feat(ios): SessionManager with UUID-based session tracking"
```

---

## Task 7: RAM Event Buffer

**Files:**
- Create: `Sources/OTelMobileSDK/Buffering/BufferedEvent.swift`
- Create: `Sources/OTelMobileSDK/Buffering/RAMEventBuffer.swift`
- Test: `Tests/OTelMobileSDKTests/Buffering/RAMEventBufferTests.swift`

- [ ] **Step 1: Write BufferedEvent model**

```swift
// Sources/OTelMobileSDK/Buffering/BufferedEvent.swift
import Foundation

public struct BufferedEvent: Sendable {
    public let sequenceId: UInt64
    public let timestampMs: UInt64
    public let sessionId: String
    public let eventData: Data      // serialized OTLP LogRecord (protobuf)
    public let sizeBytes: Int
    public let createdAt: Date

    public init(sequenceId: UInt64, timestampMs: UInt64, sessionId: String, eventData: Data, createdAt: Date = Date()) {
        self.sequenceId = sequenceId
        self.timestampMs = timestampMs
        self.sessionId = sessionId
        self.eventData = eventData
        self.sizeBytes = eventData.count
        self.createdAt = createdAt
    }
}
```

- [ ] **Step 2: Write RAMEventBuffer tests**

```swift
// Tests/OTelMobileSDKTests/Buffering/RAMEventBufferTests.swift
import XCTest
@testable import OTelMobileSDK

final class RAMEventBufferTests: XCTestCase {

    func testAppendWithinCapacity() async {
        let buffer = RAMEventBuffer(capacity: 3)
        let evicted = await buffer.append(makeEvent(id: 1))
        XCTAssertNil(evicted)
        let count = await buffer.count
        XCTAssertEqual(count, 1)
    }

    func testOverflowEvictsOldest() async {
        let buffer = RAMEventBuffer(capacity: 2)
        await buffer.append(makeEvent(id: 1))
        await buffer.append(makeEvent(id: 2))
        let evicted = await buffer.append(makeEvent(id: 3))
        XCTAssertEqual(evicted?.sequenceId, 1)
        let count = await buffer.count
        XCTAssertEqual(count, 2)
    }

    func testFlushReturnsAllEvents() async {
        let buffer = RAMEventBuffer(capacity: 10)
        await buffer.append(makeEvent(id: 1))
        await buffer.append(makeEvent(id: 2))
        await buffer.append(makeEvent(id: 3))
        let events = await buffer.flush()
        XCTAssertEqual(events.count, 3)
        let afterCount = await buffer.count
        XCTAssertEqual(afterCount, 0)
    }

    func testFlushWindowReturnsOnlyMatching() async {
        let buffer = RAMEventBuffer(capacity: 10)
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        await buffer.append(makeEvent(id: 1, timestampMs: now - 60_000))  // 1 min ago
        await buffer.append(makeEvent(id: 2, timestampMs: now - 30_000))  // 30s ago
        await buffer.append(makeEvent(id: 3, timestampMs: now))           // now
        let events = await buffer.flushWindow(lastMs: 45_000) // last 45 seconds
        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events[0].sequenceId, 2)
    }

    func testSequenceIdsAreMonotonic() async {
        let buffer = RAMEventBuffer(capacity: 10)
        await buffer.append(makeEvent(id: 1))
        await buffer.append(makeEvent(id: 2))
        let events = await buffer.flush()
        XCTAssertTrue(events[0].sequenceId < events[1].sequenceId)
    }

    // MARK: - Helpers

    @discardableResult
    private func makeEvent(id: UInt64, timestampMs: UInt64? = nil) -> BufferedEvent {
        BufferedEvent(
            sequenceId: id,
            timestampMs: timestampMs ?? UInt64(Date().timeIntervalSince1970 * 1000),
            sessionId: "test-session",
            eventData: Data("test-\(id)".utf8)
        )
    }
}
```

- [ ] **Step 3: Implement RAMEventBuffer**

```swift
// Sources/OTelMobileSDK/Buffering/RAMEventBuffer.swift
import Foundation
import DequeModule

public actor RAMEventBuffer {
    private var events: Deque<BufferedEvent>
    private let capacity: Int

    public init(capacity: Int) {
        self.capacity = capacity
        self.events = Deque()
    }

    public var count: Int { events.count }

    @discardableResult
    public func append(_ event: BufferedEvent) -> BufferedEvent? {
        if events.count >= capacity {
            let evicted = events.removeFirst()
            events.append(event)
            return evicted
        }
        events.append(event)
        return nil
    }

    public func flush() -> [BufferedEvent] {
        let result = Array(events)
        events.removeAll()
        return result
    }

    public func flushWindow(lastMs: UInt64) -> [BufferedEvent] {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        let cutoff = now - lastMs
        let matching = events.filter { $0.timestampMs >= cutoff }
        events.removeAll { $0.timestampMs >= cutoff }
        return matching
    }

    public func peek() -> [BufferedEvent] {
        Array(events)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd otel-ios-mobile && swift test --filter RAMEventBufferTests`
Expected: All pass.

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Buffering/ otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/
git commit -m "feat(ios): RAMEventBuffer actor with Deque-backed ring buffer"
```

---

## Tasks 8-26: Remaining Sprint 1 Implementation

> **Note:** Tasks 8-26 follow the same TDD pattern established in Tasks 1-7. Each task has: failing test → implement → pass → commit. The remaining tasks are summarized here with file paths and key implementation details. The executing agent should expand each into full step-by-step with code.

### Task 8: Disk Event Buffer (sqlite3)
**Files:** `Sources/OTelMobileSDK/Buffering/DiskEventBuffer.swift`, `Tests/OTelMobileSDKTests/Buffering/DiskEventBufferTests.swift`
- Import `SQLite3` (system module, no extra dependency)
- `sqlite3_open` to a file in `Application Support/io.dash0.otel/buffer.db`
- Create `buffered_events` table per spec schema
- `PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;`
- Methods: `insert(_ event:)`, `fetchAll() -> [BufferedEvent]`, `fetchWindow(lastMs:)`, `deleteUpTo(sequenceId:)`, `totalSizeBytes() -> Int`, `pruneByTTL(retentionHours:)`, `pruneBySize(maxMb:)`
- Tests: insert/retrieve, TTL pruning, size limit, empty DB

### Task 9: MobileLogRecordProcessor
**Files:** `Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift`, `Tests/OTelMobileSDKTests/Buffering/MobileLogRecordProcessorTests.swift`
- Implements OTel `LogRecordProcessor` protocol (`onEmit`, `forceFlush`, `shutdown`)
- Orchestrates RAM → Disk overflow
- `onEmit`: create `BufferedEvent` from `ReadableLogRecord`, assign monotonic seqId via `AtomicUInt64`, append to RAM, spill overflow to disk
- `forceFlush`: collect from RAM + disk, pass to `RetryableExporter`
- `flushWindow(minutes:)`: selective time-window flush
- Tests: mock exporter, verify overflow spill, verify flush

### Task 10: Export Pipeline (RetryableExporter + EnrichingLogRecordExporter)
**Files:** `Sources/OTelMobileSDK/Export/RetryableExporter.swift`, `Sources/OTelMobileSDK/Export/EnrichingLogRecordExporter.swift`, `Tests/OTelMobileSDKTests/Export/RetryableExporterTests.swift`
- `RetryableExporter`: wraps a `LogRecordExporter`, retries on failure (max 3, exponential backoff)
- `EnrichingLogRecordExporter`: wraps exporter, adds device/session/app attributes to each log record before export
- Tests: mock exporter that fails N times then succeeds, verify retry count

### Task 11: Policy Evaluator
**Files:** `Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift`, `Tests/OTelMobileSDKTests/Policy/PolicyEvaluatorTests.swift`
- Actor that holds `[DSLWorkflow]` and `workflowStates: [String: String]` (workflow ID → current state ID)
- `evaluate(event:context:) -> [DSLAction]`: iterate workflows by priority, match current state's matchers, return actions + transition
- `updateWorkflows(_ workflows:)`: replace workflows, reset states
- Matcher evaluation: `matches(_ matcher:event:context:) -> Bool` — implement all 21 core matchers (fleet/backend return false)
- Tests: crash matcher triggers flush, compound AND/OR, FSM state transition, disabled workflow skipped

### Task 12: Config Poller
**Files:** `Sources/OTelMobileSDK/Policy/ConfigPoller.swift`
- Actor that polls `GET /config?app_id=X&device_id=Y&dsl_version=2` using `URLSession`
- Parses response as `DSLConfigV2`
- Only calls `policyEvaluator.updateWorkflows()` when version > currentVersion
- `startPolling()`: `Task` loop with `Task.sleep(for:)`
- `stopPolling()`: cancels task

### Task 13: Secure Storage
**Files:** `Sources/OTelMobileSDK/Security/SecureStorage.swift`, `Tests/OTelMobileSDKTests/Security/SecureStorageTests.swift`
- Keychain wrapper: `save(key:data:)`, `load(key:) -> Data?`, `delete(key:)`
- Uses `kSecClassGenericPassword`, service = `"io.dash0.otel"`
- Tests: save/load round-trip, overwrite, delete

### Task 14: Context Snapshot
**Files:** `Sources/OTelMobileSDK/Context/ContextSnapshot.swift`
- Struct capturing device state at a point in time
- Fields: `deviceModel`, `osVersion`, `appVersion`, `networkType`, `batteryLevel`, `batteryState`, `thermalState`, `availableMemoryMb`, `diskFreeMb`, `locale`, `timezone`, `screenWidth`, `screenHeight`
- `static func capture() -> ContextSnapshot` — reads from UIDevice, ProcessInfo, NWPathMonitor, FileManager
- Uses `#available(iOS 16, *)` for `UIWindowScene`-based screen bounds

### Task 15: Platform Layer (SwizzleManager + UIWindowEventForwarder + ViewControllerTracker + MainThreadWatchdog)
**Files:** 4 files in `Sources/OTelMobileSDK/Platform/`
- **SwizzleManager**: singleton, `register(SwizzleRegistration)`, `unregister(moduleId:)`, `isSwizzled(selector:on:) -> Bool`, uses `method_exchangeImplementations`
- **UIWindowEventForwarder**: swizzles `UIWindow.sendEvent(_:)`, fans out to `TouchEventHub`
- **ViewControllerTracker**: swizzles `UIViewController.viewDidAppear/viewDidDisappear`, notifies subscribers, filters system VCs
- **MainThreadWatchdog**: background `DispatchQueue` pings main via `DispatchSemaphore`, reports freeze when threshold exceeded
- Platform tests go in `OTelMobilePlatformTests` (require simulator)

### Task 16: Lifecycle Instrumentation
**Files:** `Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift`
- Observes `UIApplication` notifications: `didBecomeActiveNotification`, `willResignActiveNotification`, `didEnterBackgroundNotification`, `willEnterForegroundNotification`
- Emits log record with `event.name = "app.lifecycle"`, `lifecycle.state` attribute
- `isAutoCapture = true` (no swizzling needed, just NotificationCenter)

### Task 17: Screen Instrumentation
**Files:** `Sources/ScreenInstrumentation/ScreenInstrumentation.swift`
- Subscribes to `ViewControllerTracker`
- Emits `ui.screen_view` log + starts/ends `page.<ClassName>` span
- Strips "ViewController" suffix from class name
- `isAutoCapture = true`

### Task 18: Network Instrumentation
**Files:** `Sources/NetworkInstrumentation/NetworkInstrumentation.swift`, `Sources/NetworkInstrumentation/OTelURLProtocol.swift`
- `OTelURLProtocol: URLProtocol` — intercepts HTTP requests
- Captures: method, URL (sanitized per privacy config), status code, duration, response size
- Emits `http.request` span with OTel HTTP semantic conventions
- Registers with `URLSessionConfiguration.default` on install
- `isAutoCapture = true`

### Task 19: Errors Instrumentation
**Files:** `Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift`, `Sources/ErrorsInstrumentation/SignalHandler.swift`
- `NSSetUncaughtExceptionHandler` — chains to previous
- POSIX signal handlers for SIGSEGV, SIGABRT, SIGBUS, SIGFPE (async-signal-safe)
- Deduplication via exception fingerprint (type + top 3 frames)
- Uses `RateLimiter(maxPerWindow: 10, windowMs: 60_000)`
- `isAutoCapture = true`

### Task 20: Vitals Instrumentation
**Files:** `Sources/VitalsInstrumentation/VitalsInstrumentation.swift`
- App start time: `ProcessInfo.processInfo.systemUptime` at first `viewDidAppear`
- Memory gauge: `os_proc_available_memory()` periodic reading
- Battery gauge: `UIDevice.current.batteryLevel` (enable monitoring first)
- MetricKit on iOS 16+: `MXMetricManager` subscriber
- `isAutoCapture = true`

### Task 21: Freeze Instrumentation
**Files:** `Sources/FreezeInstrumentation/FreezeInstrumentation.swift`
- Subscribes to `MainThreadWatchdog` events
- Emits `ui.freeze` event with duration
- Emits `anr` event if duration > 5000ms
- Configurable threshold via `FreezeConfig`
- `isAutoCapture = true`

### Task 22: OTelMobile Public API
**Files:** `Sources/OTelMobileSDK/OTelMobile.swift`, `Sources/OTelMobileSDK/OTelMobileBuilder.swift`, `Sources/OTelMobileSDK/MobileSDK.swift`
- **OTelMobileBuilder**: fluent builder, accumulates config + instrumentations
- **OTelMobile**: public facade, delegates to `MobileSDK` actor
  - `builder() -> OTelMobileBuilder`
  - `start(application:)` and `buildAndStart()` (deferred via `DispatchQueue.main.async`)
  - `setUser(_ identity:)`, `flush()`, `shutdown()`
- **MobileSDK actor**: creates `TracerProviderSdk`, `LoggerProviderSdk`, `Resource`, wires `MobileLogRecordProcessor`, `PolicyEvaluator`, `ConfigPoller`, `InstrumentationRegistry`
- Registers for `willTerminateNotification` and `didEnterBackgroundNotification`
- Uses `UIApplication.beginBackgroundTask` for background flush

### Task 23: Recovery Tracker
**Files:** `Sources/OTelMobileSDK/Buffering/RecoveryTracker.swift`
- On SDK start: checks `DiskEventBuffer` for un-exported events
- If found, triggers flush via `MobileLogRecordProcessor.forceFlush()`
- Stores last clean shutdown flag in `UserDefaults`

### Task 24: Demo Starter App
**Files:** `examples/demo-app-ios-starter/` (Xcode project)
- SwiftUI `@main` app using `buildAndStart()`
- Single `ContentView` with buttons: "Trigger Crash", "Make Network Call", "Navigate"
- `otel-config.json.template` with placeholder credentials
- `README.md` with setup steps

### Task 25: Gateway Changes
**Files:** Modify `mobile-otel-control-plane/gateway/internal/db/db.go`, `gateway/internal/handlers/handlers.go`
- Add `platform` column migration to `devices` table
- Accept `platform` in `RegisterDevice` and `HandleStatus`
- Filter workflows by `targeting.platform` in `HandleGetConfig`
- Default unset platform to `"android"` for backward compatibility
- Run existing Go tests: `cd gateway && go test -v -race ./...`

### Task 26: CI Script
**Files:** Create `scripts/test/run-ios-tests.sh`, Modify `run-tests.sh`
- `run-ios-tests.sh`: runs `cd otel-ios-mobile && swift test` (unit) + optional `xcodebuild test` (integration)
- `run-tests.sh`: add `--ios` flag (runs iOS only), `--all` flag (both platforms)
- Make executable: `chmod +x`

---

## Final Commit

After all tasks complete:

```bash
git add -A
git commit -m "feat(ios): Sprint 1 complete — iOS SDK foundation with 6 Tier 1 instrumentation modules

- SPM package with modular targets (Core, SDK, 6 instrumentation modules)
- Dual-tier buffer (RAM actor + sqlite3 disk) with crash recovery
- DSL v2 policy evaluator (31 matchers, 15 actions, FSM execution)
- OTLP/gRPC export via opentelemetry-swift 2.x
- Platform layer: SwizzleManager, UIWindowEventForwarder, ViewControllerTracker, MainThreadWatchdog
- Tier 1 modules: Lifecycle, Screen, Network, Errors, Vitals, Freeze
- Privacy: PiiScrubber, CoordinateBucketer, PrivacyConfig presets
- Demo starter app (SwiftUI)
- Gateway: platform column + config filtering
- CI: run-ios-tests.sh, run-tests.sh --ios/--all flags"
```
