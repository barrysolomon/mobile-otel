# otel-ios-mobile

iOS port of the Dash0 Mobile Observability SDK. Swift 5.9, iOS 15+. Built as a Swift Package with separate library targets per instrumentation module so apps depend only on what they use.

## Status

- **Shipped release: `v0.5.0-alpha`** (see [CHANGELOG.md](../CHANGELOG.md))
- Test suite green on `swift test` (macOS host) and Simulator
- E2E validated against AstronomyShop on iOS 26 Simulator
- Default export mode: `HYBRID`
- Screenshot & wireframe capture **default OFF** (opt-in + consent gate — see [Capture is opt-in](#capture-is-opt-in-screenshot--wireframe))
- Remote-config polling **default ON** (remote kill switch works out of the box)

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
| `ScreenshotInstrumentation` | Key-window screenshot capture with default-on redaction — **opt-in** (`.screenshot`) + consent gate |
| `WireframeInstrumentation` | View-hierarchy JSON tree (no pixels) — **opt-in** (`.wireframe`) + consent gate |

Each target ships as a separate `.library(...)` product in `Package.swift` so consumers can depend on `[OTelMobileSDK]` for the all-in batteries-included experience or pick individual instrumentation modules.

## Quick install (consumer)

The package lives at the repo root in `otel-ios-mobile/`. In `Package.swift`, pin the shipped tag `v0.5.0-alpha`:

```swift
dependencies: [
    .package(url: "https://github.com/barrysolomon/mobile-otel.git", exact: "v0.5.0-alpha"),
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

let mobile = try OTelMobile.start(config: MobileConfig(
    serviceName: "my-app",
    serviceVersion: "1.0.0",
    endpoint: "https://ingress.dash0.com:4318",  // OTLP/HTTP, not gRPC
    authToken: "Bearer YOUR_DASH0_TOKEN",
    extraHeaders: ["Dash0-Dataset": "otel-mobile"]
    // exportMode defaults to .hybrid
))
```

> `start(config:)` is `throws` — it rejects a misconfigured (e.g. cleartext) endpoint rather than crashing the host. See [Transport security](#transport-security) below.

> **Transport note:** iOS uses **OTLP/HTTP `:4318`**, not gRPC `:4317` like Android. See memory `feedback_rn_transport_asymmetry.md`.

### Capture is opt-in (screenshot / wireframe)

Screenshot and wireframe capture **default OFF** — `MobileConfig.autoCaptureOptions` defaults to `.default`, which is every module **except** `.screenshot` and `.wireframe`. To turn them on, add the options and supply a consent gate:

```swift
let mobile = try OTelMobile.start(config: MobileConfig(
    serviceName: "my-app",
    endpoint: "https://ingress.dash0.com:4318",
    authToken: "Bearer YOUR_DASH0_TOKEN",
    autoCaptureOptions: AutoCaptureOptions.default.union([.screenshot, .wireframe]),
    screenshotConfig: ScreenshotConfig().withConsentGate { ctx in
        // Consulted synchronously on the main thread before each capture.
        ConsentManager.shared.allowsCapture(ctx)   // return false to skip
    }
))
```

Captures are redacted by default (`redactTextFields: true`): UIKit `isSecureTextEntry` fields and any view you tag are masked. Tag sensitive views deterministically with `Dash0.redact(_:)` / `UIView.dash0MarkSensitive()`, or in SwiftUI with `.dash0Redacted()`. See [docs/IOS_CONFIGURATION.md](../docs/IOS_CONFIGURATION.md#screenshotconfig).

### Transport security

- **HTTPS enforced by default.** A cleartext `http://` endpoint to a non-loopback host is rejected (`start` throws / the affected pipeline is disabled); the host app never crashes. Loopback/`.local` hosts stay permitted for local-collector development. Set `allowInsecureTransport: true` only for a deliberate, network-isolated deployment.
- **Optional pinning.** Pass `pinning: TransportSecurity.PinningConfig(spkiSHA256Pins:certificates:)` for SPKI public-key and/or DER certificate pinning on both the OTLP exporters and the config poller (fail-closed per connection).
- **Signed remote config.** Pass `configSigningKey` (HMAC-SHA256 shared secret) so the remote kill switch can't be flipped by a MITM/OTA payload.

### Remote config polling defaults ON

`MobileConfig.enablePolicyPolling` defaults to **`true`**, so the remote kill switch (`sdk.enabled`) and global sampling override (`sample_rate`) are functional out of the box. Set it to `false` to disable remote config entirely.

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
