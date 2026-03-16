# Demo Runbook — OpenTelemetry Mobile SDK

Full demo for showing the SDK to an OTel audience. Runs on 2 Android emulators, exercises all scenario suites, and sends telemetry to Dash0.

**Total time: ~12 minutes**

---

## Prerequisites

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

## Step 2 — Run unit tests (~4s)

194 behavioral config tests + full suite across all modules:

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:testDebugUnitTest \
  :otel-android-mobile-core:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest
```

---

## Step 3 — Install & launch demo app on both emulators

```bash
./gradlew installDebug
adb -s emulator-5554 shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
adb -s emulator-5556 shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
```

Both emulator windows should show the demo app.

---

## Step 4 — Run full demo scenario suite (~8 min)

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

## Step 5 — Run SDK instrumented tests (~30s)

```bash
./gradlew :otel-android-mobile:connectedDebugAndroidTest
```

Runs 9 buffer integration tests per emulator (RAM + SQLite ring buffer, flush, TTL).

---

## Step 6 — Show telemetry in Dash0

1. Open Dash0 dashboard, filter to dataset `otel-mobile`
2. Show UI interaction events: `ui.tap`, `ui.screen_view`, `ui.scroll`
3. Show stress signals: `device.health` metrics, `battery.change`, `thermal.status`
4. Show conditional flush: 20+ events arriving at once after crash trigger
5. Show span hierarchy: journey → page → ui.tap parent-child relationships

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
| **Modular instrumentation** | 9 modules: tap, scroll, text-input, back-press, freeze, screen, errors, vitals, network |
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
