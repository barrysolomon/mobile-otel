# Competitive Parity & Superiority Epic

> **Goal:** Close every gap against Datadog and Splunk Mobile RUM, then extend our lead
> **Battle cards:** [vs Datadog](../BATTLE_CARD_VS_DATADOG.md) | [vs Splunk](../BATTLE_CARD_VS_SPLUNK.md)
> **Last updated:** 2026-04-13

---

## Current Standing

**Already ahead of both competitors (validated on-device):**
- On-device policy engine (21 matchers, 10 actions) — evaluated locally, unit tested
- 3 export modes (Conditional / Continuous / Hybrid) — they only have continuous
- Selective time-window flush — neither has this
- Predictive flush (crash risk + network loss risk) — neither has this
- Dual-tier crash-safe buffer with seqId dedup — architecturally superior
- 19 instrumentation modules vs Datadog ~14 / Splunk ~11
- Native OTLP export (Splunk deprecated theirs; Datadog never had it)
- Debug widget — neither has anything comparable
- Zero build-plugin requirement — both competitors require Gradle plugins
- <0.5% battery in conditional mode — neither can offer this

**Claimed but not yet validated end-to-end (requires Phase 13.5):**
- Visual policy editor (React Flow) — builds, but UI → Gateway → SDK loop never tested
- Remote policy updates without app release — polling exists but never tested against live gateway
- "Remote-updatable" policies — on-device engine works with bundled config; remote delivery unvalidated

**Gaps to close (ordered by competitive impact):**

---

## Phase 13.5 — Control Plane End-to-End Validation

**Competitive impact: CRITICAL — underpins our #1 differentiator claim**
**Status: NEVER TESTED END-TO-END**
**Blocking:** Every battle card claim about "visual policy editor" and "remote policy updates"

The control plane (React UI + Go gateway) builds and starts, but the full loop — UI publishes workflow → gateway stores DSL → SDK polls config → policy evaluates → triggers flush — has **never been validated end-to-end**. Both repos were developed in parallel with an assumed contract.

### Known Risks

1. **DSL format mismatch**: SDK's `PolicyEvaluator.fetchConfig()` hits `GET /config` with no `dsl_version` param (gets v1 by default). It expects `{"workflows": [{"nodes": {"trigger": [{"data": {"match": {...}}}]}}]}`. Nobody has verified the v1 compiler (`graphToDSL.ts`) produces exactly that shape.
2. **v2 not negotiated**: SDK doesn't request `?dsl_version=2` — the v2 compiler and all 29 node types may be unreachable from the SDK today.
3. **No frontend tests**: Zero React component tests. The workflow builder could silently produce invalid graphs.
4. **Config polling untested**: `PolicyEvaluator` has a polling loop but it has only been tested with hardcoded/bundled config, never against a live gateway.
5. **Fleet intelligence untested against real devices**: 21 fleet node types, WebSocket push, circuit breakers — all have backend tests but zero device-side validation.

### Work Items

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-E2E-001 | **Contract test: v1 DSL roundtrip** — publish workflow from UI, capture gateway response, feed to SDK's `parseConfig()`, verify policy evaluates correctly | P0 | The single most important test we don't have |
| CP-E2E-002 | **Contract test: v2 DSL roundtrip** — same as above with `?dsl_version=2`, update SDK to negotiate v2 | P0 | Required to use 29 node types |
| CP-E2E-003 | **SDK v2 negotiation** — update `PolicyEvaluator.fetchConfig()` to request `?dsl_version=2` and parse FSM format | P0 | Currently SDK only speaks v1 |
| CP-E2E-004 | **UI → Gateway → SDK live test** — start gateway + UI locally, publish a crash-handler workflow, start demo app pointing at gateway, trigger crash, verify selective flush fires | P0 | The "it actually works" test |
| CP-E2E-005 | **React component tests** — at minimum: WorkflowBuilder graph validity, graphToDSL output shape, graphToDSLv2 output shape | P1 | Prevent silent graph corruption |
| CP-E2E-006 | **Config polling integration test** — SDK polls gateway on interval, receives updated policy, applies it to next event | P1 | Validates the "remote update without app release" claim |
| CP-E2E-007 | **Fleet intelligence smoke test** — publish fleet rules via UI, verify WebSocket push reaches a connected device simulator | P2 | Validates fleet node types |
| CP-E2E-008 | **Bundled config fallback test** — SDK starts with no gateway reachable, falls back to bundled config, later connects and receives remote update | P1 | Graceful degradation |

**Exit criteria:** A human can publish a workflow from the UI, see it take effect on a running demo app, trigger a policy match, and observe the selective flush in Dash0. Automated contract tests prevent regression.

**Why this is Phase 13.5 (not later):** Every competitive differentiator we claim — visual policy editor, remote config updates, on-device intelligence — requires the control plane to actually work. If we ship battle cards claiming these capabilities and a prospect asks for a demo, we need it to work. This phase de-risks Phases 14-21 by validating the integration contract before we port it to iOS.

---

## Phase 14 — iOS SDK Port

**Competitive impact: TABLE STAKES — cannot sell mobile without this**
**Blocked by:** Nothing (Phase 11 security hardening complete)
**Matches:** Datadog (iOS + tvOS + SwiftUI), Splunk (iOS)

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-001 | iOS SDK core — Swift package, buffer, export, session | P0 | Architecture is cross-platform; policy DSL, buffering model are platform-agnostic |
| CP-002 | iOS auto-instrumentation — UIViewController lifecycle, UIKit gestures, URLSession | P0 | Match Datadog's UIKit + URLSession coverage |
| CP-003 | iOS SwiftUI support — view lifecycle, navigation, gesture detection | P1 | Datadog has this; critical for modern iOS apps |
| CP-004 | iOS crash reporting — NSException, Mach exceptions, signal handlers | P0 | Both competitors have this |
| CP-005 | iOS dSYM symbolication — upload + deobfuscation pipeline | P1 | Datadog has CLI tooling; Splunk has built-in |
| CP-006 | iOS policy engine — evaluate DSL on-device (port Kotlin → Swift) | P0 | **Exceeds both** — neither has on-device policy for iOS |
| CP-007 | iOS dual-tier buffer — RAM + Core Data/SQLite, crash-safe | P0 | **Exceeds both** — neither has this for iOS |
| CP-008 | iOS conditional export + selective flush | P0 | **Exceeds both** — unique to us |

**Exit criteria:** iOS SDK with feature parity to Android core — auto-instrumentation, policy engine, dual-tier buffer, conditional export, OTLP. SwiftUI support at P1.

---

## Phase 15 — Crash Symbolication Pipeline

**Competitive impact: HIGH — unreadable stack traces are a dealbreaker for crash triage**
**Matches:** Datadog (ProGuard/R8 auto-upload, NDK symbols, dSYM), Splunk (ProGuard, dSYM)

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-010 | ProGuard/R8 mapping.txt upload — Gradle task or CLI | P0 | Datadog has Gradle plugin auto-upload |
| CP-011 | Server-side deobfuscation — mapping file storage + stack trace rewriting | P0 | In collector processor or Dash0 backend |
| CP-012 | Build ID matching — auto-associate mappings to app versions | P1 | Datadog does this via Gradle plugin |
| CP-013 | dSYM upload for iOS — CLI tool or CI integration | P1 | Blocked on iOS SDK (Phase 14) |
| CP-014 | Symbolication viewer — Dash0 UI shows deobfuscated stack traces | P1 | Can start backend work before iOS ships |

**Exit criteria:** ProGuard/R8 stack traces render as readable source locations in Dash0 dashboards. iOS dSYM follows when Phase 14 ships.

---

## Phase 16 — Session Replay Viewer

**Competitive impact: HIGH — Datadog's strongest mobile differentiator**
**Matches:** Datadog (wireframe-based replay + privacy masking), Splunk (Session Replay add-on)
**We already have:** Screenshot capture + wireframe view hierarchy JSON on-device

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-020 | Wireframe renderer — React component rendering `WireframeNode` JSON as SVG | P0 | Already specced in BACKLOG Track 3 |
| CP-021 | Journey timeline — horizontal filmstrip of wireframe thumbnails ordered by sequence | P0 | Query `ui.wireframe` logs by session |
| CP-022 | Interaction overlay — plot tap/scroll/swipe on wireframes as colored markers | P1 | Correlate `ui.tap` coordinates with wireframe frame |
| CP-023 | Screenshot final frame — render `ui.screenshot` data URL as terminal frame | P1 | Crash state visualization |
| CP-024 | Session picker — filter by screen, error state, duration, device type | P1 | Standard session explorer |
| CP-025 | Privacy masking controls — configurable per-element redaction rules in UI | P1 | Datadog has this; our on-device text redaction is already stronger |
| CP-026 | APM trace correlation in replay — click a network request in the timeline to jump to backend trace | P2 | Datadog's "1-click correlation" — polish differentiator |

**Exit criteria:** Wireframe-based journey replay in control plane UI with interaction overlay, session filtering, and privacy controls. Not pixel-video — wireframe reconstruction (matches Datadog's approach, avoids bandwidth/privacy issues).

**Our advantage even at parity:** Replay + selective flush means we capture wireframes around incidents without continuously uploading them. Datadog captures wireframes from 100% of sampled sessions.

---

## Phase 17 — APM Trace Correlation

**Competitive impact: MEDIUM-HIGH — SREs need mobile → backend trace linkage**
**Matches:** Datadog (W3C traceparent + DD headers, 1-click UI), Splunk (Server-Timing header)

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-030 | W3C Trace Context propagation — inject `traceparent` in OkHttp interceptor | P0 | Standard OTel; may already be partial via upstream |
| CP-031 | Server-side trace stitching — collector processor links mobile spans to backend spans | P1 | Our collector processor can do this |
| CP-032 | Dash0 UI correlation — click mobile network span → jump to backend trace | P1 | Depends on Dash0 platform UI work |
| CP-033 | `Server-Timing` response header parsing — extract backend trace ID from response | P2 | Splunk's approach; nice-to-have |

**Exit criteria:** Mobile network request spans carry W3C trace context; Dash0 UI can navigate from mobile span to backend trace.

---

## Phase 18 — NDK / Native Crash Reporting

**Competitive impact: MEDIUM — matters for apps with C++ / game engines / Flutter**
**Matches:** Datadog (NDK crash reports + symbol upload)

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-040 | NDK crash handler — signal handler for SIGSEGV/SIGABRT/SIGBUS | P2 | Datadog uses `NdkCrashReports.enable()` opt-in |
| CP-041 | Minidump or tombstone capture — persist native crash data to disk | P2 | Must survive process death |
| CP-042 | Native symbol upload — CLI/Gradle task for .so debug symbols | P2 | Datadog has Gradle task |
| CP-043 | Native stack trace symbolication — server-side | P2 | Requires symbol server |

**Exit criteria:** Native crashes captured, persisted, and symbolicated. Opt-in module.

**Defer rationale:** Most Kotlin/Java Android apps don't need NDK crash reporting. Prioritize after iOS and symbolication.

---

## Phase 19 — Cross-Platform Framework Support

**Competitive impact: MEDIUM — React Native and Flutter are growing fast**
**Matches:** Datadog (React Native SDK + Session Replay, Kotlin Multiplatform), Splunk (React Native)

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-050 | React Native bridge — JS → native SDK bridge for RN apps | P2 | Datadog has full RN SDK |
| CP-051 | Flutter plugin — Dart → native SDK bridge | P2 | Neither competitor has official Flutter RUM |
| CP-052 | Kotlin Multiplatform — shared SDK code for Android + iOS | P2 | Datadog supports KMP |

**Exit criteria:** At least one cross-platform framework bridge. Flutter is a whitespace opportunity (neither DD nor Splunk has it).

**Strategic note:** Flutter plugin would be a unique competitive advantage — neither Datadog nor Splunk offers official Flutter RUM support.

---

## Phase 20 — Resource Timing & Network Depth

**Competitive impact: MEDIUM — Datadog has more granular network instrumentation**
**Matches:** Datadog (DNS, TLS, connect, first byte, download timing per request)

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-060 | Connection timing breakdown — DNS, TLS, connect phases via OkHttp EventListener | P1 | Datadog captures all timing phases |
| CP-061 | GraphQL instrumentation — operation name, type, variables extraction | P2 | Datadog has Apollo client support |
| CP-062 | WebSocket instrumentation — connection lifecycle, frame tracking | P2 | Already specced in backlog (Phase 2b) |
| CP-063 | Cronet / alternative HTTP client support | P2 | Datadog supports Cronet |

**Exit criteria:** Network spans include DNS/TLS/connect timing breakdown. GraphQL operation-level instrumentation.

---

## Phase 21 — Advanced Error Tracking

**Competitive impact: MEDIUM — Datadog's error grouping and version tracking is polished**
**Matches:** Datadog (error grouping into issues, deployment tracking, version regression detection)

| ID | Item | Priority | Notes |
|----|------|----------|-------|
| CP-070 | Error grouping — cluster similar crashes into issues by stack signature | P1 | Datadog auto-groups; Splunk has basic grouping |
| CP-071 | Version regression detection — flag new error patterns per app version | P1 | Datadog highlights which release introduced errors |
| CP-072 | Crash-free session rate — per version, per device segment | P1 | Standard stability metric |
| CP-073 | Instant crash alerts — configurable thresholds on error rate spikes | P2 | Datadog has this; Dash0 platform may handle |

**Exit criteria:** Crashes grouped into issues, version-over-version regression tracking, crash-free rate metric.

**Note:** Much of this may be handled by Dash0 platform capabilities rather than SDK-level work.

---

## Competitive Parity Milestones

| Milestone | Phases | What it means |
|-----------|--------|---------------|
| **"Demo-ready"** | 13.5 | Control plane E2E validated → can demo the full loop honestly |
| **"Credible alternative"** | + 14 + 15 | iOS + symbolication → can be evaluated seriously |
| **"Feature competitive"** | + 16 + 17 | Session replay + APM correlation → no deal-breaking gaps |
| **"Architecturally superior"** | 13.5 validates this | On-device intelligence, OTLP, cost efficiency — proven, not just claimed |
| **"Category leader"** | + 20 + 21 | Deeper signals + better error tracking + our unique architecture |

---

## Items We Already Exceed Both Competitors On (No Work Needed)

These are pure advantages to emphasize in sales conversations:

| Capability | Our Advantage |
|------------|---------------|
| On-device policy engine | 21 matchers, 10 actions — they have zero |
| Conditional / Hybrid export | 3 modes — they only do continuous |
| Selective time-window flush | Flush last N minutes — they flush everything |
| Predictive flush | Crash risk + network loss risk → pre-emptive export |
| Battery efficiency | <0.5% conditional — they're 3-5% |
| Dual-tier crash-safe buffer | RAM + SQLite with seqId dedup |
| OTLP native | Standard export — Splunk deprecated, Datadog never had |
| Remote policy update | No app release — they require redeployment |
| Visual policy editor | React Flow graph — they have YAML/code only |
| Debug widget | Live in-app overlay — they have verbose logging |
| Zero build-plugin | Runtime interceptors — both require Gradle plugins |
| Text input tracking | EditText focus/blur — neither has this |
| Screen orientation | Rotation tracking — neither has this |
| Database instrumentation | Room/SQLite spans — neither auto-instruments |
| File I/O instrumentation | File operation spans — neither has this |
| System events | Battery, power, thermal — neither has this |
| Journey breadcrumbs | 50-entry circular buffer on crash — they have timeline views |

---

## Integration with Existing Roadmap

| Existing Phase | Status | Competitive Relevance |
|----------------|--------|----------------------|
| Phases 1-9 | COMPLETE | Foundation, upstream supersession, validation |
| Phase 10 | NOT STARTED | Production readiness — prerequisite for credibility |
| Phase 11 | COMPLETE | Security hardening |
| Phase 12 | NOT STARTED | Tech debt — enabler for Phase 14+ |
| Phase 13 | NOT STARTED | Sentry parity (breadcrumbs, Timber, system events, frame timing) — overlaps with competitive advantage list |
| **Phase 14** | **NOT STARTED** | **iOS SDK — #1 competitive priority** |
| **Phases 15-21** | **NEW** | **Competitive parity & superiority roadmap** |

**Recommended execution order:**
1. **Phase 13.5 (control plane E2E)** — validate our #1 differentiator claim before anything else
2. Phase 14 (iOS SDK) — table stakes, highest competitive impact
3. Phase 10 (production readiness) — CI/CD, publishing, credibility
4. Phase 15 (crash symbolication) — closes #2 gap
5. Phase 16 (session replay viewer) — closes #3 gap, leverages existing capture primitives
6. Phase 17 (APM correlation) — connects mobile → backend story
7. Phase 12 + 13 (tech debt + Sentry parity) — quality + depth
8. Phases 18-21 (NDK, cross-platform, network depth, error tracking) — extend lead
