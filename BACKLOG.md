# Backlog

Prioritized remaining work. Items are grouped by track, ordered by priority within each track.

**Legend:** `[x]` done, `[ ]` not started, `[~]` partially done

---

## Track 1: SDK Completeness

### P0 — Bugs / Blockers

- [x] **Fix processor.go import** — `pdata.Value` -> `pcommon.Value` (done)
- [x] **Implement DiskLogBuffer deserialization** — `toLogRecordData()` fully implemented with JSON round-trip (attributes, resource attrs, severity, scope, body)
- [ ] **iOS: log records exported twice** — `OTelMobile.swift:270-273` registers both `bufferProcessor` (with `RetryableExporter(otlpLogExporter)`) and `batchLogProcessor` (`BatchLogRecordProcessor(logRecordExporter: otlpLogExporter)`) on the same LoggerProvider. Every log is emitted on both pipelines, so Dash0 sees duplicate records for every event. Confirmed via Dash0 CLI query on `otel-rn-astronomy-shop` showing every `cart.add_item` / `shop.view_product` appearing twice at identical timestamps. Fix: either drop the batch processor and let `bufferProcessor` own all export (the Android model), or flag the buffer processor as "observe-only" and let the batch processor own export. Mirror whichever choice on Android so the platforms don't drift.
- [ ] **RN bridge: span durations show 0ms for most child spans** — `packages/react-native/src/index.ts` uses `Date.now() * 1_000_000` in `nowUnixNano()` to build `startTimeUnixNano` / `endTimeUnixNano`. That's ms resolution; nested spans that start and end within the same ms tick serialize identical timestamps and land with `duration=0ms` in Dash0. Visible on every child in the 14-span checkout tree (root shows 6ms; children all 0ms). Fix: swap to `global.performance.now()` (sub-ms, always available in RN 0.85 new-arch) and mix with a fixed epoch from `Date.now()` captured once at `Dash0Mobile.start()`. Mirror on Android if the JS bridge does the same thing there (it does).

### P1 — Auto-Capture Enhancements

- [ ] **Compose Navigation support** — Intercept `NavHostController` navigation events for breadcrumbs
- [ ] **Fragment lifecycle** — `FragmentManager.registerFragmentLifecycleCallbacks()` for breadcrumbs
- [ ] **Early event queue** — Buffer up to 100 events before SDK init completes, replay after init
- [ ] **ProGuard/R8 symbolication** — Parse mapping.txt to deobfuscate stack traces in ErrorInstrumentation. (Future enhancement, stubbed)

---

## Track 2: Testing

### P0 — Unit Tests (Android)

Target: >80% coverage. All core test files exist. Gaps remaining:

- [x] **MobileLogRecordProcessorTest** — done
- [x] **DiskLogBufferTest** — done
- [x] **PolicyEvaluatorTest** — done (including geo/device extension)
- [x] **PiiScrubberTest** — done
- [x] **SessionTrackerTest** — done
- [x] **DynamicSamplerTest** — done
- [x] **ExportModeTest** — CONDITIONAL/CONTINUOUS/HYBRID mode behavior, policy trigger gates (~60 tests)
- [x] **UserJourneyExportModeTest** — 18 scenario tests across 4 journey families, cross-mode comparisons (~40 tests)
- [ ] **JourneyBreadcrumbBufferTest** — FIFO eviction, thread safety, time-window filtering, JSON serialization (~15 tests)
- [ ] **NavigationInstrumentationTest** — Activity lifecycle, deep link capture, manual navigation (~20 tests)

### P0 — Unit Tests (Go)

- [x] **factory_test.go** — Factory creation, default config, processor creation, capabilities, invalid config, start/shutdown (7 tests)

### P1 — Espresso Scenario Tests (Dash0 telemetry generation)

- [x] **UserJourneyScenarios** — happy-path booking, browse-without-booking, network error recovery, get-directions (4 tests)
- [x] **FaultScenarios** — jank detection, memory pressure, ANR detection, crash recovery (4 tests)
- [x] **ConditionalFlushScenarios** — quiet-buffer-then-crash-flush, http-error-flush with buffer inspection (2 tests)
- [x] **EmulatorStressScenarios** — battery drain, thermal throttle, memory pressure, network degradation, rapid drain, combined stress, extreme low battery (7 tests via UIAutomation shell commands)
- [x] **run-dash0-scenarios.sh** — CLI script to run scenario suites with `--all/--journeys/--faults/--conditional/--stress`, single test, device selection, repeat, run-id tagging

### P1 — Integration Tests

- [~] **Android integration** — End-to-end buffer flow (RAM -> Disk -> Export), real Room database, crash recovery, concurrent capture. 9 tests passing on emulator. Crash-mirror dedup validated.
- [ ] **Collector integration** — Processor in real collector, full OTLP pipeline, policy matching, annotation propagation (~20 tests). Requires custom collector via ocb.

### P1 — Comprehensive Telemetry Validation (Phase 9)

- [ ] **Validation framework** — Structured JSON assertion library for collector output: event existence, ordering, timestamp monotonicity, span hierarchy, attribute checks
- [ ] **User journey validation** — 8 journeys: happy path booking, browse+refresh, network error recovery, get directions, multi-tab navigation, form input lifecycle, session lifecycle, background/foreground
- [ ] **Stress signal validation** — Battery drain, thermal throttle, memory pressure, combined stress, network loss — verify device metrics, prediction scores, and flush triggers in collector output
- [ ] **Policy flush validation** — Crash-triggered, HTTP-error-triggered, UI-freeze-triggered, no-false-flush (CONDITIONAL accumulation without export)
- [ ] **Buffer validation** — RAM overflow to disk, disk TTL enforcement, selective time-window flush (flushWindow(N) exports only last N minutes)
- [ ] **Telemetry ordering** — Timestamp monotonicity, span parent-child integrity, cross-signal correlation (log timestamps within parent span duration)
- [ ] **Export mode validation** — CONTINUOUS periodic flush timing, HYBRID heartbeat + conditional, CONDITIONAL zero-export under normal conditions
- [ ] **CI integration** — Wire into GitHub Actions with Docker collector, test matrix across API 28/33/36

See [Upstream Supersession Epic — Phase 9](docs/epics/UPSTREAM_SUPERSESSION_EPIC.md) (US-049 through US-077) for full breakdown.

### P2 — E2E & Performance

- [ ] **E2E test scripts** — Android -> Collector -> Backend for all 3 demo scenarios, `demo_run_id` correlation
- [ ] **Performance benchmarks** — Event capture latency, policy evaluation time, export throughput, memory profiling
- [ ] **Load tests** — 10K events/sec, RAM overflow under load, disk buffer under load

---

## Track 3: Infrastructure

### P0 — Custom Collector Build

- [ ] Create `builder-config.yaml` for OpenTelemetry Collector Builder (ocb)
- [ ] Build custom collector binary with mobilepolicyprocessor
- [ ] Create Dockerfile for `otelcol-mobile`
- [ ] Verify processor loads and OTLP pipeline works

### P1 — Demo App Enhancements

- [ ] **Scenario E: Vitals Spike** — Intentional frame drops -> vitals metrics -> policy-triggered flush
- [ ] **Scenario F: Journey Reconstruction** — Multi-screen flow -> error at end -> full journey in crash recovery
- [ ] Add settings UI for enabling/disabling individual modules
- [ ] Environment-specific configs (dev, staging, prod)

### P1 — Control Plane: Wireframe Journey Replay

Visual user journey replay in the [control plane UI](https://github.com/barrysolomon/mobile-otel-control-plane),
consuming `ui.wireframe`, `ui.screenshot`, and interaction events from the backend.

- [ ] **Wireframe renderer** — React component that renders a `WireframeNode` JSON tree as SVG (rectangles for containers, rounded for buttons, X-boxes for images, gray lines for text placeholders)
- [ ] **Journey timeline view** — Query `ui.wireframe` logs by `mobile.session.id`, order by `mobile.wireframe.sequence`, display as a horizontal filmstrip of wireframe thumbnails
- [ ] **Interaction overlay** — Plot `ui.tap` / `ui.scroll` / `ui.swipe` events on the matching wireframe frame as colored dots/arrows at the event coordinates
- [ ] **Screenshot final frame** — Display `ui.screenshot` data URL as the terminal frame in the journey (e.g., the crash state), rendered alongside the wireframe sequence
- [ ] **Journey diff** — Compare wireframe sequences across two sessions side-by-side, highlighting structural differences (added/removed/moved views)
- [ ] **Session picker** — Filter sessions by screen name, error state, duration, device type; click to open journey replay
- [ ] **Wireframe-to-code mapping** — Click a wireframe node to see its `resource_id`, link to source when available

---

## Track 4: Documentation & OTEPs

### P1 — OTEPs (for upstream contribution)

- [ ] **OTEP: Mobile Buffering Pattern** — Two-tier ring buffer (RAM + disk), overflow policy, TTL, crash recovery. (~2-3 days)
- [ ] **OTEP: Conditional Export for Mobile** — Policy-based selective flush, DSL specification, operators, actions, collector integration. (~2-3 days)

### P1 — API Documentation

- [x] **Auto-instrumentation reference** — `docs/AUTO_INSTRUMENTATION.md`: page span hierarchy, UI signals, screen nav, lifecycle, errors, network, vitals, predictive export, ring buffer metrics, sampling, privacy controls
- [ ] Add KDoc to all public Android classes and methods
- [ ] Add GoDoc to all exported types and functions in collector processor
- [ ] Generate HTML documentation sites

### P2 — Tutorials

- [ ] Tutorial: Integrating the Android library (Gradle setup -> first events)
- [ ] Tutorial: Configuring the collector processor (policies, testing)
- [ ] Tutorial: Creating custom export policies (DSL syntax, operators, best practices)

### P2 — Architecture Diagrams

- [ ] Sequence diagram: Event capture -> buffer -> export
- [ ] Sequence diagram: Policy evaluation -> flush
- [ ] Component diagram: Android library internal structure

---

## Track 5: Scale Readiness (Production Hardening)

**Epic:** [SCALE_READINESS_EPIC.md](docs/epics/SCALE_READINESS_EPIC.md) — 25 findings from production readiness review

### P0 — Critical (before any fleet deployment)

- [ ] **SR-001: Cache disk event count** — Replace `runBlocking { COUNT(*) }` on metric gauge callback with `AtomicInteger` updated on insert/delete. ANR vector on slow-storage devices.
- [ ] **SR-002: Async flush pipeline** — Eliminate `runBlocking` in `flushWindow()`/`forceFlush()`. Dedicated coroutine scope for disk I/O so executor threads never block.
- [ ] **SR-003: Singleton lifecycle management** — Clear `DiskLogBuffer` and `MobileLoggerProvider` singletons on `shutdown()`. Prevent stale config on re-initialization.
- [ ] **SR-004: Bound persistedToDisk set** — Remove entries when TTL cleanup deletes disk events. Prevent unbounded memory growth in long sessions.
- [ ] **SR-005: FleetAlertHandler thread safety** — Synchronize `alertTimestamps` and `activeOverrides` collections. Race condition defeats rate limiting.

### P0 — High (before beta deployment)

- [x] **SR-006: Explicit Room migrations** — Added explicit `Migration` objects for v1→v2→v3→v4. `fallbackToDestructiveMigration` kept as safety net. Done 2026-04-09.
- [ ] **SR-007: Deferred VACUUM** — Move `VACUUM` from hot insert path to periodic cleanup. Prevent exclusive DB lock during burst ingestion.
- [ ] **SR-008: Shared OkHttpClient** — Inject app-level OkHttpClient into PolicyEvaluator instead of creating per-instance.
- [ ] **SR-009: Retry jitter** — Add `* (0.5 + random * 0.5)` to RetryableExporter backoff. Prevent thundering herd on collector recovery.
- [ ] **SR-010: Lock-free trigger evaluation** — Snapshot buffer before evaluating user predicates in LogTailBuffer. Prevent deadlock from user code.
- [ ] **SR-011: Remove demo_app_prefs from SDK** — ContextSnapshot reads demo app SharedPreferences in library code. Remove and use explicit API.
- [ ] **SR-012: Pre-compile Go regexes** — Cache compiled regexes in Go processor at policy load time. Currently recompiles on every ConsumeLogs call.

### P1 — Medium (before GA)

- [ ] **SR-013: Atomic sampler revert** — Fix DynamicSampler read→write lock upgrade race with `compareAndSet`.
- [ ] **SR-014: Provider singleton reset** — Clear MobileLoggerProvider on shutdown (covered by SR-003).
- [ ] **SR-015: Logical size enforcement** — Use row count not filesystem bytes for disk buffer limits (covered by SR-007).
- [ ] **SR-016: Crash recovery accuracy** — Only mark clean shutdown in explicit `stop()`, not on every background event.
- [ ] **SR-017: Crash-safe flush** — On crash path, persist to disk only (skip gRPC export). Crash-recovery handles re-export on next launch.
- [x] **SR-018: Multi-type attribute lookup** — Try all 4 `AttributeKey` types (string, long, double, bool) in PolicyEvaluator.getAttributeValue(). Done 2026-04-14.
- [ ] **SR-019: ID-based delete in flushWindow** — Delete by row ID list instead of timestamp range to eliminate TOCTOU data loss.

### P2 — Low (opportunistic)

- [ ] **SR-020:** Replace synchronized regexCache with ConcurrentHashMap
- [ ] **SR-021:** Add IPv6 loopback `[::1]` to isLocalhostEndpoint()
- [ ] **SR-022:** Ensure JankDetector constructs Choreographer on main thread
- [ ] **SR-023:** Fix DynamicSampler negative-Long sampling bias (50% always sampled)
- [ ] **SR-024:** Add GDPR/CCPA privacy docs for ContextSnapshot demographics
- [ ] **SR-025:** Use two-value type assertion in Go processor event.name

---

## Track 6: Upstream Contribution

### P2 — Code Cleanup (includes SR-011, SR-020–SR-025)

- [ ] Remove demo-specific code from Android library
- [ ] Remove hardcoded values, add configuration validation
- [ ] Run ktlint and fix all issues
- [ ] Run golangci-lint and fix all issues
- [ ] Add Apache-2.0 license headers to all source files
- [ ] Create CHANGELOG.md

### P2 — Community Engagement

- [ ] Submit OTEP PRs to `opentelemetry-specification` repo
- [ ] Present at OpenTelemetry SIG meetings (Android SIG, Collector SIG)
- [ ] Create PR for Android library -> `opentelemetry-android` or `-contrib`
- [ ] Create PR for collector processor -> `opentelemetry-collector-contrib`
- [ ] Respond to community review feedback, iterate

---

## Track 7: Upstream Supersession

**Epic:** [UPSTREAM_SUPERSESSION_EPIC.md](docs/epics/UPSTREAM_SUPERSESSION_EPIC.md) — Compatible superset of `opentelemetry-android`, 31 work items across 4 phases

### Phase 1 — Foundation (P0)

- [ ] Remove phantom dep `instrumentation:0.4.0-alpha`, add `session:0.10.0-alpha` + `instrumentation-android-instrumentation:0.10.0-alpha`
- [ ] Update semconv `1.39.0` → `1.40.0`
- [ ] `MobileSessionProvider extends SessionProvider` + `UpstreamSessionProviderAdapter`
- [ ] `UpstreamInstrumentationAdapter` (upstream modules in our framework)
- [ ] `MobileInstrumentationAdapter` (our modules in upstream framework)
- [ ] `@Supersedes` annotation + conflict resolution in `InstrumentationRegistry`
- [ ] `discoverUpstreamInstrumentations()` + `discoverAllInstrumentations()` on builder
- [ ] Optional `Clock` field on `InstrumentationContext`
- [ ] Phase 1 test suite (7 test classes)
- [ ] iOS spec section 5 stale notice

### Phase 2a — New Modules (P1, parallel with 2b/3)

- [ ] `ComposeClickInstrumentation` + `ComposeClickConfig` — Compose-aware tap detection
- [ ] `ScreenOrientationInstrumentation` — orientation change events
- [ ] New Gradle modules, tests, wire into demo app

### Phase 2b — Reimplemented Modules (P2, parallel with 2a/3)

- [ ] `WebSocketInstrumentation` + `OTelWebSocketListener` — OkHttp WebSocket spans
- [ ] `AndroidLogInstrumentation` — android.util.Log bridge (optional, P2)

### Phase 3 — API Surface Parity (P1, parallel with 2a/2b)

- [ ] Kotlin DSL configuration (`mobileOtel { }` entry point + `@MobileOtelDsl`)
- [ ] Exporter customizer chain (log, span, metric)
- [ ] `OpenTelemetryRumCompat` shim for upstream migration
- [ ] Tests for DSL, customizers, compat shim

### Phase 4 — Interface Convergence (P1, after all above)

- [ ] Converge `InstrumentationContext` to embed `InstallationContext`
- [ ] Converge `MobileInstrumentation extends AndroidInstrumentation`
- [ ] Update all 20+ modules to converged interface
- [ ] Remove `MobileInstrumentationAdapter` (no longer needed)
- [ ] Update iOS port spec section 5 (full rewrite)
- [ ] Full regression test pass

---

## Track 8: Competitive Parity & Superiority

**Epic:** [COMPETITIVE_PARITY_EPIC.md](docs/epics/COMPETITIVE_PARITY_EPIC.md) — Close gaps vs Datadog & Splunk, then extend lead. 9 phases, 35+ work items.

### Phase 13.5 — Control Plane E2E Validation (P0, de-risks everything)

- [ ] Contract test: v1 DSL roundtrip (UI → gateway → SDK parseConfig → policy evaluates)
- [ ] Contract test: v2 DSL roundtrip (same with `?dsl_version=2`)
- [ ] SDK v2 negotiation (update PolicyEvaluator to request and parse v2 FSM format)
- [ ] Live E2E test: publish workflow from UI → demo app polls config → trigger crash → verify flush
- [ ] React component tests (WorkflowBuilder graph validity, compiler output shape)
- [ ] Config polling integration test (SDK polls gateway, receives updates, applies them)
- [ ] Bundled config fallback test (SDK starts offline, falls back, later receives remote update)

### Phase 13.6 — Offline Sync Framework Instrumentation (P0, deal-driven)

- [ ] AWS Amplify DataStore sync lifecycle spans (start, query, save, delete)
- [ ] Amplify Hub event capture (syncStarted, modelSynced, outboxMutation*)
- [ ] Amplify conflict resolution tracking (ConflictHandler spans)
- [ ] MongoDB Realm SyncSession lifecycle spans (state changes)
- [ ] Realm sync progress metrics (transferredBytes, transferableBytes)
- [ ] Realm SyncException error capture (compensating writes, client reset)
- [ ] Network-correlated sync failures (attach connectivity state to sync spans)
- [ ] Sync failure → selective flush policy (built-in DSL trigger)
- [ ] Sync journey breadcrumbs (sync events in breadcrumb buffer)

### Phase 14 — iOS SDK Port (P0, table stakes)

- [ ] iOS SDK core (Swift package, buffer, export, session, policy engine)
- [ ] iOS auto-instrumentation (UIViewController, UIKit gestures, URLSession)
- [ ] iOS SwiftUI support (view lifecycle, navigation, gestures)
- [ ] iOS crash reporting (NSException, Mach exceptions, signal handlers)
- [ ] iOS dSYM symbolication (upload + deobfuscation pipeline)

### Phase 15 — Crash Symbolication Pipeline (P0)

- [ ] ProGuard/R8 mapping.txt upload (Gradle task or CLI)
- [ ] Server-side deobfuscation (mapping storage + stack trace rewriting)
- [ ] Build ID matching (auto-associate mappings to app versions)
- [ ] iOS dSYM upload (CLI tool, after Phase 14)

### Phase 16 — Session Replay Viewer (P1)

- [ ] Wireframe renderer (React component, SVG from WireframeNode JSON)
- [ ] Journey timeline (horizontal filmstrip by session)
- [ ] Interaction overlay (tap/scroll/swipe markers on wireframes)
- [ ] Screenshot final frame + session picker + privacy controls

### Phase 17 — APM Trace Correlation (P1)

- [ ] W3C Trace Context propagation in OkHttp interceptor
- [ ] Server-side trace stitching (collector processor)
- [ ] Dash0 UI correlation (mobile span → backend trace)

### Phase 18 — NDK / Native Crash Reporting (P2)

- [ ] NDK crash handler (SIGSEGV/SIGABRT/SIGBUS)
- [ ] Native symbol upload + server-side symbolication

### Phase 19 — Cross-Platform Framework Support

- [ ] **React Native bridge (JS → native SDK)** — **P1 (Innovapptive-gated)**; epic: [docs/epics/REACT_NATIVE_EPIC.md](docs/epics/REACT_NATIVE_EPIC.md); scaffold + failing-first tests landed 2026-04-20 under `packages/react-native/` and `examples/upstream-demo-app-rn/`
- [ ] Flutter plugin (Dart → native SDK) — P2, **whitespace opportunity**
- [ ] Realm instrumentation for RN (Innovapptive follow-up; depends on RN bridge)
- [ ] Amplify DataStore for RN (Innovapptive follow-up; depends on RN bridge)
- [ ] Expo config plugin (RN follow-up)

### Phase 20 — Network Depth (P1)

- [ ] Connection timing breakdown (DNS, TLS, connect via OkHttp EventListener)
- [ ] GraphQL instrumentation (Apollo operation-level)
- [ ] WebSocket instrumentation (already specced in Track 7 Phase 2b)

### Phase 21 — Advanced Error Tracking (P1)

- [ ] Error grouping (cluster by stack signature)
- [ ] Version regression detection (flag new errors per version)
- [ ] Crash-free session rate metric

---

## Completed (reference)

- [x] Phase 1-3: Foundation, Android OTEL migration, collector processor
- [x] Dash0 Web SDK integration (Phases 1-5): Session management, breadcrumbs, vitals, network instrumentation, error instrumentation
- [x] Auto-capture: tap, scroll, back-press, freeze/ANR detection, lifecycle, recovery
- [x] CI/CD: GitHub Actions (unit tests, lint, build verification)
- [x] Control Plane UI: React Flow workflow builder, graph-to-DSL compiler (moved to [sister repo](https://github.com/barrysolomon/mobile-otel-control-plane))
- [x] Gateway: Go HTTP server with OTEL export (moved to [sister repo](https://github.com/barrysolomon/mobile-otel-control-plane))
- [x] Demo app: Scenarios A/B/C, configuration UI
- [x] Predictive telemetry module (DeviceHealthMonitor, OnDevicePredictor)
- [x] Sampling system (dynamic, factory, config)
- [x] Device metrics collector (HealthMetricsCollector)
- [x] Log tailing system
- [x] **SDK auto-wiring** — MobileOtel.initialize() wires ErrorInstrumentation, VitalsCollector, PredictiveExportPolicy, HealthMetricsCollector automatically
- [x] **Events module** — `MobileOtel.sendEvent()` API with attribute type coercion
- [x] **Error reporting** — `MobileOtel.reportError()` with dedup, rate limiting, breadcrumb attachment, auto-flush
- [x] **OTelMobile delegation** — `OTelMobile.start()` delegates to `MobileOtel.initialize()` for full module wiring
- [x] **Predictive flush** — PredictiveExportPolicy emits OTel log events for prediction cycles and high-risk alerts
- [x] **forceFlush(windowMinutes)** — Selective time-window flush via `MobileLogRecordProcessor.flushWindow()`
- [x] **seqId dedup** — BufferedEvent.seqId prevents crash-safety mirrors from being double-exported in forceFlush/flushWindow
- [x] **Script organization** — All scripts in categorized `scripts/{demo,ci,e2e,test,setup,lib}` with root forwarders
- [x] **monkey-test.sh device targeting** — `--device` flag, auto-select single emulator, fixed activity name
- [x] **FreezeDetector infinite loop** — Reset `lastTickAtMs` + re-post tick in `emitPendingFreeze()` to prevent ever-growing freeze.duration_ms after ANR
- [x] **Page span sampling** — `sampling.priority=high` on all page spans ensures TapCapture/ScrollCapture emit child spans (trace waterfall) not flat logs
- [x] **Split OTel/Dash0 config** — `ConfigActivity` (OTel SDK settings) + `Dash0ConfigActivity` (backend connection) as separate screens
- [x] **Demo polish** — Version on Profile tab, `TelemetryFlags.showDebugToolbar`, CalVer versioning `1.1.0-20260306`
- [x] **BookFragment comprehensive instrumentation** — device context snapshot (battery/network/memory), form fill time, retry tracking, changed fields, booking date look-ahead, span events for all outcomes
- [x] **Espresso scenario test suites** — 17 tests in 4 suites (journeys, faults, conditional flush, emulator stress) generating live Dash0 telemetry
- [x] **run-dash0-scenarios.sh** — unified CLI for running scenario suites with suite/test/device selection, repeat, Dash0 run-id tagging
- [x] **Auto-instrumentation documentation** — `docs/AUTO_INSTRUMENTATION.md` covering all captured signals, trace hierarchy, privacy controls
- [x] **ExportModeTest + UserJourneyExportModeTest** — 100+ unit tests validating CONDITIONAL/CONTINUOUS/HYBRID behavior across realistic user journeys
