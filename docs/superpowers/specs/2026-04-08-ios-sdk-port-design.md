# iOS SDK Port — Design Specification

**Date:** 2026-04-08
**Status:** Approved
**Scope:** Port Dash0 Mobile Observability Android SDK to iOS with full feature parity

---

## Table of Contents

1. [Overview & Decisions](#1-overview--decisions)
2. [Public API & SDK Initialization](#2-public-api--sdk-initialization)
3. [Dual-Tier Buffer System](#3-dual-tier-buffer-system)
4. [Policy DSL v2 Evaluator](#4-policy-dsl-v2-evaluator)
5. [Instrumentation Module System](#5-instrumentation-module-system)
6. [Platform Integration Layer & Privacy](#6-platform-integration-layer--privacy)
7. [Demo Apps & Debug Diagnostics Panel](#7-demo-apps--debug-diagnostics-panel)
8. [Control Plane Changes & Gateway Updates](#8-control-plane-changes--gateway-updates)
9. [Testing Strategy](#9-testing-strategy)
10. [Sprint Structure & Epics](#10-sprint-structure--epics)

---

## 1. Overview & Decisions

### What We're Porting

The Dash0 Mobile Observability Android SDK: ~10,700 lines of Kotlin across 48 files, 16 instrumentation modules, a dual-tier buffer (RAM + SQLite), policy DSL v2 evaluator, OTLP/gRPC export, predictive export, and session/journey tracking. Plus a full demo app (~7,300 LOC) and a minimal starter app.

### Architecture: "Shared Core, Native Shell" (Approach C)

Domain logic (policy evaluation, buffer management, DSL parsing, session tracking) uses a shared conceptual architecture with matching type names and file organization across Android and iOS. The platform integration layer (swizzling, UIKit hooks, OS APIs) is fully native Swift, cleanly isolated in a `Platform/` directory.

### Locked Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Min deployment | iOS 16+ (architect for iOS 15 compat) | Sweet spot: modern APIs, ~90% device coverage. iOS 15 compat via `#available` guards. |
| Distribution | Swift Package Manager only | Modern standard, matches OTel Swift SDK distribution. Modular so CocoaPods can be added later. |
| OTel dependency | Thin wrapper — own buffer/policy, use opentelemetry-swift v2.x for traces/metrics/export | Own the critical differentiating path (buffering, policy eval), leverage community for standard OTel plumbing. Logs API is stable in v2.x. |
| Demo app | Starter + full demo with improved debug/ring buffer panel | Starter for onboarding, full demo for showcase. Debug panel is a must-have. |
| Repo location | Inside `mobile-otel/` as peer directories | Shares collector processor, demo backend, test scripts. One data plane, multiple platform clients. |
| Module phasing | Tier 1 (Sprint 1) → Tier 2 (Sprint 2) → Tier 3 (Sprint 3) | Ordered by iOS implementation complexity and value. |
| Swizzling | On by default, opt-out per module | Matches Android's auto-capture philosophy. Centralized SwizzleManager for conflict detection. |

### Repo Structure

```
mobile-otel/
├── otel-android-mobile/          # existing Android SDK
├── otel-android-mobile-core/     # existing Android core interfaces
├── otel-ios-mobile/              # NEW — Swift Package (SDK + Core + Instrumentation)
├── instrumentation/              # existing Android instrumentation modules
├── collector-processor/          # shared (Go) — no changes
├── examples/
│   ├── demo-app/                 # existing Android demo
│   ├── demo-app-starter/         # existing Android starter
│   ├── demo-app-ios/             # NEW — full SwiftUI demo
│   ├── demo-app-ios-starter/     # NEW — minimal starter
│   └── demo-backend/             # shared (Node.js) — no changes
└── scripts/
    └── test/
        └── run-ios-tests.sh      # NEW
```

**Credentials pattern:** iOS demo apps follow the same `.json.template` pattern as Android. `otel-config.json` is git-ignored; developers copy from `otel-config.json.template` and fill in Dash0 credentials.

---

## 2. Public API & SDK Initialization

### Entry Point

```swift
let mobile = OTelMobile.builder()
    .setServiceName("my-app")
    .setEndpoint("https://collector.dash0.com:4317")
    .setAuthToken("dash0-token")
    .setPrivacyConfig(.production)
    .setBufferConfig(.default)
    .addInstrumentation(TapInstrumentation())
    .addInstrumentation(NetworkInstrumentation())
    .addInstrumentation(ScreenInstrumentation())
    .addInstrumentation(ErrorsInstrumentation())
    .addInstrumentation(VitalsInstrumentation())
    .addInstrumentation(FreezeInstrumentation())
    .setExportMode(.conditional)           // or .continuous, .hybrid
    .setAutoCaptureOptions(.all)
    .build()

// In AppDelegate or @main App
mobile.start(application: application)

// Identify user
mobile.setUser(UserIdentity(userId: "u123", email: "user@example.com"))
```

### Design Details

- **Builder pattern** matches Android for cross-platform documentation consistency
- `start(application:)` takes `UIApplication` to register lifecycle observers and scene notifications
- Each instrumentation is a **separate SPM target** — customers import only what they need
- `AutoCaptureOptions` controls swizzling: `.all`, `.none`, or `.custom([.tap, .network])`
- Thread safety: `OTelMobile` is a reference type backed by an internal `actor MobileSDK` coordinator
- iOS 15 compatibility: public API uses no iOS 16+ types; internal `#available` guards where needed

### SwiftUI App Lifecycle Support

SwiftUI apps using `@main struct MyApp: App` don't have a traditional `AppDelegate`. The SDK supports both patterns:

```swift
// Option A: UIKit AppDelegate
mobile.start(application: application)

// Option B: SwiftUI — use the .onAppear modifier or init
@main struct MyApp: App {
    init() {
        OTelMobile.builder()
            .setServiceName("my-app")
            // ...
            .buildAndStart()  // convenience that combines build() + start()
    }
}
```

**Important:** `UIApplication.shared` may not be available when a SwiftUI `@main` App's `init()` runs. `buildAndStart()` defers lifecycle registration to the next run loop tick via `DispatchQueue.main.async`, ensuring `UIApplication` is fully initialized before the SDK hooks into it. For scene-based apps, the SDK also observes `UIScene` notifications via `NotificationCenter`.

### Shutdown & Flush

```swift
// Clean shutdown — flushes remaining buffer, stops all instrumentation
await mobile.shutdown()

// Manual flush without shutdown (e.g., before a critical section)
await mobile.flush()
```

Both are critical for clean teardown. The SDK registers for `UIApplication.willTerminateNotification` internally, but **this notification is NOT reliably called** when iOS kills the app due to memory pressure, thermal throttling, or watchdog timeout. The real safety net is `RecoveryTracker` on next launch, which detects un-exported disk events and flushes them. The `didEnterBackground` handler (with background task extension) is the more reliable save point.

---

## 3. Dual-Tier Buffer System

Maps from Android's `MobileLogRecordProcessor` (916 lines) and `DiskLogBuffer` (611 lines).

### Architecture

```
New Event
    │
    ▼
┌─────────────────────────────────┐
│  MobileLogRecordProcessor       │  ← Swift actor
│  (orchestrator)                 │
│                                 │
│  ┌───────────┐  overflow  ┌────────────┐
│  │ RAMBuffer  │──────────▶│ DiskBuffer  │
│  │ (actor)    │           │ (SQLite)    │
│  │ 5000 evts  │           │ 50MB / 24h  │
│  └───────────┘            └────────────┘
│        │                        │
│        ▼── flush ───────────────▼
│  ┌──────────────────────────────┐
│  │ RetryableExporter            │
│  │ → EnrichingLogRecordExporter │
│  │ → OTel OTLP Exporter        │
│  └──────────────────────────────┘
└─────────────────────────────────┘
```

### Implementation Choices

- **Thread safety via Swift Actors** — `RAMEventBuffer` and `MobileLogRecordProcessor` are actors. Actors guarantee serial access at compile time, eliminating the class of race conditions that synchronized blocks are prone to.
- **`Deque` from swift-collections** — O(1) append and removeFirst, matching ring buffer semantics.
- **Monotonic sequence IDs** — `UInt64` counter for deduplication during crash-safe flush (same approach as Android's seqId fix).
- **Raw sqlite3 for disk buffer** — Zero external dependencies, ships with iOS, schema maps 1:1 from Android Room. Core Data is overkill for a single-table ring buffer.

### Disk Buffer Schema

```sql
CREATE TABLE buffered_events (
    sequence_id INTEGER PRIMARY KEY,
    timestamp_ms INTEGER NOT NULL,
    session_id TEXT NOT NULL,
    event_data BLOB NOT NULL,        -- serialized OTLP LogRecord (protobuf)
    size_bytes INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX idx_timestamp ON buffered_events(timestamp_ms);
CREATE INDEX idx_session ON buffered_events(session_id);
```

### SQLite Configuration

- **WAL mode**: `PRAGMA journal_mode=WAL` for concurrent read/write performance. Critical when buffer is being written to and flushed simultaneously.
- **Synchronous mode**: `PRAGMA synchronous=NORMAL` — balances durability with performance.

### Crash Safety

- Events written to disk synchronously on `applicationWillTerminate` and `applicationDidEnterBackground`
- **iOS background task extension**: Uses `UIApplication.beginBackgroundTask(expirationHandler:)` to request additional time (up to ~30 seconds) for disk flush when entering background. iOS gives only ~5 seconds by default — insufficient for large buffer flushes. The expiration handler performs a best-effort partial flush.
- On next launch, `RecoveryTracker` checks for un-exported disk events and flushes them
- Buffer config is dynamic — the `adjust_buffer` DSL action can change `ram_events`, `disk_mb`, and `retention_hours` at runtime

---

## 4. Policy DSL v2 Evaluator

Maps from Android's `PolicyEvaluator.kt` (648 lines). Parses the same DSL v2 JSON from the Gateway, runs the same finite state machine logic on-device.

### DSL v2 Parsing

The entire DSL v2 schema maps to Swift `Codable` structs. Key types:

- `DSLConfigV2` — top-level config (version, buffer_config, targeting, workflows)
- `Workflow` — id, name, enabled, priority, initialState, states array
- `WorkflowState` — id, matchers, onMatch, onTimeout
- `Matcher` — Swift enum with associated values (replaces Kotlin sealed class). **31 cases** + compound matcher. Custom `Codable` conformance reads `"type"` discriminator from JSON. Includes:
  - 21 core matchers: `event_match`, `log_severity`, `metric_threshold`, `http_match`, `crash`, `exception_pattern`, `ui_freeze`, `slow_operation`, `frame_drop`, `network_loss`, `low_memory`, `battery_drain`, `thermal_throttle`, `storage_low`, `field_presence`, `field_absence`, `timeout`, `predictive_risk`, `anr`, `app_lifecycle`, `resource_snapshot`
  - 7 fleet intelligence matchers: `fleet_threshold`, `fleet_rate`, `fleet_absence`, `fleet_correlation`, `fleet_anomaly`, `fleet_prediction`, `fleet_root_cause`
  - 3 backend matchers: `backend_health`, `backend_deploy`, `backend_capacity`
- `PolicyAction` — Swift enum. **15 cases**:
  - 10 core actions: `flushBuffer`, `recordSession`, `emitMetric`, `createFunnel`, `createSankey`, `takeScreenshot`, `annotate`, `setSampling`, `adjustBuffer`, `sendAlert`
  - 5 fleet actions: `fleetFlush`, `fleetSetSampling`, `fleetAdjustConfig`, `fleetScreenshot`, `fleetClientCircuitBreak`

### Fleet & Backend Matchers — Scope Note

The 7 fleet matchers (`fleet_threshold`, `fleet_rate`, etc.) and 3 backend matchers (`backend_health`, `backend_deploy`, `backend_capacity`) are **server-side evaluated** — they operate on aggregate data across the device fleet, not on individual device events. The on-device `PolicyEvaluator` does NOT evaluate these matchers locally. Instead, the Gateway/Collector evaluates them and pushes resulting actions (e.g., `fleet_flush`) to devices via config updates. The iOS SDK must:
- **Parse** all 31 matcher types in DSL v2 JSON (so it doesn't fail on unknown types)
- **Evaluate locally** only the 21 core matchers
- **Execute** all 15 action types (including fleet actions received from server-side triggers)

### State Machine Execution

`PolicyEvaluator` is an actor. On every new event from instrumentation:
1. Iterate enabled workflows by priority
2. For each, look up current FSM state (default: `initialState`)
3. Evaluate all matchers in the current state against the event + `ContextSnapshot`
4. If matched: execute actions, transition to next state, reset timers
5. Return collected actions to `MobileLogRecordProcessor` for execution

### Config Polling

`ConfigPoller` actor polls `GET /config?app_id=X&device_id=Y&dsl_version=2` every 5 minutes (configurable). Only updates `PolicyEvaluator` when config version increments.

### Platform-Specific Matcher Mapping

| DSL Matcher | Android | iOS |
|-------------|---------|-----|
| `crash` | `UncaughtExceptionHandler` | `NSSetUncaughtExceptionHandler` + Mach exception handler |
| `anr` | Main thread watchdog (250ms tick) | Same — background thread pings main via `DispatchQueue.main.async` |
| `ui_freeze` | `Choreographer.doFrame` delay | `CADisplayLink` callback gap detection |
| `frame_drop` | `Choreographer` frame count | `CADisplayLink.timestamp` delta tracking |
| `thermal_throttle` | `PowerManager.getThermalStatus()` | `ProcessInfo.thermalState` + notification observer |
| `low_memory` | `ActivityManager.getMemoryInfo()` | `os_proc_available_memory()` (iOS 13+) |
| `battery_drain` | `BatteryManager` | `UIDevice.batteryLevel` + `UIDevice.batteryState` |
| `storage_low` | `StatFs` | `FileManager.attributesOfFileSystem(forPath:)` — check `FileAttributeKey.systemFreeSize` |

---

## 5. Instrumentation Module System

> **STALE (2026-04-09):** The `MobileInstrumentation` interface is being
> aligned with upstream `opentelemetry-android`'s `AndroidInstrumentation`
> as part of the Upstream Supersession epic. The iOS `MobileInstrumentation`
> protocol will be updated when Phase 4 (Interface Convergence) completes.
> See: `docs/superpowers/specs/2026-04-09-upstream-supersession-design.md`

### Core Protocol

```swift
public protocol MobileInstrumentation {
    var id: String { get }
    var isAutoCapture: Bool { get }
    func install(context: InstrumentationContext)
    func uninstall()
}

public struct InstrumentationContext {
    public let tracer: Tracer              // OTel tracer for creating spans
    public let logger: Logger              // OTel logger — LogRecords flow to MobileLogRecordProcessor
    public let meter: Meter                // OTel meter for creating metrics
    public let sessionProvider: SessionProvider
    public let eventHub: TouchEventHub
    public let config: MobileConfig
    public let privacyConfig: PrivacyConfig
}
// Note: Instrumentation modules emit telemetry via standard OTel APIs (Tracer, Logger, Meter).
// LogRecords created via Logger automatically flow through the OTel LogRecordProcessor pipeline
// into MobileLogRecordProcessor, which handles buffering, policy evaluation, and export.
// This decouples instrumentation modules from buffer internals.
```

### RateLimiter

Port of Android's `RateLimiter` from `otel-android-mobile-core/`. Shared, thread-safe rolling-window rate limiter used by screenshot, wireframe, and errors modules to prevent excessive telemetry. Configurable `maxPerWindow` and `windowMs`. On iOS, implemented as a Sendable struct using `os_unfair_lock` (or actor if simpler) — the same CopyOnWriteArrayList-of-timestamps approach as Android.

### SwizzleManager

Centralized swizzle registry. All swizzling goes through one place:
- Prevents double-swizzling conflicts
- Enables clean opt-out per module
- `isSwizzled()` check detects conflicts with other SDKs (Datadog, Firebase, etc.)
- `unregister(moduleId:)` restores original implementations

### Module Tiers

**Tier 1 — Sprint 1 (direct port, well-understood iOS APIs):**

| Module | iOS Mechanism |
|--------|--------------|
| `LifecycleInstrumentation` | `UIApplication` notifications / `UISceneDelegate` |
| `ScreenInstrumentation` | Swizzle `UIViewController.viewDidAppear/viewDidDisappear` |
| `NetworkInstrumentation` | `URLProtocol` subclass registered with `URLSession`. **Note:** `URLProtocol` auto-intercepts `URLSession.shared` and sessions using `URLSessionConfiguration.default`. For sessions with `.ephemeral` or custom configs, customers must explicitly add the protocol class to their config's `protocolClasses`. The SDK provides `OTelURLProtocol.self` for manual registration. |
| `ErrorsInstrumentation` | `NSSetUncaughtExceptionHandler` for Swift/ObjC exceptions + POSIX signal handlers (SIGSEGV, SIGABRT, SIGBUS, SIGFPE) for C-level crashes. Chains to previous handler. Dedup via exception fingerprint. **Caution:** Signal handler + exception handler interaction is complex on iOS — signal handlers must be async-signal-safe (no heap allocation, no ObjC). Consider evaluating PLCrashReporter integration as an alternative to raw signal handling. Prototype in Sprint 1, harden in Sprint 3. |
| `VitalsInstrumentation` | App start time (process start → first viewDidAppear), jank via `CADisplayLink`, memory/battery gauges, MetricKit on iOS 16+ |
| `FreezeInstrumentation` | `MainThreadWatchdog` — background thread pings main queue every 250ms |

**Tier 2 — Sprint 2 (moderate complexity, iOS-specific patterns):**

| Module | iOS Mechanism |
|--------|--------------|
| `TapInstrumentation` | Subscribe to `UIWindowEventForwarder` for `.touches` events |
| `ScrollInstrumentation` | Swizzle `UIScrollViewDelegate` methods |
| `TextInputInstrumentation` | `UITextField`/`UITextView` notification observers |
| `BackPressInstrumentation` | `UINavigationController` pop detection (no hardware back on iOS) |
| `ScreenshotInstrumentation` | `UIGraphicsImageRenderer` / `drawHierarchy(in:afterScreenUpdates:)` |
| `WireframeInstrumentation` | `UIView` hierarchy traversal → JSON |

**Tier 3 — Sprint 3 (harder / less direct mapping):**

| Module | iOS Mechanism |
|--------|--------------|
| `DatabaseInstrumentation` | Core Data / SQLite `sqlite3_trace` hook |
| `FileIOInstrumentation` | `fishhook` (Facebook, BSD license) for rebinding POSIX file I/O symbols in Mach-O binaries, or `FileManager` observation as a lighter alternative. `fishhook` added as an optional SPM dependency for this module only. |
| `SystemEventsInstrumentation` | `NotificationCenter` for battery, memory warnings, thermal state changes |
| `OSLogInstrumentation` | `OSLog` / `os_log` integration (replaces Android's Timber) |

### SPM Package Structure

```swift
let package = Package(
    name: "OTelMobile",
    platforms: [.iOS(.v15)],  // Min is 15 for compat; optimized for 16+ (see Section 1)
    products: [
        .library(name: "OTelMobileSDK", targets: ["OTelMobileSDK"]),
        .library(name: "OTelMobileCore", targets: ["OTelMobileCore"]),
        .library(name: "TapInstrumentation", targets: ["TapInstrumentation"]),
        .library(name: "NetworkInstrumentation", targets: ["NetworkInstrumentation"]),
        // ... all 16 modules as separate products
    ],
    dependencies: [
        .package(url: "https://github.com/open-telemetry/opentelemetry-swift.git", from: "2.1.1"),
        .package(url: "https://github.com/apple/swift-collections.git", from: "1.1.0"),
        // Sprint 3: fishhook for FileIOInstrumentation (optional, only if POSIX interception chosen)
        // .package(url: "https://github.com/nicklama/fishhook-swift.git", from: "1.0.0"),
    ],
    targets: [
        .target(name: "OTelMobileCore", dependencies: [
            .product(name: "OpenTelemetryApi", package: "opentelemetry-swift"),
        ]),
        .target(name: "OTelMobileSDK", dependencies: [
            "OTelMobileCore",
            .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift"),
            .product(name: "OpenTelemetryProtocolExporter", package: "opentelemetry-swift"),  // gRPC OTLP exporter
            .product(name: "DequeModule", package: "swift-collections"),
        ]),
        .target(name: "TapInstrumentation", dependencies: ["OTelMobileCore"]),
        // ... each module depends only on Core
    ]
)
```

Customers include only what they need: `import OTelMobileSDK` + specific instrumentation modules.

### OpenTelemetry Swift SDK Notes

Key facts verified against the actual opentelemetry-swift repo (v2.3.0 as of 2026-04):

- **Version**: v2.x (minimum 2.1.1). The v2.x release moved core APIs to a separate `opentelemetry-swift-core` repo, but the main package re-exports them. The Logs API is **stable** in v2.x (not experimental as it was in v1.x).
- **SPM product names** (verified against `Package.swift`):
  - `OpenTelemetryApi` — Tracer, Meter, Logger interfaces
  - `OpenTelemetrySdk` — SDK implementations
  - `OpenTelemetryProtocolExporter` — gRPC OTLP exporter (production-ready)
  - `OpenTelemetryProtocolExporterHTTP` — HTTP OTLP exporter (experimental)
- **gRPC binary size impact**: The gRPC exporter pulls in `grpc-swift` (pinned at 1.26.1), which adds ~15-20MB uncompressed to binary size. If binary size is a concern, the HTTP exporter is lighter but still experimental. For Sprint 1 we use gRPC (matching Android); evaluate HTTP as an option in Sprint 3.
- **Minimum iOS**: opentelemetry-swift requires iOS 13+, which is within our iOS 15+ floor.
- **Swift concurrency**: Full async/await support via `TaskLocalContextManager`.

---

## 6. Platform Integration Layer & Privacy

### Platform Layer (`Platform/` directory)

All iOS-specific code isolated here:

**UIWindowEventForwarder:**
- Swizzles `UIWindow.sendEvent(_:)` once
- Fans out to registered listeners (tap, scroll, text-input modules subscribe)
- Single swizzle, multiple consumers — adding new UI modules never adds more swizzles

**ViewControllerTracker:**
- Swizzles `viewDidAppear(_:)` and `viewDidDisappear(_:)` on `UIViewController`
- Filters system VCs (UINavigationController, UITabBarController, UIInputWindowController)
- `ScreenInstrumentation` subscribes, but other modules (wireframe, screenshot) can too

**MainThreadWatchdog:**
- Background queue pings main queue via `DispatchSemaphore`
- 250ms tick interval (matching Android)
- Reports freeze when main queue doesn't respond within threshold
- Uses `DispatchWorkItem` with deadline, not `Timer` (more reliable under load)

### Secure Storage

Android uses `EncryptedSharedPreferences` (AndroidX Security Crypto) for auth tokens and device tokens. iOS equivalent: **Keychain Services** (`SecItemAdd`/`SecItemCopyMatching`). The SDK wraps this in a `SecureStorage` helper. Non-sensitive config (service name, endpoint URL, feature flags) uses `UserDefaults`.

### Privacy — Direct Port

Platform-agnostic, nearly identical to Android:

- **PiiScrubber** — Same regex patterns (email, phone, SSN). `scrub()` replaces matches with `[REDACTED]`.
- **CoordinateBucketer** — Quantize tap coordinates to 50px grid for privacy.
- **PrivacyConfig presets:**
  - `.default` — PII scrubbing on, location off, coordinate bucketing on
  - `.minimal` — Everything off (internal/debug builds)
  - `.production` — PII scrubbing on, location off, text redaction on screenshots
  - `.debug` — Verbose logging, no scrubbing

### ContextSnapshot — iOS Device Context

**System framework dependencies:** `Network.framework` (for `NWPathMonitor`), `UIKit.framework`, `SystemConfiguration.framework`. All ship with iOS — no third-party dependencies.

| Context Field | iOS Source |
|---------------|-----------|
| Device model | `UIDevice.model` + `sysctlbyname("hw.machine")` |
| OS version | `UIDevice.systemVersion` |
| App version | `Bundle.main.infoDictionary` |
| Network type | `NWPathMonitor` |
| Battery level | `UIDevice.batteryLevel` |
| Battery state | `UIDevice.batteryState` |
| Thermal state | `ProcessInfo.thermalState` |
| Available memory | `os_proc_available_memory()` |
| Disk free | `FileManager.attributesOfFileSystem` |
| Locale/timezone | `Locale.current` / `TimeZone.current` |
| Screen size | `UIWindowScene`-based screen bounds (iOS 16+), fallback to `UIScreen.main.bounds` on iOS 15 |

---

## 7. Demo Apps & Debug Diagnostics Panel

### Demo App Structure

```
examples/
├── demo-app-ios/                    # Full showcase
│   ├── DemoApp/
│   │   ├── App.swift                # @main, SDK init
│   │   ├── Screens/
│   │   │   ├── HomeView.swift       # Tab hub
│   │   │   ├── BookingFlow/         # Appointment journey (matches Android)
│   │   │   ├── ShopView.swift       # Network-heavy
│   │   │   ├── ProfileView.swift    # Text input showcase
│   │   │   └── SettingsView.swift   # SDK config toggles
│   │   ├── Debug/
│   │   │   ├── DebugPanel.swift         # Floating overlay + collapsed pill
│   │   │   ├── RingBufferView.swift     # Tab 1: buffer visualization
│   │   │   ├── PolicyStateView.swift    # Tab 2: FSM state diagram
│   │   │   ├── ExportHistoryView.swift  # Tab 3: export timeline
│   │   │   ├── InstrumentationView.swift # Tab 4: module status
│   │   │   └── SDKMonitor.swift         # ObservableObject bridge
│   │   └── Config/
│   │       └── ConfigManager.swift  # otel-config.json loader
│
├── demo-app-ios-starter/            # Minimal "5-minute" starter
│   ├── StarterApp/
│   │   ├── App.swift                # SDK init, 3 lines
│   │   └── ContentView.swift        # Single screen, trigger buttons
│   └── README.md
```

### Debug Diagnostics Panel — Improved Over Android

Floating overlay with 4 tabs:

**Collapsed pill:** Shows event rate, buffer fill %, last export status. Tap to expand. Draggable to reposition.

**Tab 1 — Ring Buffer Visualization:**
- Live Swift Charts (iOS 16+) showing RAM buffer fill over time, falls back to Canvas on iOS 15
- Disk buffer usage bar (MB used / capacity)
- Event timeline: scrollable list with timestamp, type icon, module source, size
- Flush markers on chart timeline

**Tab 2 — Policy State (NEW — not on Android, backport planned):**
- Each workflow rendered as FSM diagram
- Current state highlighted
- Recent state transitions with timestamps
- Matcher evaluation results: which matchers are passing/failing in real time
- This provides visibility into policy evaluation that Android currently lacks

**Tab 3 — Export History:**
- Timeline of export attempts with event count, byte size, duration, status
- Failed exports show error reason + retry count
- Running totals: events exported, bytes sent, success rate

**Tab 4 — Instrumentation Status:**
- Each installed module with enabled/disabled toggle (live)
- Swizzle status (active/inactive)
- Event count since install, last event timestamp
- Useful for debugging "why am I not seeing tap events?"

### SDKMonitor

`@MainActor` `ObservableObject` that bridges actor-based SDK internals to SwiftUI observation. Polls SDK actors on 500ms timer, publishes to SwiftUI views.

### Backport Note

The Policy State debug panel concept (Tab 2) should be backported to the Android `RingBufferActivity` after the iOS implementation validates the approach.

---

## 8. Control Plane Changes & Gateway Updates

### Gateway Changes (Small)

**1. Device registration — add platform field:**

The current `devices` table schema has: `device_id`, `device_token`, `device_group`, `os_version`, `app_version`, `registered_at`, `last_seen`, `last_config_fetch`, `current_config_version`, `config_applied_successfully`. No platform column exists today.

```go
type DeviceRegistration struct {
    DeviceID    string `json:"device_id"`
    Platform    string `json:"platform"`     // NEW: "android" | "ios"
    OSVersion   string `json:"os_version"`
    AppVersion  string `json:"app_version"`
    DeviceGroup string `json:"device_group"`
}
```

```sql
ALTER TABLE devices ADD COLUMN platform TEXT NOT NULL DEFAULT 'android';
```

**Note:** Platform targeting currently exists at the config level (`DSLTargeting.platform`) but not per-device. Adding a platform column to the devices table enables the Gateway to filter config delivery by device platform, rather than relying solely on DSL targeting rules. Both mechanisms should work together: the device's registered platform is matched against workflow targeting rules.

**2. Config delivery — filter workflows by platform targeting:**
- If workflow has `targeting.platform` set, only deliver to matching devices
- If no `targeting.platform`, deliver to all (backward compatible)

**3. Heartbeat — include platform in status body:**

```go
type StatusRequest struct {
    DeviceID      string   `json:"device_id"`
    AppID         string   `json:"app_id"`
    Platform      string   `json:"platform"`      // NEW: "android" | "ios"
    SessionID     string   `json:"session_id"`
    BufferUsageMB float64  `json:"buffer_usage_mb"`
    LastTriggers  []string `json:"last_triggers"`
    ConfigVersion int      `json:"config_version"`
}
```

- Existing Android devices without platform field default to `"android"` — no breaking changes
- Gateway uses platform from heartbeat for active device count metrics (iOS vs Android dashboard)

### Control Plane UI Changes (Moderate)

1. **Platform toggle on workflows** — All / Android / iOS selector per workflow
2. **Platform-aware matcher palette** — Show all matchers but indicate platform availability. `back_press` shows warning if targeted at iOS.
3. **Device list — platform icon** — Show Android/iOS icons, allow filtering

### No Changes Required

| Component | Change? |
|-----------|---------|
| DSL v2 schema | No — already supports iOS targeting |
| `graphToDSLv2.ts` compiler | No |
| Config versioning (v1/v2) | No |
| Rollback support | No |
| OTLP export format | No |
| Collector processor (Go) | No |
| Demo backend (Node.js) | No |

---

## 9. Testing Strategy

### Test Pyramid

- **Unit tests (Sprint 1, from day 1):** Policy evaluator, PII scrubber, buffer logic, serialization, coordinate bucketer, config parsing
- **Integration tests (Sprint 2):** Buffer→export round-trips with mock OTLP receiver, config polling with mock HTTP server, crash recovery simulation
- **E2E tests (Sprint 3):** Real device/simulator, full demo app flows via XCUITest

### Unit Test Coverage

Every core component has dedicated test files:
- `PolicyEvaluatorTests` — local evaluation tests for all 21 core matchers, parsing tests for all 31 types (ensuring fleet/backend matchers are parsed without error but skipped during local evaluation), compound matchers, FSM transitions, timeout handling
- `RAMEventBufferTests` — append, overflow eviction, flush with time window, sequence ID monotonicity
- `DiskEventBufferTests` — persist/retrieve, TTL expiration, size limit enforcement
- `PiiScrubberTests` — email, phone, SSN patterns, edge cases
- `DSLv2ModelsTests` — JSON parsing for all matcher/action types, malformed input handling
- Per-instrumentation module tests

### E2E Scenario Suites (Ported from Android)

| Suite | What It Tests |
|-------|---------------|
| `UserJourneyScenarios` | Multi-screen booking flow, breadcrumbs, screen spans |
| `SimulatorStressScenarios` | Thermal throttle, memory pressure, battery drain |
| `FaultScenarios` | Main thread freeze, crash capture, jank detection |
| `ConditionalFlushScenarios` | Policy triggers buffer flush on crash/error |
| `OfflineResilienceScenarios` | Network loss → buffer to disk → reconnect → flush |

### Test Infrastructure

```
otel-ios-mobile/
├── Tests/
│   ├── OTelMobileSDKTests/          # Pure-logic unit tests (swift test compatible, no UIKit)
│   │   ├── Buffering/               # RAM/disk buffer, sequence IDs
│   │   ├── Policy/                  # DSL parsing, matcher evaluation, FSM
│   │   ├── Export/                  # Enricher, retry logic
│   │   ├── Privacy/                 # PII scrubber, coordinate bucketer
│   │   └── Session/                 # Session manager, config
│   ├── OTelMobilePlatformTests/     # UIKit-dependent tests (requires simulator)
│   │   ├── SwizzleManagerTests/
│   │   ├── ViewControllerTrackerTests/
│   │   └── MainThreadWatchdogTests/
│   ├── InstrumentationTests/        # Per-module tests (requires simulator)
│   └── IntegrationTests/            # Buffer→export round-trips (requires simulator)

examples/demo-app-ios/
├── DemoAppUITests/                  # XCUITest E2E scenarios
```

### CI Integration

```bash
swift test                              # SPM unit tests — no simulator needed, runs in seconds
xcodebuild test -scheme DemoApp \
  -destination 'platform=iOS Simulator,name=iPhone 16'  # Integration + UI tests

./run-tests.sh              # Android only (backward compat)
./run-tests.sh --ios        # iOS only  
./run-tests.sh --all        # Both platforms
```

**CI advantage:** SPM unit tests (`swift test`) run on any macOS runner without Xcode simulator overhead. **However**, `swift test` can only run tests that don't import UIKit — this means buffer logic, policy evaluation, DSL parsing, PII scrubbing, and coordinate bucketing tests all run via `swift test`. Tests for instrumentation modules, platform layer, or anything that touches UIKit/SwiftUI require `xcodebuild test` with a simulator. Structure test targets accordingly: `OTelMobileSDKTests` (pure logic, `swift test`-compatible) vs `OTelMobilePlatformTests` (UIKit-dependent, simulator-required).

---

## 10. Sprint Structure & Epics

### Sprint 1: Foundation + Tier 1 Instrumentation

**Goal:** SDK initializes, buffers events, evaluates policies, exports to Dash0, 6 instrumentation modules working.

| Epic | Deliverables |
|------|-------------|
| SDK Core | `OTelMobile` public API, builder (incl. `buildAndStart()` for SwiftUI), `MobileSDK` actor, `MobileConfig`, `SessionManager`, `shutdown()`/`flush()` lifecycle APIs |
| Buffering | `RAMEventBuffer` actor, `DiskEventBuffer` (sqlite3 + WAL), `MobileLogRecordProcessor`, `RetryableExporter`, `RecoveryTracker`, background task extension, crash recovery |
| Policy Engine | `DSLv2Models` (Codable), `PolicyEvaluator` actor, `ConfigPoller`, FSM execution |
| Export | `EnrichingLogRecordExporter`, OTel Swift SDK integration, OTLP/gRPC |
| Platform Layer | `SwizzleManager`, `UIWindowEventForwarder`, `ViewControllerTracker`, `MainThreadWatchdog` |
| Tier 1 Modules | Lifecycle, Screen, Network, Errors, Vitals, Freeze |
| Privacy & Security | `PiiScrubber`, `CoordinateBucketer`, `PrivacyConfig` presets, `SecureStorage` (Keychain wrapper), `RateLimiter` |
| Context | `ContextSnapshot` (device, OS, network via `NWPathMonitor`, battery, thermal, locale) |
| Testing | Unit tests for all core components, CI script |
| Demo Starter | `demo-app-ios-starter/` — minimal app |
| Gateway | Add `platform` column, platform filter on config delivery |

### Sprint 2: Tier 2 Instrumentation + Full Demo App

**Goal:** All UI auto-capture modules, full demo app with debug panel, session/journey tracking.

| Epic | Deliverables |
|------|-------------|
| Tier 2 Modules | Tap, Scroll, TextInput, BackPress (nav pop), Screenshot, Wireframe |
| Session/Journey | `BreadcrumbManager`, `JourneyBreadcrumb`, journey span hierarchy |
| Predictive Export | `OnDevicePredictor`, `PredictiveExportPolicy`, `DeviceHealthMonitor` |
| Dynamic Sampling | `DynamicSampler`, `SamplingConfig` |
| Debug Panel | Floating overlay, RingBufferView (Charts), PolicyStateView (FSM viz), ExportHistoryView, InstrumentationView |
| Full Demo App | Booking flow, shop, profile, settings, debug panel |
| Integration Tests | Buffer→export, config polling, mock OTLP receiver |
| Control Plane UI | Platform toggle, platform-aware matchers, device list icons |

### Sprint 3: Tier 3 + E2E + Parity Hardening

**Goal:** Full feature parity with Android, all E2E scenarios passing, production-ready.

| Epic | Deliverables |
|------|-------------|
| Tier 3 Modules | Database, FileIO, SystemEvents, OSLog |
| Log Tailing | `LogTailingConfig`, `LogTailBuffer` |
| Fleet Alerts | `FleetAlertHandler`, `FleetAlertDeduplicator` |
| E2E Scenarios | All 5 suites ported (XCUITest) |
| Stress Testing | Thermal, memory pressure, battery, network degradation |
| iPad Support | Multitasking (split view, slide over), multi-window scenes, different screen sizes, pointer/trackpad events in `TapInstrumentation`, external keyboard in `TextInputInstrumentation`, Stage Manager on iPadOS 16+ |
| iOS 15 Compat | `#available` audit, fallback paths, test on iOS 15 simulator |
| Documentation | Integration guide, API reference, migration guide |
| Backport | Policy State debug panel → Android `RingBufferActivity` |

### Cross-Sprint: Continuous

| Activity | Cadence |
|----------|---------|
| Unit tests | Every PR |
| Integration tests | Every PR |
| iOS simulator CI | Nightly |
| Android regression | Nightly |

---

## Appendix: Android → iOS API Mapping Reference

| Android API | iOS Equivalent |
|-------------|---------------|
| `ActivityLifecycleCallbacks` | `UIApplication` notifications / `UISceneDelegate` |
| `Window.Callback` wrapper | `UIWindow.sendEvent` swizzle |
| `Choreographer.doFrame` | `CADisplayLink` |
| `UncaughtExceptionHandler` | `NSSetUncaughtExceptionHandler` + signal handlers |
| `ConnectivityManager` | `NWPathMonitor` |
| `PowerManager.getThermalStatus()` | `ProcessInfo.thermalState` |
| `ActivityManager.getMemoryInfo()` | `os_proc_available_memory()` |
| `BatteryManager` | `UIDevice.batteryLevel/batteryState` |
| `Room` (SQLite ORM) | Raw `sqlite3` C API |
| `ConcurrentLinkedQueue` + `synchronized` | Swift `actor` |
| `kotlinx.serialization` | Swift `Codable` |
| `Kotlin coroutines` | Swift structured concurrency (`async/await`) |
| `SharedPreferences` | `UserDefaults` (or `Keychain` for secrets) |
| `PixelCopy` | `UIGraphicsImageRenderer` / `drawHierarchy` |
| `View` hierarchy traversal | `UIView` subview recursion |
| Timber logging | `OSLog` / `os_log` |
| OkHttp interceptor | `URLProtocol` subclass |
| OTel Java SDK 1.58.0 | opentelemetry-swift 2.1.1+ |
| OTLP gRPC Exporter (Java) | `OpenTelemetryProtocolExporter` (grpc-swift) |
| Espresso (UI tests) | XCUITest |
| Robolectric (unit tests) | XCTest (no simulator needed for unit tests) |
