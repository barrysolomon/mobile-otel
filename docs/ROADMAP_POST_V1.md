# Post-v1.0 Roadmap

**Status:** forward-looking. `v1.0.0` GA shipped **2026-07-14** (`main @ 9b56bc2d`).

This is the **forward-looking companion** to [BACKLOG.md](../BACKLOG.md) (the detailed,
track-by-track tracker) and [ROADMAP.md](../ROADMAP.md) (the pre-1.0 audited roadmap,
preserved for history). BACKLOG.md remains the source of truth for individual work
items and their state; this document groups the ~80 open ideas into themed tracks,
tiers them by likely sequencing, and folds in context (parked epics, the Innovapptive
offline-sync driver, and the post-1.0 hygiene leftovers) that lives outside the repo
files.

## SemVer is now in force

As of `1.0.0`, the full [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
guarantee applies:

- Un-annotated public symbols are **stable** — a breaking change requires a **MAJOR**
  bump plus a deprecation cycle.
- `@Incubating` / experimental symbols may still change in a MINOR.
- Version parity is enforced across all four code surfaces (npm / gradle / iOS
  `ResourceBuilder` / RN distro) plus the git tag.

Everything below must respect this contract. Anything that changes a stable public API
is a MAJOR-version candidate and is flagged as such where it applies (e.g. the namespace
move).

**Effort key:** S (<1 day) · M (1–3 days) · L (1–2 weeks) · XL (>2 weeks).
**Repo key:** items live in **this repo** unless marked
**[control-plane]** (sister repo [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane))
or **[Dash0 backend]** (server-side platform work, out of this repo).

---

## Recently shipped in 1.0.0 (reference)

For the full list see [CHANGELOG.md](../CHANGELOG.md). Headline items so this roadmap has
context for what is already done:

- **v1.0.0 GA** — SemVer-stable, four-surface version parity, GA soak complete.
- **SR-007 / SR-010 / SR-015** production-hardening fixes (deferred VACUUM, lock-free
  trigger eval, logical disk-budget on both Android **and** iOS).
- **#66** iOS CI executor-starvation guard fixed (runs via `swiftpm-testing-helper`).
- **Apache-2.0 license headers** across all source (upstream-derived §4(c) notices kept).
- **golangci-lint + ktlint** CI gates added.
- Pre-1.0 foundation already GA: on-device policy engine (CONDITIONAL/CONTINUOUS/HYBRID),
  dual-tier RAM+SQLite buffering + crash recovery, iOS SDK, React Native bridge,
  symbolication Phases 1–2 (`app.build.id` + `symbol-upload` CLI), upstream
  `opentelemetry-android` convergence.

---

## Tracks

### Track A — SDK Completeness & Auto-Instrumentation Depth

Fill the remaining auto-capture gaps and deepen instrumentation coverage. Highest
density of small, unblocked, high-value work.

| Idea | Effort | Dependencies | Notes |
|------|--------|--------------|-------|
| Compose Navigation support — intercept `NavHostController` events for breadcrumbs | M | module already wired into umbrella (N13b) | P1 auto-capture gap |
| Early event queue — buffer up to 100 events pre-init, replay after `start()` | M | — | improves cold-start capture fidelity |
| iOS UIKit auto-capture parity — tap/scroll/text-input (today opt-in SwiftUI only) | M | existing UIKit swizzle pattern | no-platform-drift |
| `identify()` / `UserIdentity` → iOS + RN (Android-only today) | M | RN bridge | table-stakes; user-impact analysis depends on it |
| App-start depth — TTFD (`reportFullyDrawn()`/`reportFullyDisplayed()`), hot-start, iOS prewarm detection, first-frame via CADisplayLink | M | `AppStartInstrumentation` (TTID plumbing exists) | Android TTFD half is S |
| Map invented `mobile.*` attributes to OTel `app.*` semconv; document the rest as vendor extensions | M | — | resolves the project's own OTel-native rule violation |
| WorkManager / background-job instrumentation | M | — | top mobile-perf blind spot, zero coverage today |
| WebView / hybrid instrumentation — link webview pages into native session/journey | L | — | enterprise apps are hybrid |
| Feature-flag context API — `recordFeatureFlag()` (OTel `feature_flag` semconv) + LaunchDarkly/Firebase adapters | M | — | flags-at-crash-time, OTel-native differentiator |
| Compose recomposition / frame-perf module | L | Compose Runtime observation | pairs with existing jank detection |
| Network wrapper cleanup — resolve dead `@Supersedes("okhttp")` `NetworkInstrumentation` name-collision | S | — | pick blessed path or delete |
| Swift 6 strict-concurrency readiness — 43 `@unchecked Sendable` sites + `-strict-concurrency=complete` CI job | M | — | future-proofing |
| iOS NSFileProtection — encrypt at-rest stores + exclude from backup | M | — | *(tracked)* screenshots/URLs/crash data cleartext today |
| iOS RetryableExporter auth-error detection — stop burning retry budget on 401/403 | M | HTTPClient-layer wrap (OTLP decorator-trap pattern) | *(tracked)* |

### Track B — Testing, Validation & CI Depth

The single largest open track in BACKLOG.md (Phase 9 validation). Depends on the custom
collector (Track C) for the collector-output assertions.

| Idea | Effort | Dependencies | Notes |
|------|--------|--------------|-------|
| Collector integration tests — processor in a real collector, full OTLP pipeline, policy matching, annotation propagation (~20 tests) | L | custom collector (Track C) | |
| Validation framework — structured JSON assertion library over collector output (existence, ordering, timestamp monotonicity, span hierarchy, attribute checks) | M | collector | underpins all validation below |
| User-journey validation — 8 journeys (booking, browse+refresh, error recovery, directions, multi-tab, form lifecycle, session lifecycle, bg/fg) | M | validation framework | |
| Stress-signal validation — battery/thermal/memory/network — verify device metrics + prediction scores + flush triggers | M | validation framework | |
| Policy-flush validation — crash / HTTP-error / UI-freeze triggered + no-false-flush | M | validation framework | |
| Buffer validation — RAM→disk overflow, disk TTL, selective window flush | M | validation framework | |
| Telemetry-ordering validation — timestamp monotonicity, span parent-child integrity, cross-signal correlation | M | validation framework | |
| Export-mode validation — CONTINUOUS timing, HYBRID heartbeat+conditional, CONDITIONAL zero-export baseline | M | validation framework | |
| CI integration — GitHub Actions + Docker collector, matrix API 28/33/36 | M | collector | |
| E2E test scripts — Android→Collector→Backend for 3 demo scenarios, `demo_run_id` correlation | M | collector | P2 |
| Performance benchmarks — capture latency, policy-eval time, export throughput, memory profiling | L | — | #1 battle-card metric; repurpose stress scripts |
| Load tests — 10K events/sec, RAM overflow & disk buffer under load | M | — | P2 |
| Coverage measurement — Kover + jest `coverageThreshold` + `xcodebuild -enableCodeCoverage` + go coverprofile; report-only ratchet first | M | — | TEST_PLAN claims 100% but no tooling exists |
| API / binary-compatibility gates — binary-compatibility-validator (Android), api-extractor (RN), swift-api-digester | M | — | install while surface is small; mandatory now at 1.0 |
| iOS + RN size budgets (twin of the AAR gate) | S | — | repo's own drift rule violated in CI |
| Per-push iOS host tests (split host-safe logic from nightly-only 530) | M | — | |
| Device-matrix breadth — API 26/28/34/36 + 2–3 iOS runtimes nightly; periodic real-device crash/recovery | M | — | *(tracked)* |
| UAT matrix into CI — nightly 3-cell × 4 platforms, weekly full Android sweep | L | — | *(tracked)*; blocker resolved |
| Dependabot + CodeQL across all five ecosystems | S/M | — | stdlib drift (#60) already bit once |

### Track C — Custom Collector & Server-Side Processing

Build and publish the custom collector; unblocks Track B integration tests and Track E
trace stitching.

| Idea | Effort | Dependencies | Notes |
|------|--------|--------------|-------|
| `builder-config.yaml` for OpenTelemetry Collector Builder (ocb) | S | — | P0 for this track |
| Build custom collector binary with `mobilepolicyprocessor` | S | ocb config | |
| Dockerfile for `otelcol-mobile` | S | binary | |
| Verify processor loads + OTLP pipeline works | S | Dockerfile | |
| Collector-processor modernization decision — bump off frozen 0.91.0 (CVE exposure), publish to GHCR, unify the 3 policy schemas (shared JSON Schema), make control-plane consume it — **or** demote to explicit side-quest | M | — | resolve the "same DSL server-side" overclaim |
| GoDoc on all exported collector types/functions | M | — | |

### Track D — Crash & Error Intelligence

The #1 competitive gap area. Symbolication Phases 1–2 shipped; the rest is backend +
new capture surfaces.

| Idea | Effort | Dependencies | Notes / repo |
|------|--------|--------------|--------------|
| Symbolication Phase 3 — server-side deobfuscation (mapping storage + stack rewriting: R8 retrace / `atos` / source-map) | L | Phase 1–2 build-id store (done) | **[Dash0 backend]** |
| Symbolication Phase 4 — retroactive re-symbolication (Embrace parity) | L | Phase 3 | **[Dash0 backend]** |
| ProGuard/R8 symbolication in-SDK — parse `mapping.txt` to deobfuscate in `ErrorInstrumentation` | M | — | currently stubbed; complements backend track |
| RN source-map symbolication — Hermes `.hbc` handling + Metro post-`bundle` hook injecting build-id into `start({ buildId })` | L | RN bridge (done); `symbol-upload react-native` (done) | P0 for RN |
| MetricKit on iOS — `MXCrashDiagnostic`/`MXHangDiagnostic` for watchdog terminations (`0x8badf00d`), disk-write exceptions, hangs | M | — | structurally invisible to signal handlers |
| ApplicationExitInfo on Android — fatal ANRs, OOM/system kills via `getHistoricalProcessExitReasons` on launch | L | — | pairs with `app.recovery_start` |
| NDK / native crash handler — SIGSEGV/SIGABRT/SIGBUS | XL | — | P2; hard-blocks games segment + Flutter engine crashes |
| Native symbol upload + server-side native symbolication | XL | NDK handler | **[Dash0 backend]** for the resolve half |
| Error grouping — cluster by stack signature | M | — | Phase 21 |
| Version-regression detection — flag new errors per app version | M | error grouping | Phase 21 |
| Crash-free session rate metric — emit semconv session start/end + abnormal-exit + version | L | — | *(tracked)*; aggregation belongs in Dash0 platform — clarify SDK-vs-platform ownership first |

### Track E — Distributed Tracing & APM Correlation

Mobile→backend linkage; SREs' most-requested correlation.

| Idea | Effort | Dependencies | Notes / repo |
|------|--------|--------------|--------------|
| W3C Trace Context propagation in OkHttp interceptor | M | — | highest-value APM item |
| Server-side trace stitching (collector processor) | M | custom collector (Track C) | |
| Dash0 UI correlation — mobile span → backend trace | M | trace stitching | **[Dash0 backend]** |
| Network depth — OkHttp `EventListener` DNS/TLS/connect timing breakdown | S | — | closes the demo gap |
| GraphQL / Apollo operation-level instrumentation | M | — | |
| WebSocket instrumentation — OkHttp `WebSocket` spans (`WebSocketInstrumentation` + `OTelWebSocketListener`) | M | — | specced in Track 7 Phase 2b |
| `android.util.Log` bridge (`AndroidLogInstrumentation`) | S | — | optional, P2 |
| gRPC `ClientInterceptor` for enterprise backends | M | — | on demand |

### Track F — Offline-Sync Instrumentation (Innovapptive-driven)

Deal-driven track for the **Innovapptive** prospect: industrial mobile app requiring
offline sync over **AWS Amplify DataStore + MongoDB Realm**, beta planned. Frames the
Amplify/Realm backlog under a real use case; gated on that engagement's timeline.

| Idea | Effort | Dependencies | Notes |
|------|--------|--------------|-------|
| Amplify DataStore sync lifecycle spans (start, query, save, delete) | M | — | |
| Amplify Hub event capture (syncStarted, modelSynced, outboxMutation*) | M | — | |
| Amplify conflict-resolution tracking (`ConflictHandler` spans) | M | — | |
| Realm `SyncSession` lifecycle spans (state changes) | M | — | |
| Realm sync progress metrics (transferred/transferable bytes) | S | — | |
| Realm `SyncException` capture (compensating writes, client reset) | M | — | |
| Network-correlated sync failures (attach connectivity to sync spans) | M | — | |
| Sync-failure → selective-flush policy (built-in DSL trigger) | S | policy engine (done) | |
| Sync journey breadcrumbs (sync events in breadcrumb buffer) | S | breadcrumb buffer (done) | |
| Realm instrumentation for RN | M | RN bridge (done) | Innovapptive RN follow-up |
| Amplify DataStore for RN | M | RN bridge (done) | Innovapptive RN follow-up |

### Track G — Cross-Platform & Framework Support

Widen the framework surface beyond Android/iOS/RN.

| Idea | Effort | Dependencies | Notes |
|------|--------|--------------|-------|
| Flutter plugin (Dart → native SDKs) | XL | native SDKs (done) | "whitespace" claim is **stale** — Sentry/Embrace/Datadog/Faro all ship Flutter; RN bridge is the proven template |
| Expo config plugin (RN follow-up) | M | RN bridge | *(tracked)* EXPO-001 — named RN growth blocker |
| RN old-arch support statement | S | — | |
| RN per-trigger screenshot config through the bridge | M | — | |
| react-navigation peer-dep + tested-versions table | S | — | |
| RN version matrix backing the ≥0.72 claim | M | — | |
| Cross-platform demo app — iOS-native + React Native versions of the scheduling demo (Android-only today) | L | — | **parked epic**; feeds demos on all platforms |
| Explicitly park Unity / tvOS with rationale | S | — | |

### Track H — Standardization & Upstream Contribution

Establish the patterns as OTel standard; PROPOSAL stage since 2026-06-25.

| Idea | Effort | Dependencies | Notes |
|------|--------|--------------|-------|
| OTEP: Mobile Buffering Pattern — two-tier ring buffer, overflow, TTL, crash recovery | M | — | draft exists in `docs/OTEPs/` |
| OTEP: Conditional Export for Mobile — policy DSL, operators, actions, collector integration | M | — | draft exists in `docs/OTEPs/` |
| Submit OTEP PRs to `opentelemetry-specification` | M | OTEP drafts | pick the `@Supersedes`-vs-`view.click` reconciliation path first |
| Present at OTel SIG meetings (Android SIG, Collector SIG) | S | — | |
| PR: Android library → `opentelemetry-android` or `-contrib` | XL | stabilize WindowEventHub | opentelemetry-swift fire-and-forget exporter bug is a good starter |
| PR: collector processor → `opentelemetry-collector-contrib` | L | Track C modernization | |
| Respond to community review, iterate | L | PRs open | |

### Track I — Documentation & Developer Experience

Biggest gap vs Sentry/Datadog/Embrace is a rendered docs site + generated API reference.

| Idea | Effort | Dependencies | Notes |
|------|--------|--------------|-------|
| KDoc on all public Android classes/methods | M | — | |
| GoDoc on collector exports | M | — | (also in Track C) |
| Docs site — Phase 1: publish generated API docs (Dokka/DocC/TypeDoc) to GitHub Pages | L | KDoc/GoDoc | |
| Docs site — Phase 2: Docusaurus/MkDocs portal | XL | Phase 1 | |
| Tutorials — Android integration, collector-processor config, custom export policies | M | — | P2 |
| Architecture diagrams — capture→buffer→export, policy-eval→flush, Android component diagram | S | — | P2 |
| Fix broken doc links + lychee CI job | M | — | ~45 broken links audited pre-1.0 |
| Merge the 3 overlapping configuration guides | M | — | |
| Split internal docs from adopter docs | M | — | ~170 files, ~50 indexed; sales/scratch pollute the tree |
| iOS + RN TROUBLESHOOTING coverage (Android+collector only today) | M | — | UIScene / forceFlushBuffered / stdlib-pin known issues |
| One product name across all entry points | S | — | "Mobile OTel SDK" vs "Dash0 Mobile Observability SDK" vs "OpenTelemetry Mobile Extensions" |
| Community — enable Discussions, issue/PR templates, CODEOWNERS, OpenSSF badge, BATTLE_CARD_VS_EMBRACE | S/M | — | |

### Track J — Distribution, Governance & Post-1.0 Hygiene

Trust and distribution-maturity signals; includes the leftovers from the 1.0 push and
two parked epics.

| Idea | Effort | Dependencies | Notes |
|------|--------|--------------|-------|
| Finish Maven Central publishing — owner accounts + one gradle line (signing/POMs/gated CI already wired) | S | org account decision | *(tracked N11)*; personal Pages Maven reads as hobby-project to enterprises |
| Namespace / org decision — repo `github.com/barrysolomon`, Maven `io.github.barrysolomon`, npm `@barrysolomon/` | L | org decision | **MAJOR** — breaking coordinate change on all 3 channels; force decision **before** Innovapptive adoption grows |
| IP-disclaimer / provenance / copyright-attribution decision — shipped with "Copyright 2025 Barry Solomon"; the attribution/provenance-header decision was **never finalized** | S | owner decision | **unresolved 1.0 leftover** |
| Demo/example Dependabot cleanup — 1 critical (`vitest`) + highs, all in `examples/demo-backend` / example Gemfiles — **not in the shipped SDK** | S | — | **1.0 leftover**; hygiene only |
| Collector-management tool — expand the demo control center into a full mobile-collector management tool | XL | — | **parked epic** |
| Collector security review — third-party security review + penetration testing of the mobile collectors | L | — | **parked epic** |
| GitHub Releases from CHANGELOG (automated) | S | — | |
| Control-plane CI — Go tests + Playwright suite (run on memory today, zero CI) | M | — | **[control-plane]** |

### Track K — Session Replay & Journey Visualization **[control-plane]**

> **Repo boundary:** the entire visual replay UI (React/SVG renderer, timelines,
> overlays, session pickers) lives in the **sister repo**
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane).
> The **capture primitives** (`ui.wireframe`, `ui.screenshot`, `ui.tap`/`scroll`/`swipe`
> events) already ship from this repo and are Incubating. Do **not** look for the
> renderer here.

Strategic decision first: invest in continuous wireframe replay + masking rules, **or**
explicitly own the "queryable OTel journey" narrative in collateral. The in-between loses
replay deals while paying replay maintenance.

| Idea | Effort | Repo |
|------|--------|------|
| Wireframe renderer — React component rendering `WireframeNode` JSON as SVG | L | **[control-plane]** |
| Journey timeline — horizontal filmstrip by `mobile.session.id`, ordered by `mobile.wireframe.sequence` | M | **[control-plane]** |
| Interaction overlay — plot tap/scroll/swipe events on the matching wireframe frame | M | **[control-plane]** |
| Screenshot final frame — render terminal `ui.screenshot` data URL (e.g. crash state) | S | **[control-plane]** |
| Journey diff — side-by-side wireframe sequences, highlight structural deltas | M | **[control-plane]** |
| Session picker — filter by screen/error/duration/device, click to open replay | M | **[control-plane]** |
| Wireframe-to-code mapping — click a node → `resource_id` + source link | M | **[control-plane]** |
| Config-negotiation E2E — v1/v2 DSL roundtrip contract tests, SDK v2 FSM negotiation, live publish→poll→trigger→flush test | M | this repo + **[control-plane]** |

---

## Priority tiering

A cross-track view of what is likely next vs aspirational. This is sequencing judgment,
not a commitment; BACKLOG.md priorities (P0/P1/P2) still govern within each track.

### Near-term (next few releases)

Small, high-value, unblocked SDK work + the 1.0 hygiene leftovers.

- **Post-1.0 hygiene:** demo Dependabot cleanup (Track J), IP/copyright-header decision
  (Track J), Maven Central finish (Track J).
- **SDK gaps:** Compose Navigation, early event queue, `identify()` on iOS+RN,
  Android TTFD, `mobile.*`→semconv mapping, network wrapper cleanup (Track A).
- **Custom collector:** ocb config → binary → Dockerfile → pipeline verify (Track C) —
  unblocks the whole validation track.
- **Collector integration tests + validation framework** (Track B, once the collector
  exists).
- **CI hygiene:** Dependabot + CodeQL, coverage measurement (report-only), API/binary-compat
  gates, iOS/RN size budgets (Track B).
- **Docs:** KDoc/GoDoc, fix broken links + lychee CI, one product name (Track I).
- **W3C trace context propagation** in the OkHttp interceptor (Track E) — highest-value,
  low-cost APM item.

### Mid-term

Competitive-parity depth; several depend on near-term foundations.

- **Crash story:** ProGuard/R8 in-SDK symbolication, MetricKit (iOS), ApplicationExitInfo
  (Android), error grouping + version-regression detection (Track D).
- **Full Phase-9 validation suite** + CI integration + performance benchmarks (Track B).
- **Trace stitching** (server-side) + network depth (DNS/TLS/connect, GraphQL, WebSocket)
  (Tracks C, E).
- **Instrumentation depth:** WorkManager, feature-flag API, iOS UIKit auto-capture parity,
  app-start depth (Track A).
- **Offline-sync (Innovapptive):** Amplify + Realm instrumentation — timing gated on the
  deal (Track F).
- **Docs site Phase 1** (generated API reference) + tutorials (Track I).
- **Expo config plugin** and RN follow-ups (Track G).
- **OTEP drafts finalized + submitted**, SIG presentations (Track H).

### Long-term / exploratory

Strategic bets, backend-heavy, or org-gated.

- **Standardization:** upstream PRs to `opentelemetry-android` / `-collector-contrib`,
  full community-review cycle (Track H).
- **Flutter plugin** (Track G).
- **NDK / native crash capture + native symbolication** (Track D).
- **Symbolication Phases 3–4** (server-side + retroactive) — **[Dash0 backend]** (Track D).
- **Session replay viewer** — full wireframe/journey UI, pending the replay-vs-narrative
  strategic decision — **[control-plane]** (Track K).
- **Namespace / org move** — MAJOR-version breaking change; force the decision before
  adoption grows (Track J).
- **Crash-free session rate** + release-health aggregation — SDK-vs-platform ownership
  decision first (Track D).
- **Collector-management tool** and **collector security review** — parked epics (Track J).
- **WebView/hybrid** and **Compose recomposition** modules (Track A).
- **Docs site Phase 2** (full portal) (Track I).

---

## See also

- [BACKLOG.md](../BACKLOG.md) — detailed, per-item tracker (the source of truth for state)
- [ROADMAP.md](../ROADMAP.md) — pre-1.0 audited roadmap (historical)
- [CHANGELOG.md](../CHANGELOG.md) — what shipped, per release
- [docs/VERSIONING.md](VERSIONING.md) — version policy and stability guarantees
