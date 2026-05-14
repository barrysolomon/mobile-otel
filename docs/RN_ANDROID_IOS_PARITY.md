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
| OTLP/gRPC export | ✅ | ✅ | ✅ (native) |
| Retry with exponential backoff | ✅ | ✅ | ✅ (native) |
| 50 ms JS→native bridge batching | — | — | ✅ |

## Auto-instrumentation

| Feature | Android | iOS | React Native (JS) |
|---------|:------:|:---:|:-----------------:|
| HTTP request tracing | ✅ OkHttp | ✅ URLProtocol | ✅ fetch + XHR |
| Uncaught exception capture | ✅ | ✅ | ✅ ErrorUtils |
| Unhandled promise rejection | — | — | ✅ |
| App lifecycle (fg/bg) | ✅ | ✅ | ✅ AppState |
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
| AstronomyShop demo | ✅ | ✅ | 🟡 src complete, host projects pending (RN-003) |
| 14-span checkout trace tree | ✅ | ✅ | ✅ (via ShopTelemetry) |
| AutoDemoDriver | ✅ (monkey) | ✅ (XCUITest) | ✅ (JS state machine) |

## CI coverage

| Check | Android | iOS | React Native |
|---------|:------:|:---:|:-----------------:|
| Unit tests in CI | ✅ `test.yml` | ✅ `ios-tests.yml` | ✅ `rn-tests.yml` |
| Simulator/emulator build in CI | ✅ | ✅ | ⬜ (pending RN-003) |
| Safety audit (forbidden patterns) | ✅ | ✅ | ⬜ |

## Gaps tracked as follow-ups

- **RN-SCROLL / RN-TEXT / RN-BACK** — scroll, text input, back-press auto-instrumentation parity
- **RN-BROWNFIELD-001** — brownfield sample (RN screens in native shell)
- **EXPO-001** — Expo config plugin for no-eject integration
- **REALM-001..N** — MongoDB Realm instrumentation (Innovapptive)
- **AMPLIFY-RN-001..N** — Amplify DataStore RN port
- **RN-SCREENSHOT-001** — screenshot/wireframe per-trigger flags need a bridge contract change so JS-side `autoCapture.{screenshot,wireframe}: {captureOnPolicyMatch: ...}` flows through to native. **Unblocked:** privacy design done — both native SDKs already redact text by default. As of 2026-05-14 the JS `ScreenshotAutoCapture` / `WireframeAutoCapture` types exist (`packages/react-native/src/bridge/types.ts`) but the bridge protocol carries only the `enabled` bit. Per-trigger flags must be set natively (Android: `MobileConfig.screenshotConfig` / `wireframeConfig`; iOS: same) until the bridge grows per-module options.

## See also

- [REACT_NATIVE_SDK_GUIDE.md](REACT_NATIVE_SDK_GUIDE.md)
- [HOW_TO_DEMO_RN.md](HOW_TO_DEMO_RN.md)
- [epics/REACT_NATIVE_EPIC.md](epics/REACT_NATIVE_EPIC.md)
