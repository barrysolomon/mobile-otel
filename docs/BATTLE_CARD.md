# Dash0 Mobile SDK vs upstream opentelemetry-android

## One-Line Summary

Dash0's SDK is a strict superset of `opentelemetry-android`: every upstream module runs in our framework unmodified, plus 13 additional capabilities they don't have.

---

## Signal Coverage: 22 modules vs 9 auto-installed

| Category | Upstream | Dash0 | Advantage |
|----------|----------|-------|-----------|
| **Activity lifecycle** | activity | lifecycle (combines activity + fragment) | Unified |
| **Fragment lifecycle** | fragment | (included in lifecycle) | Unified |
| **Crash reporting** | crash | errors (+ coroutine errors, dedup, rate limiting, auto-flush) | Richer |
| **ANR detection** | anr | vitals (combines ANR + jank + memory + battery + cold/warm start) | Richer |
| **Slow/frozen rendering** | slowrendering | freeze (full freeze detection, not just slow frames) | Richer |
| **Startup timing** | startup | (included in vitals) | Unified |
| **Network connectivity** | network | (pass-through via adapter) | Equivalent |
| **Session lifecycle** | sessions | (pass-through via adapter) | Equivalent |
| **View click** | view-click (if added separately) | tap (+ long-press, swipe, gesture detection) | Richer |
| **Compose click** | Not published | compose-click (semantics tree walker) | **Only us** |
| **Screen orientation** | Not published | screen-orientation | **Only us** |
| **Scroll tracking** | -- | scroll (RecyclerView, throttled) | **Only us** |
| **Text input** | -- | text-input (EditText focus/blur) | **Only us** |
| **Back press** | -- | back-press (hardware + gesture) | **Only us** |
| **Screen view / navigation** | -- | screen (page spans + screen_view logs) | **Only us** |
| **Database queries** | -- | database (Room/SQLite spans) | **Only us** |
| **File I/O** | -- | file-io (file operation spans) | **Only us** |
| **System events** | -- | system-events (battery, power, storage) | **Only us** |
| **Timber logging** | -- | timber (Timber bridge to OTel logs) | **Only us** |
| **Wireframe replay** | -- | wireframe (view hierarchy JSON, incubating) | **Only us** |
| **Screenshot capture** | -- | screenshot (pixel capture, incubating) | **Only us** |
| **OkHttp** | okhttp3 (requires ByteBuddy Gradle plugin) | network (interceptor, no build plugin needed) | Simpler |
| **HttpURLConnection** | httpurlconnection (requires ByteBuddy) | -- (skip, legacy API) | N/A |

---

## Architecture Advantages

| Feature | Upstream | Dash0 |
|---------|----------|-------|
| **Export mode** | Always-on (continuous) | Conditional / Continuous / Hybrid |
| **Buffering** | Single-tier disk (offline resilience) | Dual-tier RAM (5K) + SQLite (50MB, 24h TTL, crash-safe) |
| **Selective flush** | None | Policy-driven time-window flush (last N minutes on trigger) |
| **Policy engine** | None | DSL with 21 matchers, 10 actions, evaluates on every event |
| **Battery impact** | 3-5% (always exporting) | <0.5% in CONDITIONAL mode |
| **Crash recovery** | Disk buffer survives restarts | Dual-tier + seqId dedup survives force-kill |
| **Predictive export** | None | Device health monitoring, pre-emptive flush before crashes |
| **Visual control plane** | None | React Flow editor for authoring policies |

---

## Developer Experience

| Feature | Upstream | Dash0 |
|---------|----------|-------|
| **Init API** | `OpenTelemetryRumInitializer.initialize(ctx) { }` | `MobileOtel.initialize(ctx) { }` (matching pattern) |
| **Config style** | Kotlin DSL | Kotlin DSL (compatible pattern) |
| **Return type** | `OpenTelemetryRum` | `OpenTelemetryMobile` (richer: flush, flushWindow, shutdown) |
| **Module discovery** | Classpath auto-detect | SPI discovery + `@Supersedes` conflict resolution |
| **Exporter customizers** | `addSpanExporterCustomizer { }` | `exportCustomizers { log { }; span { }; metric { } }` |
| **UI telemetry mode** | Events only | EVENTS / SPANS / BOTH (consumer chooses) |
| **ByteBuddy required?** | Yes (for OkHttp, HttpURLConnection, android-log) | No (runtime interceptors/reflection) |
| **Compose support** | Not published | Works via SPI discovery, 100% reflection |

---

## Merge Proposal Readiness

- **Compatible superset**: all upstream published modules run via bidirectional adapter
- **`@Supersedes` annotation**: prevents duplicate telemetry when both frameworks' modules are present
- **Interface convergence** (Phase 4): `MobileInstrumentation extends AndroidInstrumentation` planned for when upstream publishes `name` property
- **OTEPs in progress**: mobile buffering pattern, conditional export
- **Apache 2.0 licensed**, OTel-native, exports standard OTLP
- **Same demo app, side-by-side comparison**: `examples/upstream-demo-app/` with product flavors

---

## Quick Demo Script (5 minutes)

1. Build both: `./gradlew :upstream-demo-app:installUpstreamDebug :upstream-demo-app:installDash0Debug`
2. Open both apps side-by-side on emulator
3. Same flow: browse telescopes, add to cart, checkout
4. Open Dash0 dashboard, filter by `service.name`
5. Show: "Upstream captured 9 signal types. We captured 22."
6. Show: scroll events, compose click with `testTag` identity, orientation changes
7. Show conditional export: "Upstream sent 47 events continuously. We sent 0 until the crash, which flushed 2 minutes of context."
