# iOS Configuration Reference

Field-by-field reference for `MobileConfig` and every sub-config used by the Dash0 iOS SDK. See [IOS_SDK_GUIDE.md](IOS_SDK_GUIDE.md) for narrative coverage and [MobileConfig.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/MobileConfig.swift) for the source of truth.

## Table of Contents

- [MobileConfig](#mobileconfig)
- [BufferConfig](#bufferconfig)
- [PrivacyConfig](#privacyconfig)
- [ExportMode](#exportmode)
- [AutoCaptureOptions](#autocaptureoptions)
- [ScreenshotConfig](#screenshotconfig)
- [WireframeConfig](#wireframeconfig)
- [Capture consent & redaction](#capture-consent--redaction)
- [Transport security](#transport-security)
- [NetworkConfig](#networkconfig)
- [Full example](#full-example)

## MobileConfig

Source: [MobileConfig.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/MobileConfig.swift).

### `serviceName`

- **Type:** `String`
- **Default:** none — required.
- **What it does:** Emitted as OTel `service.name` resource attribute on every log, span, and metric.
- **When to change it:** Always. Use the same name across platforms for a given app so Android and iOS signals land in the same service view.
- **Example:** `serviceName: "otel-ios-astronomy-shop"`.

### `serviceVersion`

- **Type:** `String`
- **Default:** `"1.0.0"`.
- **What it does:** Emitted as OTel `service.version`. Use it to correlate crashes and regressions to app builds.
- **When to change it:** On every release. Wire it to `Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")` if you want it automatic.
- **Example:** `serviceVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"`.

### `endpoint`

- **Type:** `String`
- **Default:** none — required.
- **What it does:** Root OTLP/HTTP URL. The SDK appends `/v1/logs`, `/v1/traces`, and `/v1/metrics` per signal. Dash0 collectors listen on port 4318 for OTLP/HTTP.
- **When to change it:** For Dash0 cloud, use `https://ingress.YOUR-DOMAIN.dash0.com:4318`. For a local collector, use `http://localhost:4318` (or your LAN IP from a real device).
- **Example:** `endpoint: "https://ingress.eu-west-1.aws.dash0.com:4318"`.

### `authToken`

- **Type:** `String?`
- **Default:** `nil`.
- **What it does:** Bearer token. The full header value — include the `Bearer ` prefix yourself. If `nil`, no `Authorization` header is sent.
- **When to change it:** Always for Dash0. Never commit the real token; load it from `otel-config.json` at startup as the demo apps do.
- **Example:** `authToken: "Bearer auth_0123abcd..."`.

### `exportMode`

- **Type:** `ExportMode` — see [ExportMode](#exportmode).
- **Default:** `.hybrid`.
- **What it does:** Selects flush behavior. `.continuous` drains the RAM buffer through OTLP on the `logExportIntervalSeconds` cadence; `.conditional` flushes only on a policy trigger or an explicit `flushWindow(minutes:)`; `.hybrid` (the default) does both — periodic heartbeats/metrics plus policy-triggered selective flush.
- **When to change it:** Use `.conditional` for a battery-frugal, trigger-only stream; `.continuous` for a chatty stream you always want shipped.
- **Example:** `exportMode: .hybrid`.

### `bufferConfig`

- **Type:** `BufferConfig`.
- **Default:** `.default` — 5 000 RAM events, 50 MB on-disk budget (reserved), 24 h retention.
- **What it does:** Sizes the RAM event ring buffer backing `flushWindow(minutes:)`. The disk tier is parsed but not yet wired on iOS; the `diskMb` / `retentionHours` fields are reserved for the upcoming disk buffer.
- **When to change it:** Raise `ramEvents` for chatty apps (more events in the selective-flush window). Lower it on memory-constrained devices.
- **Example:** `bufferConfig: BufferConfig(ramEvents: 10_000, diskMb: 50, retentionHours: 24)`.

### `privacyConfig`

- **Type:** `PrivacyConfig` — see [PrivacyConfig](#privacyconfig).
- **Default:** `.default`.
- **What it does:** PII scrubbing (active on the crash/error path today), location capture, and coordinate bucketing knobs.
- **When to change it:** Switch to `.production` for shipping builds.
- **Example:** `privacyConfig: .production`.

### `autoCaptureOptions`

- **Type:** `AutoCaptureOptions` — see [AutoCaptureOptions](#autocaptureoptions).
- **Default:** `.default` — every module **except** `.screenshot` and `.wireframe`.
- **What it does:** Selects which instrumentation modules `OTelMobile.start(config:)` installs automatically. `.lifecycle`, `.screen`, `.network`, `.errors`, `.freeze`, `.vitals`, and `.deviceStats` are wired; `.tap`/`.scroll`/`.textInput` are placeholders with no effect yet; `.screenshot`/`.wireframe` are real but opt-in (not in `.default`).
- **When to change it:** Add `.screenshot` / `.wireframe` (with a consent gate) to enable visual capture; use `.none` to wire modules by hand; tailor a custom set (e.g. `[.network, .errors]`) for a lean demo.
- **Example:** `autoCaptureOptions: AutoCaptureOptions.default.union([.screenshot, .wireframe])`.

### `pollingIntervalSeconds`

- **Type:** `Int`
- **Default:** `300`.
- **What it does:** Remote-config poll cadence in seconds. Active whenever `enablePolicyPolling` is `true` (the default) — the poller hits `<endpoint>/config?dsl_version=2`.
- **When to change it:** Lengthen for less frequent kill-switch propagation; shorten to react faster.
- **Example:** `pollingIntervalSeconds: 600`.

### `enablePolicyPolling`

- **Type:** `Bool`
- **Default:** `true`.
- **What it does:** **Defaults ON.** `start` constructs a `ConfigPoller` and feeds the `PolicyEvaluator` + shared `RemoteGate`, so the remote kill switch (`sdk.enabled`) and global sampling override (`sample_rate`) work out of the box.
- **When to change it:** Set `false` to disable remote config entirely (no `/config` polling).
- **Example:** `enablePolicyPolling: false`.

### `samplingConfig`

- **Type:** `SamplingConfig`
- **Default:** `.dynamic(normalRate: 0.1, highPriorityRate: 1.0)`.
- **What it does:** Trace sampling — 10% baseline, 100% for `page.*` and `app.startup`. Override with `.alwaysOn()` (dev) or `.production(rate:)` (fixed-rate trace-id sampling).
- **Example:** `samplingConfig: .alwaysOn()`.

### `screenshotConfig` / `wireframeConfig`

- **Type:** `ScreenshotConfig` / `WireframeConfig` — see [ScreenshotConfig](#screenshotconfig) / [WireframeConfig](#wireframeconfig).
- **Default:** `ScreenshotConfig()` / `WireframeConfig()`.
- **What it does:** Tuning + the `shouldCapture` consent gate for the visual-capture modules. Only used when `.screenshot` / `.wireframe` is in `autoCaptureOptions`.
- **Example:** `screenshotConfig: ScreenshotConfig().withConsentGate { ctx in ConsentManager.shared.allows(ctx) }`.

### `allowInsecureTransport`

- **Type:** `Bool`
- **Default:** `false`.
- **What it does:** When `false`, a cleartext `http://` endpoint to a non-loopback host is **rejected** — the affected pipeline is disabled (never a host crash). Loopback / `127.0.0.1` / `::1` / `*.local` stay exempt for local-collector development.
- **When to change it:** Set `true` only for a deliberate, network-isolated deployment. Telemetry (and any PII) is then unencrypted.
- **Example:** `allowInsecureTransport: false`.

### `pinning`

- **Type:** `TransportSecurity.PinningConfig?`
- **Default:** `nil` (no pinning).
- **What it does:** SPKI public-key (`spkiSHA256Pins`) and/or DER certificate (`certificates`) pinning applied to both the OTLP exporters and the config poller. A pin mismatch fails only that connection (fail-closed), never the host.
- **Example:** `pinning: TransportSecurity.PinningConfig(spkiSHA256Pins: ["base64spki…"])`.

### `configSigningKey`

- **Type:** `Data?`
- **Default:** `nil`.
- **What it does:** HMAC-SHA256 shared secret. When set, the poller verifies the `X-Dash0-Config-Signature` header over the raw remote-config body before applying it — closing the kill-switch MITM/OTA vector. On verification failure it keeps the last-applied config (fails toward availability).
- **Example:** `configSigningKey: Data(/* shared secret bytes */)`.

### `extraHeaders`

- **Type:** `[String: String]`
- **Default:** `[:]`.
- **What it does:** Headers merged onto every OTLP/HTTP export. Commonly `"Dash0-Dataset"` for multi-dataset accounts.
- **When to change it:** Always, for Dash0 — the backend requires `Dash0-Dataset` alongside the bearer token.
- **Example:** `extraHeaders: ["Dash0-Dataset": "otel-mobile"]`.

## BufferConfig

Source: [BufferConfig.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/BufferConfig.swift).

| Field | Swift type | Default | Description |
| --- | --- | --- | --- |
| `ramEvents` | `Int` | `5000` | Capacity of the in-memory ring buffer feeding `flushWindow(minutes:)`. Overflow evicts oldest-first. |
| `diskMb` | `Int` | `50` | Disk budget for the upcoming SQLite mirror. Parsed but not enforced today. |
| `retentionHours` | `Int` | `24` | TTL for events on the disk tier. Parsed but not enforced today. |

Preset: `BufferConfig.default = BufferConfig(ramEvents: 5000, diskMb: 50, retentionHours: 24)`.

Example:

```swift
BufferConfig(ramEvents: 10_000, diskMb: 100, retentionHours: 48)
```

## PrivacyConfig

Source: [PrivacyConfig.swift](../otel-ios-mobile/Sources/OTelMobileCore/PrivacyConfig.swift) (lives in `OTelMobileCore` to avoid a cyclic dependency from `InstrumentationContext`).

| Field | Swift type | Default | Description |
| --- | --- | --- | --- |
| `scrubPii` | `Bool` | `true` | Matching values (email, phone numbers, etc.) are scrubbed from crash/error bodies and attributes. The crash-marker reason and stack-trace paths are scrubbed today via `PiiScrubber`. |
| `captureLocation` | `Bool` | `false` | Whether to include coarse location metadata. Off by default — opt in consciously. |
| `bucketCoordinates` | `Bool` | `true` | If tap telemetry is emitted, quantize coordinates to a coarse grid so exact-pixel trajectories can't be reconstructed. |
| `redactTextOnScreenshots` | `Bool` | `false` | Privacy-config-level redaction hint. Note: the shipped screenshot/wireframe modules redact via [`ScreenshotConfig.redactTextFields`](#screenshotconfig) (default `true`) and the `Dash0.redact(_:)` tagging API — see that section for the authoritative knobs. |

Presets:

| Preset | `scrubPii` | `captureLocation` | `bucketCoordinates` | `redactTextOnScreenshots` | Use when |
| --- | --- | --- | --- | --- | --- |
| `.default` | `true` | `false` | `true` | `false` | Most apps. |
| `.minimal` | `false` | `false` | `false` | `false` | Local debugging only. |
| `.production` | `true` | `false` | `true` | `true` | Shipping builds with screenshot capture. |
| `.debug` | `false` | `true` | `false` | `false` | Field-debugging a specific bug. Include explicit user consent. |

Example:

```swift
PrivacyConfig(
    scrubPii: true,
    captureLocation: false,
    bucketCoordinates: true,
    redactTextOnScreenshots: true
)
```

## ExportMode

Source: [ExportMode.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/ExportMode.swift).

| Case | Raw value | Description |
| --- | --- | --- |
| `.conditional` | `"conditional"` | Selective flush only — nothing leaves the buffer unless a policy trigger fires or `flushWindow(minutes:)` is called. Battery-friendly. |
| `.continuous` | `"continuous"` | Periodic export — the buffer processor drains the RAM ring through OTLP every `logExportIntervalSeconds` (default 30 s). |
| `.hybrid` | `"hybrid"` | Both periodic and trigger-based. **The `MobileConfig` default.** |

This enum is `Codable` + `Sendable` and is used both by the DSL on the wire and by `MobileConfig`. The mode governs the log-flush cadence (continuous periodic drain vs trigger-only); spans/metrics always go through their batch processors / periodic reader.

## AutoCaptureOptions

Source: [AutoCaptureOptions.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift).

`OptionSet` with one bit per instrumentation module:

| Case | Bit | Auto-installed | Notes |
| --- | --- | --- | --- |
| `.tap` | `1 << 0` | No | Placeholder — no effect yet. |
| `.scroll` | `1 << 1` | No | Placeholder — no effect yet. |
| `.lifecycle` | `1 << 2` | **Yes** | Installs `LifecycleInstrumentation`. |
| `.screen` | `1 << 3` | **Yes** | Installs `ScreenInstrumentation` (SwiftUI `.trackScreen(_:)` bridge; UIKit swizzle stays opt-in via `enableUIKitSwizzle: true`). |
| `.network` | `1 << 4` | **Yes** | Installs `NetworkInstrumentation` with `NetworkConfig.default`. |
| `.errors` | `1 << 5` | **Yes** | Installs `ErrorsInstrumentation`. |
| `.freeze` | `1 << 6` | **Yes** | Installs `FreezeInstrumentation` (main-thread watchdog). |
| `.vitals` | `1 << 7` | **Yes** | Installs `VitalsInstrumentation` + `AppStartInstrumentation`. |
| `.textInput` | `1 << 8` | No | Placeholder — no effect yet. |
| `.screenshot` | `1 << 9` | **Opt-in** | Installs `ScreenshotInstrumentation`. Not in `.default` — add explicitly + supply a consent gate. |
| `.wireframe` | `1 << 10` | **Opt-in** | Installs `WireframeInstrumentation`. Not in `.default` — add explicitly + supply a consent gate. |
| `.deviceStats` | `1 << 11` | **Yes** | Auto-starts the `DeviceStatsCollector` gauge loop (cadence `deviceStatsIntervalSeconds`, default 15 s). |

Convenience:

| Preset | Expands to |
| --- | --- |
| `.default` | `.all` minus `.screenshot` and `.wireframe` — **the value `MobileConfig` uses when you don't specify `autoCaptureOptions`.** |
| `.all` | `[.tap, .scroll, .lifecycle, .screen, .network, .errors, .freeze, .vitals, .textInput, .screenshot, .wireframe, .deviceStats]` |
| `.none` | `[]` |

Examples:

```swift
// The default — everything except screenshot/wireframe.
autoCaptureOptions: .default

// Add visual capture (also set a consent gate on screenshotConfig/wireframeConfig).
autoCaptureOptions: AutoCaptureOptions.default.union([.screenshot, .wireframe])

// Hand-pick three modules.
autoCaptureOptions: [.network, .lifecycle, .errors]

// Fully manual — wire modules yourself afterwards.
autoCaptureOptions: .none
```

## ScreenshotConfig

Source: [ScreenshotConfig.swift](../otel-ios-mobile/Sources/ScreenshotInstrumentation/ScreenshotConfig.swift). Used only when `.screenshot` is in `autoCaptureOptions` (it is **not** in `.default`).

| Field | Swift type | Default | Description |
| --- | --- | --- | --- |
| `enabled` | `Bool` | `true` | Module-level enable. Note the *actual* on/off switch for the default build is whether `.screenshot` is in `autoCaptureOptions` — which it is not by default. |
| `maxWidthPx` | `Int` | `480` | Downscale cap (width). |
| `maxHeightPx` | `Int` | `960` | Downscale cap (height). |
| `quality` | `Int` | `60` | JPEG quality (0–100). |
| `format` | `ScreenshotFormat` | `.jpeg` | `.jpeg` or `.png`. |
| `maxPayloadKb` | `Int` | `256` | Drop the capture if the encoded payload exceeds this. |
| `maxCapturesPerMinute` | `Int` | `5` | Rate limit. |
| `redactTextFields` | `Bool` | `true` | When `true`, masks UIKit `isSecureTextEntry` fields and any view tagged via `Dash0.redact(_:)` / `.dash0Redacted()` before encoding. Setting `false` disables redaction entirely (DEBUG/internal only). |
| `redactAllText` | `Bool` | `false` | When `true`, masks **every** text-bearing view (`UITextField`/`UITextView`/`UILabel`) — maximally conservative. No effect when `redactTextFields` is `false`. |
| `shouldCapture` | `CaptureConsentGate?` | `nil` | Consent gate — see [Capture consent & redaction](#capture-consent--redaction). |
| `captureOnScreenView` | `Bool` | `false` | Capture on each screen change. |
| `captureOnError` | `Bool` | `true` | Capture when `recordError` fires. |
| `captureOnPolicyMatch` | `Bool` | `true` | Capture when a buffered-export policy fires (crash-recovery / ui-freeze / http-error). Rate limited. |
| `screenViewDelayMs` | `Int` | `300` | Delay after a screen change before capturing (let the screen settle). |

Set the consent gate fluently with `.withConsentGate { ctx in ... }`.

## WireframeConfig

Source: [WireframeConfig.swift](../otel-ios-mobile/Sources/WireframeInstrumentation/WireframeConfig.swift). A wireframe is a structural view-hierarchy tree with **no pixels**. Used only when `.wireframe` is in `autoCaptureOptions` (not in `.default`).

| Field | Swift type | Default | Description |
| --- | --- | --- | --- |
| `enabled` | `Bool` | `true` | Module-level enable (gated overall by the `.wireframe` option). |
| `maxCapturesPerMinute` | `Int` | `30` | Rate limit. |
| `maxDepth` | `Int` | `20` | View-tree walk depth cap. |
| `captureOnScreenView` | `Bool` | `true` | Capture on each screen change. |
| `captureOnTap` | `Bool` | `false` | Capture on tap. |
| `captureOnError` | `Bool` | `true` | Capture when `recordError` fires. |
| `captureOnPolicyMatch` | `Bool` | `true` | Capture when a buffered-export policy fires. |
| `dedupeByContentHash` | `Bool` | `true` | Emit a lightweight `ui.wireframe.ref` (prior id) instead of re-sending an identical tree. |
| `includeAccessibilityIdentifiers` | `Bool` | `true` | Include accessibility ids on nodes. |
| `includeTextHints` | `Bool` | `false` | Include (redaction-aware) text hints. |
| `includeContentDescription` | `Bool` | `true` | Include content descriptions. |
| `includeInteractionState` | `Bool` | `true` | Include enabled/selected/focused state. |
| `shouldCapture` | `CaptureConsentGate?` | `nil` | Consent gate — see below. |

Set the consent gate fluently with `.withConsentGate { ctx in ... }`.

## Capture consent & redaction

Screenshot and wireframe capture are **off by default** (`AutoCaptureOptions.default` excludes them). To turn them on you (1) add the option and (2) should provide a consent gate.

**Consent gate** (`shouldCapture: CaptureConsentGate?`, source: [CaptureContext.swift](../otel-ios-mobile/Sources/OTelMobileCore/Capture/CaptureContext.swift)). It is a `@Sendable (CaptureContext) -> Bool` consulted **synchronously on the main thread immediately before each capture** (after `enabled` + rate-limit checks, before any render/walk). Return `false` to skip the capture entirely — no view-tree walk, no pixel render, no log. The `CaptureContext` carries `trigger` (`error` / `policy(name:)` / `screenView` / `tap` / `manual`), `kind` (`.screenshot` / `.wireframe`), and `screenName`, so you can decide per-screen. Keep the closure cheap and non-blocking — it runs on the main thread in the capture hot path.

```swift
let screenshotConfig = ScreenshotConfig().withConsentGate { ctx in
    switch ctx.kind {
    case .screenshot: return ConsentManager.shared.allowsScreenshots && !currentScreenIsSensitive(ctx.screenName)
    case .wireframe:  return ConsentManager.shared.allowsWireframes
    }
}
```

**Deterministic redaction** (source: [Dash0Redaction.swift](../otel-ios-mobile/Sources/OTelMobileCore/Capture/Dash0Redaction.swift)). With `redactTextFields` on (the default), captures mask:

- UIKit fields whose `isSecureTextEntry` is `true` (the OS-level secure flag, not a guess);
- any view explicitly tagged via `Dash0.redact(_:)` / `UIView.dash0MarkSensitive()` (call `Dash0.unredact(_:)` to clear);
- SwiftUI views marked with the `.dash0Redacted()` modifier (installs a transparent backing `UIView` carrying the same flag — no reliance on private SwiftUI class names).

The old class-name heuristic is demoted to an off-by-default, opt-in last resort (`Dash0.conservativeClassNameFallbackEnabled`).

```swift
// UIKit
Dash0.redact(cardNumberLabel)

// SwiftUI
SecureField("Password", text: $password)
    .dash0Redacted()
```

## Transport security

Source: [TransportSecurity.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Export/TransportSecurity.swift). Applied to both the OTLP exporters and the config poller.

- **HTTPS enforcement.** A cleartext `http://` endpoint to a non-loopback host is rejected unless `allowInsecureTransport == true` (default `false`). Loopback (`localhost`, `127.0.0.1`, `::1`) and `*.local` mDNS names are always exempt for local-collector dev. Rejection disables only the affected pipeline — it never crashes the host.
- **Pinning** (`pinning: TransportSecurity.PinningConfig?`). SPKI SHA-256 public-key pins (`spkiSHA256Pins`, same format as OkHttp `sha256/…`) and/or full DER certificate pins (`certificates`). Pinning is an *addition* to default trust, enforced in the TLS handshake; a mismatch fails only that connection (fail-closed).
- **Signed remote config** (`configSigningKey: Data?`). HMAC-SHA256 over the raw config body, verified against the `X-Dash0-Config-Signature` header (hex or base64 accepted, constant-time compare) before the config is applied. On failure the last-applied config is kept (fails toward availability) so a bad signature can't itself disable telemetry.

```swift
let config = MobileConfig(
    serviceName: "my-ios-app",
    endpoint: "https://ingress.YOUR-DOMAIN.dash0.com:4318",
    authToken: "Bearer \(token)",
    allowInsecureTransport: false,
    pinning: TransportSecurity.PinningConfig(spkiSHA256Pins: ["AAAA…base64spki…"]),
    configSigningKey: sharedSecretData
)
```

## NetworkConfig

Source: [NetworkConfig.swift](../otel-ios-mobile/Sources/NetworkInstrumentation/NetworkConfig.swift).

Consumed by `NetworkInstrumentation.shared.install(tracer:config:)`. `OTelMobile.start(config:)` installs with `NetworkConfig.default` — pass a custom config by calling `install` manually after `start` returns, or by setting `autoCaptureOptions` to exclude `.network` and then installing by hand.

| Field | Swift type | Default | Description |
| --- | --- | --- | --- |
| `ignoredHosts` | `Set<String>` | `[]` | Case-insensitive exact-match denylist. Hosts here never produce spans. |
| `allowedHosts` | `Set<String>` | `[]` | If non-empty, only these hosts produce spans (allowlist). Empty means "capture all". |
| `stripQueryStrings` | `Bool` | `true` | Remove query parameters from `url.full` before recording. Privacy-safe default. |
| `capturedResponseHeaders` | `Set<String>` | `["content-type"]` | Response headers to record under `http.response.header.<name>`. Case-insensitive. |
| `capturedRequestHeaders` | `Set<String>` | `[]` | Request headers to record. `Authorization`, `Cookie`, and other sensitive headers are always refused even if listed here. |
| `errorStatusThreshold` | `Int` | `500` | Status codes `>=` this mark the span as failed. Set to `400` to treat client errors as failures too. |
| `propagateTraceContext` | `Bool` | `false` | Inject W3C `traceparent` on outgoing requests. Opt-in — enable once the backend is correlating. |

Example:

```swift
let netConfig = NetworkConfig(
    ignoredHosts: ["analytics.third-party.example"],
    allowedHosts: [],
    stripQueryStrings: true,
    capturedResponseHeaders: ["content-type", "x-request-id"],
    capturedRequestHeaders: [],
    errorStatusThreshold: 400,
    propagateTraceContext: true
)

if let tracer = mobile.tracer {
    NetworkInstrumentation.shared.install(tracer: tracer, config: netConfig)
}
```

## Full example

```swift
import OTelMobileSDK
import NetworkInstrumentation

let config = MobileConfig(
    serviceName: "otel-ios-astronomy-shop",
    serviceVersion: "2.3.1",
    endpoint: "https://ingress.YOUR-DOMAIN.dash0.com:4318",
    authToken: "Bearer \(token)",
    exportMode: .hybrid,
    bufferConfig: BufferConfig(ramEvents: 10_000, diskMb: 50, retentionHours: 24),
    privacyConfig: .production,
    autoCaptureOptions: [.network, .lifecycle, .errors],
    extraHeaders: ["Dash0-Dataset": "otel-mobile"]
    // enablePolicyPolling defaults to true → remote kill switch works out of the box.
    // allowInsecureTransport defaults to false → cleartext endpoints rejected.
)

let mobile = try OTelMobile.start(config: config)

// Override NetworkConfig with a tuned version post-start.
if let tracer = mobile.tracer {
    NetworkInstrumentation.shared.install(
        tracer: tracer,
        config: NetworkConfig(
            allowedHosts: ["api.my-app.com"],
            stripQueryStrings: true,
            capturedResponseHeaders: ["content-type", "x-request-id"],
            errorStatusThreshold: 400,
            propagateTraceContext: true
        )
    )
}

// Manual opt-in to device stats.
if let meter = mobile.meter {
    mobile.deviceStats.start(meter: meter, intervalSeconds: 5)
}
```

## Related Documentation

- [iOS SDK Integration Guide](IOS_SDK_GUIDE.md)
- [iOS Demo Runbook](HOW_TO_DEMO_IOS.md)
- [Android Configuration Reference](CONFIGURATION.md) — Sibling doc for Android parity.
