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
| US-026 | Converge `InstrumentationContext` to embed `InstallationContext` | [ ] |
| US-027 | Converge `MobileInstrumentation extends AndroidInstrumentation` | [ ] |
| US-028 | Update all 20+ modules to converged interface | [ ] |
| US-029 | Remove `MobileInstrumentationAdapter` | [ ] |
| US-030 | Update iOS port spec section 5 (full rewrite) | [ ] |
| US-031 | Full regression test pass | [ ] |

## Dependencies

```
Phase 1 (Foundation)
    |
    +---> Phase 2a (Compose + Screen Orientation)  --+
    +---> Phase 2b (WebSocket + Android Log)         +---> Phase 4 (Convergence)
    +---> Phase 3 (DSL + Customizers + Compat)     --+
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

### Phase 7 -- Real Crash Scenarios (P1, needs design)

| ID | Title | Status |
|----|-------|--------|
| US-042 | Replace faked crash in ConditionalFlushScenarios with real uncaught exception crash + recovery | [ ] |
| US-043 | Add real ANR scenario (block main thread >5s, recover, verify telemetry flush) | [ ] |
| US-044 | Verify crash-recovery telemetry includes full pre-crash context window | [ ] |

**Goal:** The conditional flush demo currently fakes crashes. Real crashes (uncaught exceptions that kill the process) followed by app restart should demonstrate that the dual-tier buffer survives process death and flushes the pre-crash context on next launch. This is the most compelling demo of why our architecture is superior.

### Phase 8 -- Configuration Documentation + Runtime Config (P1)

| ID | Title | Status |
|----|-------|--------|
| US-045 | User-facing configuration guide: how to set up otel-config.json, what each field does, examples | [ ] |
| US-046 | Technical docs: MobileConfig field reference, ExportMode behavior, buffer tuning, sampling | [ ] |
| US-047 | Runtime config override mechanism (SharedPreferences or intent) for test/debug switching | [ ] |
| US-048 | Fix validated tests: use runtime config override instead of build-time asset swap | [ ] |

**Goal:** Developers need to know how to configure the SDK. Currently the only reference is the code and CLAUDE.md. Need a proper configuration guide (user-facing) and API reference (technical). The runtime config override also unblocks validated testing — the current build-time asset swap doesn't work because Gradle caches the APK.

---

## Risks

| Risk | Mitigation |
|------|-----------|
| Upstream changes `AndroidInstrumentation` interface | Pin exact versions, monitor releases, adapter is thin |
| OTel SDK version conflict | Gradle resolves to highest (ours); OTel API is backward-compat within 1.x |
| ByteBuddy transitive pull | Only depend on interface artifact, not agent modules |
| Compose detection fails on minified builds | Fall back to `contentDescription`/`Role`; document `testTag` requirements |
| Phase 4 breaks all modules at once | Compile-time-only change; full test suite as safety net |
