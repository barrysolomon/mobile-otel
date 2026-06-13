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

The SDK ships as a Swift Package with ten library products (the package itself is named `OTelMobile` and lives in `otel-ios-mobile/` at the repo root). Pin the shipped tag `v0.4.1-alpha` in your app's `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/barrysolomon/mobile-otel.git", exact: "v0.4.1-alpha"),
    // For local development: .package(path: "../mobile-otel/otel-ios-mobile")
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "OTelMobileSDK", package: "mobile-otel"),
        ]
    ),
]
```

Products and when to import them (see [Package.swift](../otel-ios-mobile/Package.swift)):

| Product | Import when you want... |
| --- | --- |
| `OTelMobileSDK` | The main SDK entry point (`OTelMobile.start`). Pulls in everything below transitively. |
| `OTelMobileCore` | Just the protocol/type layer (no UIKit, no providers). Also home of the capture consent types (`CaptureContext`) and redaction API (`Dash0.redact(_:)`). |
| `NetworkInstrumentation` | To configure or manually control the URLSession swizzle. |
| `LifecycleInstrumentation` | To customize the lifecycle installer or inspect its state. |
| `ErrorsInstrumentation` | To call `recordError(_:)` for caught errors manually. |
| `ScreenInstrumentation` | The SwiftUI `.trackScreen(_:)` ViewModifier and (opt-in) UIKit swizzle. Auto-installed via `.screen`. |
| `VitalsInstrumentation`, `FreezeInstrumentation` | App-start / jank / memory vitals and the main-thread freeze watchdog. Auto-installed via `.vitals` / `.freeze`. |
| `ScreenshotInstrumentation`, `WireframeInstrumentation` | Visual capture modules. **Off by default** — opt in with `.screenshot` / `.wireframe` plus a consent gate (see [AutoCaptureOptions](#autocaptureoptions)). |

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
- Installs `NetworkInstrumentation` synchronously (so requests fired in `App.init` are captured), then posts an async block to the main queue that installs the rest of the modules in `config.autoCaptureOptions` — by default `LifecycleInstrumentation`, `ErrorsInstrumentation`, `ScreenInstrumentation`, `FreezeInstrumentation`, `VitalsInstrumentation` + `AppStartInstrumentation`, and the `DeviceStatsCollector` gauge loop. `.screenshot` / `.wireframe` are excluded from the default set. The deferral is intentional — see [Troubleshooting](#troubleshooting) for why synchronous install breaks SwiftUI.
- Starts the always-on `sdk.enabled` / `sdk.sample_rate` self-telemetry gauges, and (since `enablePolicyPolling` defaults to `true`) a `ConfigPoller` for the remote kill switch.

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
| `endpoint` | `String` | — | Required. Root OTLP/HTTP URL (e.g. `https://ingress.YOUR-DOMAIN.dash0.com:4318`). Each exporter appends its own `/v1/...` suffix. Cleartext `http://` to a non-loopback host is rejected unless `allowInsecureTransport` is set. |
| `authToken` | `String?` | `nil` | Bearer token. Pass the full header value including `Bearer ` prefix. |
| `exportMode` | `ExportMode` | `.hybrid` | One of `.conditional`, `.continuous`, `.hybrid`. `.hybrid` (the default) emits periodic device heartbeats/metrics and still supports policy-triggered selective flush. |
| `bufferConfig` | `BufferConfig` | `.default` | RAM-buffer sizing for the selective-flush path. |
| `privacyConfig` | `PrivacyConfig` | `.default` | PII, location, coordinate bucketing, screenshot redaction knobs. |
| `autoCaptureOptions` | `AutoCaptureOptions` | `.default` | Which auto-instrumentation modules `start` installs. `.default` is every module **except** `.screenshot` and `.wireframe`. See below. |
| `pollingIntervalSeconds` | `Int` | `300` | Remote-config poll cadence (seconds). Active when `enablePolicyPolling` is `true`. |
| `enablePolicyPolling` | `Bool` | `true` | **Defaults ON.** `start` constructs a `ConfigPoller` against `<endpoint>/config?dsl_version=2` so the remote kill switch (`sdk.enabled`) and global sampling override (`sample_rate`) work out of the box. Set `false` to disable remote config entirely. |
| `samplingConfig` | `SamplingConfig` | `.dynamic(0.1, 1.0)` | Trace sampling: 10% baseline, 100% for `page.*` / `app.startup`. Use `.alwaysOn()` for dev or `.production(rate:)` for fixed-rate. |
| `extraHeaders` | `[String: String]` | `[:]` | Merged onto every OTLP/HTTP request — commonly `"Dash0-Dataset"` alongside the bearer token. |
| `extraResourceAttributes` | `[String: String]` | `[:]` | Extra resource attributes merged into the built-in resource (e.g. `telemetry.distro.*`). |
| `screenshotConfig` | `ScreenshotConfig` | `ScreenshotConfig()` | Used only when `.screenshot` is enabled. Carries the `shouldCapture` consent gate and redaction knobs. |
| `wireframeConfig` | `WireframeConfig` | `WireframeConfig()` | Used only when `.wireframe` is enabled. Carries the `shouldCapture` consent gate. |
| `allowInsecureTransport` | `Bool` | `false` | When `false`, cleartext `http://` to a non-loopback host is rejected (the pipeline is disabled, never a host crash). Loopback/`.local` stays exempt for local-collector dev. |
| `pinning` | `TransportSecurity.PinningConfig?` | `nil` | Optional SPKI public-key and/or DER certificate pinning, applied to both the OTLP exporters and the config poller (fail-closed per connection). |
| `configSigningKey` | `Data?` | `nil` | Optional HMAC-SHA256 shared secret. When set, the poller verifies the `X-Dash0-Config-Signature` header over the raw body before applying remote config (keeps last-applied config on verification failure). |

Example with everything tuned:

```swift
MobileConfig(
    serviceName: "my-ios-app",
    serviceVersion: "2.3.1",
    endpoint: "https://ingress.YOUR-DOMAIN.dash0.com:4318",
    authToken: "Bearer \(token)",
    exportMode: .hybrid,
    bufferConfig: BufferConfig(ramEvents: 2500, diskMb: 25, retentionHours: 12),
    privacyConfig: .production,
    autoCaptureOptions: [.network, .lifecycle, .errors],
    extraHeaders: ["Dash0-Dataset": "otel-mobile"]
)
```

### Known behavior

- `pollingIntervalSeconds` drives the remote-config poll cadence whenever `enablePolicyPolling` is `true` (the default). The poller feeds the `PolicyEvaluator` and the shared `RemoteGate` (kill switch + global sampling).
- `exportMode` selects flush behavior: `.continuous` drains the RAM buffer through OTLP on the `logExportIntervalSeconds` cadence; `.conditional` flushes only on a policy trigger or an explicit `flushWindow(minutes:)`; `.hybrid` (default) does both.

## AutoCaptureOptions

`AutoCaptureOptions` is a Swift `OptionSet`. From [AutoCaptureOptions.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift):

| Flag | Auto-installed by `start`? | Notes |
| --- | --- | --- |
| `.tap` | Placeholder | No UIKit gesture swizzle ships today. |
| `.scroll` | Placeholder | Same — Android parity is pending. |
| `.lifecycle` | **Yes** | Installs `LifecycleInstrumentation`. |
| `.screen` | **Yes** | Installs `ScreenInstrumentation` — enables the SwiftUI `.trackScreen(_:)` / `.trackTaps(target:)` ViewModifiers. The UIKit `UIViewController` swizzle stays opt-in (`enableUIKitSwizzle: true`) because it races with SwiftUI hosting-controller lifecycle. |
| `.network` | **Yes** | Installs `NetworkInstrumentation` (synchronously, so requests in `App.init` are captured). |
| `.errors` | **Yes** | Installs `ErrorsInstrumentation`. |
| `.freeze` | **Yes** | Installs `FreezeInstrumentation` (main-thread watchdog). |
| `.vitals` | **Yes** | Installs `VitalsInstrumentation` + `AppStartInstrumentation` (cold/warm start spans). |
| `.deviceStats` | **Yes** | Auto-starts the `DeviceStatsCollector` gauge loop (memory/battery/thermal/storage). |
| `.textInput` | Placeholder | No effect yet. |
| `.screenshot` | **Opt-in only** | Installs `ScreenshotInstrumentation`. **Not in `.default`** — add it explicitly and supply a `shouldCapture` consent gate (see below). |
| `.wireframe` | **Opt-in only** | Installs `WireframeInstrumentation`. **Not in `.default`** — add it explicitly and supply a consent gate. |
| `.default` | — | The value `MobileConfig` uses when you don't specify `autoCaptureOptions`: every module above **except** `.screenshot` and `.wireframe`. |
| `.all` | — | Every flag including `.screenshot` and `.wireframe`. |
| `.none` | — | Disable all auto-instrumentation — wire modules by hand. |

The default (`.default`) deliberately excludes the privacy-sensitive `.screenshot` and `.wireframe` modules — they capture screen pixels / view-hierarchy content and must be opt-in. Placeholder flags (`.tap`, `.scroll`, `.textInput`) have no runtime effect yet.

### Opting into screenshot / wireframe capture

Add the option(s) and provide a consent gate. The gate is consulted **synchronously on the main thread immediately before each capture**; return `false` to skip the capture entirely (no view-tree walk, no render, no log):

```swift
let config = MobileConfig(
    serviceName: "my-ios-app",
    endpoint: "https://ingress.YOUR-DOMAIN.dash0.com:4318",
    authToken: "Bearer \(token)",
    autoCaptureOptions: AutoCaptureOptions.default.union([.screenshot, .wireframe]),
    screenshotConfig: ScreenshotConfig().withConsentGate { ctx in
        // ctx.kind is .screenshot or .wireframe; ctx.trigger / ctx.screenName
        // let you decide per-screen (e.g. never on a screen showing a card number).
        ConsentManager.shared.allows(ctx)
    },
    wireframeConfig: WireframeConfig().withConsentGate { ctx in
        ConsentManager.shared.allows(ctx)
    }
)
```

Captures are redacted by default (`ScreenshotConfig.redactTextFields == true`). To mark sensitive regions deterministically: `Dash0.redact(view)` / `UIView.dash0MarkSensitive()` for UIKit, or `.dash0Redacted()` on a SwiftUI view. See [IOS_CONFIGURATION.md](IOS_CONFIGURATION.md#screenshotconfig) for the full knob set. You can also trigger a capture manually via `mobile.captureScreenshot()` / `mobile.captureWireframe()`.

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

The two paths differ in what they can safely persist, because the signal handler runs in a true async-signal-safe context (no allocation, no Foundation, no locks):

- **Signal path** — the `sigaction` handler does the bare minimum: `write(2)` a fixed **3-byte `S<sig>` marker** to a pre-opened file descriptor, then restore the default handler and re-raise so the OS / debugger / App Store crash reporter still records the crash. **No stack trace is captured on this path** — collecting one safely from a signal handler requires a dedicated crash reporter (see [IOS_CRASH_REPORTING.md](IOS_CRASH_REPORTING.md)).
- **NSException path** — the trampoline runs in normal execution context, so it writes a richer newline-separated marker (`io.dash0.mobile.crash-marker` under Caches) with kind, scrubbed name/reason, timestamp, and up to 50 `callStackSymbols` frames, then chains through any previously-installed handler (Sentry/Firebase/etc.). The exception reason is PII-scrubbed before it touches disk.

On the next `install(logger:)` call, `emitAnyPendingCrash` reads whichever marker is present, emits an `app.crash` log at severity `fatal` with `crash.*` (and `exception.stacktrace` only when the NSException path captured frames), then deletes the marker. This is the "recovery marker" pattern — the N+1'th launch reports the crash that killed the N'th.

Manual error reporting is also available:

```swift
ErrorsInstrumentation.shared.recordError(error, attributes: ["context": .string("checkout")])
```

Production apps with symbolication requirements should still consider pairing this with PLCrashReporter or KSCrash. This built-in path has zero external dependencies and is good enough for observability signals.

### ScreenInstrumentation

Source: [ScreenInstrumentation.swift](../otel-ios-mobile/Sources/ScreenInstrumentation/ScreenInstrumentation.swift).

Emits a `ui.screen_view` log (named `screen.view` before 0.4.1-alpha) and opens/closes a `page.<ScreenName>` span per screen. **Auto-installed by `start`** when `.screen` is in `autoCaptureOptions` (it is, by default). The safe default path is the SwiftUI bridge: annotate screens with the `.trackScreen("Name")` ViewModifier (and `.trackTaps(target:)`).

The UIKit `UIViewController.viewDidAppear`/`viewDidDisappear` swizzle is **not** enabled by default because `UIHostingController` hierarchies are sensitive to it; it is opt-in for pure-UIKit apps. To enable the swizzle, install manually:

```swift
if let tracer = mobile.tracer {
    ScreenInstrumentation.shared.install(tracer: tracer, logger: mobile.logger, enableUIKitSwizzle: true)
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
| `telemetry.sdk.version` | `"0.4.1-alpha"` — the literal in [ResourceBuilder.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Resource/ResourceBuilder.swift) (`sdkVersion`), matching the shipped package tag `v0.4.1-alpha`. |
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
- **App crash logs** (from `ErrorsInstrumentation`): `event.name=app.crash`, `crash.from_marker=true`, `crash.kind` (`exception` or `signal`), `crash.name`, `crash.signal`, `crash.reason`, `crash.timestamp`, severity `fatal`. `exception.stacktrace` is present only for the NSException path; signal-path crashes carry no stack (see [IOS_CRASH_REPORTING.md](IOS_CRASH_REPORTING.md)).
- **App error logs** (manual `recordError`): `event.name=app.error`, `error.type`, `error.message`, severity `error`.
- **Lifecycle logs** (from `LifecycleInstrumentation`): `event.name=app.launch | app.foreground | app.background | app.will_terminate | app.memory_warning`.
- **Screen view logs** (if `ScreenInstrumentation` is wired): `event.name=ui.screen_view` (was `screen.view` before 0.4.1-alpha), `screen.name`.
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
4. Stream the app's Console output — replace `AstronomyShop` with the process name of your app:
   ```bash
   xcrun simctl spawn booted log stream --predicate 'process == "AstronomyShop"' --level debug
   ```

### Signal handler not restoring between crashes

`ErrorsInstrumentation` installs a handler, writes the marker, calls `signal(sig, SIG_DFL)`, then re-raises. If you're hot-reloading the SDK in a debug build and the second crash hits the default handler instead of yours, call `ErrorsInstrumentation.shared.uninstall()` and `install(logger:)` again to re-register the `sigaction`.

## Related Documentation

- [iOS Configuration Reference](IOS_CONFIGURATION.md) — Every field on `MobileConfig` and its sub-configs.
- [iOS Demo Runbook](HOW_TO_DEMO_IOS.md) — Step-by-step for running the Starter and Astronomy Shop demos.
- [Android SDK Guide](ANDROID_SDK_GUIDE.md) — Sibling doc for the Android SDK.
- [DSL v2 Schema](../../mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md) — Cross-platform policy DSL consumed by both SDKs (sibling `mobile-otel-control-plane` repo).
