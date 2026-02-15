# Backlog

Prioritized remaining work. Items are grouped by track, ordered by priority within each track.

**Legend:** `[x]` done, `[ ]` not started, `[~]` partially done

---

## Track 1: SDK Completeness

### P0 — Bugs / Blockers

- [ ] **Fix processor.go import** — Line 87: `pdata.Value` → `pcommon.Value` (5 min)
- [ ] **Implement DiskLogBuffer deserialization** — `toLogRecordData()` throws NotImplementedError. Needs JSON or protobuf round-trip for full fidelity. (2-4 hours)

### P0 — Missing Core Modules

- [ ] **Events module** (`mobile-events/`) — `sendEvent()` API with reserved namespace `mobile.*` protection, event name validation, attribute validation. (~1 day)
- [ ] **ProGuard/R8 symbolication** — Parse mapping.txt to deobfuscate stack traces in ErrorInstrumentation. (Future enhancement, stubbed)

### P1 — Auto-Capture Enhancements

- [ ] **Compose Navigation support** — Intercept `NavHostController` navigation events for breadcrumbs
- [ ] **Fragment lifecycle** — `FragmentManager.registerFragmentLifecycleCallbacks()` for breadcrumbs
- [ ] **Early event queue** — Buffer up to 100 events before SDK init completes, replay after init

---

## Track 2: Testing

### P0 — Unit Tests (Android)

Target: >80% coverage. Follow existing patterns in `src/test/`.

- [~] **MobileLogRecordProcessorTest** — RAM buffer, overflow to disk, policy flush, thread safety, shutdown (~30 tests)
- [~] **DiskLogBufferTest** — Persistence, time window queries, TTL cleanup, size eviction, concurrent writes (~25 tests)
- [ ] **PolicyEvaluatorTest** — All operators (equals, gt, lt, contains, regex), AND/OR logic, config fetch, network failure, MockWebServer (~40 tests)
- [ ] **SessionManagerTest** — Session creation/termination, identity, global attributes, inactivity timeout (~20 tests)
- [ ] **PiiScrubberTest** — URL, deep link, exception message, stack trace scrubbing, PII pattern detection (~25 tests)
- [ ] **JourneyBreadcrumbBufferTest** — FIFO eviction, thread safety, time-window filtering, JSON serialization (~15 tests)
- [ ] **NavigationInstrumentationTest** — Activity lifecycle, deep link capture, manual navigation (~20 tests)

### P0 — Unit Tests (Go)

- [ ] **factory_test.go** — Factory creation, default config, processor creation, capabilities, invalid config (~10 tests)

### P1 — Integration Tests

- [ ] **Android integration** — End-to-end buffer flow (RAM → Disk → Export), real Room database, crash recovery, concurrent capture (~20 tests). Requires emulator.
- [ ] **Collector integration** — Processor in real collector, full OTLP pipeline, policy matching, annotation propagation (~20 tests). Requires custom collector via ocb.

### P2 — E2E & Performance

- [ ] **E2E test scripts** — Android → Collector → Backend for all 3 demo scenarios, `demo_run_id` correlation
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

- [ ] **Scenario E: Vitals Spike** — Intentional frame drops → vitals metrics → policy-triggered flush
- [ ] **Scenario F: Journey Reconstruction** — Multi-screen flow → error at end → full journey in crash recovery
- [ ] Add settings UI for enabling/disabling individual modules
- [ ] Environment-specific configs (dev, staging, prod)

---

## Track 4: Documentation & OTEPs

### P1 — OTEPs (for upstream contribution)

- [ ] **OTEP: Mobile Buffering Pattern** — Two-tier ring buffer (RAM + disk), overflow policy, TTL, crash recovery. (~2-3 days)
- [ ] **OTEP: Conditional Export for Mobile** — Policy-based selective flush, DSL specification, operators, actions, collector integration. (~2-3 days)

### P1 — API Documentation

- [ ] Add KDoc to all public Android classes and methods
- [ ] Add GoDoc to all exported types and functions in collector processor
- [ ] Generate HTML documentation sites

### P2 — Tutorials

- [ ] Tutorial: Integrating the Android library (Gradle setup → first events)
- [ ] Tutorial: Configuring the collector processor (policies, testing)
- [ ] Tutorial: Creating custom export policies (DSL syntax, operators, best practices)

### P2 — Architecture Diagrams

- [ ] Sequence diagram: Event capture → buffer → export
- [ ] Sequence diagram: Policy evaluation → flush
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
- [ ] Create PR for Android library → `opentelemetry-android` or `-contrib`
- [ ] Create PR for collector processor → `opentelemetry-collector-contrib`
- [ ] Respond to community review feedback, iterate

---

## Completed (reference)

- [x] Phase 1-3: Foundation, Android OTEL migration, collector processor
- [x] Dash0 Web SDK integration (Phases 1-5): Session management, breadcrumbs, vitals, network instrumentation, error instrumentation
- [x] Auto-capture: tap, scroll, back-press, freeze/ANR detection
- [x] CI/CD: GitHub Actions (unit tests, lint, build verification)
- [x] Control Plane UI: React Flow workflow builder, graph-to-DSL compiler
- [x] Gateway: Go HTTP server with OTEL export
- [x] Demo app: Scenarios A/B/C, configuration UI
- [x] Predictive telemetry module
- [x] Sampling system (dynamic, factory, config)
- [x] Device metrics collector
- [x] Log tailing system
