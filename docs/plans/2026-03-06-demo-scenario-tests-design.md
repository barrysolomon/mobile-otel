# Demo Scenario Tests Design

**Date:** 2026-03-06
**Status:** Approved
**Goal:** Espresso-driven instrumented tests that generate rich, demoable OTel telemetry visible in Dash0 dashboards.

---

## Overview

A suite of 8 Android instrumented tests (Espresso) that drive real UI interactions on the demo app, generating authentic breadcrumbs, spans, logs, and device metrics that flow to Dash0. Each step is paced with a configurable delay (default 2s) so traces are visually separated in Dash0.

Primary audience: demo/sales use — these tests are run against live emulators with the Dash0 endpoint configured, not CI.

---

## File Structure

```
examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/
├── DemoScenarioPace.kt          # Reads --paceMs arg; default 2000ms; sleeps + emits demo.step log
├── DemoScenarioBase.kt          # Base class: launches SchedulingActivity, holds pace + helpers
├── scenarios/
│   ├── UserJourneyScenarios.kt  # 4 multi-step user flows
│   └── FaultScenarios.kt        # 4 isolated fault injections
```

---

## Pace Flag

All scenarios share a configurable inter-step delay injected via instrumentation arguments:

```bash
# Default (2s pauses — use for demos)
./gradlew :android:connectedDebugAndroidTest

# Fast (no pauses — use for CI or volume)
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.paceMs=0
```

`DemoScenarioPace.step(scenarioName, stepName)` does two things:
1. Emits an OTel log with body `demo.step`, attributes:
   - `scenario.name` — e.g. `happyPathBooking`
   - `scenario.step` — e.g. `navigate_to_book`
   - `scenario.step_index` — integer counter
   - `demo.run_id` — UUID generated once per test run (all steps for one run share this)
2. Sleeps `paceMs` milliseconds

This makes every scenario's steps filterable and correlatable in Dash0 by `demo.run_id`.

---

## Scenarios

### User Journeys (`UserJourneyScenarios.kt`)

| # | Method | Steps | Key Dash0 Signal |
|---|--------|-------|-----------------|
| 1 | `happyPathBooking` | launch → Calendar → Book tab → fill form → Appointments tab | breadcrumb trail, booking span, session trace |
| 2 | `browseAndRefresh` | launch → Appointments → swipe-to-refresh ×2 (success) | HTTP timing histogram, network spans |
| 3 | `networkErrorRecovery` | launch → expand DebugToolbar → trigger HTTP 500 → Appointments | error log, policy flush trigger, 5-min window export |
| 8 | `getDirections` | launch → Directions tab → interact with directions UI | navigation span, location context attributes |

### Fault Scenarios (`FaultScenarios.kt`)

| # | Method | Steps | Key Dash0 Signal |
|---|--------|-------|-----------------|
| 4 | `jankDetection` | launch → navigate screens → trigger jank via DebugToolbar | `ui.jank` event, frame_duration_ms > 16 |
| 5 | `memoryPressure` | launch → navigate 3 screens → trigger memory pressure | `device.memory.low`, available_mb metric |
| 6 | `anrDetection` | launch → expand DebugToolbar → trigger ANR (6s block) | ANR risk event from SDK monitor |
| 7 | `crashAndRecovery` | launch → build breadcrumbs → trigger crash → re-launch | `app.crash_recovery`, 10-min pre-crash flush |

---

## Base Class Design

`DemoScenarioBase` (extends `ActivityScenarioRule<SchedulingActivity>`):
- Initializes `DemoScenarioPace` with `paceMs` from `InstrumentationRegistry.getArguments()`
- Provides `navigateTo(navId)` helper — clicks bottom nav item
- Provides `expandDebugToolbar()` helper — clicks debug toolbar header
- Provides `clickDebugButton(id)` helper — triggers fault buttons
- Espresso `IdlingRegistry` not needed — pace sleeps replace it for demo purposes

---

## Telemetry Attributes on Every Step

```
demo.run_id         = "a3f9c2d1-..."   # UUID, constant per test run
scenario.name       = "happyPathBooking"
scenario.step       = "navigate_to_book"
scenario.step_index = 3
service.name        = "otel-mobile-demo"  # from otel-config.json
```

---

## Constraints

- **Crash scenario** (`crashAndRecovery`): Espresso cannot survive a process kill. The test triggers the crash, then re-launches the app via `ActivityScenario.launch()` in the same test method to capture the recovery telemetry.
- **ANR scenario**: Uses the 6s DebugToolbar block — long enough for the SDK's ANR monitor to fire, short enough not to trigger the OS ANR dialog (which would require UIAutomator to dismiss).
- **No Espresso assertions**: These tests don't assert UI state. They drive interactions and let telemetry be the verification layer (visible in Dash0).
- **Debug build only**: `TelemetryFlags.showDebugToolbar` is `true` in debug, so the DebugToolbar is visible and clickable.

---

## Running

```bash
# Run all demo scenarios
cd examples/demo-app
./gradlew :android:connectedDebugAndroidTest

# Run a single scenario
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
  io.opentelemetry.android.demo.scenarios.FaultScenarios#jankDetection

# Run fast (no pauses)
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.paceMs=0
```
