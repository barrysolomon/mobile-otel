# Demo Runbook — OpenTelemetry Mobile SDK

Full demo for showing the SDK to an OTel audience. Runs on 2 Android emulators, exercises all scenario suites, and sends telemetry to Dash0.

**Total time: ~12 minutes**

---

## One-liner Scripts

For quick setup, use the automation scripts instead of following the manual steps below:

| Script | What it does | Time |
|--------|-------------|------|
| `./run-demo-full.sh` | 2 emulators, backend, all tests, screenshots + wireframes | ~12 min |
| `./run-demo-quick.sh` | 1 emulator, backend, build + launch, no tests | ~5 min |
| `./run-demo-scenarios.sh` | Run Espresso scenario suites (app must be installed) | ~8 min |
| `./run-demo-single.sh <name>` | Run one scenario by short name (e.g. `crashFlush`, `jank`) | ~1 min |
| `./run-demo-ci.sh` | Headless CI: unit + lint + build + instrumented + Go tests | ~15 min |
| `./run-demo-backend.sh` | Start/stop/status for the demo backend | instant |

Common flags: `--skip-emu` (emulators already running), `--headless` (no window), `--incubating` (enable screenshot + wireframe).

```bash
# Full demo with screenshots + wireframes (the "showstopper" run)
./run-demo-full.sh

# Already have emulators running? Skip boot:
./run-demo-full.sh --skip-emu

# Quick launch for manual poking around:
./run-demo-quick.sh --incubating

# Run just the crash-flush scenario:
./run-demo-single.sh crashFlush

# List all available scenario short names:
./run-demo-single.sh --list
```

---

## Manual Steps

If you prefer to run each step individually:

### Prerequisites

- Android SDK with emulator images installed
- Available AVDs: `Pixel_7`, `Pixel_3a` (or `Medium_Phone_API_36.1`)
- Dash0 credentials configured: `examples/demo-app/android/src/debug/assets/otel-config.json`
  ```bash
  cp examples/demo-app/android/src/debug/assets/otel-config.json.template \
     examples/demo-app/android/src/debug/assets/otel-config.json
  # Edit: fill in YOUR_COLLECTOR_ENDPOINT, YOUR_AUTH_TOKEN, YOUR_DATASET_NAME
  ```

---

## Step 1 — Start emulators (windowed)

```bash
nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu1.log 2>&1 &
nohup emulator -avd Pixel_3a -no-snapshot-save > /tmp/emu2.log 2>&1 &
```

Wait ~4 minutes for boot, then verify:

```bash
adb devices
# Should show:
#   emulator-5554   device
#   emulator-5556   device
```

Do NOT proceed until `sys.boot_completed=1`:
```bash
until adb -s emulator-5554 shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
until adb -s emulator-5556 shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
```

---

## Step 2 — Start demo backend

The demo app connects to a local Express.js backend at `http://10.0.2.2:3001` (the emulator's alias for host localhost). Without it, booking API calls will fail with "Failed to connect to /10.0.2.2:3001".

```bash
cd examples/demo-backend
npm install        # first time only
npm run dev &      # starts on port 3001 with hot-reload
```

Verify it's running:
```bash
curl -s http://localhost:3001/api/doctors | head -c 80
# Should return JSON array of doctors
```

> **Note:** The demo app has mock fallbacks — it will still function without the backend, but booking flows will show network errors and distributed traces won't include the backend spans.

---

## Step 3 — Run unit tests

### Option A: OTel-native modules only (~4s)

Run the standard OTel-compliant instrumentation tests:

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:testDebugUnitTest \
  :otel-android-mobile-core:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest
```

### Option B: All modules including incubating (~6s)

Screenshot and wireframe capture are **not part of the OTel spec** — they are incubating, non-standard extensions disabled by default. This command runs tests for all 11 modules:

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:testDebugUnitTest \
  :otel-android-mobile-core:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest \
  :instrumentation-screenshot:testDebugUnitTest \
  :instrumentation-wireframe:testDebugUnitTest
```

---

## Step 4 — Install & launch demo app on both emulators

```bash
./gradlew installDebug
adb -s emulator-5554 shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
adb -s emulator-5556 shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
```

Both emulator windows should show the demo app.

### Enabling incubating modules (screenshot + wireframe)

Screenshot and wireframe are **off by default**. To enable them at runtime on each emulator, use `adb` to flip the SharedPreferences flags before launching:

```bash
# Enable screenshot capture
adb -s emulator-5554 shell "am broadcast -a android.intent.action.RUN \
  --es 'key' 'screenshot_enabled' --ez 'value' true \
  -n io.opentelemetry.android.demo/.ConfigReceiver" 2>/dev/null
adb -s emulator-5556 shell "am broadcast -a android.intent.action.RUN \
  --es 'key' 'screenshot_enabled' --ez 'value' true \
  -n io.opentelemetry.android.demo/.ConfigReceiver" 2>/dev/null

# Enable wireframe capture
adb -s emulator-5554 shell "am broadcast -a android.intent.action.RUN \
  --es 'key' 'wireframe_enabled' --ez 'value' true \
  -n io.opentelemetry.android.demo/.ConfigReceiver" 2>/dev/null
adb -s emulator-5556 shell "am broadcast -a android.intent.action.RUN \
  --es 'key' 'wireframe_enabled' --ez 'value' true \
  -n io.opentelemetry.android.demo/.ConfigReceiver" 2>/dev/null
```

Or toggle them manually in the demo app's **Settings screen**.

> **Why opt-in?** These modules emit non-standard telemetry (`ui.screenshot` with base64 image data, `ui.wireframe` with JSON view trees). They are useful for journey replay and debugging, but they are not part of the OpenTelemetry specification and produce larger payloads than standard OTel signals.

---

## Step 5 — Run full demo scenario suite (~8 min)

```bash
./gradlew :android:connectedDebugAndroidTest
```

Runs **18 tests on each emulator** (36 total) across 4 scenario suites:

| Suite | What it does | Signals in Dash0 |
|-------|-------------|------------------|
| **UserJourneyScenarios** | Multi-screen booking flow, error recovery, navigation | Breadcrumb trails, navigation spans, session traces |
| **EmulatorStressScenarios** | Battery drain, thermal throttle, memory pressure, network degradation | `device.health` metrics, `battery.change`, `thermal.status`, predictive flush |
| **FaultScenarios** | Jank detection, ANR triggers, memory pressure faults | `ui.jank`, `app.anr`, `ui.freeze` events |
| **ConditionalFlushScenarios** | Silent buffer accumulation, crash triggers flush | 20+ events arrive at once, `buffer.snapshot`, `app.crash` |

You can watch the emulator windows — Espresso drives the UI through each scenario automatically.

To run a single scenario:
```bash
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
  io.opentelemetry.android.demo.scenarios.ConditionalFlushScenarios#quietBufferThenCrashFlush
```

---

## Step 6 — Run SDK instrumented tests (~30s)

```bash
./gradlew :otel-android-mobile:connectedDebugAndroidTest
```

Runs 9 buffer integration tests per emulator (RAM + SQLite ring buffer, flush, TTL).

---

## Step 7 — Show telemetry in Dash0

### OTel-native signals (always present)

1. Open Dash0 dashboard, filter to dataset `otel-mobile`
2. Show UI interaction events: `ui.tap`, `ui.screen_view`, `ui.scroll`
3. Show stress signals: `device.health` metrics, `battery.change`, `thermal.status`
4. Show conditional flush: 20+ events arriving at once after crash trigger
5. Show span hierarchy: journey → page → ui.tap parent-child relationships

### Incubating signals (only if enabled in Step 3)

1. Filter for `ui.screenshot` — each event contains a `mobile.screenshot.data_url` attribute with a base64 JPEG data URL. Paste it into a browser address bar to see the captured screen (text redacted by default).
2. Filter for `ui.wireframe` — each event contains a `mobile.wireframe.data` attribute with a compact JSON view-hierarchy tree (~1–5 KB). Shows the structural layout of every screen the user visited.

---

## Talking Points

| Topic | Detail |
|-------|--------|
| **OTel-native** | Uses `LogRecordExporter`/`SpanExporter`, standard OTLP/gRPC — no proprietary protocols |
| **Dual-tier buffering** | RAM ring buffer (5000 events) + SQLite (50MB, 24h TTL) — survives process death |
| **Export policy DSL** | Conditional / continuous / hybrid modes — battery-efficient selective flush |
| **Behavioral test coverage** | 194 tests proving every config toggle changes runtime behavior |
| **UiTelemetryMode** | EVENTS / SPANS / BOTH — backend consumer chooses the signal type |
| **Privacy by default** | PII scrubbing, `captureLocation=false`, configurable network privacy presets |
| **Modular instrumentation** | 9 OTel-native modules + 2 incubating (screenshot, wireframe) — opt-in via config flags |
| **Selective flush** | `flushWindow(minutes)` exports only a time slice — not the entire buffer |

---

## Quick Reference — Individual Scenarios

```bash
# Happy path booking journey
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
  io.opentelemetry.android.demo.scenarios.UserJourneyScenarios#happyPathBooking

# Battery drain stress test
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
  io.opentelemetry.android.demo.scenarios.EmulatorStressScenarios#batteryDrain

# Jank detection
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
  io.opentelemetry.android.demo.scenarios.FaultScenarios#jankDetection

# Quiet buffer → crash flush (the showstopper)
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
  io.opentelemetry.android.demo.scenarios.ConditionalFlushScenarios#quietBufferThenCrashFlush
```
