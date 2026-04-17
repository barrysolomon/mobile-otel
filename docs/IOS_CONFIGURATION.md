# iOS Configuration Reference

Field-by-field reference for `MobileConfig` and every sub-config used by the Dash0 iOS SDK. See [IOS_SDK_GUIDE.md](IOS_SDK_GUIDE.md) for narrative coverage and [MobileConfig.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/MobileConfig.swift) for the source of truth.

## Table of Contents

- [MobileConfig](#mobileconfig)
- [BufferConfig](#bufferconfig)
- [PrivacyConfig](#privacyconfig)
- [ExportMode](#exportmode)
- [AutoCaptureOptions](#autocaptureoptions)
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
- **Default:** `.conditional`.
- **What it does:** Declares intent for how the policy layer should flush. The DSL parser reads this; the production `start(config:)` path always installs the same batch exporters, so today the practical effect is on the selective-flush policy engine rather than on transport cadence.
- **When to change it:** Leave at `.conditional` unless you are instrumenting a known high-bandwidth telemetry stream (`.continuous`) or running both (`.hybrid`).
- **Example:** `exportMode: .conditional`.

### `bufferConfig`

- **Type:** `BufferConfig`.
- **Default:** `.default` — 5 000 RAM events, 50 MB on-disk budget (reserved), 24 h retention.
- **What it does:** Sizes the RAM event ring buffer backing `flushWindow(minutes:)`. The disk tier is parsed but not yet wired on iOS; the `diskMb` / `retentionHours` fields are reserved for the upcoming disk buffer.
- **When to change it:** Raise `ramEvents` for chatty apps (more events in the selective-flush window). Lower it on memory-constrained devices.
- **Example:** `bufferConfig: BufferConfig(ramEvents: 10_000, diskMb: 50, retentionHours: 24)`.

### `privacyConfig`

- **Type:** `PrivacyConfig` — see [PrivacyConfig](#privacyconfig).
- **Default:** `.default`.
- **What it does:** PII scrubbing, location capture, coordinate bucketing, and screenshot text redaction knobs. Consumed by future instrumentation modules; currently the flags are carried on the config and readable by downstream code.
- **When to change it:** Switch to `.production` for shipping builds if you plan to enable screenshot capture.
- **Example:** `privacyConfig: .production`.

### `autoCaptureOptions`

- **Type:** `AutoCaptureOptions` — see [AutoCaptureOptions](#autocaptureoptions).
- **Default:** `.all`.
- **What it does:** Selects which instrumentation modules `OTelMobile.start(config:)` installs automatically. Flags for modules that are not yet wired (tap, scroll, screen, freeze, vitals, textInput, screenshot, wireframe) are silently no-ops today.
- **When to change it:** Set to `.none` if you want to wire modules by hand, or tailor a custom combination (e.g. `[.network, .errors]`) for a lean demo.
- **Example:** `autoCaptureOptions: [.network, .lifecycle, .errors]`.

### `pollingIntervalSeconds`

- **Type:** `Int`
- **Default:** `300`.
- **What it does:** Reserved for the remote-config poller. Not wired into the production `start(config:)` path.
- **When to change it:** Not actionable today — treat it as reserved until the poller ships.
- **Example:** `pollingIntervalSeconds: 600`.

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
| `scrubPii` | `Bool` | `true` | When instrumentation modules wire through `PrivacyConfig`, matching values are scrubbed from log bodies and attributes (email, phone numbers, etc.). Consumed by future modules. |
| `captureLocation` | `Bool` | `false` | Whether to include coarse location metadata. Off by default — opt in consciously. |
| `bucketCoordinates` | `Bool` | `true` | If tap telemetry is emitted, quantize coordinates to a coarse grid so exact-pixel trajectories can't be reconstructed. |
| `redactTextOnScreenshots` | `Bool` | `false` | When screenshots eventually ship, overwrite detected text regions with solid rectangles. |

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
| `.conditional` | `"conditional"` | Selective flush only — nothing leaves the buffer unless a policy trigger fires or `flushWindow(minutes:)` is called. Battery-friendly default. |
| `.continuous` | `"continuous"` | Periodic export by the batch processors on their own schedule. |
| `.hybrid` | `"hybrid"` | Both periodic and trigger-based. |

This enum is `Codable` + `Sendable` and is used both by the DSL on the wire and by `MobileConfig`. The SDK's production `start(config:)` always installs the same OTel batch exporters regardless of which case is set — the mode's practical effect today is in the policy-engine layer and in how the control plane emits DSL.

## AutoCaptureOptions

Source: [AutoCaptureOptions.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift).

`OptionSet` with one bit per instrumentation module:

| Case | Bit | Wired today | Notes |
| --- | --- | --- | --- |
| `.tap` | `1 << 0` | No | Placeholder. |
| `.scroll` | `1 << 1` | No | Placeholder. |
| `.lifecycle` | `1 << 2` | **Yes** | Installs `LifecycleInstrumentation`. |
| `.screen` | `1 << 3` | Staged | `ScreenInstrumentation` exists but not auto-installed. Call `ScreenInstrumentation.shared.install(tracer:logger:)` manually. |
| `.network` | `1 << 4` | **Yes** | Installs `NetworkInstrumentation` with `NetworkConfig.default`. |
| `.errors` | `1 << 5` | **Yes** | Installs `ErrorsInstrumentation`. |
| `.freeze` | `1 << 6` | No | Placeholder. |
| `.vitals` | `1 << 7` | No | Placeholder — `DeviceStatsCollector` is the opt-in metrics path today. |
| `.textInput` | `1 << 8` | No | Placeholder. |
| `.screenshot` | `1 << 9` | No | Placeholder. |
| `.wireframe` | `1 << 10` | No | Placeholder. |

Convenience:

| Preset | Expands to |
| --- | --- |
| `.all` | `[.tap, .scroll, .lifecycle, .screen, .network, .errors, .freeze, .vitals, .textInput, .screenshot, .wireframe]` |
| `.none` | `[]` |

Examples:

```swift
// Everything (default).
autoCaptureOptions: .all

// Hand-pick three modules.
autoCaptureOptions: [.network, .lifecycle, .errors]

// Fully manual — wire modules yourself afterwards.
autoCaptureOptions: .none
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
    exportMode: .conditional,
    bufferConfig: BufferConfig(ramEvents: 10_000, diskMb: 50, retentionHours: 24),
    privacyConfig: .production,
    autoCaptureOptions: [.network, .lifecycle, .errors],
    pollingIntervalSeconds: 300,
    extraHeaders: ["Dash0-Dataset": "otel-mobile"]
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
