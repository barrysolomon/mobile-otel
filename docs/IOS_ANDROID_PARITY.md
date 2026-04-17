# Android &harr; iOS Feature Parity Matrix

Updated: 2026-04-17 (post-health-predictive-fleet session)
iOS branch: iPhone (25+ commits ahead of main, 134 tests)
Android SDK module: `otel-android-mobile/` + 20 instrumentation submodules
iOS SDK package: `otel-ios-mobile/` (SwiftPM, 8 products)

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

- Screenshot/Wireframe SwiftUI ViewModifiers — need privacy design before
  shipping. Design spec lives at [`docs/design/screenshot-wireframe-privacy.md`](./design/screenshot-wireframe-privacy.md).
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
| screenshot | &#9989; module | &#10060; | not-started | PixelCopy equivalent would be CGRenderContext / UIGraphicsImageRenderer |
| scroll | &#9989; module | &#10060; | not-started | RecyclerView OnScrollListener; iOS UIScrollView delegate planned |
| system-events | &#9989; module | &#10060; | not-started | BroadcastReceiver-based; maps to NotificationCenter on iOS |
| tap | &#9989; module (18+ tests) | &#10060; | not-started | SwiftUI gesture capture via `.simultaneousGesture` or ViewModifier planned |
| text-input | &#9989; module | &#10060; | not-started | EditText focus loss; iOS UITextField delegate planned |
| timber | &#9989; module | N/A | N/A | Timber is a Kotlin/Android logging library; OSLog bridge would be the iOS analog |
| vitals | &#9989; (app-start, jank, meter gauges) | &#9989; [VitalsInstrumentation.swift](../otel-ios-mobile/Sources/VitalsInstrumentation/VitalsInstrumentation.swift) + [DeviceStatsCollector](../otel-ios-mobile/Sources/OTelMobileSDK/Metrics/DeviceStatsCollector.swift) | shipped | iOS: `CADisplayLink` frame-time watcher for `ui.jank`, `app.start` duration, `app.memory_warning` on UIApplication pressure. `DeviceStatsCollector` provides continuous gauges separately |
| wireframe | &#9989; module | &#10060; | not-started | View-hierarchy JSON capture |

**Module count:** Android 20 (incl. 2 N/A on iOS) &middot; iOS 7 targets all functional (errors, lifecycle, network, screen, freeze, vitals, + SwiftUI modifiers bundled with screen)

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
| Crash-safe flush / seqId dedup | &#9989; `BufferedEvent.seqId`, SR-017 crash-safe mirror | &#9989; [`SequenceCounter.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/SequenceCounter.swift) + crash marker in `ErrorsInstrumentation` | shipped | iOS: monotonic `seqId` on every `BufferedEvent`; crash marker file persists buffer state across process death; next launch emits `app.crash` + drains |
| MobileLogRecordProcessor | &#9989; | &#9989; [MobileLogRecordProcessor.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift) | shipped | |
| RetryableExporter | &#9989; | &#10060; | not-started | |
| ExportStatusManager | &#9989; | &#10060; | not-started | |
| OTLP/gRPC export | &#9989; | &#9989; [`OTLPExporterFactory.makeGrpcLogExporter`/`makeGrpcTraceExporter`](../otel-ios-mobile/Sources/OTelMobileSDK/Export/OTLPExporterFactory.swift) | shipped | Opt-in gRPC via `swift-grpc` + `NIO`. HTTP remains the default auto-wired path |
| Selective flush (flushWindow) | &#9989; `flushWindow(minutes)` | &#9989; `RAMEventBuffer.flushWindow(lastMs:)` + `OTelMobile.flushWindow(minutes:)` | shipped | |
| Export modes (CONDITIONAL / CONTINUOUS / HYBRID) | &#9989; | &#9989; [`ExportMode.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Config/ExportMode.swift) | shipped | Enum parity; runtime behavior not exercised (no evaluator) |
| Policy DSL v2 parser | &#9989; `PolicyEvaluator.parseConfigV2` | &#9989; [`PolicyParser.parseConfigV2`](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift) | shipped | 14 behavioral-parity tests copied verbatim from Android JSON bodies |
| Policy DSL v1 compiler | &#9989; `PolicyEvaluatorV1CompilerTest` | &#10060; | not-started | Auto-detect (v1 vs v2) missing on iOS |
| Policy evaluator runtime | &#9989; `PolicyEvaluator` trigger matching | &#9989; [`PolicyEvaluator.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift) + [`ConfigPoller.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/ConfigPoller.swift) | shipped | Full closing loop: `ConfigPoller` → `PolicyEvaluator.updatePolicies` → `onEmit` conditional `flushWindow` → OTLP export. 17 evaluator tests; 5 end-to-end integration tests |
| Dynamic sampler | &#9989; `DynamicSampler`, `SamplerFactory`, `SamplingConfig` | &#10060; | not-started | |
| Session manager | &#9989; `SessionManager` + `SessionConfig` | &#9989; [`SessionManager.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Session/SessionManager.swift) | shipped | iOS: UUID with 15-min inactivity rotation, `UserDefaults` persistence across launches |
| User identity | &#9989; `UserIdentity` | &#10060; | not-started | |
| Boot tracker | &#9989; `BootTracker` | &#10060; | not-started | |
| PII scrubber | &#9989; `PiiScrubberTest` (40 tests) | &#10060; | not-started | |
| Context snapshot provider | &#9989; `ContextSnapshotProvider` | &#9989; [`ContextSnapshotProvider.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/ContextSnapshotProvider.swift) | shipped | iOS: `NWPathMonitor` (network), `UIDevice` (battery state + device class), `Locale`/`TimeZone`, `ProcessInfo` (OS version). 10s TTL cache |
| Device health monitor | &#9989; `DeviceHealthMonitor` | &#9989; [`DeviceHealthMonitor.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Predictive/DeviceHealthMonitor.swift) | shipped | iOS: `mach_task_basic_info` for memory, `UIDevice` for battery, `ProcessInfo.thermalState` (4 levels vs Android's 7), one-step history for drain-rate deltas |
| On-device predictor | &#9989; `OnDevicePredictor` | &#9989; [`OnDevicePredictor.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Predictive/OnDevicePredictor.swift) | shipped | Rule-based: crash / network-loss / perf-degradation / battery-drain risks, clamped 0..1, 20-snapshot history + network-event deque, 8-test regression suite |
| Predictive export policy | &#9989; `PredictiveExportPolicy` | &#9989; [`PredictiveExportPolicy.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Predictive/PredictiveExportPolicy.swift) | shipped | `DispatchSourceTimer` on utility queue; emits `prediction.cycle` DEBUG + `prediction.high_risk_alert` WARN; triggers `flushWindow` on threshold crossings. Opt-in via `MobileConfig.enablePredictiveExport` |
| Privacy config | &#9989; `PrivacyConfig`, `PrivacyMode`, `PrivacyUtils` | &#128993; [`PrivacyConfig.swift`](../otel-ios-mobile/Sources/OTelMobileCore/PrivacyConfig.swift) | partial | Struct exists; no privacy-presets / redaction pipeline |
| Resource builder | &#9989; | &#9989; [`ResourceBuilder.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Resource/ResourceBuilder.swift) | shipped | |
| EnrichingLogRecordExporter | &#9989; | &#10060; | not-started | |
| Auto-capture options | &#9989; `AutoCaptureOptions` + session/recovery/flush trackers | &#128993; [`AutoCaptureOptions.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Config/AutoCaptureOptions.swift) | partial | Option set only; wires network/lifecycle/errors; no recovery/session tracker |
| Fleet alerts | &#9989; `FleetAlert`, `FleetAlertHandler`, `FleetAlertDeduplicator` | &#9989; [`FleetAlert.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Fleet/FleetAlert.swift) + [`FleetAlertHandler.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Fleet/FleetAlertHandler.swift) + [`FleetAlertDeduplicator.swift`](../otel-ios-mobile/Sources/OTelMobileSDK/Fleet/FleetAlertDeduplicator.swift) | shipped | `UserDefaults`-backed dedup (replaces SharedPreferences), rolling 1h rate-limit window (max 5 alerts), privacy gates, 9-test regression suite. Host app feeds alerts via `mobile.fleetAlertHandler.handle(alert)` |
| Log tailing | &#9989; `LogTailBuffer`, `LogTailingConfig` | &#10060; | not-started | |
| Predictive export | &#9989; `OnDevicePredictor`, `PredictiveExportPolicy`, `DeviceHealthMonitor` | &#9989; see Context/Health/Predictor/Policy rows above | shipped | Full predictive stack ported: DeviceHealthMonitor + OnDevicePredictor + PredictiveExportPolicy, opt-in via `MobileConfig.enablePredictiveExport` |
| Device metrics collector | &#9989; `DeviceMetricsCollector` | &#9989; [`DeviceStatsCollector`](../otel-ios-mobile/Sources/OTelMobileSDK/Metrics/DeviceStatsCollector.swift) | shipped | Auto-started when `AutoCaptureOptions.deviceStats` is enabled (default, 15s cadence). Emits memory / battery / thermal / storage gauges |
| Jank detector | &#9989; | &#10060; | not-started | iOS CADisplayLink-based analog TBD |
| App-start instrumentation | &#9989; `AppStartInstrumentation` | &#10060; | not-started | |
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
| Retryable exporter | 7 | 0 | **0%** |
| Config (MobileConfig) | 18 + 24 (security) + 6 (customizers) + 9 (DSL) = **57** | 5 (`MobileConfig`) | **9%** |
| Autocapture / Recovery / SessionTracker | 41 + 24 + 16 + 30 + 20 = **131** | 0 | **0%** |
| Network | 33 + 25 + 8 = **66** | 9 (`NetworkConfig`) | **14%** |
| Session | 19 + 7 = **26** | 0 | **0%** |
| PII / privacy | 40 (`PiiScrubberTest`) + 20 (`PrivacyUtilsTest`) = **60** | 0 | **0%** |
| Errors | 36 + 29 = **65** | 0 | **0%** |
| Vitals / jank | 8 | 0 | **0%** |
| Sampling | 30 + 17 = **47** | 0 | **0%** |
| Fleet alerts | 12 | 0 | **0%** |
| Log tailing | 22 | 0 | **0%** |
| Device metrics | 27 | 0 | **0%** |
| Navigation | 23 | 0 | **0%** |
| Matrix / cross-cutting | 20+24+7+4+4+11+3+2+2+12+16 = **~105** | 0 | **0%** |
| Resource builder | n/a | 4 (`ResourceBuilderTests`) | iOS-only |
| Smoke | n/a | 2 | iOS-only |
| **Totals (approx)** | **~980** across 55 files | **134** across 18 suites | **~14%** |

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
| validate-us063-crash-flush.sh | `scripts/test/validate-us063-crash-flush.sh` | &#10060; | not-started |
| validate-us064-http-error-flush.sh | `scripts/test/validate-us064-http-error-flush.sh` | &#10060; | not-started |
| validate-us065-freeze-flush.sh | `scripts/test/validate-us065-freeze-flush.sh` | &#10060; | not-started |
| validate-us066-no-false-flush.sh | `scripts/test/validate-us066-no-false-flush.sh` | &#10060; | not-started |
| validate-us067-ram-overflow.sh | `scripts/test/validate-us067-ram-overflow.sh` | &#10060; | not-started |
| validate-us068-disk-ttl.sh | `scripts/test/validate-us068-disk-ttl.sh` | &#10060; | not-started (disk buffer absent) |
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

## Critical gaps (ranked)

1. **Disk buffer / crash-safe persistence.** Android has `DiskLogBuffer` (Room
   SQLite v4, 50MB, 24h TTL) and `seqId`-based dedup for RAM&rarr;disk mirrors
   (SR-017). iOS buffer is RAM-only, actor-backed `Deque`. The "survives process
   death" marketing claim only applies to Android today. No Swift port of
   Room/SQLite bridging, no migration story.
2. **Policy evaluator runtime.** iOS parses DSL v2 at behavioral parity (14
   tests) but has zero trigger-matching / condition-evaluation engine. Parsed
   `PolicyConfig` currently has no consumer. Android `PolicyEvaluator` has
   ~116 runtime tests (crash match, error match, condition DSL, geo, security).
   This blocks conditional/hybrid export modes on iOS.
3. **Instrumentation breadth (15 of 20 modules absent).** tap, scroll,
   text-input, back-press, compose-click, wireframe, screenshot, freeze,
   system-events, database, file-io, amplify-datastore, screen-orientation,
   debug-widget, timber. Freeze and Vitals are `public enum` placeholders. The
   `WindowEventHub` / `TouchEventHub` has no installer wired on iOS, so even
   when modules land there is no dispatch infrastructure.
4. **No validation-suite parity.** 28 `validate-us0XX-*.sh` scenario scripts
   exist for Android (happy-path, network-loss, crash-flush, RAM-overflow,
   disk-TTL, selective-flush, timestamp-monotonic, etc.). Zero exist for iOS.
   There is no `scripts/test/run-phase9-suite.sh` analog, no
   `validate-telemetry.sh` for iOS. CI-readiness for iOS is not established.
5. **Zero user-facing iOS documentation.** None of `ANDROID_SDK_GUIDE`,
   `QUICK_START`, `CONFIGURATION`, `API_REFERENCE`, `EXPORT_MODES`,
   `AUTO_INSTRUMENTATION`, `SAMPLING`, `DEVICE_METRICS` have iOS analogs. The
   [`docs/guides/TUTORIAL_ANDROID_QUICKSTART.md`](guides/TUTORIAL_ANDROID_QUICKSTART.md)
   has no iOS twin. Customers landing from a Dash0 website link have nothing to
   read.

## Secondary gaps

- **Session rotation**: iOS `StaticSessionProvider` never rotates; Android has
  idle + app-lifecycle rotation and persistence.
- **Retry/backoff**: no `RetryableExporter` on iOS &mdash; OTLP/HTTP failures
  follow the SDK batch processor defaults, no dedicated retry layer.
- **Export-status surfacing**: no iOS `ExportStatusManager` &mdash; debug widget
  can't render iOS status today.
- **Sampling**: no `DynamicSampler` or `SamplerFactory` on iOS.
- **PII scrubbing**: `PrivacyConfig` carries fields but there is no scrubbing
  pipeline; Android has 40 tests covering regex scrubbing.
- **Fleet alerting**: absent on iOS.
- **Predictive telemetry (OTEP)**: absent on iOS.
- **Log tailing**: absent on iOS.
- **Jank / app-start / coroutine-error instrumentation**: absent on iOS.
- **gRPC transport**: iOS is HTTP-only.
- **DSL-based fluent builder**: iOS `MobileConfig` is a single-shot struct init,
  not a nested builder DSL.

## What's ahead (not in scope for the iPhone branch)

- Swift port of `DiskLogBuffer` + SR-017 crash-safe flush on CoreData or SQLite
  (GRDB candidate).
- Policy evaluator runtime: port `PolicyEvaluator` match/condition engine from
  [`PolicyEvaluator.kt`](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt).
- Touch-event hub installer: wire a `UIWindow` subclass swizzle (or
  `UIGestureRecognizer` bridge) to feed TapInstrumentation / ScrollInstrumentation.
- SwiftUI `.swiftGesture` modifier for tap/long-press/swipe so the tap module
  works in pure-SwiftUI apps without UIKit swizzles.
- iOS user-docs: `IOS_SDK_GUIDE.md`, `IOS_QUICK_START.md`,
  `TUTORIAL_IOS_QUICKSTART.md`, iOS sections in `CONFIGURATION.md`,
  `EXPORT_MODES.md`, `AUTO_INSTRUMENTATION.md`.
- iOS validation-suite: port `validate-us0XX-*.sh` scenarios to
  `xcodebuild test` + simulator control (equivalent of `adb` scripting).
- iOS CI workflow in `.github/workflows/` (currently only Android + Go).
- iOS expansion of `upstream-demo-app-ios` to cover all 6 upstream screens
  (RecommendedSection, About, ConfirmCrashPopUp, CheckoutInfo, CheckoutConfirmation).
- iOS OTLP/gRPC exporter (currently HTTP-only).
