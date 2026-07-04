# Roadmap: Becoming the Best Mobile OTel SDK

**Audited:** 2026-07-03 against `main` @ `ae772dfa` (v0.5.2-alpha)
**Method:** 12-dimension audit (Android core, iOS, React Native, instrumentation modules, docs, testing/CI/release, OTel compliance, competitive landscape, ops/security/privacy/perf, backlog inventory, ecosystem repos, OSS hygiene). Findings cite `file:line` evidence. Items already tracked in BACKLOG.md/epics are marked *(tracked)*.
**How to read:** Three horizons — **NOW** (0–2 weeks, trust/correctness blockers), **NEXT** (2–8 weeks, competitive parity), **LATER** (quarter+, strategic bets). Effort: S(<1d) M(1–3d) L(1–2wk) XL(>2wk).

---

## Where the SDK already wins (don't break these)

- **On-device policy engine + CONDITIONAL/CONTINUOUS/HYBRID export modes** — no competitor researched (Datadog, Sentry, Embrace, Splunk) has conditional on-device export. This is *the* differentiator.
- **Offline durability** — dual-tier RAM+SQLite buffering, offline flush budget, crash persistence + `app.recovery_start`, proven by 48/48 green UAT cells across 4 platforms.
- **Vendor-neutral OTLP** — strongest anti-lock-in story in the market; Datadog has no OTLP export, Splunk deprecated theirs.
- **Engineering hygiene rare at this stage** — zero TODO/FIXME debt in Android core, per-symbol API stability contract (docs/API_STABILITY.md), 700KB AAR budget + R8 consumer gate in CI, honest parity matrices, async-signal-safe iOS crash handler that chains prior handlers.
- **Remote config is real** — `PolicyEvaluator.fetchConfig()` polls `/config?dsl_version=2` with optional HMAC-SHA256 verification; `RemoteGate` (kill switch + sampling override) exists on Android (`RemoteGate.kt:88-96`).
- **Three-platform parity discipline** with UAT proof — ahead of upstream, which has no cross-platform story.

---

## NOW — trust & correctness blockers (0–2 weeks)

These are cheap, and every one of them is the kind of thing an evaluating enterprise (or App Store review) uses to disqualify an SDK.

### Security / privacy P0s

| # | Item | Evidence | Effort |
|---|------|----------|--------|
| N1 | ✅ **CLOSED (was already fixed — audit claim was stale)**: no header/token logging exists; `MobileLoggerProvider.kt` carries a `SECURITY:` guard comment forbidding reintroduction | verified 2026-07-03 against code | — |
| N2 | ✅ **CLOSED (was already fixed — audit claim was stale)**: `Dash0RedactionPolicy.shouldRedact` masks `isSecureTextEntry` fields + tagged SwiftUI views, with redact-all-text mode; covered by ScreenshotInstrumentationTests | `OTelMobileCore/Capture/Dash0Redaction.swift` | — |
| N3 | ✅ **CLOSED (was already fixed — audit claim was stale)**: `AutoCaptureOptions.default = all.subtracting([.screenshot, .wireframe])`, matching Android; pinned by `MobileConfigTests` | `AutoCaptureOptions.swift:30` | — |
| N4 | ✅ **DONE 2026-07-03**: `PrivacyInfo.xcprivacy` shipped for `OTelMobileCore` (SystemBootTime 35F9.1) and `OTelMobileSDK` (UserDefaults CA92.1, DiskSpace E174.1, SystemBootTime 35F9.1 + collected-data declarations, no tracking); registered as SPM resources; guarded by `PrivacyManifestTests` (7 tests). Note: audit's "file timestamps" claim was wrong — no such API is used | `otel-ios-mobile/Sources/*/PrivacyInfo.xcprivacy` | — |
| N5 | ✅ **DONE 2026-07-03**: SECURITY.md added (channels, timelines, scope); GitHub Private Vulnerability Reporting enabled via API; CONTRIBUTING.md now links it | SECURITY.md | — |
| N6 | **Crash-loop self-disable** — SDK never disables itself after repeated crashes; SDK_SAFETY.md flags the risk with no mitigation. Count crash markers on launch → degrade/disable after N crashes, self-clear on clean session | grep `crashLoop` → zero hits | M |
| N7 | ✅ **DONE 2026-07-03**: kill switch verified both platforms (earlier same day) AND RN plumbing shipped — `gatewayEndpoint`/`enablePolicyPolling`/`configPollIntervalSeconds` flow JS → both bridges → native `MobileConfig`; new native `gatewayEndpoint` field on Android + iOS so config polling can target the gateway when `endpoint` is plain OTLP ingest. Tests: GatewayEndpointTest (Android), MobileConfigTests + dispatcher tests (iOS), module tests (RN) | packages/react-native + both SDK configs | — |

### Correctness / adoption blockers

| # | Item | Evidence | Effort |
|---|------|----------|--------|
| N8 | ✅ **DONE 2026-07-03**: swept both NXDOMAIN legacy hostnames from 18 adopter-facing docs/templates + iOS sources/tests → `ingress.us-west-2.aws.dash0.com`; CI grep guard added to ci.yml `secret-scan` job (archival docs/superpowers excluded) | ci.yml dead-host guard | — |
| N9 | ✅ **DONE 2026-07-03**: OPERATIONS_GUIDE diagram/prose/table + CLAUDE.md (3 spots) now state OTLP/HTTP protobuf default with gRPC :4317 opt-in via `OtlpProtocol.GRPC` | OPERATIONS_GUIDE.md, CLAUDE.md | — |
| N10 | ✅ **DONE 2026-07-03**: `verify-published` job appended to publish.yml — polls npm registry for the exact version + dist-tags, then runs `smoke-rn-public-consumer.sh` clean-room resolve against the live public Pages Maven (with CDN-propagation retries) | publish.yml `verify-published` | — |
| N11 | **Finish Maven Central** — everything is wired (signing, POMs, gated CI job); remaining work is owner accounts + one gradle line. Personal GitHub Pages Maven reads as hobby-project to enterprises | docs/MAVEN_CENTRAL.md:56-73 *(tracked)* | S |
| N12 | ✅ **DONE 2026-07-03**: `npm publish --provenance` (+ `id-token: write`) in publish-npm; publish-android-ghpackages treats 409 (version exists) as re-run success; both recorded in BACKLOG.md Track 3 → Release Pipeline | publish.yml | — |
| N13 | ✅ **DONE 2026-07-03**: kiosk-demo now resolves `io.github.barrysolomon:mobile:0.5.2-alpha` from the public Pages Maven (mavenLocal dropped); docs/checklist updated; needed the issue-#60 consumer-side kotlin-stdlib pin; both flavors build clean | kiosk-demo (sibling repo) | — |
| N13b | ✅ **DONE 2026-07-03**: `api(project(":instrumentation-compose-navigation"))` added to the umbrella; build green, module tests pass, AAR at 89% of the 700 KB budget | otel-android-mobile/build.gradle.kts | — |
| N14 | ✅ **DONE 2026-07-03**: scheduler construction guarded in both (nullable scheduler via injected factory; TapCapture degrades to synchronous emit, FreezeDetector to watchdog-off); `ThreadConstructionGuardTest` (5 tests); same pattern in 3 other instrumentation modules flagged as follow-up task | `TapCapture.kt`, `FreezeDetector.kt` | — |
| N14b | ✅ **CLOSED (all three were already fixed — BACKLOG was stale, now corrected)**: SR-001 `DiskLogBuffer` caches the count (no gauge-path `runBlocking`); SR-005 `FleetAlertHandler` uses `ConcurrentHashMap`/`CopyOnWriteArrayList`; SR-023 `DynamicSampler` divides as unsigned `ULong` (fix comment cites SR-023). SR-002 (flush-path `runBlocking`) remains genuinely open in BACKLOG | verified 2026-07-03 against code; BACKLOG.md checkboxes updated | — |

### Identity & doc-truth quick wins

| # | Item | Evidence | Effort |
|---|------|----------|--------|
| N15 | **Resolve the OTel-affiliation misattribution** — NOTICE claims "Copyright The OpenTelemetry Authors"; CONTRIBUTING.md is titled/closed as an OTel project and routes support to OTel Slack/CNCF channels. CNCF trademark + trust risk. Decide identity (independent OTel-native SDK), fix NOTICE/CONTRIBUTING/headers | NOTICE:1-2; CONTRIBUTING.md:1,264-274,290 | M |
| N16 | **Stale-doc reconciliation batch** — UAT_MATRIX_EPIC grid shows red/untested (reality: 48/48 green); SCALE_READINESS says 11/25 while PRODUCTION_READINESS claims PR-001..006 done (direct contradiction); ios-parity-epic 2.5mo stale; BACKLOG Track 3/7 checkboxes contradict shipped code; IOS_ANDROID_PARITY marks tap/scroll/text-input "❌" though shipped (`SwiftUIScreenModifiers.swift:39`); battle cards say "iOS in development / RN not yet" | multiple, see audits | M |
| N17 | ✅ **DONE 2026-07-03**: UPSTREAM_VS_DASH0.md corrected to state the real pin (1.39.0) with the 1.40.0 bump noted; upstream `io.opentelemetry.android` 1.5.0 GA **confirmed real** — `session:1.5.0` and `agent-api:1.5.0` both return 200 on repo1.maven.org | verified against Maven Central | — |
| N18 | **README "Stability & 1.0" section** — link the excellent-but-invisible VERSIONING.md / API_STABILITY.md / FEATURE_MATURITY_MATRIX.md from the adoption entry point | zero inbound links today | S |

---

## NEXT — competitive parity (2–8 weeks)

### 1. Crash story (the #1 competitive gap — every competitor ships this)

- **Symbolication pipeline** *(tracked, promoted to top)*: ✅ **Phase 1 DONE 2026-07-03** — `app.build.id` resource attr on all 3 platforms (iOS Mach-O `LC_UUID` self-derived via `BuildIdReader`; Android manifest stamp `io.dash0.mobile.BUILD_ID` via `BuildId`+`MobileResource`; RN `start({ buildId })`), TDD'd on each platform. ✅ **Phase 2 DONE 2026-07-04** — `symbol-upload` Go CLI (`tools/symbol-upload/`) with Android/iOS/RN adapters, all keyed by the same `app.build.id`; content-addressed `(platform, build-id)` store with `HEAD`-before-`PUT` idempotency; `symbol-upload-tool` CI job; build-system wiring in `tools/symbol-upload/README.md`. **Next**: Phase 3 — Dash0 backend symbolicator that resolves frames from the stored artifacts (R8 retrace / `atos` / source-map lookup), then Phase 4 retroactive re-symbolication (Embrace parity). Both are backend tracks. `BACKLOG.md:23`, docs/design/symbolication.md ("the #1 competitive gap").
- **MetricKit on iOS** (M, untracked): `MXCrashDiagnostic`/`MXHangDiagnostic` subscriber — watchdog terminations (`0x8badf00d`), disk-write exceptions, and hangs are *structurally invisible* to signal handlers. Emit as `app.watchdog_termination` / `app.hang.metrickit`, distinct from signal-based `app.crash`.
- **ApplicationExitInfo on Android** (L, untracked): fatal ANRs, OOM kills, system kills via `getHistoricalProcessExitReasons` on launch. Pairs naturally with `app.recovery_start`.
- **RN source-map symbolication** (L, untracked, P0 for RN): release JS stacks ship minified (`errors.ts:69`); the `symbol-upload react-native` adapter now uploads the source-map keyed by bundle hash — remaining work is Hermes `.hbc` bytecode handling + the Metro post-`bundle` build hook that computes the id and injects it into `start({ buildId })`.

### 2. Platform parity (no-platform-drift rule enforcement)

- **`identify()`/UserIdentity → iOS + RN** (M, untracked): Android-only today (`MobileOtel.kt:57`). Table-stakes; user-impact analysis depends on it.
- **iOS UIKit auto-capture parity** (M): tap/scroll/text-input exist only as opt-in SwiftUI modifiers; extend the existing UIKit swizzle pattern (screen tracking) to `UIControl.sendAction`/scroll/text delegates.
- **iOS NSFileProtection** (M, *tracked*): at-rest stores (screenshots, URLs, crash data) are cleartext; apply `CompleteUntilFirstUserAuthentication` + exclude from backup.
- **App-start depth both platforms** (M): TTFD (`reportFullyDisplayed()`), hot-start classification, iOS prewarming detection (`ActivePrewarm` env — currently skews cold-start p50/p90), CADisplayLink first-frame instead of next-tick proxy.
- **iOS RetryableExporter auth-error detection** (M, *tracked*): wrap HTTPClient (same pattern as the OTLP decorator-trap fix) to stop burning retry budget on 401/403.
- **Swift 6 strict-concurrency readiness** (M): 43 `@unchecked Sendable` sites; add a `-strict-concurrency=complete` CI job.
- **RN**: Expo config plugin (M, *tracked* EXPO-001 — named growth blocker); old-arch support statement (S); per-trigger screenshot config through the bridge (M); react-navigation peer-dep + tested-versions table (S); RN version matrix to back the ≥0.72 claim (M).

### 3. Proof: measurement & release engineering

- **Published overhead benchmarks** (L, *tracked*): startup ms beyond the 50ms gate, steady-state memory, battery, bandwidth/event, binary-size trend. Sentry publishes theirs; it's the #1 battle-card metric. Repurpose the existing stress-scenario scripts.
- **Coverage measurement** (M): TEST_PLAN.md claims "100% JaCoCo" but no coverage tooling exists on any platform. Kover + jest `coverageThreshold` + `xcodebuild -enableCodeCoverage` + go coverprofile upload; report-only ratchet first.
- **iOS size budget + npm tarball budget** (S): the AAR gate has no iOS/RN twin — the repo's own drift rule violated in CI.
- **Per-push iOS host tests** (M): iOS is compile-only per push; 530 tests run nightly only. Split host-safe logic tests to run per-push.
- **Device matrix breadth** (M, *tracked*): API 34 only + first-available simulator today; matrix nightly across API 26/28/34/36 + 2-3 iOS runtimes; periodic real-device pass for crash/recovery paths.
- **UAT matrix into CI** (L, *tracked*): the 48-cell harness runs only on the laptop; its stated blocker (removed workflows) is resolved. Nightly 3-cell subset × 4 platforms, weekly full Android sweep.
- **API/binary-compatibility gate** (M): binary-compatibility-validator (Android), api-extractor (RN), swift-api-digester — install while the surface is small; mandatory at 1.0.
- **GitHub Releases from CHANGELOG** (S) and **collector integration test in CI** (S — script exists, ubuntu runners have Docker).
- **Dependabot + CodeQL** (S/M): five ecosystems, zero automation; stdlib drift (#60) already bit once.

### 4. Docs & DX

- **Docs site + generated API reference** (L then XL): no Dokka/DocC/TypeDoc, no rendered site — the single biggest gap vs Sentry/Datadog/Embrace docs. Phase 1: publish generated API docs to the existing GitHub Pages. Phase 2: Docusaurus/MkDocs portal.
- **Fix 45 broken links + lychee CI job** (M); **merge the 3 overlapping configuration guides** (M); **split docs/internal/ from adopter docs** (M — 170 files, only ~50 indexed; session-scratch and sales collateral pollute the tree; TUTORIAL.md is internal onboarding wearing the prime adopter filename and cites private memory paths).
- **TROUBLESHOOTING for iOS/RN** (M): currently Android+collector only — the known field issues (UIScene, forceFlushBuffered, stdlib pin) are documented only in memory/epics.
- **One product name** (S): "Mobile OTel SDK" vs "Dash0 Mobile Observability SDK" vs "OpenTelemetry Mobile Extensions" across entry points.

### 5. Instrumentation depth & semconv hygiene

- **Map the invented `mobile.*` attribute namespace to OTel semconv** (M, untracked): vitals/freeze/errors emit `mobile.ui.frame.dropped`, `mobile.freeze.duration_ms`, `mobile.error.fingerprint`, etc. — none are semconv, conflicting with the project's own OTel-native rule. Map to emerging `app.*` semconv where it exists; document the rest as vendor extensions in `MobileSemconv.kt`.
- **WorkManager/background-job module** (M, untracked): zero coverage; background work is a top mobile-perf blind spot.
- **Android TTFD** (S): hook `Activity.reportFullyDrawn()` in the existing `AppStartInstrumentation.kt` (TTID plumbing already there) — the cheap half of the app-start-depth item above.
- **Network wrapper cleanup** (S): the `@Supersedes("okhttp")` `NetworkInstrumentation` wrapper is dead code and name-collides with the RN package's class of the same name — register it as the blessed path or delete it.
- **Maturity-matrix "Wiring" column** (S): the table conflates auto-installed, config-gated, user-wired (Network), and not-even-bundled (compose-navigation) modules.
- **Record the untracked missing instrumentations in BACKLOG.md** (S): WebView, WorkManager, gRPC client, Compose recomposition, TTFD — none are tracked anywhere today.

### 6. Namespace decision (do early — blast radius grows weekly)

- **Move off personal namespace** (L): repo `github.com/barrysolomon/mobile-otel`, Maven `io.github.barrysolomon`, npm `@barrysolomon/` all contradict a Dash0-branded best-in-class ambition. For consumers it's a breaking coordinate change on all three channels — force the org decision (Dash0 org vs neutral) **before** Innovapptive beta adoption grows. docs/MAVEN_CENTRAL.md:33-36 undersells this as "one property change."

---

## LATER — strategic bets (quarter+)

- **Session replay positioning decision** (strategic): capture primitives are shipped and Incubating; Sentry/Datadog mobile replay is GA. Either invest in continuous wireframe replay + masking rules, or explicitly own the "queryable OTel journey" narrative in collateral. The in-between loses replay deals while paying replay maintenance. Then: wireframe replay viewer UI in control-plane *(tracked, Phase 16)*.
- **Release health** (L, *tracked* Phase 21): emit semconv session start/end + abnormal-exit + app version so backends compute crash-free sessions/users; aggregation belongs in Dash0 platform. Clarify SDK-vs-platform ownership first.
- **W3C trace context → backend stitching** (M, *tracked* CP-030): highest-value APM correlation item; SREs need mobile→backend linkage.
- **Network depth** (L, *tracked*): OkHttp EventListener DNS/TLS/connect waterfall first (closes the demo gap), GraphQL/Apollo next, gRPC `ClientInterceptor` for enterprise backends, Cronet/Ktor on demand. Retrofit rides on OkHttp so it already works; an optional call-adapter for logical route names is a nice-to-have.
- **Compose recomposition/frame-perf module** (L, untracked): recomposition counts + skipped frames via Compose Runtime observation; pairs with existing jank detection.
- **Feature-flag context API** (M, untracked): `recordFeatureFlag()` emitting OTel `feature_flag` semconv + LaunchDarkly/Firebase adapters — free OTel-native differentiator, flags-at-crash-time story.
- **WebView/hybrid instrumentation** (L, untracked): zero coverage today; enterprise apps are hybrid; link webview pages into the native session/journey.
- **Flutter** (XL): re-rank from P2 — the "whitespace" claim is stale (Sentry/Embrace/Datadog/Faro all ship Flutter now). Thin Dart bridge over native SDKs; RN bridge is the proven template. Explicitly park Unity/tvOS with rationale.
- **NDK/native crash capture** (XL, *tracked* P2): keep parked unless targeting games — but it hard-blocks that segment and Flutter engine crashes surface as native crashes (sequencing dependency).
- **Collector processor decision** (M): frozen on collector 0.91.0 (Dec 2023-era, CVE exposure), distributed nowhere, module path squats upstream namespace, and README overclaims "same policy DSL server-side" (three incompatible policy schemas exist). Either: bump + publish to GHCR + make control-plane consume it + unify policy schema (JSON Schema shared artifact), or demote it to explicit side-quest status.
- **Control-plane hardening**: zero CI in that repo (Go tests + Playwright suite run on memory); CP-E2E-004 "the it-actually-works test" for the visual policy editor *(tracked P0 in epic)*.
- **Scale readiness closure**: reconcile the SR/PR contradiction (N16), then burn down the genuinely-open SR items (deferred VACUUM, lock-free trigger eval, crash-safe flush, regexCache concurrency).
- **Upstream contribution** (XL, *tracked*, PROPOSAL stage since 2026-06-25): pick the `@Supersedes`-vs-`view.click` reconciliation path, stabilize WindowEventHub, then open the first real PR (the opentelemetry-swift fire-and-forget exporter bug is a good-citizenship starter regardless).
- **Offline sync instrumentation** (XL, *tracked*, Innovapptive-gated): Realm sync (SYNC-010..014) + sync-failure selective flush; Amplify module partially covers SYNC-001/002.
- **Early event queue** *(tracked)*: buffer pre-init events, replay after start.
- **Community**: enable Discussions (advertised but disabled), issue/PR templates with platform/version fields, CODEOWNERS, OpenSSF Best Practices badge (most criteria already met), BATTLE_CARD_VS_EMBRACE.md (closest OTel-native competitor has no card).

---

## The path to 1.0

docs/VERSIONING.md already defines 1.0 gates. This audit adds the missing mechanical enforcement to make them credible:

1. All NOW items closed (security P0s are 1.0 blockers by definition).
2. Symbolication MVP shipped (crash story is table stakes).
3. Coverage measured + binary-compat gates on (contract → CI).
4. Overhead benchmarks published (the enterprise question is always "what does it cost me").
5. Maven Central + org namespace + signed/provenance artifacts (distribution trust).
6. Device matrix + UAT-in-CI green for a full release cycle.
7. Promote device-tests to a publish gate (currently documented-as-future at 1.0).

## Known tensions to verify first — ALL RESOLVED 2026-07-03

- ✅ **Kill switch**: the doc was stale. Android AND iOS both ship `RemoteGate` in code; PRR row corrected. RN bridge plumbing is the only real gap (N7).
- ✅ **opentelemetry-android 1.5.0 GA status**: confirmed real — `session:1.5.0` / `agent-api:1.5.0` return 200 on repo1.maven.org (N17).
- ✅ **Scale-readiness true status**: the epic was right, BACKLOG was stale — SR-001/005/023 verified fixed in code and checked off; SR-002 remains genuinely open (N14b).
- (The instrumentation module-by-module audit *did* complete: 8 sampled modules all have tests + READMEs, 6/8 wired default-on, maturity matrix spot-checks honest except the wiring distinctions noted in NEXT §5.)
