# Epic: Upstream Supersession — Compatible Superset of `opentelemetry-android`

**Status:** In Progress
**Priority:** P0
**Owner:** Barry Solomon
**Created:** 2026-04-09
**Target:** SDK is a strict superset of upstream before merge proposal
**Design Spec:** [2026-04-09-upstream-supersession-design.md](../superpowers/specs/2026-04-09-upstream-supersession-design.md)

---

## Summary

Make Dash0's mobile-otel SDK a strict, compatible superset of the upstream `opentelemetry-android` project (v0.10.0-alpha core and instrumentations). All upstream modules run unmodified in our framework via a bidirectional adapter. Our modules offer richer telemetry (policy engine, dual-tier buffering, selective flush, 20 instrumentation modules vs upstream's 17) that upstream lacks. The architecture positions for eventual merge as the next-generation official SDK.

## Strategy

Compatible superset (Phase A) leading to interface convergence and merge proposal (Phase B):
1. Adapter wraps upstream's `AndroidInstrumentation` so their modules plug into our registry
2. Fill gaps: Compose click, screen orientation, WebSocket
3. API parity: Kotlin DSL, exporter customizers, `OpenTelemetryRum` compat shim
4. Converge interfaces: `MobileInstrumentation extends AndroidInstrumentation`, update iOS spec

## Success Criteria

- All 10 published upstream instrumentation modules run in our framework via adapter
- `@Supersedes` prevents duplicate telemetry when both frameworks' modules are present
- Our 20+ modules can be wrapped as `AndroidInstrumentation` for upstream compatibility
- Compose clicks produce `ui.tap` events with composable identity
- Kotlin DSL produces identical runtime behavior to existing builder
- Zero regressions in 194+ existing behavioral config tests
- iOS port spec updated with converged interface

## Work Items

### Phase 1 -- Foundation (P0)

| ID | Title | Status |
|----|-------|--------|
| US-001 | Remove phantom dep `instrumentation:0.4.0-alpha` | [ ] |
| US-002 | Add `session:0.10.0-alpha` + `instrumentation-android-instrumentation:0.10.0-alpha` deps | [ ] |
| US-003 | Update semconv `1.39.0` to `1.40.0` | [ ] |
| US-004 | `MobileSessionProvider extends SessionProvider` | [ ] |
| US-005 | `UpstreamSessionProviderAdapter` | [ ] |
| US-006 | `UpstreamInstrumentationAdapter` (upstream modules in our framework) | [ ] |
| US-007 | `MobileInstrumentationAdapter` (our modules in upstream framework) | [ ] |
| US-008 | `@Supersedes` annotation | [ ] |
| US-009 | Conflict resolution in `InstrumentationRegistry.install()` | [ ] |
| US-010 | `discoverUpstreamInstrumentations()` + `discoverAllInstrumentations()` | [ ] |
| US-011 | Optional `Clock` field on `InstrumentationContext` | [ ] |
| US-012 | Phase 1 test suite (7 test classes) | [ ] |
| US-013 | iOS spec section 5 stale notice | [ ] |

### Phase 2a -- New Modules (P1, parallel with 2b/3)

| ID | Title | Status |
|----|-------|--------|
| US-014 | `ComposeClickInstrumentation` + `ComposeClickConfig` | [ ] |
| US-015 | `ScreenOrientationInstrumentation` | [ ] |
| US-016 | New Gradle modules (`instrumentation/compose-click/`, `instrumentation/screen-orientation/`) | [ ] |
| US-017 | Tests for Compose + screen orientation | [ ] |
| US-018 | Wire into `otel-android-mobile/build.gradle.kts` + demo app | [ ] |

### Phase 2b -- Reimplemented Modules (P2, parallel with 2a/3)

| ID | Title | Status |
|----|-------|--------|
| US-019 | `WebSocketInstrumentation` + `OTelWebSocketListener` | [ ] |
| US-020 | `AndroidLogInstrumentation` (optional) | [ ] |
| US-021 | Tests for WebSocket module | [ ] |

### Phase 3 -- API Surface Parity (P1, parallel with 2a/2b)

| ID | Title | Status |
|----|-------|--------|
| US-022 | Kotlin DSL configuration (`mobileOtel { }`, `@MobileOtelDsl`, DSL classes) | [ ] |
| US-023 | Exporter customizer chain (log, span, metric) | [ ] |
| US-024 | `OpenTelemetryRumCompat` shim | [ ] |
| US-025 | Tests for DSL, customizers, compat shim | [ ] |

### Phase 4 -- Interface Convergence (P1, after all above)

| ID | Title | Status |
|----|-------|--------|
| US-026 | Converge `InstrumentationContext` to embed `InstallationContext` | [x] |
| US-027 | Converge `MobileInstrumentation extends AndroidInstrumentation` | [x] |
| US-028 | Update all 20+ modules to converged interface | [x] |
| US-029 | Remove `MobileInstrumentationAdapter` | [x] |
| US-030 | Update iOS port spec section 5 (full rewrite) | [x] |
| US-031 | Full regression test pass | [x] |

## Dependencies

```
Phase 1 (Foundation)
    |
    +---> Phase 2a (Compose + Screen Orientation)  --+
    +---> Phase 2b (WebSocket + Android Log)         +---> Phase 4 (Convergence)
    +---> Phase 3 (DSL + Customizers + Compat)     --+

Phase 8 (Config Docs + Runtime Config) ──► Phase 9 (Telemetry Validation Suite)
                                                         ▲
Phase 7 (Real Crash Scenarios) ──────────────────────────┘
```

### Phase 5 -- Comparison Tutorial (P1, after Phase 1)

| ID | Title | Status |
|----|-------|--------|
| US-032 | Clone upstream demo app, instrument with upstream `opentelemetry-android` SDK | [ ] |
| US-033 | Instrument same demo app with our SDK (swap dependency, same app code) | [ ] |
| US-034 | Side-by-side telemetry comparison: capture both outputs in Dash0 | [ ] |
| US-035 | Write step-by-step tutorial doc highlighting diffs (signal richness, battery efficiency, policy engine, UI gesture detection, buffering) | [ ] |
| US-036 | Create before/after screenshots of Dash0 dashboards showing telemetry gaps in upstream vs our SDK | [ ] |

**Goal:** A reproducible walkthrough that anyone can follow to see exactly why our SDK is the superior superset. Instrument the upstream `opentelemetry-android` demo app with both SDKs, run the same user scenarios, and compare the telemetry side by side. Highlights: our 22 modules vs their 17, conditional export vs always-on, dual-tier buffering vs single-tier, gesture/scroll/text-input signals they don't have, wireframe replay they don't have.

### Phase 6 -- Embeddable Debug Widget & Profile Page (P1, needs design)

| ID | Title | Status |
|----|-------|--------|
| US-037 | Design embeddable debug toolbar API — drop-in overlay any host app can enable with one line | [ ] |
| US-038 | Design embeddable profile/diagnostics page — lightweight, non-intrusive replacement for current ham-fisted full-screen approach | [ ] |
| US-039 | Implement debug widget as opt-in SDK module (`instrumentation-debug/`) | [ ] |
| US-040 | Implement profile page as composable/fragment that host apps embed in their settings | [ ] |
| US-041 | Document integration guide: "Add debug tools to your app in 2 lines" | [ ] |

**Goal:** Let any app that integrates our SDK optionally surface a debug toolbar (buffer stats, export status, live event stream) and a profile/diagnostics page (config viewer, session info, telemetry toggle, force flush) without the current demo-app-specific UI. The current profile page is tightly coupled to the demo app — this redesigns it as a reusable SDK component that looks good in any app. **Needs design spec before implementation.**

### Phase 7 -- Real Crash Scenarios (P1)

| ID | Title | Status |
|----|-------|--------|
| US-042 | Real uncaught exception crash + recovery via orchestrator (RealCrashScenarios.kt) | [ ] |
| US-043 | Add real ANR scenario (block main thread >5s, system kill, verify recovery_type=anr_force_kill) | [ ] |
| US-043b | Add real OOM scenario (allocate until system kill, verify recovery_type=low_memory_kill) | [ ] |
| US-044 | Verify crash-recovery telemetry includes full pre-crash context window (validated against local collector) | [ ] |

**Design spec:** `docs/superpowers/specs/2026-04-10-phase7-real-crash-design.md`

**Goal:** Real crashes (uncaught exceptions that kill the process) followed by app restart demonstrate that the dual-tier buffer survives process death and flushes the pre-crash context on next launch. Uses `androidx.test:orchestrator` so the test runner survives app death. ANR and OOM scenarios are on the roadmap after the uncaught exception scenario is proven.

**Implementation order:** US-042 + US-044 first (uncaught exception + validated telemetry). US-043 (ANR) and US-043b (OOM) follow once the orchestrator infrastructure is proven.

### Phase 8 -- Configuration Documentation + Runtime Config (P1)

| ID | Title | Status |
|----|-------|--------|
| US-045 | User-facing configuration guide: how to set up otel-config.json, what each field does, examples | [x] |
| US-046 | Technical docs: MobileConfig field reference, ExportMode behavior, buffer tuning, sampling | [x] |
| US-047 | Runtime config override mechanism (SharedPreferences or intent) for test/debug switching | [x] |
| US-048 | Fix validated tests: use runtime config override instead of build-time asset swap | [x] |

**Goal:** Developers need to know how to configure the SDK. Currently the only reference is the code and CLAUDE.md. Need a proper configuration guide (user-facing) and API reference (technical). The runtime config override also unblocks validated testing — the current build-time asset swap doesn't work because Gradle caches the APK.

### Phase 9 -- Comprehensive Telemetry Validation Suite (P0)

| ID | Title | Status |
|----|-------|--------|
| US-049 | Validation framework: structured JSON assertion library for collector output (ordered events, timestamps, attribute checks, parent-child spans) | [x] |
| US-050 | Journey: Happy path booking — calendar → book → confirm → appointments; validate page span hierarchy, ui.tap sequence, booking.submit HTTP span, form fill timing | [ ] |
| US-051 | Journey: Browse and refresh — appointments list → swipe-to-refresh × 3; validate ui.scroll events, HTTP retry spans, refresh timing histogram | [ ] |
| US-052 | Journey: Network error recovery — trigger HTTP 500 → verify error span + policy flush → retry succeeds; validate flush_window(5min) fires, error context propagates | [ ] |
| US-053 | Journey: Get directions — location permission → geocode → route; validate 2 child HTTP spans (Nominatim + OSRM) under navigation parent, location attributes present | [ ] |
| US-054 | Journey: Multi-screen navigation breadcrumb — visit all 5 tabs in sequence; validate screen_view log for each, page span start/end ordering, breadcrumb trail in correct order | [ ] |
| US-055 | Journey: Form input lifecycle — BookFragment: tap provider → tap slot → type notes → submit; validate ui.tap, ui.text_input, form.fill_time attribute, booking event with device context | [ ] |
| US-056 | Journey: Session lifecycle — launch → interact → 15min idle → interact again; validate session.id changes after timeout, new session events carry new ID, old session flushed | [ ] |
| US-057 | Journey: App background/foreground — launch → home → return; validate app.background + app.foreground events with correct timestamps, page span paused/resumed | [ ] |
| US-058 | Stress: Battery drain progression — 100% → 5% in steps; validate device.battery_level gauge at each step, mobile.prediction with crash_risk ≥ 0.7 at ≤ 15%, pre-emptive flush trigger | [ ] |
| US-059 | Stress: Thermal throttle escalation — inject thermal status 0→4; validate device.thermal_status gauge, network_loss_risk prediction, SDK behavior at EMERGENCY level | [ ] |
| US-060 | Stress: Memory pressure cascade — inject RUNNING_LOW → CRITICAL; validate device.memory.trim_level logs, available_memory_mb gauge, crash_risk prediction, flush at CRITICAL | [ ] |
| US-061 | Stress: Combined stress — simultaneous battery (12%) + thermal (SEVERE) + memory (CRITICAL); validate combined risk prediction, accelerated flush, all signals present together | [ ] |
| US-062 | Stress: Network loss and recovery — disable wifi+cellular → generate events → reconnect; validate connectivity.change log, buffer accumulation during offline, drain after reconnect | [ ] |
| US-063 | Policy: Crash-triggered conditional flush — accumulate 30 silent events → emit app.crash → validate flush_window(5min) exports exactly the buffered events, verify event count and time window | [ ] |
| US-064 | Policy: HTTP error-triggered flush — accumulate 20 silent events → emit http.error (500) → validate policy match, flush window, error event included in flush batch | [ ] |
| US-065 | Policy: UI freeze-triggered flush — accumulate events → emit ui.freeze (>2s) → validate ui-freeze-detector policy fires, flush_window(2min), freeze event attributes (duration, stack) | [ ] |
| US-066 | Policy: No false flushes — accumulate 50 normal events over 5 minutes with no policy triggers; validate ZERO exports in CONDITIONAL mode (buffer grows, nothing leaves) | [ ] |
| US-067 | Buffer: RAM overflow to disk — generate > ramBufferSize events; validate disk buffer receives overflow, total event count preserved, no data loss, seqId monotonic | [ ] |
| US-068 | Buffer: Disk TTL enforcement — write events, advance clock past diskBufferTtlHours; validate expired events pruned, recent events retained | [ ] |
| US-069 | Buffer: Selective time-window flush — buffer 10min of events → flushWindow(3) → validate only last 3 minutes exported, older events still in buffer | [ ] |
| US-070 | Telemetry ordering: Timestamp monotonicity — run full booking journey; validate all exported events have monotonically increasing observedTimeUnixNano within each scope | [ ] |
| US-071 | Telemetry ordering: Span parent-child integrity — run multi-screen journey; validate every child span's parentSpanId matches an existing span, journey → page → ui.tap hierarchy correct | [ ] |
| US-072 | Telemetry ordering: Cross-signal correlation — run booking + HTTP; validate log event timestamps fall within parent span duration, HTTP span traceId matches page span traceId | [ ] |
| US-073 | Service identity: Resource attributes — validate every exported batch has service.name, service.version, device.id, device.manufacturer, device.model.name, os.name, os.version, telemetry.sdk.* | [ ] |
| US-074 | Sampling: Dynamic sampling correctness — configure dynamic(0.1, 1.0); generate 100 normal + 10 error events; validate ~10% normal sampled, 100% errors sampled (within tolerance) | [ ] |
| US-075 | Export modes: CONTINUOUS periodic flush — set CONTINUOUS with 5s interval; generate events for 20s; validate ≥ 3 export batches with roughly uniform timing | [ ] |
| US-076 | Export modes: HYBRID heartbeat + conditional — set HYBRID; validate device metrics export periodically while event data waits for policy trigger | [ ] |
| US-077 | Validation CI integration — wire validate-telemetry.sh into GitHub Actions as emulator-based job, docker-compose collector, test matrix across API levels 28/33/36 | [ ] |

**Note (2026-04-11):** US-049 (validation framework) is complete. US-050 through US-077 scripts are implemented but have not yet been run against real emulator data for every scenario — marked [ ] pending full emulator validation runs.

**Goal:** Prove that every major user journey produces exactly the right telemetry — right signals, right order, right timestamps, right parent-child relationships — validated against a local OTel Collector. This is the test suite that makes our SDK demonstrably superior: not just "we emit telemetry" but "we emit correct, complete, ordered telemetry for every real-world scenario." The validated test infrastructure from Phase 8 (SharedPreferences runtime config → local collector → file exporter → JSON validation) is the foundation.

**Architecture:** Each test scenario runs as an Espresso instrumented test on an emulator, exporting to a local OTel Collector (Docker) with file exporters. After the scenario completes, a bash validation script reads the JSON output and asserts on structure, ordering, and content. The validation framework (US-049) provides reusable assertion functions: `assert_event_exists`, `assert_event_order`, `assert_span_hierarchy`, `assert_timestamp_monotonic`, `assert_attribute_value`.

**Dependency:** Phase 8 (runtime config override) must be complete (it is). Phase 7 (real crash scenarios) feeds US-063.

---

## Risks

| Risk | Mitigation |
|------|-----------|
| Upstream changes `AndroidInstrumentation` interface | Pin exact versions, monitor releases, adapter is thin |
| OTel SDK version conflict | Gradle resolves to highest (ours); OTel API is backward-compat within 1.x |
| ByteBuddy transitive pull | Only depend on interface artifact, not agent modules |
| Compose detection fails on minified builds | Fall back to `contentDescription`/`Role`; document `testTag` requirements |
| Phase 4 breaks all modules at once | Compile-time-only change; full test suite as safety net |
