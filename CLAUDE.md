# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Mobile observability system built on OpenTelemetry. The Android SDK captures events into a dual-tier ring buffer (RAM + SQLite), evaluates export policies via a DSL engine, and selectively flushes event windows to a Go gateway that converts them to OTEL Logs and forwards via gRPC to an OTEL Collector. A React control plane provides a visual drag-and-drop policy builder.

**Terminology:** "Export policies" (not "workflows") and "selective flush" (not "replay"). Legacy code may still reference "workflows."

## Build & Test Commands

### Android SDK (`otel-android-mobile/`)
```bash
cd examples/demo-app
./gradlew :otel-android-mobile:test                        # Unit tests (JUnit 4 + Robolectric + Mockk)
./gradlew :otel-android-mobile:test --tests "*.PolicyEvaluatorGeoDeviceTest"  # Single test class
./gradlew :otel-android-mobile:testDebugUnitTestCoverage   # Coverage report
./gradlew :otel-android-mobile:lint                        # Android lint
./gradlew :otel-android-mobile:build                       # Full build
./gradlew :otel-android-mobile:connectedAndroidTest        # Instrumented tests (requires emulator)
```

Note: The SDK library (`otel-android-mobile/`) does not have its own `gradlew`. Build it through `examples/demo-app/` which includes it as a project dependency via `settings.gradle.kts`.

### Go Collector Processor (`collector-processor/mobilepolicyprocessor/`)
```bash
cd collector-processor/mobilepolicyprocessor
go test -v -race ./...                # Unit tests with race detection
go build ./...                        # Build
go vet ./...                          # Vet
```

### Gateway (`gateway/`)
```bash
cd gateway
go build ./...                        # Build
go test ./...                         # Tests
```

### Control Plane UI (`control-plane-ui/`)
```bash
cd control-plane-ui
npm install                           # Install deps
npm run dev                           # Dev server on :3000 (proxies /api to :8080)
npm run build                         # Production build
npm run lint                          # ESLint
```

### Demo App (`examples/demo-app/`)
```bash
cd examples/demo-app
./gradlew assembleDebug               # Build debug APK
./gradlew installDebug                # Install on device/emulator
```

### Cross-Project
```bash
./run-tests.sh                        # All tests (Android + Go)
./run-tests.sh --android-only         # Android only
./run-tests.sh --go-only              # Go only
./run-tests.sh --integration          # Include emulator tests
```

## Architecture

```
Android SDK ──OTLP/gRPC :4317──► OTEL Collector ──► Backends
    ▲                                    ▲
    │ GET /config                        │
    └─────────────────┐                  │
                      │                  │
               ┌──────┴─────────┐       │
               │ Gateway (Go)   │───────┘
               │ :8080          │ OTLP/gRPC
               │ /ingest,/config│
               │ /admin/*       │
               └──────┬─────────┘
                      ▲
                      │ /api proxy
               ┌──────┴─────────┐
               │ Control Plane  │
               │ React + Vite   │
               │ :3000          │
               └────────────────┘
```

### Four Independent Components

1. **Android SDK** (`otel-android-mobile/`) — Kotlin library, Android API 26+, JDK 17. Published as `io.opentelemetry.android:mobile:0.1.0-alpha`. Core namespace: `io.opentelemetry.android.mobile`.

2. **Gateway** (`gateway/`) — Go HTTP server. Routes: `/ingest`, `/config`, `/health`, `/admin/*`. Uses SQLite for persistence, exports to OTEL Collector via gRPC.

3. **Control Plane UI** (`control-plane-ui/`) — React 18 + TypeScript + Vite. Visual policy builder using React Flow. Vite proxies `/api` requests to gateway at `:8080`.

4. **OTEL Collector** (`k8s/`) — Standard OTEL Collector deployed via Kubernetes manifests. Receives OTLP/gRPC on :4317 and OTLP/HTTP on :4318.

Additionally: `collector-processor/mobilepolicyprocessor/` is a custom OTEL Collector processor plugin (Go).

### Android SDK Internal Architecture

Two entry points:
- `OTelMobile.start()` — Auto-capture drop-in. Delegates to `MobileOtel.initialize()`, then starts `AutoCaptureManager`.
- `MobileOtel.initialize()` — Core facade. Wires all auto-instrumentation modules.

**Auto-initialized modules** (wired by `MobileOtel.initialize()`):
- **Errors** (`errors/`): `ErrorInstrumentation` captures uncaught exceptions, coroutine errors, RxJava errors. Deduplication (5-min window), rate limiting (10/min), stack trace scrubbing, breadcrumb attachment. On error → triggers buffer flush.
- **Vitals** (`vitals/`): `VitalsCollector`, `JankDetector`, `AppStartInstrumentation` for performance metrics as OTel Meter gauges.
- **Predictive** (`predictive/`): `PredictiveExportPolicy` monitors device health via `DeviceHealthMonitor` and `OnDevicePredictor`. When crash risk ≥ 0.7 or network loss risk ≥ 0.7, triggers pre-emptive flush. Emits prediction events as OTel logs.
- **Health Metrics** (`predictive/`): `HealthMetricsCollector` exposes 9-14 device health metrics (memory, battery, thermal, storage, predictions) as OTel gauges.

**Core subsystems:**
- **Buffering** (`buffering/`): `MobileLogRecordProcessor` routes logs through a dual-tier buffer — RAM via `ConcurrentLinkedQueue` (5000 events) and disk via `DiskLogBuffer` (Room/SQLite, 50MB, 24h TTL). `RetryableExporter` handles export failures. `flushWindow(minutes)` enables selective time-window export.
- **Policy evaluation** (`policy/`): `PolicyEvaluator` matches events against DSL-defined trigger conditions in real-time.
- **Auto-capture** (`autocapture/`): `AutoCaptureManager` registers tap, scroll, back-press, freeze, and ANR detectors via `WindowCallbackWrapper`. Privacy modes control data sensitivity. Only activated via `OTelMobile.start()`.
- **Export** (`export/`): `EnrichingLogRecordExporter` enriches logs with device/session attributes before export. `LoggingHttpExporter` for HTTP-based export.
- **Session/Breadcrumb** (`core/`, `breadcrumb/`): `SessionManager` for session lifecycle, `BreadcrumbManager` for user journey tracking.

**User-wired modules:**
- **Network** (`network/`): `OTelNetworkInterceptor` — OkHttp interceptor. User adds to their OkHttpClient. Configurable via `NetworkConfig` with privacy presets (default, minimal, debug, production).

### Control Plane UI Architecture

- `WorkflowBuilder.tsx` — React Flow canvas with 8 node types (triggers, logic gates, actions)
- `graphToDSL.ts` — Compiles visual graphs to executable JSON DSL (with cycle detection and type validation)
- `gateway.ts` — API client for gateway communication
- `ConfigManager.tsx` / `CollectorConfig.tsx` — Dash0 backend configuration

## CI/CD

GitHub Actions (`.github/workflows/test.yml`) runs on push to `main`/`develop` and PRs:
- **android-unit-tests**: JDK 17, `./gradlew test`, Codecov upload
- **go-tests**: Go 1.21, race detection, Codecov upload
- **lint**: Android lint + `go vet` + `golangci-lint`
- **build**: Full build verification including demo app
- **android-integration-tests**: Emulator-based, main branch only

## Key Dependencies

| Component | Key Deps |
|-----------|----------|
| Android SDK | OpenTelemetry SDK 1.58.0, Room 2.8.4, OkHttp 4.12.0, Coroutines 1.10.2, Kotlin Serialization 1.6.0 |
| Gateway | Go 1.24, OTEL SDK 1.39.0, gRPC 1.77.0, SQLite3 |
| UI | React 18, React Flow 11.10.4, Vite 5, TypeScript 5.3, Axios 1.6.5 |

## Export Modes

- **CONDITIONAL** — Event-driven flush (battery efficient, only exports when policy triggers match)
- **CONTINUOUS** — Periodic time-based export
- **HYBRID** — Combination of both

## Key Documents

- **[DESIGN.md](DESIGN.md)** — Vision, system architecture, core concepts (buffer, policy DSL, flush triggers, session/identity), SDK modules, privacy defaults, OTel compliance, demo scenarios
- **[BACKLOG.md](BACKLOG.md)** — Prioritized remaining work across 5 tracks: SDK completeness, testing, infrastructure, documentation/OTEPs, upstream contribution
- **[docs/reference/ARCHITECTURE.md](docs/reference/ARCHITECTURE.md)** — System architecture deep dive
- **[docs/ANDROID_SDK_GUIDE.md](docs/ANDROID_SDK_GUIDE.md)** — Android SDK integration guide
- **[docs/EXPORT_MODES.md](docs/EXPORT_MODES.md)** — Export mode details
- **[docs/DEVICE_METRICS.md](docs/DEVICE_METRICS.md)** — Device metrics reference
- **[docs/GEO_DEVICE_POLICY_EXTENSION.md](docs/GEO_DEVICE_POLICY_EXTENSION.md)** — Geo/device policy DSL
- **[docs/guides/TESTING_STRATEGY.md](docs/guides/TESTING_STRATEGY.md)** — Testing approach
