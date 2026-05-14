# otel-ios-mobile

iOS port of the Dash0 Mobile Observability SDK. Swift 5.9, iOS 15+. Built as a Swift Package with separate library targets per instrumentation module so apps depend only on what they use.

## Status

- Test suite: **403/403 green** on `swift test` (macOS host) and Simulator
- E2E validated against AstronomyShop on iOS 26 Simulator
- All 12 UAT scenarios passing
- Default export mode: `HYBRID`

See [docs/IOS_SDK_GUIDE.md](../docs/IOS_SDK_GUIDE.md) for the full integration guide.

## Package layout

| Target | Role |
|--------|------|
| `OTelMobileCore` | `OTel-Native` protocols, session, buffering primitives — analogous to `otel-android-mobile-core/` on Android |
| `OTelMobileSDK` | Public entry point `OTelMobile.start(...)`; depends on all instrumentation modules |
| `LifecycleInstrumentation` | App foreground/background, scene lifecycle |
| `ScreenInstrumentation` | Page spans for `UIViewController` |
| `NetworkInstrumentation` | URLSession swizzle + HTTP error logs |
| `ErrorsInstrumentation` | Uncaught NSError, signal handlers (SIGSEGV / SIGBUS / SIGABRT) |
| `VitalsInstrumentation` | Memory, battery, thermal, jank metrics |
| `FreezeInstrumentation` | Main-thread watchdog (ANR equivalent) |
| `ScreenshotInstrumentation` | `UIScreen` capture with text redaction |
| `WireframeInstrumentation` | View-hierarchy JSON tree |

Each target ships as a separate `.library(...)` product in `Package.swift` so consumers can depend on `[OTelMobileSDK]` for the all-in batteries-included experience or pick individual instrumentation modules.

## Quick install (consumer)

In `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/<org>/mobile-otel.git", from: "0.2.0-alpha"),
],
targets: [
    .target(name: "MyApp", dependencies: [
        .product(name: "OTelMobileSDK", package: "mobile-otel"),
    ])
]
```

In `AppDelegate.swift` or your `@main` entry:

```swift
import OTelMobileSDK

OTelMobile.start(MobileConfig(
    serviceName: "my-app",
    serviceVersion: "1.0.0",
    collectorEndpoint: "https://ingress.dash0.com:4318",  // HTTP, not gRPC
    exportMode: .hybrid
))
```

> **Transport note:** iOS uses **OTLP/HTTP `:4318`**, not gRPC `:4317` like Android. See memory `feedback_rn_transport_asymmetry.md`.

## Build & test

```bash
# macOS host (fast — for SDK contract changes)
swift test

# Full matrix incl. Simulator
./run-tests.sh

# Or from the workspace root:
./scripts/test/run-ios-tests.sh
```

## Gotchas

- **`forceFlush()` vs `forceFlushBuffered()`** — `forceFlush()` is the OTel-protocol RAM-only flush. `forceFlushBuffered()` drains RAM **+ disk**. Offline-recovery contracts MUST use the latter. (Memory: `feedback_ios_forceflush_two_methods.md`)
- **iOS 26 Simulator** — RN host apps need `UIApplicationSceneManifest` in `Info.plist` or the root window never paints. (Memory: `project_ios_rn_uiscene.md`)
- **OTLP exporter return value** — `OtlpHttpTraceExporter` returns `.success` even on network failure; we intercept at the `HTTPClient` layer instead. (Memory: `feedback_otlp_exporter_failure_detection.md`)

## See also

- [CLAUDE.md](CLAUDE.md) — internal build notes
- [docs/IOS_SDK_GUIDE.md](../docs/IOS_SDK_GUIDE.md) — integration guide
- [docs/IOS_ANDROID_PARITY.md](../docs/IOS_ANDROID_PARITY.md) — feature matrix vs Android
- [docs/IOS_CRASH_REPORTING.md](../docs/IOS_CRASH_REPORTING.md) — signal-handler details
