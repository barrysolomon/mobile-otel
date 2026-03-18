# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenTelemetry Android Mobile SDK. Captures events into a dual-tier ring buffer (RAM + SQLite), evaluates export policies via a DSL engine, and selectively flushes event windows via OTLP/gRPC to an OTEL Collector.

The management plane (Go gateway + React control plane UI) lives in the sister repo [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane).

**Terminology:** "Export policies" (not "workflows") and "selective flush" (not "replay"). Legacy code may still reference "workflows."

## Build Environment

- **Android Gradle Plugin**: 9.0.0 (Kotlin support is bundled — do **not** add a separate `org.jetbrains.kotlin.android` plugin)
- **Gradle**: 8.9 (via wrapper in `examples/demo-app/`)
- **KSP**: 2.3.4
- **JDK**: 17 for the library (`otel-android-mobile/`); CI runs on JDK 21; demo app uses JVM 1.8 + desugaring
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

### Emulators

Available AVDs: `Medium_Phone_API_36.1`, `Pixel_3a`, `Pixel_7`

```bash
# Start 1-2 emulators WITH windows (for demos / poking around)
nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu1.log 2>&1 &
nohup emulator -avd Pixel_3a -no-snapshot-save > /tmp/emu2.log 2>&1 &

# Or headless (CI / background work)
# nohup emulator -avd Pixel_7 -no-window -no-audio -no-snapshot-save > /tmp/emu1.log 2>&1 &

# Wait for boot (API 36 can take ~4 min)
adb wait-for-device
until adb -s <serial> shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done

# List running emulators
adb devices
```

**Important:** Do NOT install APKs or run instrumented tests until both `dev.bootcomplete=1` AND `sys.boot_completed=1`.

### Demo App (`examples/demo-app/`)
```bash
cd examples/demo-app
./gradlew assembleDebug               # Build debug APK
./gradlew installDebug                # Install on all connected emulators
```

### Demo Runbook (full meeting demo)

Run these steps in order. Total time: ~12 minutes with both emulators.

**Step 1 — Start emulators (windowed, so you can watch)**
```bash
nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu1.log 2>&1 &
nohup emulator -avd Pixel_3a -no-snapshot-save > /tmp/emu2.log 2>&1 &
# Wait ~4 min for boot, then:
adb devices   # Should show emulator-5554 and emulator-5556
```

**Step 2 — Run unit tests (~4s, 194 behavioral config tests + full suite)**
```bash
cd examples/demo-app
./gradlew :otel-android-mobile:testDebugUnitTest \
  :otel-android-mobile-core:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest
```

**Step 3 — Install & launch demo app on both emulators**
```bash
./gradlew installDebug
adb -s emulator-5554 shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
adb -s emulator-5556 shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
```

**Step 4 — Run full demo scenario suite on both emulators (~8 min)**
```bash
./gradlew :android:connectedDebugAndroidTest
```
This runs 18 tests on each emulator (36 total) across 4 scenario suites:
- **UserJourneyScenarios** — multi-screen booking flow, error recovery, navigation breadcrumbs
- **EmulatorStressScenarios** — battery drain, thermal throttle, memory pressure, network degradation
- **FaultScenarios** — jank detection, ANR triggers, memory pressure faults
- **ConditionalFlushScenarios** — silent buffer accumulation → crash triggers flush of all buffered events

**Step 5 — Run SDK instrumented tests on both emulators (~30s)**
```bash
./gradlew :otel-android-mobile:connectedDebugAndroidTest
```
Runs 9 buffer integration tests on each emulator (RAM + SQLite ring buffer, flush, TTL).

**Step 6 — Show telemetry in Dash0**
- Open Dash0 dashboard, filter to dataset `otel-mobile`
- Show `ui.tap`, `ui.screen_view`, `ui.scroll` events from step 4
- Show stress signals: `device.health` metrics, `battery.change`, `thermal.status`
- Show conditional flush: 20+ events arriving at once after crash trigger
- Show journey → page → ui.tap parent-child span hierarchy

**Talking points:**
- OTel-native: `LogRecordExporter`/`SpanExporter`, standard OTLP/gRPC
- Dual-tier buffering: RAM ring buffer + SQLite (survives process death)
- Export policy DSL: conditional/continuous/hybrid — battery-efficient selective flush
- 194 behavioral config tests: every toggle proven to change runtime behavior
- UiTelemetryMode: EVENTS/SPANS/BOTH — consumer chooses signal type
- Privacy by default: PII scrubbing, `captureLocation=false`, network privacy presets
- Modular instrumentation: tap, scroll, text-input, back-press, freeze, screen, errors, vitals, network

### Demo Backend (`examples/demo-backend/`)
```bash
cd examples/demo-backend
npm install                            # Install dependencies
npm run dev                            # Start with nodemon (hot-reload)
npm start                              # Start production
npm test                               # Unit tests (vitest)
```

Node.js + Express + SQLite (better-sqlite3) backend that serves the demo app's appointment API. Instrumented with `@opentelemetry/sdk-node` and exports via OTLP. Has Docker support via `docker-compose.yml`.

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
```

### Components in This Repo

1. **Android SDK** (`otel-android-mobile/`) — Kotlin library, Android API 26+, JDK 17. Published as `io.opentelemetry.android:mobile:0.1.0-alpha`. Core namespace: `io.opentelemetry.android.mobile`.

2. **Collector Processor** (`collector-processor/mobilepolicyprocessor/`) — Custom OTEL Collector processor plugin (Go) that evaluates mobile export policies server-side.

3. **Demo Backend** (`examples/demo-backend/`) — Express.js/TypeScript API server with SQLite, OTel-instrumented. Serves the demo app's appointment booking flow.

4. **Control Plane UI** (`control-plane-ui/`) — React UI for managing export policies. The Go gateway lives in the sister repo [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane).

### Android SDK Internal Architecture

Entry point: `OTelMobile.start()` — calls `OTelMobileBuilder` which wires all instrumentation modules and installs the `WindowEventHubInstaller`.

**Modular instrumentation system** (`otel-android-mobile-core/`):

- `OTelMobileBuilder` — fluent builder; creates `WindowEventHub`, installs `WindowEventHubInstaller`, wires `InstrumentationContext`, calls `InstrumentationRegistry.install()`.
- `WindowEventHubInstaller` — registers `ActivityLifecycleCallbacks` that wraps each activity's `Window.Callback` with a `HubDispatcher`. The dispatcher fans all touch/key events to the hub before delegating to the original callback. This is what connects Espresso and real user input to `TapInstrumentation` etc.
- `WindowEventHub` — `CopyOnWriteArrayList`-backed fan-out dispatcher. Any `WindowEventListener` implementation registered via `addListener()` receives all touch and key events from all activity windows.
- `InstrumentationContext` — carries `OpenTelemetry`, `MobileSessionProvider`, `WindowEventHub`, and `Application` to each instrumentation at install time.

**UI instrumentation modules** (each under `instrumentation/<name>/`):

- **Lifecycle** (`lifecycle/`): `LifecycleInstrumentation` — activity/fragment lifecycle tracking.
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
| Collector Processor | Go 1.24, OTel Collector 1.39.0 |

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

- **Kotlin `/*` in strings/comments** — The Kotlin compiler misparses `/*` inside string literals in doc comments as a block-comment start. In `PolicyEvaluator.kt`, timezone wildcards like `"America/*"` must be written as `"America/wildcard"` or similar. Symptom: `Unclosed comment` error at end of file.
- **`go.sum` untracked** — `collector-processor/mobilepolicyprocessor/go.sum` is not committed. Run `go mod tidy` before building the processor for the first time.

## Key Documents

- **[docs/README.md](docs/README.md)** — Full documentation index
- **[DESIGN.md](DESIGN.md)** — Vision, system architecture, core concepts, SDK modules, OTel compliance
- **[BACKLOG.md](BACKLOG.md)** — Prioritized remaining work across 5 tracks
- **[HOW_TO_DEMO.md](HOW_TO_DEMO.md)** — Full demo runbook (2 emulators, 12 min)
- **[docs/QUICK_START.md](docs/QUICK_START.md)** — SDK integration or demo setup
- **[docs/ANDROID_SDK_GUIDE.md](docs/ANDROID_SDK_GUIDE.md)** — Android SDK integration guide
- **[docs/CONFIGURATION.md](docs/CONFIGURATION.md)** — MobileConfig, export modes, policy DSL, sub-configs
- **[docs/reference/ARCHITECTURE.md](docs/reference/ARCHITECTURE.md)** — Architecture deep dive
- **[docs/guides/TESTING_STRATEGY.md](docs/guides/TESTING_STRATEGY.md)** — Testing approach
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — How to contribute
