# Mobile OTel Architecture Overview

> This document provides a comprehensive technical overview of the mobile observability system:
> what it is, how it works, what was built, and why it matters.

---

## What This Is

A production-grade mobile observability SDK built on OpenTelemetry standards. The system captures
user interactions, errors, performance signals, and network activity from Android apps and exports
them as structured OTLP telemetry — traces, metrics, and logs — to any OTel-compatible backend
(Dash0, Jaeger, Grafana, etc.).

The distinguishing features vs. existing mobile SDKs (Firebase Crashlytics, DataDog Mobile, Sentry):

- **Zero-dependency OTel compliance** — all signals use native OTLP; no proprietary wire format
- **Selective flush** — only the relevant time window is exported, not the entire buffer
- **Policy-driven export** — a DSL engine decides *when* to export based on event content and device context
- **Predictive pre-emptive flush** — on-device ML predicts crash risk and exports before the app dies
- **Contrib-style plugin architecture** — each instrumentation is a separate Gradle module implementing `MobileInstrumentation`, discoverable via ServiceLoader SPI

---

## System Topology

```
┌─────────────────────────────────────────────────┐
│                 Android App                      │
│                                                 │
│  OTelMobile.start() ──► MobileOtel.initialize() │
│       │                                         │
│  AutoCaptureManager                             │
│  ├── TapCapture          ─┐                     │
│  ├── ScrollCapture        │  WindowCallbackWrapper
│  ├── BackPressCapture     │  (wraps each Activity window)
│  ├── FreezeDetector       │                     │
│  ├── RecoveryTracker      │                     │
│  └── TextInputCapture    ─┘                     │
│                                                 │
│  MobileLogRecordProcessor                       │
│  ├── RAM Buffer (5 000 events, ConcurrentLinkedQueue)
│  ├── Disk Buffer (50 MB, 24h TTL, Room/SQLite)  │
│  └── PolicyEvaluator ──► selective flushWindow  │
│                                                 │
│  EnrichingLogRecordExporter                     │
│  └── OTLP/gRPC ──────────────────────────────► │
└─────────────────────────────────────────────────┘
            │
            ▼
┌──────────────────────────┐
│   OTEL Collector         │
│   mobilepolicyprocessor  │  ← custom processor annotates with policy.matched
│   OTLP/gRPC :4317        │
└──────────┬───────────────┘
           │
           ▼
     Backend (Dash0)
```

---

## Component Inventory

### 1. Android SDK — `otel-android-mobile/`

**Entry points:**

| Class | Purpose |
|-------|---------|
| `OTelMobile` | Drop-in entry point. `start()` wires everything. `stop()` cleanly tears down. |
| `MobileOtel` | Core facade. Exposes session, identity, custom events, flush, error reporting. |
| `OTelMobileBuilder` | Fine-grained builder (OTel contrib style). Compose exactly which instrumentations to activate. |

**Auto-capture subsystem (`autocapture/`):**

| Class | What It Captures |
|-------|-----------------|
| `AutoCaptureManager` | Orchestrator. Registers lifecycle callbacks, manages page spans. |
| `TapCapture` | Touch events → `ui.tap` child spans under the current page span |
| `ScrollCapture` | Scroll velocity + distance → `ui.scroll` spans |
| `BackPressCapture` | Back gesture → `ui.back_press` log |
| `FreezeDetector` | Main-thread watchdog (250ms tick). Freeze threshold 500ms, ANR threshold 5s. Emits freeze/ANR events, triggers flush. Fixed: resets `lastTickAtMs` after emitting to prevent infinite freeze loop. |
| `RecoveryTracker` | Detects crash/ANR/low-memory recovery across app restarts via SharedPreferences markers. |
| `SessionTracker` | Session ID, view ID, foreground/background transitions, session renewal after 30 min idle. |
| `WindowCallbackWrapper` | Wraps Android `Window.Callback` to intercept touch events without needing accessibility permissions. |
| `TextInputCapture` | Tracks text field interactions (PII-scrubbed by default). |

**Page span model (critical for trace waterfall):**

```
page.BookFragment   ← AutoCaptureManager.startPageSpan()
                      sampling.priority=high (always sampled — see below)
├── ui.tap          ← TapCapture child span
├── ui.tap
├── booking.submit  ← manually created by BookFragment
│   └── POST /posts ← OkHttp span via OTelNetworkInterceptor
└── ui.scroll       ← ScrollCapture child span
```

`sampling.priority=high` is set on every page span to ensure DynamicSampler (65% baseline)
does not drop them. Without it, ~35% of page spans are dropped, causing taps and manual child
spans to appear as flat unparented logs instead of a trace waterfall.

**Buffering (`buffering/`):**

| Class | Role |
|-------|------|
| `MobileLogRecordProcessor` | Dual-tier ring buffer. RAM first (5000 events), overflows to disk. `flushWindow(minutes)` exports only the last N minutes. |
| `DiskLogBuffer` | Room/SQLite backing store. 50MB max, 24h TTL, automatic cleanup. Fully implemented. |
| `RetryableExporter` | Wraps the OTLP exporter. Exponential backoff on transient failures. |

**Policy evaluation (`policy/`):**

`PolicyEvaluator` fetches a JSON DSL from the collector/gateway, evaluates each incoming
log record in real time, and triggers `flushWindow()` when a policy matches.

Policy DSL supports:
- Attribute operators: `equals`, `gt`, `lt`, `gte`, `lte`, `contains`, `regex`
- Logical: `and` / `or`
- Geo context: country, timezone (wildcard supported)
- Device context: network type, battery state

**Predictive subsystem (`predictive/`):**

| Class | Role |
|-------|------|
| `DeviceHealthMonitor` | Polls memory, battery, thermal, network every N seconds |
| `OnDevicePredictor` | Logistic regression over health signals → risk scores (0.0–1.0) |
| `PredictiveExportPolicy` | If crash risk ≥ 0.70 or network loss risk ≥ 0.70 → pre-emptive flush |
| `HealthMetricsCollector` | Exposes 9–14 device health metrics as OTel gauges (memory, battery, thermal, storage, predictions) |

**Error instrumentation (`errors/`):**

`ErrorInstrumentation` hooks:
- `Thread.UncaughtExceptionHandler` (catches all JVM crashes)
- Kotlin coroutine exception handler
- RxJava `RxJavaPlugins.setErrorHandler`

Features: 5-minute deduplication window, 10/min rate limit, stack trace scrubbing,
breadcrumb attachment. Any captured error triggers `processor.forceFlush()`.

**Session & breadcrumbs (`core/`, `breadcrumb/`):**

`SessionManager` maintains a session ID (UUID) and renews after 30 min background.
`JourneyBreadcrumbBuffer` is a thread-safe circular buffer (default 50 entries, ReentrantReadWriteLock)
of typed breadcrumbs: NAVIGATION, USER_INPUT, NETWORK, ERROR, LIFECYCLE, CUSTOM.
`BreadcrumbManager` is the global entry point, attaches recent breadcrumbs to error reports.

**Network instrumentation (`network/`):**

`OTelNetworkInterceptor` is an OkHttp interceptor. User adds it to their `OkHttpClient`.
Emits `http.request` spans with method, URL, status code, duration. Configurable privacy presets.

**Vitals (`vitals/`):**

| Class | Metric |
|-------|--------|
| `AppStartInstrumentation` | Cold/warm start duration as histogram |
| `JankDetector` | Frames >16ms flagged as jank; jank rate as gauge |
| `VitalsCollector` | Orchestrates vitals, emits as OTel metrics via Meter |

**Sampling (`sampling/`):**

`DynamicSampler` probabilistic trace sampler (default 0.65 baseline). Respects `sampling.priority=high`
attribute to force-sample. `SamplerFactory` builds the sampler from `SamplingConfig`.

**Export (`export/`):**

`EnrichingLogRecordExporter` adds device/session/app attributes to every log record before export.
`AttributeEnricher` supplies: `device.id`, `device.model.name`, `os.version`, `mobile.session.id`, `app.version`, `service.name`.

---

### 2. Contrib-Style Instrumentation Registry — `otel-android-mobile-core/`

A new architectural layer implementing the OpenTelemetry contrib pattern.

**Key interfaces:**

| Interface/Class | Role |
|-----------------|------|
| `MobileInstrumentation` | Contract: `instrumentationName`, `instrumentationVersion`, `install()`, `uninstall()` |
| `InstrumentationContext` | Shared carrier: OpenTelemetry instance, session provider, window event hub |
| `InstrumentationRegistry` | Installs and tracks all active instrumentations, handles uninstall |
| `WindowEventHub` | Publish/subscribe bus for Android window events (tap, scroll, back) |
| `OTelMobileBuilder` | Builder API. `addInstrumentation()` or `discoverInstrumentations()` (ServiceLoader SPI) |
| `OTelMobileHandle` | Returned by `build()`. Holds the live registry; call `uninstall()` to tear down. |
| `MobileSemconv` | Semantic convention constants (attribute keys) shared across modules |

**Instrumentation modules** (each in `instrumentation/<name>/`):

```
instrumentation/
├── back-press/    BackPressInstrumentation
├── errors/        ErrorsInstrumentation
├── freeze/        FreezeInstrumentation
├── lifecycle/     LifecycleInstrumentation
├── network/       NetworkInstrumentation
├── screen/        ScreenViewInstrumentation
├── scroll/        ScrollInstrumentation
├── tap/           TapInstrumentation
├── text-input/    TextInputInstrumentation
└── vitals/        VitalsInstrumentation
```

Each module is a standalone Gradle library with its own tests. Users include only what they need.

---

### 3. Go Gateway — `gateway/`

Lightweight HTTP server (no collector required). Routes:

| Route | Purpose |
|-------|---------|
| `POST /ingest` | Receives OTLP/JSON from Android SDK |
| `GET /config` | Returns active export policies to SDK |
| `GET /health` | Liveness probe |
| `GET/POST /admin/*` | Policy management API |

Gateway re-exports telemetry to an OTEL Collector via OTLP/gRPC. Persists policies in SQLite.
Architecture is **gateway-optional** — the Android SDK can export directly to any OTLP endpoint.

---

### 4. Custom OTEL Collector Processor — `collector-processor/mobilepolicyprocessor/`

A standard OTEL Collector processor plugin (Go) for server-side policy annotation.

**What it does:** Receives log records from the mobile SDK, evaluates them against the configured
policies, and annotates matching records with:
- `policy.matched = true`
- `policy.id = <id>`
- Any action parameters from the policy definition

**Policy DSL** (collector config YAML):
```yaml
processors:
  mobilepolicyprocessor:
    policies:
      - id: ui-freeze-alert
        enabled: true
        match:
          attributes:
            event.name:
              equals: "ui.freeze"
            duration_ms:
              gt: 2000
        actions:
          alert: true
          flush_window_minutes: 2
```

Supports operators: `equals`, `contains`, `gt`, `lt`, `gte`, `lte`, `regex`.

---

### 5. Control Plane UI — `control-plane-ui/`

React 18 + TypeScript + Vite visual policy builder.

- **WorkflowBuilder.tsx** — React Flow canvas. 8 node types: triggers, conditions, logic gates, actions.
- **graphToDSL.ts** — Compiles the visual graph to the JSON DSL, with cycle detection and type validation.
- **gateway.ts** — API client talking to the Go gateway.
- **ConfigManager.tsx / CollectorConfig.tsx** — Dash0 backend connection configuration.

---

### 6. Demo App — `examples/demo-app/`

A full-featured "Schedulr" appointment scheduling app that exercises every SDK capability:

| Screen | OTel Signal |
|--------|-------------|
| Calendar | `page.CalendarFragment` span, tap/scroll children |
| Appointments | Network spans via `OTelNetworkInterceptor` |
| Book | Manual `booking.submit` span nested under `page.BookFragment` |
| Profile | OTel SDK config (`ConfigActivity`), Dash0 connection config (`Dash0ConfigActivity`), app version |

**Config screens** (split in d3ea9e1):
- `ConfigActivity` — OTel SDK: buffering limits, export mode, sampling rate, capture toggles (taps, scrolls, freezes, lifecycle). Material 3 design with cards and section headers.
- `Dash0ConfigActivity` — Backend connection: endpoint, protocol (gRPC/HTTP), auth token, dataset. Dark slate theme.

Both accessible from the Profile tab. OTel Config preserves Dash0 settings on save and vice versa.

---

## Data Flow: Tap to Trace

1. User taps a button in `BookFragment`
2. `WindowCallbackWrapper.dispatchTouchEvent()` fires
3. `TapCapture` creates `ui.tap` as a child span of the active `page.BookFragment` span
4. Span is emitted to `MobileLogRecordProcessor`
5. Processor appends to RAM buffer (ConcurrentLinkedQueue)
6. `PolicyEvaluator` checks the event: does it match any flush policy?
7. If match: `flushWindow(2)` exports the last 2 minutes of buffered events
8. `EnrichingLogRecordExporter` adds device/session attributes
9. OTLP/gRPC sends to collector
10. `mobilepolicyprocessor` annotates with `policy.matched=true`
11. Trace appears in Dash0 with full waterfall: `page.BookFragment → ui.tap`

---

## Test Coverage

### Android SDK (`otel-android-mobile/`) — 18 test files

| Test File | Tests | Coverage Area |
|-----------|-------|---------------|
| `MobileLogRecordProcessorTest` | ~20 | RAM buffer, disk overflow, flushWindow, forceFlush |
| `DiskLogBufferTest` | ~15 | Room persistence, TTL eviction, capacity limits |
| `PolicyEvaluatorTest` | ~25 | DSL operators, logical composition, flush triggering |
| `PolicyEvaluatorGeoDeviceTest` | ~15 | Geo/device context filters |
| `DynamicSamplerTest` | ~12 | Probabilistic sampling, sampling.priority override |
| `PiiScrubberTest` | ~18 | URL scrubbing, element ID redaction, deep link scrubbing |
| `SessionTrackerTest` | ~14 | Session ID generation, renewal, screen views |
| `RecoveryTrackerTest` | ~10 | Crash/ANR/low-memory recovery markers |
| `PageSpanModelTest` | ~12 | Page span lifecycle, child span nesting |
| `TelemetryFlushScenarioTest` | ~8 | End-to-end flush scenarios |
| `PrivacyUtilsTest` | ~12 | Privacy mode redaction |
| `MobileLoggerProviderTest` | ~8 | Provider initialization, scoped loggers |
| `MobileConfigTest` | ~6 | Config validation, defaults |
| `NetworkConfigTest` | ~8 | Network privacy presets |
| `JourneyBreadcrumbBufferTest` | 30 | Buffer FIFO, thread safety, JSON, filters |
| `NavigationInstrumentationTest` | 23 | Lifecycle breadcrumbs, deep links, enable/disable |

### Core Registry (`otel-android-mobile-core/`) — 6 test files

| Test File | Tests | Coverage Area |
|-----------|-------|---------------|
| `InstrumentationRegistryTest` | ~12 | Install/uninstall lifecycle, ordering |
| `OTelMobileBuilderTest` | ~8 | Builder patterns, SPI discovery |
| `InstrumentationContextTest` | ~6 | Context propagation to instrumentations |
| `DefaultMobileSessionProviderTest` | ~5 | Session ID generation |
| `WindowEventHubTest` | ~8 | Publish/subscribe, listener management |
| `MobileSemconvTest` | ~4 | Attribute key constants |

### Instrumentation Modules (`instrumentation/`) — 10 test files

One test file per instrumentation module:
`TapInstrumentationTest`, `ScrollInstrumentationTest`, `BackPressInstrumentationTest`,
`FreezeInstrumentationTest`, `LifecycleInstrumentationTest`, `ScreenViewInstrumentationTest`,
`NetworkInstrumentationTest`, `ErrorsInstrumentationTest`, `VitalsInstrumentationTest`,
`TextInputInstrumentationTest`

### Go Collector Processor — 3 test files

| Test File | Tests | Coverage Area |
|-----------|-------|---------------|
| `factory_test.go` | 7 | Factory creation, type, capabilities, start/shutdown |
| `processor_test.go` | ~15 | Policy matching, attribute annotation, all operators |
| `config_test.go` | ~10 | Config validation, YAML parsing |

**Total: ~430+ tests across 37 test files, 0 failures.**

---

## Known Gaps and Future Work

| Item | Priority | Notes |
|------|----------|-------|
| Custom collector build | P0 | `builder-config.yaml` + Dockerfile for `otelcol-mobile` with `mobilepolicyprocessor` embedded |
| `AutoCaptureManager` is `@Deprecated` | P1 | Replaced by `OTelMobileBuilder` + contrib modules. Demo app should migrate. |
| ConfigManager version migration | P2 | `KEY_CONFIG_LOADED` flag requires `adb shell pm clear` to force re-seed after config JSON update |
| iOS SDK | P3 | Not started. Same architecture would apply with Swift equivalents. |
| `DiskLogBuffer` disk-to-export path | P2 | Disk events ARE persisted; the deserialization path for export is implemented but not exercised by `flushWindow()` (which only flushes from RAM). |

---

## Engineering Decisions Worth Noting

**Why page spans are force-sampled:**
`DynamicSampler` samples by trace ID hash. At 0.65 baseline, ~35% of page spans are dropped.
Dropped page spans cause child spans (taps, API calls) to start new root traces with no parent
context. `sampling.priority=high` on `startPageSpan()` ensures the trace waterfall is always
visible in Dash0 without disabling sampling globally.

**Why the FreezeDetector watchdog resets after emit:**
The ANR detector posts a tick to the main thread every 250ms. After emitting a freeze event,
the old code left `lastTickAtMs` at its pre-freeze value. The next watchdog check would
immediately compute a large delay again and re-emit, creating an infinite freeze storm.
The fix resets `lastTickAtMs = SystemClock.uptimeMillis()` and re-posts the tick at the
start of `emitPendingFreeze()`.

**Why two config screens:**
OTel SDK settings (buffer size, sampling rate, capture toggles) are developer/engineering concerns.
Dash0 connection settings (endpoint, auth token, dataset) are ops/deployment concerns. Mixing them
in one screen created a confusing UX. The split makes each screen scannable and purposeful.

**Why the contrib plugin architecture:**
The `AutoCaptureManager` monolith works but is not composable — users get everything or nothing.
The `MobileInstrumentation` interface + `InstrumentationRegistry` + `OTelMobileBuilder` pattern
mirrors `opentelemetry-android`'s approach, enabling per-signal Gradle dependencies, independent
versioning, and ServiceLoader-based auto-discovery. This is the path to contributing upstream.

---

## Versioning

| Field | Value |
|-------|-------|
| `versionCode` | `20260306` |
| `versionName` | `1.1.0-20260306` |
| `serviceVersion` | `1.1.0-20260306` |
| OTel SDK | `1.58.0` |
| AGP | `9.0.0` |
| Min SDK | API 26 (Android 8.0) |

CalVer format: `MAJOR.MINOR.0-YYYYMMDD`. The date suffix makes every build uniquely identifiable
in traces and crash reports without managing a separate build number.
