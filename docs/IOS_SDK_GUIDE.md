# iOS SDK Integration Guide

Complete guide for integrating the `otel-ios-mobile` SDK into your iOS (or macOS) app.

## Table of Contents

1. [Overview](#overview)
2. [Installation](#installation)
3. [Minimal Setup](#minimal-setup)
4. [MobileConfig Reference](#mobileconfig-reference)
5. [AutoCaptureOptions](#autocaptureoptions)
6. [Public API](#public-api)
7. [Instrumentation Modules](#instrumentation-modules)
8. [Resource Attributes](#resource-attributes)
9. [OpenTelemetry Semantic Conventions](#opentelemetry-semantic-conventions)
10. [Toolchain Notes](#toolchain-notes)
11. [Troubleshooting](#troubleshooting)

## Overview

The iOS SDK is a thin wrapper over the upstream [opentelemetry-swift](https://github.com/open-telemetry/opentelemetry-swift) and [opentelemetry-swift-core](https://github.com/open-telemetry/opentelemetry-swift-core) packages. It assembles the standard OTel Swift providers (Logger, Tracer, Meter) against a single `Resource`, wires an OTLP/HTTP exporter per signal, and layers Dash0-specific pieces on top: a RAM event buffer with selective-flush semantics, an on-device DSL v2 policy model, and auto-instrumentation modules for network, lifecycle, and errors.

```
Your App
   │
   ├─ OTelMobile.start(config:) ──► NetworkInstrumentation (URLSession swizzle)
   │                            ├──► LifecycleInstrumentation (UIApplication notifications)
   │                            ├──► ErrorsInstrumentation (NSException + POSIX signals)
   │                            └──► DeviceStatsCollector (opt-in via deviceStats.start)
   │
   │  Log records ─► MobileLogRecordProcessor ─► RAMEventBuffer (5000 events)
   │                                          └─► BatchLogRecordProcessor ─► OTLP/HTTP
   │  Spans       ─► BatchSpanProcessor  ─► OTLP/HTTP
   │  Metrics     ─► PeriodicMetricReader ─► OTLP/HTTP (10 s cadence)
```

**Requirements:** iOS 15+ or macOS 13+, Swift 5.9+, Xcode 15+ (Xcode 26 recommended for the demo apps — the starter and Astronomy Shop projects assume an iOS 17/18 simulator runtime).

Source entry point: [OTelMobile.swift](../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift).

## Installation

The SDK ships as a Swift Package with seven products. Add it to your app's `Package.swift`:

```swift
dependencies: [
    // Placeholder URL — replace once the package is published. For local
    // development use `.package(path: "../mobile-otel/otel-ios-mobile")`.
    .package(url: "https://github.com/dash0hq/otel-ios-mobile.git", from: "0.1.0-alpha"),
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "OTelMobileSDK", package: "otel-ios-mobile"),
        ]
    ),
]
```

Products and when to import them (see [Package.swift](../otel-ios-mobile/Package.swift)):

| Product | Import when you want... |
| --- | --- |
| `OTelMobileSDK` | The main SDK entry point (`OTelMobile.start`). Pulls in everything below transitively. |
| `OTelMobileCore` | Just the protocol/type layer (no UIKit, no providers). Useful for sharing code between SDK and app. |
| `NetworkInstrumentation` | To configure or manually control the URLSession swizzle. |
| `LifecycleInstrumentation` | To customize the lifecycle installer or inspect its state. |
| `ErrorsInstrumentation` | To call `recordError(_:)` for caught errors manually. |
| `ScreenInstrumentation` | Reserved. Currently staged and not auto-installed — see the note in [OTelMobile.swift](../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift). |
| `VitalsInstrumentation`, `FreezeInstrumentation` | Placeholders only — see [VitalsInstrumentation.swift](../otel-ios-mobile/Sources/VitalsInstrumentation/VitalsInstrumentation.swift) and [FreezeInstrumentation.swift](../otel-ios-mobile/Sources/FreezeInstrumentation/FreezeInstrumentation.swift). |

## Minimal Setup

Five lines of Swift in your `@main` struct is enough to boot the SDK, wire all three signals, and auto-install the currently-shipped instrumentation:

```swift
import OTelMobileSDK

let config = MobileConfig(
    serviceName: "my-ios-app",
    endpoint: "https://ingress.YOUR-DOMAIN.dash0.com:4318",
    authToken: "Bearer YOUR_DASH0_TOKEN",
    extraHeaders: ["Dash0-Dataset": "otel-mobile"]
)
let mobile = try OTelMobile.start(config: config)
```

What `start(config:)` does out of the box, based on [OTelMobile.swift](../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift):

- Builds a shared `Resource` via [ResourceBuilder.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Resource/ResourceBuilder.swift).
- Assembles OTLP/HTTP exporters for logs, traces, and metrics against `config.endpoint`. Each exporter normalizes its own `/v1/logs`, `/v1/traces`, `/v1/metrics` suffix.
- Wires a `LoggerProvider` with two processors: a `MobileLogRecordProcessor` (buffer for selective flush) and a `BatchLogRecordProcessor` (2 s schedule delay, 2048 queue, 512 batch) that streams to OTLP.
- Wires a `TracerProvider` with a single `BatchSpanProcessor` to OTLP.
- Wires a `MeterProvider` with a `PeriodicMetricReader` at 10 s cadence.
- Posts an async block to the main queue that installs `NetworkInstrumentation`, `LifecycleInstrumentation`, and `ErrorsInstrumentation` based on `config.autoCaptureOptions`. The deferral is intentional — see [Troubleshooting](#troubleshooting) for why synchronous install breaks SwiftUI.

Two entry points exist:

| Entry Point | Use When |
| --- | --- |
| `OTelMobile.start(config:)` | Production path. Wires logs + traces + metrics + auto-instrumentation via OTLP/HTTP. |
| `OTelMobile.start(config:exporter:)` | Tests, demos, or custom transports. Wires only the log pipeline against a caller-supplied `BufferedEventExporter`. Returns an instance whose `tracer` and `meter` are `nil`. |

## MobileConfig Reference

From [MobileConfig.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/MobileConfig.swift):

| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `serviceName` | `String` | — | Required. Emitted as OTel `service.name` on every signal. |
| `serviceVersion` | `String` | `"1.0.0"` | Emitted as `service.version`. |
| `endpoint` | `String` | — | Required. Root OTLP/HTTP URL (e.g. `https://ingress.YOUR-DOMAIN.dash0.com:4318`). Each exporter appends its own `/v1/...` suffix. |
| `authToken` | `String?` | `nil` | Bearer token. Pass the full header value including `Bearer ` prefix. |
| `exportMode` | `ExportMode` | `.conditional` | One of `.conditional`, `.continuous`, `.hybrid`. Parsed by the DSL layer; the production export pipeline currently runs the batch processors regardless — see [Known behavior](#known-behavior). |
| `bufferConfig` | `BufferConfig` | `.default` | RAM-buffer sizing for the selective-flush path. |
| `privacyConfig` | `PrivacyConfig` | `.default` | PII, location, coordinate bucketing, screenshot redaction knobs. |
| `autoCaptureOptions` | `AutoCaptureOptions` | `.all` | Opt-out flags for auto-instrumentation. See below. |
| `pollingIntervalSeconds` | `Int` | `300` | Reserved for the config poller (not wired in the current production `start` path). |
| `extraHeaders` | `[String: String]` | `[:]` | Merged onto every OTLP/HTTP request — commonly `"Dash0-Dataset"` alongside the bearer token. |

Example with everything tuned:

```swift
MobileConfig(
    serviceName: "my-ios-app",
    serviceVersion: "2.3.1",
    endpoint: "https://ingress.YOUR-DOMAIN.dash0.com:4318",
    authToken: "Bearer \(token)",
    exportMode: .conditional,
    bufferConfig: BufferConfig(ramEvents: 2500, diskMb: 25, retentionHours: 12),
    privacyConfig: .production,
    autoCaptureOptions: [.network, .lifecycle, .errors],
    extraHeaders: ["Dash0-Dataset": "otel-mobile"]
)
```

### Known behavior

- `bufferConfig.diskMb` and `bufferConfig.retentionHours` parse and are carried on the struct, but the iOS RAM buffer is in-memory only today. A disk tier mirroring Android's SQLite buffer is planned.
- `pollingIntervalSeconds` is honored by the DSL/config poller type but not wired into the default `start(config:)` — treat it as reserved.
- `exportMode` is encoded into the shared DSL types and exercised by the on-device policy evaluator, but the production `start(config:)` path always installs the same batch exporters. Selective-flush semantics come from `flushWindow(minutes:)`, not from switching modes.

## AutoCaptureOptions

`AutoCaptureOptions` is a Swift `OptionSet`. From [AutoCaptureOptions.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift):

| Flag | Currently wired? | Notes |
| --- | --- | --- |
| `.tap` | Placeholder | No UIKit gesture swizzle ships today. |
| `.scroll` | Placeholder | Same — Android parity is pending. |
| `.lifecycle` | **Wired** | Installs `LifecycleInstrumentation`. |
| `.screen` | Staged, not auto-installed | `ScreenInstrumentation` exists but its `UIViewController` swizzle is commented out in `OTelMobile.start` pending a safer install path. Call `ScreenInstrumentation.shared.install(tracer:logger:)` manually if you want to opt into the swizzle today. |
| `.network` | **Wired** | Installs `NetworkInstrumentation`. |
| `.errors` | **Wired** | Installs `ErrorsInstrumentation`. |
| `.freeze` | Placeholder | Only [FreezeInstrumentation.swift](../otel-ios-mobile/Sources/FreezeInstrumentation/FreezeInstrumentation.swift) placeholder exists. |
| `.vitals` | Placeholder | Only [VitalsInstrumentation.swift](../otel-ios-mobile/Sources/VitalsInstrumentation/VitalsInstrumentation.swift) placeholder exists. `DeviceStatsCollector` is the opt-in metrics path today. |
| `.textInput` | Placeholder | |
| `.screenshot` | Placeholder | |
| `.wireframe` | Placeholder | |
| `.all` | — | Convenience shorthand for every flag above (ships as the default). |
| `.none` | — | Disable all auto-instrumentation — use this if you want to wire modules by hand. |

Setting a flag that is currently a placeholder has no runtime effect. The default `.all` is intentional: it mirrors Android's "drop-in observability" UX so future releases can light up additional modules without requiring callers to change their config.

## Public API

`OTelMobile` exposes the following — see [OTelMobile.swift](../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift):

| Member | Type | Purpose |
| --- | --- | --- |
| `config` | `MobileConfig` | Snapshot of what `start` was called with. |
| `resource` | `Resource` | Shared OTel `Resource` attached to every signal. Useful for tests and debug widgets. |
| `logger` | `Logger` | OTel logger for instrumentation scope `io.dash0.mobile`. |
| `tracer` | `Tracer?` | OTel tracer for the same scope. `nil` on the test-overload `start(config:exporter:)`. |
| `meter` | `MeterSdk?` | OTel meter, same scope. `nil` on the test overload. |
| `deviceStats` | `DeviceStatsCollector` | Device health gauge collector. Opt-in; call `deviceStats.start(meter:intervalSeconds:)` to begin sampling. |
| `sessionProvider` | `SessionProvider` | Static for now; future sessions will cycle via this handle. |

Methods:

```swift
// Emit a custom log event. Both overloads route through `logger.logRecordBuilder()`.
mobile.emit(body: "checkout.completed")
mobile.emit(
    body: "checkout.completed",
    severity: .info,
    attributes: ["cart.item_count": .int(3), "cart.total_cents": .int(4299)]
)

// Synchronously drain every signal: log buffer, trace batch processor,
// metric reader. Returns the log-buffer result; trace/metric errors are
// swallowed but the batch processors retry on their own cadence.
let result = mobile.forceFlush()

// Selective time-window flush — export everything emitted in the last N minutes.
let windowResult = await mobile.flushWindow(minutes: 5)

// Manual device-stats opt-in (requires `mobile.meter` to be non-nil).
if let meter = mobile.meter {
    mobile.deviceStats.start(meter: meter, intervalSeconds: 5)
}
```

`DeviceStatsCollector.start` is `@MainActor` — call it from a `Task { @MainActor in ... }` or the main-queue async block where you built the SDK. See [DeviceStatsCollector.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Metrics/DeviceStatsCollector.swift).

## Instrumentation Modules

### NetworkInstrumentation

Source: [NetworkInstrumentation.swift](../otel-ios-mobile/Sources/NetworkInstrumentation/NetworkInstrumentation.swift), [NetworkConfig.swift](../otel-ios-mobile/Sources/NetworkInstrumentation/NetworkConfig.swift).

On install, registers a custom `URLProtocol` subclass and swizzles `URLSessionConfiguration` so new sessions pick it up. From that moment forward every `URLSession.shared.dataTask(...)` (and most custom sessions) emits a client span named after the HTTP method with OTel HTTP semconv attributes (`http.request.method`, `url.full`, `http.response.status_code`, `http.response.body.size`, etc.).

`NetworkConfig` fields:

| Field | Default | Notes |
| --- | --- | --- |
| `ignoredHosts` | `[]` | Case-insensitive exact-match denylist. |
| `allowedHosts` | `[]` | If non-empty, only these hosts are captured (allowlist). |
| `stripQueryStrings` | `true` | Removes query parameters before recording `url.full`. Privacy-safe default. |
| `capturedResponseHeaders` | `["content-type"]` | Response headers to capture. Case-insensitive. |
| `capturedRequestHeaders` | `[]` | Request headers to capture. `Authorization`, `Cookie`, and other sensitive headers are always refused even if listed. |
| `errorStatusThreshold` | `500` | Status codes `>=` this mark the span as failed. Set to `400` to treat client errors as failures too. |
| `propagateTraceContext` | `false` | Inject W3C `traceparent` on outgoing requests. Opt-in because many backends don't accept unknown headers cleanly. |

Call `NetworkInstrumentation.shared.install(tracer:)` manually (with a custom `NetworkConfig`) if you want a non-default setup; otherwise `OTelMobile.start(config:)` does it for you when `.network` is in `autoCaptureOptions`.

### LifecycleInstrumentation

Source: [LifecycleInstrumentation.swift](../otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift).

Emits OTel log records (plus a bracketed span for foreground/background) for:

| Event | Trigger |
| --- | --- |
| `app.launch` | Fires once on first `install()`. |
| `app.foreground` | `UIApplication.didBecomeActiveNotification`. Also opens the `app.foreground_session` span. |
| `app.background` | `UIApplication.didEnterBackgroundNotification`. Ends the `app.foreground_session` span. |
| `app.will_terminate` | `UIApplication.willTerminateNotification`. Best-effort — iOS does not reliably deliver this. |
| `app.memory_warning` | `UIApplication.didReceiveMemoryWarningNotification`. Severity: `warn`. |

All events carry `event.name` as an attribute following OTel event semantics.

### ErrorsInstrumentation

Source: [ErrorsInstrumentation.swift](../otel-ios-mobile/Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift).

Two capture paths:

1. **Objective-C exceptions** via `NSSetUncaughtExceptionHandler`. Catches `NSException` instances, including those bridged from thrown Swift errors that escape.
2. **POSIX signals** via `sigaction` for `SIGABRT`, `SIGSEGV`, `SIGILL`, `SIGFPE`, `SIGBUS`, `SIGPIPE`, `SIGTRAP`. Covers native crashes.

Because the process is mid-crash when these fire, the handler does only async-signal-safe-ish work: writes a small newline-separated marker file under `~/Library/Caches/io.dash0.mobile.crash-marker` with signal name, timestamp, reason, and up to 50 stack frames — then re-raises the signal so the OS / debugger / App Store crash reporter still picks it up. The handler does **not** call into the OTel tracer or logger (locks and allocations are unsafe at that point).

On the next `install(logger:)` call, `emitAnyPendingCrash` reads the marker, emits an `app.crash` log at severity `fatal` with `crash.*` and `exception.stacktrace` attributes, then deletes the marker. This is the "recovery marker" pattern — the N+1'th launch reports the crash that killed the N'th.

Manual error reporting is also available:

```swift
ErrorsInstrumentation.shared.recordError(error, attributes: ["context": .string("checkout")])
```

Production apps with symbolication requirements should still consider pairing this with PLCrashReporter or KSCrash. This built-in path has zero external dependencies and is good enough for observability signals.

### ScreenInstrumentation (staged, not auto-installed)

Source: [ScreenInstrumentation.swift](../otel-ios-mobile/Sources/ScreenInstrumentation/ScreenInstrumentation.swift).

Swizzles `UIViewController.viewDidAppear` and `viewDidDisappear` to emit a `screen.view` log and open/close a `page.<ScreenName>` span per screen. Works for UIKit and SwiftUI (the `UIHostingController` wrapping each SwiftUI screen still fires these methods).

It is **not** installed by default in the current release. The comment in [OTelMobile.swift](../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift) captures why: `UIHostingController` hierarchies are sensitive to `viewDidAppear`/`Disappear` swizzles and we'd rather ship a ViewModifier-based SwiftUI integration with an optional UIKit swizzle gated by opt-in. If you want the behavior today, wire it yourself:

```swift
if let tracer = mobile.tracer {
    ScreenInstrumentation.shared.install(tracer: tracer, logger: mobile.logger)
}
```

### DeviceStatsCollector (manual opt-in)

Source: [DeviceStatsCollector.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Metrics/DeviceStatsCollector.swift).

Periodically records device health as OpenTelemetry gauges:

| Gauge | Unit | Notes |
| --- | --- | --- |
| `device.memory.used_mb` | MB | `mach_task_basic_info.resident_size`. |
| `device.memory.available_mb` | MB | Derived from `ProcessInfo.physicalMemory` minus used. |
| `device.battery.level` | 0.0-1.0 | Enables `UIDevice.isBatteryMonitoringEnabled` on start. Skipped in simulator configurations without battery. |
| `device.thermal.state` | 0-3 (long) | `ProcessInfo.thermalState.rawValue`. |
| `device.storage.available_mb` | MB | `URLResourceValues.volumeAvailableCapacityForImportantUsage` on the caches directory. |

Start/stop from app code:

```swift
if let meter = mobile.meter {
    mobile.deviceStats.start(meter: meter, intervalSeconds: 5)
}
// ...later...
mobile.deviceStats.stop()
```

Starting is idempotent. `start` is `@MainActor` because it touches `UIDevice.isBatteryMonitoringEnabled`.

## Resource Attributes

Every log, span, and metric carries the resource built by [ResourceBuilder.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Resource/ResourceBuilder.swift):

| Attribute | Value |
| --- | --- |
| `service.name` | From `MobileConfig.serviceName`. |
| `service.version` | From `MobileConfig.serviceVersion`. |
| `telemetry.sdk.name` | `"io.dash0.mobile"`. |
| `telemetry.sdk.version` | `"0.1.0-alpha"` — bump on release. |
| `telemetry.sdk.language` | `"swift"`. |
| `os.type` | `"darwin"` on iOS and macOS. |
| `os.name` | `"iOS"` or `"macOS"`. |
| `os.version` | `UIDevice.systemVersion` on iOS, `ProcessInfo.operatingSystemVersionString` on macOS. |
| `device.manufacturer` | `"Apple"`. |
| `device.model.name` | `UIDevice.model` (iOS only). |
| `device.model.identifier` | Hardware identifier via `uname.machine` (e.g. `iPhone17,3`). On the simulator, the `SIMULATOR_MODEL_IDENTIFIER` env var is preferred and suffixed with `" (Simulator)"`. |
| `device.id` | `UIDevice.identifierForVendor` UUID (iOS only). |

Callers can pass `extraAttributes: [String: String]` to override defaults on key collision — useful for test fixtures and custom deployments.

## OpenTelemetry Semantic Conventions

Signals emitted by this SDK follow OTel semconv where they apply:

- **HTTP client spans** (from `NetworkInstrumentation`): `http.request.method`, `url.full`, `http.response.status_code`, `server.address`, `server.port`, plus any configured request/response headers under `http.request.header.<name>` / `http.response.header.<name>`.
- **App crash logs** (from `ErrorsInstrumentation`): `event.name=app.crash`, `exception.stacktrace`, `crash.kind`, `crash.name`, `crash.reason`, `crash.timestamp`, severity `fatal`.
- **App error logs** (manual `recordError`): `event.name=app.error`, `error.type`, `error.message`, severity `error`.
- **Lifecycle logs** (from `LifecycleInstrumentation`): `event.name=app.launch | app.foreground | app.background | app.will_terminate | app.memory_warning`.
- **Screen view logs** (if `ScreenInstrumentation` is wired): `event.name=screen.view`, `screen.name`.
- **Device gauges** (from `DeviceStatsCollector`): metric names use the Dash0 `device.memory.*`, `device.battery.*`, `device.thermal.*`, `device.storage.*` family.

All signals carry the resource attributes listed above, so queries like `os.name="iOS" AND service.name="..."` scope cleanly to one platform.

## Toolchain Notes

From the sprint plan addendum at [2026-04-08-ios-sdk-sprint1.md](superpowers/plans/2026-04-08-ios-sdk-sprint1.md):

- **opentelemetry-swift split.** The upstream project split into `opentelemetry-swift` (exporters, propagators, heavyweight dependencies) and `opentelemetry-swift-core` (API + SDK types) in the 2.x line. `OTelMobileCore` depends on `OpenTelemetryApi` from the `-core` package only; `OTelMobileSDK` pulls in both. Don't import from the wrong product — you'll get duplicate-symbol errors at link time if you mix them.
- **Swift Testing vs XCTest.** The package uses `XCTest` for its own tests so they run under both Xcode and Command Line Tools. Swift Testing (`@Test`/`#expect`) is acceptable inside an Xcode-only CI stage, but the `run-ios-tests.sh` wrapper assumes XCTest rpath handling.
- **iOS 15 API compatibility.** `swift test` on macOS 13+ compiles code that fails at runtime on iOS 15 because macOS 13 ships iOS 16 APIs. Avoid `ContinuousClock`, `Duration` (`.milliseconds(_:)`), and `Task.sleep(for:)` without `@available(iOS 16, *)` guards. The collector's `Task.sleep(nanoseconds:)` loop in `DeviceStatsCollector` is the canonical pattern — stick to it. Always validate against a real iOS 15 simulator before merging:
  ```bash
  xcodebuild test -scheme OTelMobile-Package \
    -destination "platform=iOS Simulator,name=iPhone 17"
  ```
- **Command Line Tools vs full Xcode.** `swift build` and `swift test` work with Command Line Tools alone for the package. Building the demo apps requires a full Xcode install (`xcodebuild`, `xcrun simctl`, and XcodeGen).

## Troubleshooting

### Blank SwiftUI screen after `OTelMobile.start`

Auto-instrumentation used to install synchronously at the end of `start(config:)`. That race condition — `URLSessionConfiguration` swizzle + signal handlers + `NSException` handler vs UIKit scene setup — left SwiftUI stuck on its launch screen. Current behavior defers the installs to the main queue's next tick via `DispatchQueue.main.async`, which lets the first SwiftUI render complete before any swizzle runs. See the comment block in [OTelMobile.swift](../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift) around the `opts = config.autoCaptureOptions` line for the full explanation. If you're calling `start` from somewhere other than `App.init` / `@main` and still see the blank screen, ensure you're on the main thread.

### Simulator runtime not installed

`xcrun simctl list runtimes` shows what's available. If the runtime you want is missing:

```bash
xcrun simctl runtime add "com.apple.CoreSimulator.SimRuntime.iOS-18-0"
# or use Xcode -> Settings -> Components
```

The demo apps default to `iPhone 17` / iOS 18. Override with `IOS_SIM_NAME=... scripts/demo/demo-control-center-ios.sh full` if you prefer something different.

### Xcode vs Command Line Tools

```bash
xcode-select -p
# Should point to /Applications/Xcode.app/Contents/Developer for full builds.
# Command Line Tools (/Library/Developer/CommandLineTools) is enough for `swift test` only.
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

The demo control center exports `DEVELOPER_DIR` itself when a full Xcode is detected — see [demo-control-center-ios.sh](../scripts/demo/demo-control-center-ios.sh).

### No telemetry reaching the backend

1. Verify `endpoint` is reachable from the simulator. Simulators share the host's network, so `curl -v https://ingress.YOUR-DOMAIN.dash0.com:4318/v1/logs` from your Mac is a valid smoke test.
2. Confirm `authToken` includes the `Bearer ` prefix and `extraHeaders["Dash0-Dataset"]` is set.
3. Call `mobile.forceFlush()` explicitly — the batch processors run on a 2 s cadence by default, which can hide misconfiguration during a quick smoke test.
4. Stream the app's Console output:
   ```bash
   xcrun simctl spawn booted log stream --predicate 'process == "StarterApp"' --level debug
   ```

### Signal handler not restoring between crashes

`ErrorsInstrumentation` installs a handler, writes the marker, calls `signal(sig, SIG_DFL)`, then re-raises. If you're hot-reloading the SDK in a debug build and the second crash hits the default handler instead of yours, call `ErrorsInstrumentation.shared.uninstall()` and `install(logger:)` again to re-register the `sigaction`.

## Related Documentation

- [iOS Configuration Reference](IOS_CONFIGURATION.md) — Every field on `MobileConfig` and its sub-configs.
- [iOS Demo Runbook](HOW_TO_DEMO_IOS.md) — Step-by-step for running the Starter and Astronomy Shop demos.
- [Android SDK Guide](ANDROID_SDK_GUIDE.md) — Sibling doc for the Android SDK.
- [DSL v2 Schema](../mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md) — Cross-platform policy DSL consumed by both SDKs.
