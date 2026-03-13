# Backlog

Prioritized remaining work. Items are grouped by track, ordered by priority within each track.

**Legend:** `[x]` done, `[ ]` not started, `[~]` partially done

---

## Track 1: SDK Completeness

### P0 — Bugs / Blockers

- [x] **Fix processor.go import** — `pdata.Value` -> `pcommon.Value` (done)
- [x] **Implement DiskLogBuffer deserialization** — `toLogRecordData()` fully implemented with JSON round-trip (attributes, resource attrs, severity, scope, body)

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

- [ ] **Android integration** — End-to-end buffer flow (RAM -> Disk -> Export), real Room database, crash recovery, concurrent capture (~20 tests). Requires emulator.
- [ ] **Collector integration** — Processor in real collector, full OTLP pipeline, policy matching, annotation propagation (~20 tests). Requires custom collector via ocb.

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

## Track 5: Upstream Contribution

### P2 — Code Cleanup

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
- [x] **FreezeDetector infinite loop** — Reset `lastTickAtMs` + re-post tick in `emitPendingFreeze()` to prevent ever-growing freeze.duration_ms after ANR
- [x] **Page span sampling** — `sampling.priority=high` on all page spans ensures TapCapture/ScrollCapture emit child spans (trace waterfall) not flat logs
- [x] **Split OTel/Dash0 config** — `ConfigActivity` (OTel SDK settings) + `Dash0ConfigActivity` (backend connection) as separate screens
- [x] **Demo polish** — Version on Profile tab, `TelemetryFlags.showDebugToolbar`, CalVer versioning `1.1.0-20260306`
- [x] **BookFragment comprehensive instrumentation** — device context snapshot (battery/network/memory), form fill time, retry tracking, changed fields, booking date look-ahead, span events for all outcomes
- [x] **Espresso scenario test suites** — 17 tests in 4 suites (journeys, faults, conditional flush, emulator stress) generating live Dash0 telemetry
- [x] **run-dash0-scenarios.sh** — unified CLI for running scenario suites with suite/test/device selection, repeat, Dash0 run-id tagging
- [x] **Auto-instrumentation documentation** — `docs/AUTO_INSTRUMENTATION.md` covering all captured signals, trace hierarchy, privacy controls
- [x] **ExportModeTest + UserJourneyExportModeTest** — 100+ unit tests validating CONDITIONAL/CONTINUOUS/HYBRID behavior across realistic user journeys
