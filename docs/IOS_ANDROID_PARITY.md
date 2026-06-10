# Android &harr; iOS Feature Parity Matrix

Updated: 2026-06-10 (v0.2.0-alpha doc audit — reconciled the stale "gaps" sections against shipped iOS code: DiskLogBuffer, PolicyEvaluator runtime, DynamicSampler, SessionManager rotation, and the OTLP/gRPC factory all ship today)
iOS branch: merged to `main` (was `iPhone`)
Android SDK module: `otel-android-mobile/` + 21 instrumentation submodules
iOS SDK package: `otel-ios-mobile/` (SwiftPM, 9 library products)

## Current state

**What's defensibly at parity:**

- All 6 iOS-applicable auto-instrumentation modules ship and auto-install:
  Errors (+ NSException chain-through), Lifecycle, Network (URLProtocol),
  Screen (SwiftUI ViewModifier default, UIKit swizzle opt-in), Freeze
  (DispatchSourceTimer main-thread watchdog), Vitals (app.start + ui.jank +
  memory warnings).
- SwiftUI ViewModifiers: `.trackScreen(Name)`, `.trackTaps(target:)`,
  `.trackScrolls(target:)`, `.trackTextInput(target:)`.
- Dual-tier buffer: RAMEventBuffer (actor + Deque + size caps) + DiskLogBuffer
  (sqlite3 actor + WAL + startup recovery). Crash-safe claim closed.
- Full policy pipeline: DSL v2 parser (14 Android-parity tests) + PolicyEvaluator
  runtime (17 tests) + ConfigPoller + wiring through MobileLogRecordProcessor.onEmit
  with flushWindow on match. Five integration tests prove end-to-end firing.
- SessionManager with UUID rotation + UserDefaults persistence (replaces the
  earlier StaticSessionProvider which never rotated).
- OTLP/HTTP exporter (auto-wired) + OTLP/gRPC factory (opt-in).
- Resource attributes: os.type=darwin, os.name=iOS, os.version, device.manufacturer=Apple,
  device.model.identifier, device.id, service.*, telemetry.sdk.* — matches
  Android's resource shape line-for-line.
- Safety: async-signal-safe signal handler, zero `fatalError`/`try!` in SDK
  source (CI-enforced), buffer memory caps, NetworkInstrumentation thread-safety,
  demo off-main-thread.
- CI: .github/workflows/ios-tests.yml — host tests + iPhone 16 simulator +
  both demo app builds + static safety audit.
- Documentation: IOS_SDK_GUIDE, IOS_CONFIGURATION, HOW_TO_DEMO_IOS,
  IOS_ANDROID_PARITY, SDK_SAFETY, IOS_CRASH_REPORTING (PLCrashReporter
  integration guide), otel-ios-mobile/CLAUDE.md.
- Dual-platform demo: scripts/demo/run-dual-platform-demo.sh boots Android
  emulator + iPhone simulator concurrently, both in auto-emit mode.

**What's explicitly not iOS-applicable** (Android-specific APIs, not gaps):

- compose-click (Jetpack Compose), back-press, screen-orientation, timber,
  database, file-io, amplify-datastore, system-events, debug-widget.

**Honest remaining deferred work:**

- Screenshot/Wireframe **ship on iOS** (`ScreenshotInstrumentation`,
  `WireframeInstrumentation`) but, like Android, are **default OFF** behind the
  capture-consent gate (`AutoCaptureOptions.default` excludes `.screenshot` /
  `.wireframe`; pass `.all` or add them explicitly to opt in). Privacy design
  spec: [`docs/design/screenshot-wireframe-privacy.md`](./design/screenshot-wireframe-privacy.md).
- PLCrashReporter as optional SPM dep — documented as integration guide
  rather than bundled dep (license/conflict/size concerns — customers with
  Sentry/Firebase already have a crash reporter).
- Real-device validation — simulator-only to date. Playbook at
  [`docs/IOS_REAL_DEVICE_VALIDATION.md`](./IOS_REAL_DEVICE_VALIDATION.md).
- Android's 28 `validate-us0XX-*.sh` scenario scripts — 6 ported to iOS so far
  (see `### scripts/test/` below); the rest are tracked for future sessions.
- Upstream `opentelemetry-swift-core` `ViewRegistry.findViews` bug — we ship a
  workaround with regression test; filed issue draft at
  [`docs/upstream/opentelemetry-swift-core-viewregistry.md`](./upstream/opentelemetry-swift-core-viewregistry.md).

## Instrumentation modules

Android modules live under [`instrumentation/`](../instrumentation/). iOS modules
live under [`otel-ios-mobile/Sources/`](../otel-ios-mobile/Sources/).

| Module | Android | iOS | Status | Notes |
|---|---|---|---|---|
| amplify-datastore | &#9989; module | &#10060; | not-started | AWS Amplify DataStore instrumentation |
| back-press | &#9989; module | &#10060; | not-started | Android hardware back button; no iOS analog |
| compose-click | &#9989; module | &#10060; | not-started | Jetpack Compose click modifier; SwiftUI gesture ViewModifier planned |
| database | &#9989; module | &#10060; | not-started | Room / SQLite instrumentation |
| debug-widget | &#9989; module | &#10060; | not-started | In-app overlay widget for buffer stats / export status |
| errors | &#9989; module + [tests](../otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/errors/) | &#9989; [ErrorsInstrumentation.swift](../otel-ios-mobile/Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift) | shipped | iOS covers `NSSetUncaughtExceptionHandler` + POSIX signals; persists marker file and emits `app.crash` on next launch |
| file-io | &#9989; module | &#10060; | not-started | Java file I/O instrumentation |
| freeze | &#9989; module ([tests](../instrumentation/freeze/src)) | &#9989; [FreezeInstrumentation.swift](../otel-ios-mobile/Sources/FreezeInstrumentation/FreezeInstrumentation.swift) | shipped | iOS: `DispatchSourceTimer` watchdog; posts a zero-cost main-queue ack every `pingIntervalMs`; emits `ui.freeze` if ack not drained within `thresholdMs` |
| lifecycle | &#9989; module | &#9989; [LifecycleInstrumentation.swift](../otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift) | shipped | iOS: `app.launch`, `app.foreground`, `app.background`, `app.will_terminate`, `app.memory_warning` |
| network | &#9989; OkHttp interceptor + [NetworkConfig](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/) | &#9989; [URLProtocol + URLSessionConfig swizzle](../otel-ios-mobile/Sources/NetworkInstrumentation/) | shipped | iOS: `OTelURLProtocol` + `URLSessionConfigurationSwizzle`; NetworkConfig parity tests |
| screen | &#9989; module | &#9989; [ScreenInstrumentation.swift](../otel-ios-mobile/Sources/ScreenInstrumentation/ScreenInstrumentation.swift) + [SwiftUIScreenModifiers.swift](../otel-ios-mobile/Sources/ScreenInstrumentation/SwiftUIScreenModifiers.swift) | shipped | iOS default: SwiftUI `.trackScreen("Name")` ViewModifier path auto-installed by `OTelMobile.start()`. UIKit `viewDidAppear`/`viewDidDisappear` swizzle is opt-in via `enableUIKitSwizzle: true` (races with SwiftUI hosting controllers otherwise) |
| screen-orientation | &#9989; module | &#10060; | not-started | Android-specific config changes; iOS UIDevice orientation TBD |
| screenshot | &#9989; module | &#9989; [ScreenshotInstrumentation.swift](../otel-ios-mobile/Sources/ScreenshotInstrumentation/ScreenshotInstrumentation.swift) | shipped | iOS uses `UIGraphicsImageRenderer` + `layer.render(in:)` against the key window. `ScreenshotConfig` parity: `captureOnScreenView` / `captureOnError` / `captureOnPolicyMatch` flags all present. `OTelMobile.start` honors `MobileConfig.screenshotConfig` so consumers can override defaults via init or `otel-config.json` `incubating.screenshot`. |
| scroll | &#9989; module | &#10060; | not-started | RecyclerView OnScrollListener; iOS UIScrollView delegate planned |
| system-events | &#9989; module | &#10060; | not-started | BroadcastReceiver-based; maps to NotificationCenter on iOS |
| tap | &#9989; module (18+ tests) | &#10060; | not-started | SwiftUI gesture capture via `.simultaneousGesture` or ViewModifier planned |
| text-input | &#9989; module | &#10060; | not-started | EditText focus loss; iOS UITextField delegate planned |
| timber | &#9989; module | N/A | N/A | Timber is a Kotlin/Android logging library; OSLog bridge would be the iOS analog |
| vitals | &#9989; (app-start, jank, meter gauges) | &#9989; [VitalsInstrumentation.swift](../otel-ios-mobile/Sources/VitalsInstrumentation/VitalsInstrumentation.swift) + [DeviceStatsCollector](../otel-ios-mobile/Sources/OTelMobileSDK/Metrics/DeviceStatsCollector.swift) | shipped | iOS: `CADisplayLink` frame-time watcher for `ui.jank`, `app.start` duration, `app.memory_warning` on UIApplication pressure. `DeviceStatsCollector` provides continuous gauges separately |
| wireframe | &#9989; module | &#9989; [WireframeInstrumentation.swift](../otel-ios-mobile/Sources/WireframeInstrumentation/WireframeInstrumentation.swift) | shipped | iOS walks `UIView` tree → JSON, same shape as Android. `WireframeConfig` parity: all trigger flags + `dedupeByContentHash` (SHA-256, emits lightweight `ui.wireframe.ref` with `mobile.wireframe.id` on repeats). |

**Module count:** Android 21 (incl. 2 N/A on iOS) &middot; iOS 9 library products (errors, lifecycle, network, screen, freeze, vitals, screenshot, wireframe + SwiftUI modifiers bundled with screen)

**Cross-cutting capture features (added 2026-05-14):**

| Feature | Android | iOS | Notes |
|---|---|---|---|
| Capture on policy match | &#9989; `policyMatchHook` on `MobileLogRecordProcessor` | &#9989; same field, mirrored in [`MobileLogRecordProcessor.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift) | Every buffered-export policy fire (crash-recovery, ui-freeze, http-error) triggers a screenshot + wireframe with `policy_<id>` trigger label. Each module gate-checks its own `captureOnPolicyMatch` flag. |
| Wireframe content-hash dedup | &#9989; SHA-256 emit-path dedup | &#9989; same logic using `CryptoKit.SHA256` | Repeat captures with identical content emit `ui.wireframe.ref` carrying `mobile.wireframe.id` instead of the 1–5KB payload. |
| `otel-config.json incubating.{screenshot,wireframe}` block | &#9989; `ConfigManager.kt` parses nested object | &#9989; `ShopBootstrap.swift` parses `IncubatingConfig` and constructs `MobileConfig.screenshotConfig` / `wireframeConfig` | Both platforms accept the same JSON shape with the same field names. |

## SDK core features

Sources: Android
[`otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/`](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/)
&middot; iOS
[`otel-ios-mobile/Sources/OTelMobileSDK/`](../otel-ios-mobile/Sources/OTelMobileSDK/)

| Feature | Android | iOS | Status | Notes |
|---|---|---|---|---|
| Fluent builder / DSL | &#9989; `MobileOtelDsl`, `BufferingDsl`, `ExportDsl`, `InstrumentationsDsl`, etc. | &#128993; Flat `MobileConfig` init | partial | iOS has one-shot `MobileConfig(...)` init; no nested DSL |
| RAM buffer | &#9989; `ConcurrentLinkedQueue` (5000 evts) | &#9989; [`RAMEventBuffer`](../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/RAMEventBuffer.swift) (`actor`, `Deque`) | shipped | |
| Disk buffer (dual-tier) | &#9989; `DiskLogBuffer` Room/SQLite v4, 50MB, 24h TTL | &#9989; [`DiskLogBuffer.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskLogBuffer.swift) | shipped | iOS: `sqlite3` actor, WAL mode, size + TTL caps, startup recovery path drains pending events |
| Crash-safe flush / seqId dedup | &#9989; `BufferedEvent.seqId` + every-2s RAM→disk mirror task (`persistRamToDiskForCrashSafety`) + `persistForCrash()` writing only events not yet mirrored | &#9989; [`SequenceCounter.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/SequenceCounter.swift) + crash marker in `ErrorsInstrumentation` + on-failure persist in `forceFlushBuffered` | shipped (intentionally divergent mechanisms) | **Architectural divergence**, not drift. Both platforms preserve buffered events across a crash, but the *when* differs: **Android** preemptively mirrors RAM to disk every 2 seconds (so a crash with zero time-to-react still survives) and `persistForCrash()` writes only un-mirrored events to avoid duplicate disk rows (fixed at `c0e305e`). **iOS** writes to disk only when an export *fails* (RAM-originated events survive via the on-failure persist branch in `forceFlushBuffered`); crash recovery relies on the crash marker file + next-launch drain. The iOS path has no analog of the Android dup-row bug because no preemptive mirror exists. RN inherits each platform's mechanism. |
| MobileLogRecordProcessor | &#9989; | &#9989; [MobileLogRecordProcessor.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift) | shipped | |
| RetryableExporter | &#9989; | &#9989; [`RetryableExporter.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Export/RetryableExporter.swift) | shipped | iOS: wraps `LogRecordExporter`, exponential backoff (1s→60s, 3 retries), publishes via `ExportStatusManager`. Auth-error detection deferred (OTel-Swift's `ExportResult` is binary success/failure, no error type) |
| ExportStatusManager | &#9989; | &#9989; [`ExportStatus.swift`](../otel-ios-mobile/Sources/OTelMobileCore/Export/ExportStatus.swift) | shipped | Per-instance + `.shared` singleton; snapshot-then-iterate fan-out; 4 status variants (`success`/`failed`/`authError`/`retrying`) with Android-equivalent payloads |
| OTLP/gRPC export | &#9989; | &#9989; [`OTLPExporterFactory.makeGrpcLogExporter`/`makeGrpcTraceExporter`](../otel-ios-mobile/Sources/OTelMobileSDK/Export/OTLPExporterFactory.swift) | shipped | Opt-in gRPC via `swift-grpc` + `NIO`. HTTP remains the default auto-wired path |
| Selective flush (flushWindow) | &#9989; `flushWindow(minutes)` | &#9989; `RAMEventBuffer.flushWindow(lastMs:)` + `OTelMobile.flushWindow(minutes:)` | shipped | |
| Export modes (CONDITIONAL / CONTINUOUS / HYBRID) | &#9989; | &#9989; [`ExportMode.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Config/ExportMode.swift) | shipped | Enum parity; runtime behavior not exercised (no evaluator) |
| Policy DSL v2 parser | &#9989; `PolicyEvaluator.parseConfigV2` | &#9989; [`PolicyParser.parseConfigV2`](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift) | shipped | 14 behavioral-parity tests copied verbatim from Android JSON bodies |
| Policy DSL v1 compiler | &#9989; `PolicyEvaluatorV1CompilerTest` | &#10060; | not-started | Auto-detect (v1 vs v2) missing on iOS |
| Policy evaluator runtime | &#9989; `PolicyEvaluator` trigger matching | &#9989; [`PolicyEvaluator.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift) + [`ConfigPoller.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/ConfigPoller.swift) | shipped | Full closing loop: `ConfigPoller` → `PolicyEvaluator.updatePolicies` → `onEmit` conditional `flushWindow` → OTLP export. 17 evaluator tests; 5 end-to-end integration tests |
| Dynamic sampler | &#9989; `DynamicSampler`, `SamplerFactory`, `SamplingConfig` | &#9989; [`DynamicSampler.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Sampling/DynamicSampler.swift) + [`SamplerFactory.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Sampling/SamplerFactory.swift) + [`SamplingConfig.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Sampling/SamplingConfig.swift) | shipped | iOS port: `Sampler` conformance, `traceId.idLo`-keyed decisions (no hex parse), runtime `setSamplingRate(_:durationMinutes:)`, `page.*` + `app.startup` always sampled at high-priority rate. Wired into `OTelMobile.start` via new `MobileConfig.samplingConfig` (default `.dynamic(0.1, 1.0)`). 27 tests |
| Session manager | &#9989; `SessionManager` + `SessionConfig` | &#9989; [`SessionManager.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Session/SessionManager.swift) | shipped | iOS: UUID with 15-min inactivity rotation, `UserDefaults` persistence across launches |
| User identity | &#9989; `UserIdentity` | &#10060; | not-started | |
| Boot tracker | &#9989; `BootTracker` (reads `/proc/sys/kernel/random/boot_id`) | &#9989; [`BootTracker.swift`](../otel-ios-mobile/Sources/OTelMobileCore/Identity/BootTracker.swift) | shipped | iOS: `sysctlbyname("kern.boottime", ...)` → hex-encoded `<sec>-<usec>`. Falls back to per-process UUID on sysctl failure. 4 tests |
| PII scrubber | &#9989; `PiiScrubberTest` (40 tests) | &#9989; [`PiiScrubber.swift`](../otel-ios-mobile/Sources/OTelMobileCore/Privacy/PiiScrubber.swift) (40 tests, `PiiScrubberTests`) | shipped | 8 public methods (`scrubUrl`, `scrubDeepLink`, `scrubExceptionMessage`, `scrubStackTrace`, `scrubText`, `containsPii`, `isValidAttributeKey`, `scrubAttributes`); same `[EMAIL]` / `[PHONE]` / `[CREDIT_CARD]` / `[SSN]` / `{app-container}/` / `{uuid}` / `{id}` / `[REDACTED]` tokens. Wired into `ErrorsInstrumentation` (recordError + crash-recovery read path) and `NetworkInstrumentation` (`url.full`) |
| Context snapshot provider | &#9989; `ContextSnapshotProvider` | &#9989; [`ContextSnapshotProvider.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/ContextSnapshotProvider.swift) | shipped | iOS: `NWPathMonitor` (network), `UIDevice` (battery state + device class), `Locale`/`TimeZone`, `ProcessInfo` (OS version). 10s TTL cache |
| Device health monitor | &#9989; `DeviceHealthMonitor` | &#9989; [`DeviceHealthMonitor.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Predictive/DeviceHealthMonitor.swift) | shipped | iOS: `mach_task_basic_info` for memory, `UIDevice` for battery, `ProcessInfo.thermalState` (4 levels vs Android's 7), one-step history for drain-rate deltas |
| On-device predictor | &#9989; `OnDevicePredictor` | &#9989; [`OnDevicePredictor.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Predictive/OnDevicePredictor.swift) | shipped | Rule-based: crash / network-loss / perf-degradation / battery-drain risks, clamped 0..1, 20-snapshot history + network-event deque, 8-test regression suite |
| Predictive export policy | &#9989; `PredictiveExportPolicy` | &#9989; [`PredictiveExportPolicy.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Predictive/PredictiveExportPolicy.swift) | shipped | `DispatchSourceTimer` on utility queue; emits `prediction.cycle` DEBUG + `prediction.high_risk_alert` WARN; triggers `flushWindow` on threshold crossings. Opt-in via `MobileConfig.enablePredictiveExport` |
| Privacy config | &#9989; `PrivacyConfig`, `PrivacyMode`, `PrivacyUtils` | &#128993; [`PrivacyConfig.swift`](../otel-ios-mobile/Sources/OTelMobileCore/PrivacyConfig.swift) + [`PiiScrubber.swift`](../otel-ios-mobile/Sources/OTelMobileCore/Privacy/PiiScrubber.swift) | partial | `PrivacyConfig` struct + `PiiScrubber` redaction pipeline shipped (Phase 1.1). `PrivacyMode` presets still missing |
| Resource builder | &#9989; | &#9989; [`ResourceBuilder.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Resource/ResourceBuilder.swift) | shipped | |
| EnrichingLogRecordExporter | &#9989; | &#10060; | not-started | |
| Auto-capture options | &#9989; `AutoCaptureOptions` + session/recovery/flush trackers | &#128993; [`AutoCaptureOptions.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift) | partial | Option set only; wires network/lifecycle/errors; no recovery/session tracker |
| Fleet alerts | &#9989; `FleetAlert`, `FleetAlertHandler`, `FleetAlertDeduplicator` | &#9989; [`FleetAlert.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Fleet/FleetAlert.swift) + [`FleetAlertHandler.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Fleet/FleetAlertHandler.swift) + [`FleetAlertDeduplicator.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Fleet/FleetAlertDeduplicator.swift) | shipped | `UserDefaults`-backed dedup (replaces SharedPreferences), rolling 1h rate-limit window (max 5 alerts), privacy gates, 9-test regression suite. Host app feeds alerts via `mobile.fleetAlertHandler.handle(alert)` |
| Log tailing | &#9989; `LogTailBuffer`, `LogTailingConfig` | &#10060; | not-started | |
| Predictive export | &#9989; `OnDevicePredictor`, `PredictiveExportPolicy`, `DeviceHealthMonitor` | &#9989; see Context/Health/Predictor/Policy rows above | shipped | Full predictive stack ported: DeviceHealthMonitor + OnDevicePredictor + PredictiveExportPolicy, opt-in via `MobileConfig.enablePredictiveExport` |
| Device metrics collector | &#9989; `DeviceMetricsCollector` | &#9989; [`DeviceStatsCollector`](../otel-ios-mobile/Sources/OTelMobileSDK/Metrics/DeviceStatsCollector.swift) | shipped | Auto-started when `AutoCaptureOptions.deviceStats` is enabled (default, 15s cadence). Emits memory / battery / thermal / storage gauges |
| Jank detector | &#9989; | &#10060; | not-started | iOS CADisplayLink-based analog TBD |
| App-start instrumentation | &#9989; `AppStartInstrumentation` | &#9989; [`AppStartInstrumentation.swift`](../otel-ios-mobile/Sources/VitalsInstrumentation/AppStartInstrumentation.swift) | shipped | iOS port: emits `app.startup` (DynamicSampler high-priority), `app.start.cold`, `app.start.warm` spans with Android-parity `mobile.app.start.*` attributes. Process-start time read via `sysctl(KERN_PROC_PID)` — same accuracy guarantee as Android's `Process.getStartElapsedRealtime()`. Warm-start tracked via `UIApplication.didEnterBackgroundNotification` / `didBecomeActiveNotification`. 7 tests |
| Coroutine / async error capture | &#9989; | &#10060; | not-started | Swift concurrency unhandled-error capture TBD |
| Traces provider | &#9989; | &#9989; `TracerProviderSdk` in `OTelMobile.start(config:)` | shipped | |
| Metrics provider (periodic reader) | &#9989; | &#9989; `PeriodicMetricReaderBuilder` (10s cadence) | shipped | |
| Three-signal shared resource | &#9989; | &#9989; | shipped | |
| Config polling / bundled config | &#9989; `BUNDLED_CONFIG.md`, SR-018 v2 negotiation | &#128993; `pollingIntervalSeconds` field exists | partial | Polling field carried on config; no polling loop implementation observed |
| Instrumentation registry | &#9989; `InstrumentationRegistry` | &#9989; [`InstrumentationRegistry.swift`](../otel-ios-mobile/Sources/OTelMobileCore/InstrumentationRegistry.swift) | partial | Protocol + registry present; no `install()` fan-out in `OTelMobile.start(config:)` — wiring is inline |
| Touch event hub | &#9989; `WindowEventHub` + `WindowEventHubInstaller` | &#128993; [`TouchEventHub.swift`](../otel-ios-mobile/Sources/OTelMobileCore/TouchEventHub.swift) | partial | Hub type exists; no installer wired (no tap/scroll/back listeners to dispatch to) |
| Rate limiter | &#9989; `RateLimiter` | &#10060; | not-started | |

## Test coverage

Android file counts via `@Test`/`fun test` (55 files, **~980 test fns**).
iOS file counts via `@Test` (15 suites, **114 test fns**).

| Area | Android | iOS | Parity |
|---|---|---|---|
| Policy V2 parse | 14 (`PolicyEvaluatorV2ParseTest`) | 14 (`PolicyParserV2Tests`) | **100%** (behavioral parity — JSON bodies copied verbatim) |
| Policy DSL v2 models | n/a (embedded) | 15 (`DSLv2ModelsTests`) | **100%+** |
| Policy V1 compiler | 9 | 0 | **0%** |
| Policy evaluator runtime | 29 + 38 (condition) + 35 (geo) + 14 (security) = **116** | 0 | **0%** |
| Buffer (RAM) | 17 (`BufferSystemComprehensive`) + 18 (`BufferCrashPath`) + 26 (`DiskLogBuffer`) + 28 (`MobileLogRecordProcessor`) = **89** | 5 (`RAMEventBuffer`) + 4 (`MobileLogRecordProcessor`) = **9** | **~10%** |
| Export modes | 18 + 19 (user-journey) + 7 (equivalence) = **44** | 10 (`OTLPExporterFactory`) | **23%** |
| Retryable exporter | 7 | 7 (`RetryableExporterTests`) + 7 (`ExportStatusManagerTests`) = **14** | **200%** (overshoots Android because the iOS port covers the manager + exporter in separate suites) |
| Config (MobileConfig) | 18 + 24 (security) + 6 (customizers) + 9 (DSL) = **57** | 5 (`MobileConfig`) | **9%** |
| Autocapture / Recovery / SessionTracker | 41 + 24 + 16 + 30 + 20 = **131** | 0 | **0%** |
| Network | 33 + 25 + 8 = **66** | 9 (`NetworkConfig`) | **14%** |
| Session | 19 + 7 = **26** | 12 (`SessionManagerTests`) | **46%** |
| PII / privacy | 40 (`PiiScrubberTest`) + 20 (`PrivacyUtilsTest`) = **60** | 40 (`PiiScrubberTests`) + 4 (`NetworkConfigTests` PII-routed wiring) + 1 (`CrashRecoveryTests` scrub-on-recovery) = **45** | **75%** |
| Errors | 36 + 29 = **65** | 14 (`ErrorsInstrumentationTests`) + 5 (`CrashRecoveryTests`) = **19** | **29%** |
| Vitals / jank | 8 | 0 | **0%** |
| Sampling | 30 + 17 = **47** | 8 (`SamplingConfigTests`) + 13 (`DynamicSamplerTests`) + 6 (`SamplerFactoryTests`) = **27** | **57%** |
| Fleet alerts | 12 | 0 | **0%** |
| Log tailing | 22 | 0 | **0%** |
| Device metrics | 27 | 0 | **0%** |
| Navigation | 23 | 0 | **0%** |
| Matrix / cross-cutting | 20+24+7+4+4+11+3+2+2+12+16 = **~105** | 0 | **0%** |
| Resource builder | n/a | 4 (`ResourceBuilderTests`) | iOS-only |
| Smoke | n/a | 2 | iOS-only |
| **Totals (approx)** | **~980** across 55 files | **139** across 19 suites | **~14%** |

## Scripts

Every Android-side script under `scripts/` with iOS equivalent status. Android scripts live in
[`scripts/`](../scripts/); iOS scripts in [`scripts/test/run-ios-tests.sh`](../scripts/test/run-ios-tests.sh),
[`scripts/demo/demo-control-center-ios.sh`](../scripts/demo/demo-control-center-ios.sh), and
[`otel-ios-mobile/run-tests.sh`](../otel-ios-mobile/run-tests.sh).

### `scripts/test/`

| Script | Android path | iOS path | Status |
|---|---|---|---|
| run-unit-tests.sh | `scripts/test/run-unit-tests.sh` | `scripts/test/run-ios-tests.sh` + `otel-ios-mobile/run-tests.sh` | shipped (separate invocations) |
| integration-test.sh | `scripts/test/integration-test.sh` | &#10060; | not-started |
| demo-control-center.sh | `scripts/test/demo-control-center.sh` | `scripts/demo/demo-control-center-ios.sh` | shipped |
| monkey-test.sh | `scripts/test/monkey-test.sh` | &#10060; | not-started |
| run-dash0-tests.sh | `scripts/test/run-dash0-tests.sh` | &#10060; | not-started |
| run-integration-tests.sh | `scripts/test/run-integration-tests.sh` | &#10060; | not-started |
| run-phase9-suite.sh | `scripts/test/run-phase9-suite.sh` | &#10060; | not-started |
| run-real-crash-test.sh | `scripts/test/run-real-crash-test.sh` | &#10060; | not-started |
| run-validated-tests.sh | `scripts/test/run-validated-tests.sh` | &#10060; | not-started |
| validate-crash-recovery.sh | `scripts/test/validate-crash-recovery.sh` | &#10060; | not-started |
| validate-dash0.sh | `scripts/test/validate-dash0.sh` | &#10060; | not-started |
| validate-telemetry.sh | `scripts/test/validate-telemetry.sh` | &#10060; | not-started |
| validate-us050-happy-path.sh | `scripts/test/validate-us050-happy-path.sh` | [`validate-ios-us050-happy-path.sh`](../scripts/test/validate-ios-us050-happy-path.sh) | shipped — logs + spans + metrics arrive from AstronomyShop auto-demo |
| validate-us051-browse-refresh.sh | `scripts/test/validate-us051-browse-refresh.sh` | &#10060; | not-started |
| validate-us052-network-error.sh | `scripts/test/validate-us052-network-error.sh` | &#10060; | not-started |
| validate-us053-get-directions.sh | `scripts/test/validate-us053-get-directions.sh` | &#10060; | not-started |
| validate-us054-multi-screen-nav.sh | `scripts/test/validate-us054-multi-screen-nav.sh` | &#10060; | not-started |
| validate-us055-form-input.sh | `scripts/test/validate-us055-form-input.sh` | &#10060; | not-started |
| validate-us056-session-lifecycle.sh | `scripts/test/validate-us056-session-lifecycle.sh` | &#10060; | not-started |
| validate-us057-background-foreground.sh | `scripts/test/validate-us057-background-foreground.sh` | [`validate-ios-us057-app-lifecycle.sh`](../scripts/test/validate-ios-us057-app-lifecycle.sh) | shipped (subset) — asserts lifecycle logs + WARN path; full bg/fg cycle needs human or companion-app |
| validate-us058-battery-drain.sh | `scripts/test/validate-us058-battery-drain.sh` | &#10060; | not-started |
| validate-us059-thermal-throttle.sh | `scripts/test/validate-us059-thermal-throttle.sh` | &#10060; | not-started |
| validate-us060-memory-pressure.sh | `scripts/test/validate-us060-memory-pressure.sh` | &#10060; | not-started |
| validate-us061-combined-stress.sh | `scripts/test/validate-us061-combined-stress.sh` | &#10060; | not-started |
| validate-us062-network-loss.sh | `scripts/test/validate-us062-network-loss.sh` | &#10060; | not-started |
| validate-us063-crash-flush.sh | `scripts/test/validate-us063-crash-flush.sh` | [`validate-ios-us063-crash-flush.sh`](../scripts/test/validate-ios-us063-crash-flush.sh) | shipped — kills app with SIGSEGV via simctl, relaunches, asserts app.crash log + pre-crash buffer drain |
| validate-us064-http-error-flush.sh | `scripts/test/validate-us064-http-error-flush.sh` | &#10060; | not-started |
| validate-us065-freeze-flush.sh | `scripts/test/validate-us065-freeze-flush.sh` | &#10060; | not-started |
| validate-us066-no-false-flush.sh | `scripts/test/validate-us066-no-false-flush.sh` | &#10060; | not-started |
| validate-us067-ram-overflow.sh | `scripts/test/validate-us067-ram-overflow.sh` | &#10060; | not-started |
| validate-us068-disk-ttl.sh | `scripts/test/validate-us068-disk-ttl.sh` | &#10060; | not-started (iOS `DiskLogBuffer` ships; scenario script not yet ported) |
| validate-us069-selective-flush.sh | `scripts/test/validate-us069-selective-flush.sh` | &#10060; | not-started |
| validate-us070-timestamp-monotonic.sh | `scripts/test/validate-us070-timestamp-monotonic.sh` | &#10060; | not-started |
| validate-us071-span-hierarchy.sh | `scripts/test/validate-us071-span-hierarchy.sh` | [`validate-ios-us071-span-hierarchy.sh`](../scripts/test/validate-ios-us071-span-hierarchy.sh) | shipped — asserts 15 checkout span names + ≥13 child spans with parent id |
| validate-us072-cross-signal.sh | `scripts/test/validate-us072-cross-signal.sh` | &#10060; | not-started |
| validate-us073-resource-attributes.sh | `scripts/test/validate-us073-resource-attributes.sh` | [`validate-ios-us073-resource-attributes.sh`](../scripts/test/validate-ios-us073-resource-attributes.sh) | shipped — asserts service/os/device/telemetry.sdk attributes on every log + os.name="iOS" |
| validate-us074-dynamic-sampling.sh | `scripts/test/validate-us074-dynamic-sampling.sh` | &#10060; | not-started |
| validate-us075-continuous-periodic.sh | `scripts/test/validate-us075-continuous-periodic.sh` | &#10060; | not-started |
| validate-us076-hybrid-mode.sh | `scripts/test/validate-us076-hybrid-mode.sh` | &#10060; | not-started |
| validate-us077-ci-readiness.sh | `scripts/test/validate-us077-ci-readiness.sh` | [`validate-ios-us077-ci-readiness.sh`](../scripts/test/validate-ios-us077-ci-readiness.sh) | shipped — toolchain, SDK unit tests, scenario-script syntax, config presence; no sim required |

### `scripts/demo/`

| Script | Android path | iOS path | Status |
|---|---|---|---|
| demo-control-center.sh | `scripts/test/demo-control-center.sh` | `scripts/demo/demo-control-center-ios.sh` | shipped |
| run-demo-backend.sh | `scripts/demo/run-demo-backend.sh` | reuses same backend | shared |
| run-demo-full.sh | `scripts/demo/run-demo-full.sh` | &#10060; | not-started |
| run-demo-quick.sh | `scripts/demo/run-demo-quick.sh` | &#10060; | not-started |
| run-demo-single.sh | `scripts/demo/run-demo-single.sh` | &#10060; | not-started |
| run-demo-scenarios.sh | `scripts/demo/run-demo-scenarios.sh` | &#10060; | not-started |
| run-dash0-scenarios.sh | `scripts/demo/run-dash0-scenarios.sh` | &#10060; | not-started |

### `scripts/e2e/`

| Script | Android path | iOS path | Status |
|---|---|---|---|
| run-e2e.sh | `scripts/e2e/run-e2e.sh` | &#10060; | not-started |
| dash0-e2e-test.sh | `scripts/e2e/dash0-e2e-test.sh` | &#10060; | not-started |
| validate-demo-telemetry.sh | `scripts/e2e/validate-demo-telemetry.sh` | &#10060; | not-started |

### `scripts/ci/`

| Script | Android path | iOS path | Status |
|---|---|---|---|
| run-tests.sh | `scripts/ci/run-tests.sh` | &#10060; | not-started (no iOS CI pipeline yet) |
| run-demo-ci.sh | `scripts/ci/run-demo-ci.sh` | &#10060; | not-started |

**Tally:** 43 Android `.sh` scripts &middot; 3 iOS-specific scripts (run-ios-tests.sh,
demo-control-center-ios.sh, otel-ios-mobile/run-tests.sh) &middot; parity **&lt;10%**.

## Documentation

Android docs live in [`docs/`](../docs/) (31 top-level `.md` files + subfolders).
**No iOS-specific user-facing docs exist.**

| Topic | Android doc | iOS doc | Status |
|---|---|---|---|
| SDK Integration Guide | [`docs/ANDROID_SDK_GUIDE.md`](ANDROID_SDK_GUIDE.md) | &#10060; | not-started |
| Quick Start | [`docs/QUICK_START.md`](QUICK_START.md) | &#10060; | not-started |
| Configuration | [`docs/CONFIGURATION.md`](CONFIGURATION.md), [`CONFIGURATION_GUIDE.md`](CONFIGURATION_GUIDE.md) | &#10060; | not-started |
| API reference | [`docs/API_REFERENCE.md`](API_REFERENCE.md) | &#10060; | not-started |
| Architecture overview | [`docs/ARCHITECTURE_OVERVIEW.md`](ARCHITECTURE_OVERVIEW.md), [`reference/ARCHITECTURE.md`](reference/ARCHITECTURE.md) | &#10060; | not-started |
| Buffering / tail-sampling | [`docs/BUFFERING_AND_TAIL_SAMPLING.md`](BUFFERING_AND_TAIL_SAMPLING.md) | &#10060; | not-started |
| Export modes | [`docs/EXPORT_MODES.md`](EXPORT_MODES.md) | &#10060; | not-started |
| Auto-instrumentation | [`docs/AUTO_INSTRUMENTATION.md`](AUTO_INSTRUMENTATION.md) | &#10060; | not-started |
| Sampling | [`docs/SAMPLING.md`](SAMPLING.md) | &#10060; | not-started |
| Device metrics | [`docs/DEVICE_METRICS.md`](DEVICE_METRICS.md) | &#10060; | not-started |
| Log tailing | [`docs/LOG_TAILING.md`](LOG_TAILING.md) | &#10060; | not-started |
| Offline resilience | [`docs/guides/OFFLINE_RESILIENCE.md`](guides/OFFLINE_RESILIENCE.md) | &#10060; | not-started |
| Testing strategy | [`docs/guides/TESTING_STRATEGY.md`](guides/TESTING_STRATEGY.md) | &#10060; | not-started |
| Testing guide | [`docs/TESTING_GUIDE.md`](TESTING_GUIDE.md) | &#10060; | not-started |
| Operations guide | [`docs/OPERATIONS_GUIDE.md`](OPERATIONS_GUIDE.md) | &#10060; | not-started |
| Developer guide | [`docs/DEVELOPER_GUIDE.md`](DEVELOPER_GUIDE.md) | &#10060; | not-started |
| Bundled config | [`docs/BUNDLED_CONFIG.md`](BUNDLED_CONFIG.md) | &#10060; | not-started |
| Authentication | [`docs/guides/AUTHENTICATION.md`](guides/AUTHENTICATION.md) | &#10060; | not-started |
| Deployment guide | [`docs/guides/DEPLOYMENT_GUIDE.md`](guides/DEPLOYMENT_GUIDE.md) | &#10060; | not-started |
| Tutorial (Android quickstart) | [`docs/guides/TUTORIAL_ANDROID_QUICKSTART.md`](guides/TUTORIAL_ANDROID_QUICKSTART.md) | &#10060; | not-started (matching TUTORIAL_IOS_QUICKSTART.md does not exist) |
| Troubleshooting | [`docs/TROUBLESHOOTING_GUIDE.md`](TROUBLESHOOTING_GUIDE.md) | &#10060; | not-started |
| Geo-device policy | [`docs/GEO_DEVICE_POLICY_EXTENSION.md`](GEO_DEVICE_POLICY_EXTENSION.md), [`E2E_GEO_DEVICE_VERIFICATION.md`](E2E_GEO_DEVICE_VERIFICATION.md) | &#10060; | not-started |
| Battle cards | [`docs/BATTLE_CARD.md`](BATTLE_CARD.md), vs-Datadog, vs-Splunk | &#10060; | not-started (iOS-specific claims not differentiated) |
| Internal iOS design | &mdash; | [`docs/superpowers/specs/2026-04-08-ios-sdk-port-design.md`](superpowers/specs/2026-04-08-ios-sdk-port-design.md), [`docs/superpowers/plans/2026-04-08-ios-sdk-sprint1.md`](superpowers/plans/2026-04-08-ios-sdk-sprint1.md) | internal-only |

**Doc parity score: 0%** for end-user docs; only internal "superpowers" spec/plan
files reference iOS.

## Demo apps

| App | Android path | iOS path | Feature coverage | Screens |
|---|---|---|---|---|
| Starter (single-screen interactive) | `examples/demo-app/` (full hospital scheduler demo) | [`examples/demo-app-ios-starter/`](../examples/demo-app-ios-starter/) | ~60% | **1** screen — `ContentView.swift`: resource, logs, traces, metrics, network, errors, device stats, counters sections; no navigation/journey, no multi-activity, no crash dialog, no airplane-mode toggle, no debug widget |
| Upstream Astronomy Shop | [`examples/upstream-demo-app/`](../examples/upstream-demo-app/) (OpenTelemetry community demo port) | [`examples/upstream-demo-app-ios/`](../examples/upstream-demo-app-ios/) | ~40% | **3 of 6** screens — ProductList, ProductDetail, Cart; **missing** RecommendedSection, About (`AboutActivity`, `AppFeaturesFragment`), ConfirmCrashPopUp, CheckoutInfo+Confirmation |

## Genuine remaining gaps (ranked)

> **Reconciliation note (v0.2.0-alpha):** the items below were rewritten after
> verifying every claim against the shipped iOS sources. The earlier "Critical
> gaps" / "Secondary gaps" / "Phase progress" sections claimed iOS lacked a disk
> buffer, a policy-evaluator runtime, dynamic sampling, session rotation, and
> gRPC — **all of those ship today** (see the SDK-core-features table above:
> [`DiskLogBuffer.swift`], [`PolicyEvaluator.swift`], [`DynamicSampler.swift`],
> [`SessionManager.swift`], and [`OTLPExporterFactory.makeGrpc*`]). Those stale
> bullets were leftovers from the abandoned `iPhone` branch and have been deleted.
> What remains below are the gaps the code *actually* still has.

1. **User identity.** Android has `UserIdentity` + `MobileOtel.identify(user)`.
   iOS has no `UserIdentity` type and no `identify` API — telemetry cannot be
   attached to an end-user identity on iOS.
2. **Log tailing.** Android ships `LogTailBuffer` / `LogTailingConfig`; iOS has
   no analog.
3. **`EnrichingLogRecordExporter`.** The Android enricher depends on a
   Dash0-proprietary `ContextSnapshotProvider` (geo + per-batch policy-match
   attribution). iOS has a `ContextSnapshotProvider` but no enriching exporter
   wired around it.
4. **Standalone jank detector + coroutine/async error capture.** iOS emits
   `ui.jank` from the Vitals `CADisplayLink` watcher, but there is no separate
   `JankDetector` analog, and no Swift-concurrency unhandled-error capture
   (Android hooks `CoroutineExceptionHandler`).
5. **Rate limiter (general-purpose).** Android exposes a reusable `RateLimiter`;
   iOS rate-limits errors inline (`ErrorRecordingThrottle`) but has no shared
   utility.
6. **Policy DSL v1 compiler.** iOS parses DSL **v2** at behavioral parity, but
   has no v1 compiler / auto-detect path (Android keeps v1 for back-compat).
7. **Instrumentation breadth.** Android ships 21 instrumentation modules; iOS
   ships 9 library products. The Android-only / not-yet-ported modules are
   enumerated in the *Instrumentation modules* table above (back-press,
   compose-click, database, file-io, amplify-datastore, screen-orientation,
   system-events, debug-widget, timber, plus standalone tap/scroll/text-input
   modules — on iOS those are folded into the SwiftUI ViewModifiers).
8. **Validation-suite + docs breadth.** Most Android `validate-us0XX-*.sh`
   scenario scripts and the user-facing docs (`ANDROID_SDK_GUIDE`,
   `QUICK_START`, etc.) have no iOS twin yet. See the *Scripts* and
   *Documentation* tables for the per-item status.

## What's ahead

- **User identity on iOS**: port `UserIdentity` + `identify(_:)` onto
  `SessionManager`.
- **Log tailing on iOS**: Swift analog of `LogTailBuffer` / `LogTailingConfig`.
- **`EnrichingLogRecordExporter` on iOS**: wrap the existing
  `ContextSnapshotProvider` in an enriching exporter for geo + policy-match
  attribution parity.
- **Instrumentation breadth**: a SwiftUI `.swiftGesture` modifier for
  tap/long-press/swipe in pure-SwiftUI apps, plus the remaining portable
  Android modules.
- **iOS user docs**: `IOS_SDK_GUIDE.md` exists; still missing
  `IOS_QUICK_START.md`, `TUTORIAL_IOS_QUICKSTART.md`, and iOS sections inside the
  cross-platform `CONFIGURATION.md` / `EXPORT_MODES.md` / `AUTO_INSTRUMENTATION.md`.
- **iOS validation-suite**: port more `validate-us0XX-*.sh` scenarios to
  `xcodebuild test` + simulator control (6 ported so far — see *Scripts*).
