# SDK Feature Parity Matrix

Tracks feature parity across the three Dash0 Mobile Observability SDKs. "React Native" refers to the JS layer; since RN is native-first, most checkmarks reflect that the native Android + iOS SDK provides the feature and the RN bridge marshals through.

Legend: ✅ shipped · 🟡 partial · ⬜ not yet · — not applicable

## Core pipeline

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| `start(config)` | ✅ | ✅ | ✅ |
| `log(name, attrs, severity)` | ✅ | ✅ | ✅ |
| `startSpan` / `endSpan` | ✅ | ✅ | ✅ |
| Async `span(name, fn)` helper | ✅ | ✅ | ✅ |
| `recordMetric(name, value, counter\|histogram\|gauge)` | ✅ | ✅ | ✅ |
| `flushWindow(minutes)` — selective flush | ✅ | ✅ | ✅ (forwards to native) |
| `shutdown()` with final flush | ✅ | ✅ | ✅ |

## Buffering & export

These live in the native SDK; the RN layer inherits them by construction.

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| RAM ring buffer | ✅ | ✅ | ✅ (native) |
| Disk spill (SQLite / Core Data) | ✅ | ✅ | ✅ (native) |
| Crash-safety mirror with `seqId` dedupe | ✅ | ✅ | ✅ (native) |
| OTLP/HTTP export (default since 0.2.0-alpha) | ✅ | ✅ | ✅ (native) |
| OTLP/gRPC export (opt-in `MobileConfig.protocol`) | ✅ | — (HTTP only) | ✅ (native, Android only) |
| Retry with exponential backoff | ✅ | ✅ | ✅ (native) |
| 50 ms JS→native bridge batching | — | — | ✅ |
| Sampling (`always_on`/`always_off`/`dynamic`) | ✅ default `dynamic(0.1)` | ✅ default `dynamic(0.1)` | ✅ via `StartConfig.sampling`, RN default `always_on` |

## Auto-instrumentation

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| HTTP request tracing | ✅ OkHttp | ✅ URLProtocol | ✅ Android: native OkHttp interceptor (JS XHR shim gated off) · iOS: JS fetch/XHR shim |
| W3C `traceparent` injection (mobile→backend stitch) | ✅ | ✅ | ✅ Android native interceptor · iOS native URLProtocol (opt-in) |
| Uncaught exception capture | ✅ | ✅ | ✅ ErrorUtils |
| Unhandled promise rejection | — | — | ✅ |
| App lifecycle (`app.foreground`/`app.background`/`app.start`) | ✅ | ✅ | ✅ native (Android `ProcessLifecycleOwner`, iOS `NotificationCenter`) — no JS flag |
| Session lifecycle | ✅ | ✅ | ✅ (native) |
| Activity/Screen navigation | ✅ | ✅ SwiftUI | 🟡 opt-in (React Navigation) |
| Tap events | ✅ `TapInstrumentation` | ✅ gesture recognizer | 🟡 opt-in (`withTapTelemetry`) |
| Scroll events | ✅ | ⬜ | ⬜ |
| Text input events | ✅ | ⬜ | ⬜ |
| Back-press events | ✅ | — | ⬜ |
| Vitals (memory, battery, jank, app-start) | ✅ | ✅ | ✅ (native) |
| Screenshot capture | ✅ (incubating) | ✅ `UIGraphicsImageRenderer` (text-redacted) | ✅ (native) |
| Wireframe capture | ✅ (incubating) | ✅ (text-redacted) | ✅ (native) |
| Capture on policy match | ✅ `policyMatchHook` | ✅ same hook | ✅ (native) |
| Wireframe content-hash dedup → `ui.wireframe.ref` | ✅ SHA-256 emit-path | ✅ same logic via `CryptoKit.SHA256` | ✅ (native) |
| Per-trigger `captureOn*` flags via `otel-config.json` | ✅ `ConfigManager.kt` | ✅ `ShopBootstrap.swift` parses `IncubatingConfig` | 🟡 TS types exist (`ScreenshotAutoCapture` / `WireframeAutoCapture`); bridge carries only `enabled` bit today |

## Policy DSL

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| DSL v1 parser | ✅ | ✅ | ✅ (native) |
| DSL v2 parser | ✅ | ✅ | ✅ (native) |
| Auto-version negotiation | ✅ | ✅ | ✅ (native) |
| Control-plane config polling | ✅ | ✅ | ✅ (native, opt-in via `enablePolicyPolling`) |
| Selective flush on trigger match | ✅ | ✅ | ✅ (native) |

## Privacy

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| PII scrubbing (URL query params, headers) | ✅ | ✅ | ✅ (native) |
| `captureLocation = false` by default | ✅ | ✅ | ✅ (native) |
| Network privacy presets (default/minimal/debug) | ✅ | ✅ | ✅ (native) |
| Screenshot/wireframe default OFF + consent gate (`shouldCapture`) | ✅ | ✅ (new in 0.2.0-alpha) | ✅ (native; OFF by default on RN) |
| Capture consent API (`shouldCapture`) | ✅ | ✅ | ✅ (native) |

## Security & transport (new in 0.2.0-alpha)

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| Remote kill switch + global sampling (`sdk.enabled` / `sample_rate`) | ✅ | ✅ | ✅ (native, transitive) |
| Remote-config polling default | ✅ | ✅ ON (new in 0.2.0-alpha) | ✅ (native, opt-in via `enablePolicyPolling`) |
| HTTPS enforcement (cleartext rejected unless `allowInsecureTransport`) | ✅ | ✅ | ✅ (native) |
| Certificate / public-key pinning | ✅ | ✅ | ✅ (native) |
| HMAC-signed remote config | ✅ | ✅ | ✅ (native) |
| Disk-buffer encryption at rest | ✅ SQLCipher + Keystore (new in 0.2.0-alpha) | ✅ `NSFileProtection` | ✅ (native) |

## OTel API compatibility

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| `OpenTelemetry`-idiomatic surface | ✅ | ✅ OTel-Swift | ✅ `otel` compat shim |
| Third-party OTel JS libs work unchanged | — | — | ✅ via shim |
| Resource attributes (`device.*`, `os.*`, `app.*`) | ✅ | ✅ | ✅ (native) |
| OTel semantic conventions v1.23+ | ✅ | ✅ | ✅ |

## Integrations

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| AWS Amplify DataStore | ✅ | ⬜ | ⬜ (follow-up epic) |
| MongoDB Realm | ⬜ | ⬜ | ⬜ (Innovapptive epic) |
| Room / Core Data | ✅ Room | ⬜ | — |

## Demo apps

| Artifact | Android | iOS | React Native |
|---------|:------:|:---:|:-----------------:|
| AstronomyShop demo | ✅ | ✅ | ✅ host projects scaffolded (`AstronomyShopRN/{ios,android}`) |
| 14-span checkout trace tree | ✅ | ✅ | ✅ (via ShopTelemetry) |
| AutoDemoDriver | ✅ (monkey) | ✅ (XCUITest) | ✅ (JS state machine: browse×3 → checkout → idle) |

## CI coverage

| Check | Android | iOS | React Native |
|---------|:------:|:---:|:-----------------:|
| Unit tests in CI | ✅ `test.yml` | ✅ `ios-tests.yml` (restored in 0.2.0-alpha) | ✅ `rn-tests.yml` (incl. compiled RN-iOS production sink) |
| Simulator/emulator build in CI | ✅ | ✅ | 🟡 device-mode E2E automation still being wired up |
| Secret-scan / safety audit | ✅ | ✅ | ✅ dependency-free secret-scan job (new in 0.2.0-alpha) |

## Gaps tracked as follow-ups

- **RN-SCROLL / RN-TEXT / RN-BACK** — scroll, text input, back-press auto-instrumentation parity
- **RN-BROWNFIELD-001** — brownfield sample (RN screens in native shell)
- **EXPO-001** — Expo *managed-workflow* config plugin (no prebuild). The bare/dev-client workflow already works; 0.2.0-alpha was hardened against a real Expo SDK 56 / RN 0.85 integration (incl. `expo/fetch` capture via the native Android interceptor).
- **REALM-001..N** — MongoDB Realm instrumentation (Innovapptive)
- **AMPLIFY-RN-001..N** — Amplify DataStore RN port
- **RN-SCREENSHOT-001** — screenshot/wireframe per-trigger flags need a bridge contract change so JS-side `autoCapture.{screenshot,wireframe}: {captureOnPolicyMatch: ...}` flows through to native. **Unblocked:** privacy design done — both native SDKs already redact text by default. As of 2026-05-14 the JS `ScreenshotAutoCapture` / `WireframeAutoCapture` types exist (`packages/react-native/src/bridge/types.ts`) but the bridge protocol carries only the `enabled` bit. Per-trigger flags must be set natively (Android: `MobileConfig.screenshotConfig` / `wireframeConfig`; iOS: same) until the bridge grows per-module options.

## See also

- [REACT_NATIVE_SDK_GUIDE.md](REACT_NATIVE_SDK_GUIDE.md)
- [HOW_TO_DEMO_RN.md](HOW_TO_DEMO_RN.md)
- [epics/REACT_NATIVE_EPIC.md](epics/REACT_NATIVE_EPIC.md)
