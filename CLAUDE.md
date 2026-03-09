# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Mobile observability system built on OpenTelemetry. The Android SDK captures events into a dual-tier ring buffer (RAM + SQLite), evaluates export policies via a DSL engine, and selectively flushes event windows to a Go gateway that converts them to OTEL Logs and forwards via gRPC to an OTEL Collector. A React control plane provides a visual drag-and-drop policy builder.

**Terminology:** "Export policies" (not "workflows") and "selective flush" (not "replay"). Legacy code may still reference "workflows."

## Build Environment

- **Android Gradle Plugin**: 9.0.0 (Kotlin support is bundled — do **not** add a separate `org.jetbrains.kotlin.android` plugin)
- **Gradle**: 8.9 (via wrapper in `examples/demo-app/`)
- **KSP**: 2.3.4
- **JDK**: 17 for the library (`otel-android-mobile/`); demo app uses JVM 1.8 + desugaring
- **Min SDK**: 26 (Android 8.0); **Target/Compile SDK**: 36
- **AGP 9.0 note**: `targetSdk` must be in `testOptions` and `lint` blocks for library modules, not in `defaultConfig`

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

Entry point: `OTelMobile.start()` — calls `OTelMobileBuilder` which wires all instrumentation modules and installs the `WindowEventHubInstaller`.

**Modular instrumentation system** (`otel-android-mobile-core/`):

- `OTelMobileBuilder` — fluent builder; creates `WindowEventHub`, installs `WindowEventHubInstaller`, wires `InstrumentationContext`, calls `InstrumentationRegistry.install()`.
- `WindowEventHubInstaller` — registers `ActivityLifecycleCallbacks` that wraps each activity's `Window.Callback` with a `HubDispatcher`. The dispatcher fans all touch/key events to the hub before delegating to the original callback. This is what connects Espresso and real user input to `TapInstrumentation` etc.
- `WindowEventHub` — `CopyOnWriteArrayList`-backed fan-out dispatcher. Any `WindowEventListener` implementation registered via `addListener()` receives all touch and key events from all activity windows.
- `InstrumentationContext` — carries `OpenTelemetry`, `MobileSessionProvider`, `WindowEventHub`, and `Application` to each instrumentation at install time.

**UI instrumentation modules** (each under `instrumentation/<name>/`):

- **Tap** (`tap/`): `TapInstrumentation` + `TapConfig` — emits OTel log records AND zero-duration child spans (`ui.tap`, `ui.long_press`, `ui.swipe`) nested under the active page span. Gate via `TapConfig.addSpanEvents`. Swipe threshold: `swipeMinDistancePx` (default 50px).
- **Scroll** (`scroll/`): `ScrollInstrumentation` — throttled `RecyclerView.OnScrollListener`; emits `ui.scroll` child spans.
- **Text Input** (`text-input/`): `TextInputInstrumentation` — fires on `EditText` focus-leave; emits `ui.text_input` child spans.
- **Back Press** (`back-press/`): `BackPressInstrumentation` — fires on `KEYCODE_BACK ACTION_UP`; emits `ui.back_press` child spans.
- **Screen** (`screen/`): `ScreenViewInstrumentation` — fragment/activity lifecycle; emits `ui.screen_view` log + starts/ends `page.<ScreenName>` span as current on main thread.
- **Errors** (`errors/`): `ErrorInstrumentation` — uncaught exceptions, coroutine errors. Deduplication (5-min window), rate limiting (10/min).
- **Vitals** (`vitals/`): OTel Meter gauges for memory, battery, jank, app-start.
- **Network** (`network/`): `OTelNetworkInterceptor` — OkHttp interceptor; user-wired.

**Journey span pattern** (used in Espresso tests and production flows):

```kotlin
InstrumentationRegistry.getInstrumentation().runOnMainSync {
    journeySpan = OTelMobile.startJourney("journeyName")
    journeyScope = journeySpan!!.makeCurrent()   // sets parent on main thread
}
// All page spans started after this are automatically nested under the journey span.
```

`ScreenViewInstrumentation.startPageSpan()` calls `spanBuilder("page.X").startSpan()` which reads `Context.current()` from the main thread, so any span made current on the main thread becomes the implicit parent.

**Core subsystems:**

- **Buffering** (`buffering/`): `MobileLogRecordProcessor` routes logs through a dual-tier buffer — RAM via `ConcurrentLinkedQueue` (5000 events) and disk via `DiskLogBuffer` (Room/SQLite, 50MB, 24h TTL). `RetryableExporter` handles export failures. `flushWindow(minutes)` enables selective time-window export.
- **Policy evaluation** (`policy/`): `PolicyEvaluator` matches events against DSL-defined trigger conditions in real-time.
- **Export** (`export/`): `EnrichingLogRecordExporter` enriches logs with device/session attributes before export.
- **Session** (`core/`): `SessionManager` for session lifecycle.

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

## Demo App Credentials

Dash0 credentials (endpoint, auth token, dataset) are **not committed**. The file `examples/demo-app/android/src/debug/assets/otel-config.json` is excluded from git via `.gitignore`. To run the demo app locally, copy the template and fill in real values:

```bash
cp examples/demo-app/android/src/debug/assets/otel-config.json.template \
   examples/demo-app/android/src/debug/assets/otel-config.json
# Edit otel-config.json: replace YOUR_COLLECTOR_ENDPOINT, YOUR_AUTH_TOKEN, YOUR_DATASET_NAME
```

## Known Issues & Gotchas

- **`DiskLogBuffer.toLogRecordData()` is a stub** — Throws `NotImplementedError`. Disk events cannot be deserialized for export yet. This means `flushWindow()` only returns RAM-buffered events.
- **Kotlin `/*` in strings/comments** — The Kotlin compiler misparses `/*` inside string literals in doc comments as a block-comment start. In `PolicyEvaluator.kt`, timezone wildcards like `"America/*"` must be written as `"America/wildcard"` or similar. Symptom: `Unclosed comment` error at end of file.
- **`factory_test.go` missing** — The collector processor has no factory tests yet (P0 backlog item).
- **`go.sum` untracked** — `collector-processor/mobilepolicyprocessor/go.sum` is not committed. Run `go mod tidy` before building the processor for the first time.

## Key Documents

- **[DESIGN.md](DESIGN.md)** — Vision, system architecture, core concepts (buffer, policy DSL, flush triggers, session/identity), SDK modules, privacy defaults, OTel compliance, demo scenarios
- **[BACKLOG.md](BACKLOG.md)** — Prioritized remaining work across 5 tracks: SDK completeness, testing, infrastructure, documentation/OTEPs, upstream contribution
- **[docs/reference/ARCHITECTURE.md](docs/reference/ARCHITECTURE.md)** — System architecture deep dive
- **[docs/ANDROID_SDK_GUIDE.md](docs/ANDROID_SDK_GUIDE.md)** — Android SDK integration guide
- **[docs/EXPORT_MODES.md](docs/EXPORT_MODES.md)** — Export mode details
- **[docs/DEVICE_METRICS.md](docs/DEVICE_METRICS.md)** — Device metrics reference
- **[docs/GEO_DEVICE_POLICY_EXTENSION.md](docs/GEO_DEVICE_POLICY_EXTENSION.md)** — Geo/device policy DSL
- **[docs/guides/TESTING_STRATEGY.md](docs/guides/TESTING_STRATEGY.md)** — Testing approach
