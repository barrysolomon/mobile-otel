# Upstream vs Dash0 — What Ships Where

A capability-level comparison between the upstream `opentelemetry-android` project (v0.10.0-alpha) and the Dash0 Mobile Observability SDK. This is the **reference map** for "what does Dash0 add on top of upstream?" — useful for merge-proposal conversations, customer questions, and roadmap planning.

For the runnable side-by-side demo, see [COMPARISON_TUTORIAL.md](COMPARISON_TUTORIAL.md). For the strategic plan to converge with upstream, see [epics/UPSTREAM_SUPERSESSION_EPIC.md](epics/UPSTREAM_SUPERSESSION_EPIC.md).

---

## TL;DR

- **Upstream** ships an Android-only SDK with 10 published instrumentation modules covering crashes, ANRs, lifecycle, slow rendering, startup, sessions, and network connectivity. No iOS. No React Native. No on-device buffering, policy engine, or selective flush.
- **Dash0** is a compatible superset: every upstream module runs unmodified through our adapter, and we fill in the rest — 22 Android instrumentation modules, a native iOS SDK, a React Native bridge, dual-tier buffering, policy DSL evaluation, conditional/hybrid export, and a visual control plane.
- **Zero drift goal:** every shared behavior is mirrored across Android (Kotlin), iOS (Swift), RN (TS), and the collector processor (Go).

---

## 1. Platform Coverage

| Platform | Upstream | Dash0 | Notes |
|----------|----------|-------|-------|
| Android native | ✅ `opentelemetry-android` 0.10.0-alpha | ✅ `io.github.barrysolomon:mobile:0.9.0-beta` | Dash0 `MobileInstrumentation extends AndroidInstrumentation` — upstream modules run via adapter |
| iOS native | ❌ Not in scope | ✅ Swift 5.9, iOS 15+ | Independent port, no upstream equivalent yet |
| React Native | ❌ Not in scope | ✅ Thin JS facade over native SDKs | Datadog-style: buffering + policy eval happen in native modules |
| Collector processor | ❌ Not in scope | ✅ `mobilepolicyprocessor` (Go) | Evaluates mobile export policies server-side |
| Control plane UI | ❌ Not in scope | ✅ React + Go gateway (sister repo) | Visual workflow editor for policy DSL |

---

## 2. Instrumentation Modules

### Upstream published modules (10)

All 10 are usable from Dash0 unmodified via `UpstreamInstrumentationAdapter`:

| Upstream module | Signal | Dash0 equivalent | Status |
|-----------------|--------|------------------|--------|
| `instrumentation-crash` | Logs | `ErrorInstrumentation` (richer: dedup, rate limit, coroutines) | Dash0 `@Supersedes("crash")` |
| `instrumentation-anr` | Metrics | `VitalsInstrumentation` (ANR + memory + battery + jank + app-start) | Dash0 `@Supersedes("anr", "startup")` |
| `instrumentation-activity` | Logs | `LifecycleInstrumentation` | Dash0 `@Supersedes("activity")` |
| `instrumentation-fragment` | Logs | `LifecycleInstrumentation` | Dash0 `@Supersedes("fragment")` |
| `instrumentation-slowrendering` | Logs | `FreezeInstrumentation` | Dash0 `@Supersedes("slowrendering")` |
| `instrumentation-startup` | Metrics | Covered by `VitalsInstrumentation` | Dash0 `@Supersedes("startup")` |
| `instrumentation-sessions` | Events | Pass-through (additive) | Our `SessionManager` handles state; upstream emits the lifecycle events |
| `instrumentation-network` | Events | Pass-through (additive) | Connectivity changes (wifi/cellular/none); not covered by our `SystemEventsInstrumentation` |
| `session` (interface lib) | — | `MobileSessionProvider : SessionProvider` | Strict superset |
| `instrumentation-android-instrumentation` (interface lib) | — | `MobileInstrumentation : AndroidInstrumentation` | Strict superset |

### Upstream unpublished / build-plugin-only modules (4)

These exist in the upstream repo but ship with caveats. Dash0's stance:

| Upstream module | Why upstream gates it | Dash0 stance |
|-----------------|------------------------|--------------|
| Compose click | Not published to Maven Central | **Dash0 ships** `ComposeClickInstrumentation` — emits `ui.tap` with composable identity, `testTag` / `contentDescription` / `Role` fallbacks |
| Screen orientation | Not published to Maven Central | **Dash0 ships** `ScreenOrientationInstrumentation` |
| HttpURLConnection (ByteBuddy) | Requires ByteBuddy Gradle build plugin | **Deprioritized** — legacy API; OkHttp covers the modern surface |
| android.util.Log (ByteBuddy) | Requires ByteBuddy Gradle build plugin | **Covered** by `TimberInstrumentation` (runtime bridge, no build plugin) |
| okhttp3 (ByteBuddy agent) | Requires ByteBuddy Gradle build plugin | **Covered better** by `NetworkInstrumentation` / `OTelNetworkInterceptor` (runtime interceptor, no build plugin) |
| okhttp3-websocket (ByteBuddy) | Requires ByteBuddy Gradle build plugin | **Dash0 ships** `WebSocketInstrumentation` + `OTelWebSocketListener` (runtime, no build plugin) |

**Why no ByteBuddy in Dash0:** it forces a Gradle plugin on every consumer, complicates the build, and is harder to debug. Runtime interceptors / callbacks / reflection are lighter and cover the same ground.

### Dash0 modules with no upstream equivalent

These are net-new capabilities upstream does not ship:

| Module | Signal | What it does |
|--------|--------|--------------|
| `TapInstrumentation` | Logs + Spans | `ui.tap` / `ui.long_press` / `ui.swipe` with target hierarchy, nested under page spans |
| `ScrollInstrumentation` | Spans | Throttled `RecyclerView` scroll spans |
| `TextInputInstrumentation` | Spans | `ui.text_input` on EditText focus-leave |
| `BackPressInstrumentation` | Spans | `ui.back_press` |
| `ScreenViewInstrumentation` | Logs + Spans | `ui.screen_view` events + `page.<Screen>` parent spans |
| `ComposeNavigation` | Spans | Compose Navigation route changes as page spans |
| `DatabaseInstrumentation` | Spans | SQLite / Room call spans |
| `FileIOInstrumentation` | Spans | File read/write spans |
| `SystemEventsInstrumentation` | Logs | Battery / power / storage events |
| `TimberInstrumentation` | Logs | Bridges Timber log calls to OTel |
| `ScreenshotInstrumentation` *(incubating)* | Logs | Pixel capture with text redaction, configurable resolution + quality |
| `WireframeInstrumentation` *(incubating)* | Logs | View-hierarchy JSON for journey replay |
| `AmplifyDataStoreInstrumentation` | Logs + Spans | AWS Amplify offline sync visibility |
| `DebugWidget` *(incubating, opt-in)* | — | In-app overlay showing live buffer / export / device-health stats |

**Counts:** 22 Dash0-owned Android modules + 2 upstream pass-through = 24 instrumentations available.

---

## 3. Core Subsystems

Capabilities below are present in Dash0 but have no upstream equivalent. These are the load-bearing "what did we fill in" pieces.

| Capability | Upstream | Dash0 (Android) | Dash0 (iOS) | Dash0 (RN) |
|------------|----------|-----------------|-------------|------------|
| OTLP/gRPC export | ✅ | ✅ :4317 | ✅ via HTTPClient :4318 | ✅ (delegates to native) |
| Dual-tier buffer (RAM + disk) | ❌ | ✅ `ConcurrentLinkedQueue` + Room SQLite v4, 50 MB, 24h TTL | ✅ in-memory ring + disk buffer | ✅ via native |
| Crash-survival flush | ❌ | ✅ disk mirror with `seqId` dedup, `app.recovery_start` | ✅ persistForCrash path | ✅ via native |
| Policy DSL evaluator | ❌ | ✅ 21 matchers / 10 actions | ✅ parity | ✅ via native |
| Export modes | Continuous only | ✅ CONDITIONAL / CONTINUOUS / HYBRID | ✅ HYBRID default + offline-reconnection | ✅ via native |
| Selective time-window flush | ❌ | ✅ `flushWindow(minutes)` | ✅ `forceFlushBuffered()` | ✅ via native |
| Predictive flush (device health) | ❌ | ✅ `PredictiveExportPolicy` triggers on network-loss risk | ✅ NWPathMonitor-backed | ✅ via native |
| Offline flush budget | ❌ | ✅ flush-dedup + error coalescing + budget cap | ✅ parity (`OFFLINE_FLUSH_BUDGET_EPIC`) | ✅ via native |
| Network-restored flush | ❌ | ✅ flush within ~3 s of airplane-mode toggle | ✅ parity | ✅ via native |
| Session lifecycle | ✅ basic | ✅ `MobileSessionProvider` superset (view IDs, screen names, time-on-screen) | ✅ parity | ✅ via native |
| Journey breadcrumbs | ❌ | ✅ `BreadcrumbManager` + journey/page span hierarchy | ✅ parity | ✅ via native |
| `UiTelemetryMode` (EVENTS / SPANS / BOTH) | ❌ | ✅ | ✅ | ✅ via native |
| Resource enrichment (device, geo) | partial | ✅ `EnrichingLogRecordExporter` | ✅ | ✅ via native |
| Network privacy presets | ❌ | ✅ default / minimal / debug / production | ✅ | ✅ via native |
| PII redaction (logs + screenshots) | ❌ | ✅ | ✅ | ✅ |

---

## 4. API Surface

| Surface | Upstream | Dash0 |
|---------|----------|-------|
| Entry point | `OpenTelemetryRum.builder()` | `OTelMobile.start()` / `MobileOtel.initialize()` (idempotent) — `OpenTelemetryRumCompat` shim provides upstream's shape |
| Configuration | Kotlin builder | Kotlin builder + `mobileOtel { }` Kotlin DSL (Phase 3) |
| Custom resource attributes | ✅ | ✅ + `extraResourceAttributes` policy-DSL action |
| Exporter customizers | partial | ✅ `addLogRecordExporterCustomizer`, `addSpanExporterCustomizer`, `addMetricExporterCustomizer` (chained) |
| Bundled config (offline-first init) | ❌ | ✅ ship `otel-config.json` in assets, override via gateway poll |
| Remote config polling | ❌ | ✅ via gateway in sister repo |

---

## 5. Tooling & Operations

| Tool | Upstream | Dash0 |
|------|----------|-------|
| Visual policy editor | ❌ | ✅ control-plane-ui (React Flow graph → DSL v2) |
| Config gateway (versioned, multi-device) | ❌ | ✅ Go service, SQLite, gRPC to collector |
| Collector processor (server-side policy eval) | ❌ | ✅ `mobilepolicyprocessor` |
| K8s / Docker deploy manifests | ❌ | ✅ in sister repo |
| Demo apps | 1 (astronomy shop) | astronomy shop (upstream-mirror) + scheduling demo + RN demo + iOS StarterApp + iOS AstronomyShop |
| Battle cards vs other vendors | ❌ | [BATTLE_CARD_VS_DATADOG.md](BATTLE_CARD_VS_DATADOG.md), [BATTLE_CARD_VS_SPLUNK.md](BATTLE_CARD_VS_SPLUNK.md) |
| UAT matrix (mode × mechanism cells) | ❌ | ✅ 48-cell matrix, all 🟢 across Android + iOS + RN Android + RN iOS |

---

## 6. Compatibility Stance

Dash0 is positioned as a **strict superset** of upstream so the eventual merge proposal has zero technical argument against it:

- `MobileInstrumentation : AndroidInstrumentation` — our modules ARE upstream modules
- `MobileSessionProvider : SessionProvider` — our session provider IS an upstream session provider
- `InstrumentationContext` embeds upstream's `InstallationContext` — no schema drift
- `@Supersedes` annotation prevents duplicate telemetry when both frameworks' modules are present
- Upstream modules run unmodified via `UpstreamInstrumentationAdapter`
- Dash0 modules run unmodified in upstream's framework via `MobileInstrumentationAdapter` (kept for the merge-validation path)
- Semconv pinned to `1.39.0` (`otel-android-mobile/build.gradle.kts`); upstream has since moved to `1.40.0` — bump tracked in BACKLOG

See [epics/UPSTREAM_SUPERSESSION_EPIC.md](epics/UPSTREAM_SUPERSESSION_EPIC.md) for the full convergence plan.

---

## 7. What's Still Open

Honest list of what's not yet at parity or still in flight:

- **iOS module surface** is narrower than Android (10 modules vs 22). Tap / scroll / text-input / back-press / database / file-IO / system-events / Timber / Amplify aren't ported yet. See [IOS_ANDROID_PARITY.md](IOS_ANDROID_PARITY.md) for the gap list.
- **Compose click on minified release builds** — `testTag` requires `debugImplementation` or `isMinifyEnabled = false` for that module. Falls back to `contentDescription` and `Role`.
- **`AndroidLogInstrumentation`** (upstream-style `android.util.Log` bridge) is P2 — Timber covers most real-world use today.
- **Collector security review** is a parked epic ([COLLECTOR_SECURITY_REVIEW_EPIC](epics/)) — third-party pen test pending.

---

## 8. Related Reading

- [COMPARISON_TUTORIAL.md](COMPARISON_TUTORIAL.md) — runnable side-by-side demo (`upstream` vs `dash0` Gradle flavors)
- [epics/UPSTREAM_SUPERSESSION_EPIC.md](epics/UPSTREAM_SUPERSESSION_EPIC.md) — convergence plan, phase tracker
- [superpowers/specs/2026-04-09-upstream-supersession-design.md](superpowers/specs/2026-04-09-upstream-supersession-design.md) — full design spec with module map
- [FEATURE_MATURITY_MATRIX.md](FEATURE_MATURITY_MATRIX.md) — production-readiness state of each feature
- [IOS_ANDROID_PARITY.md](IOS_ANDROID_PARITY.md) — cross-platform parity tracker
- [RN_ANDROID_IOS_PARITY.md](RN_ANDROID_IOS_PARITY.md) — RN-specific parity tracker
- [BATTLE_CARD.md](BATTLE_CARD.md) — general talk track
