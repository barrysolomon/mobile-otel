# Offline Storm Demo Scenario

**Date:** 2026-04-09
**Status:** Approved

## Overview

A showstopper demo scenario that simulates an intense offline user session with escalating device failures, culminating in a process crash. On relaunch and reconnect, 300+ buffered events flood into Dash0 in a single burst — proving the SDK's dual-tier buffer, crash-safe mirroring, and selective flush work under worst-case conditions.

## Narrative Arc

1. **Phase 1 — Offline User Session**: Device goes offline. User actively browses the app (navigates, taps, scrolls, books appointments). Telemetry accumulates silently in RAM → disk buffer.
2. **Phase 2 — Chaos Escalation**: Still offline. Device conditions degrade (battery drain, thermal throttle, memory pressure). Faults fire (jank, ANR, HTTP 500). Real crashes occur — SDK recovers from process death and continues buffering.
3. **Phase 3 — Final Crash**: One last burst of activity, then a fatal crash kills the process. Espresso exits. The presenter manually relaunches.
4. **The Flood**: Airplane mode off, app relaunches, SDK detects crash recovery + network, flushes the entire buffer to Dash0.

## Intensity Presets

Three presets control volume, duration, and chaos level. Passed via `--intensity light|medium|heavy` (default: `heavy`).

### Phase 1 — Offline User Session

| Action | light | medium | heavy |
|--------|-------|--------|-------|
| Screen navigation cycles (all 5 tabs) | 1 | 3 | 5 |
| Taps per screen | 1-2 | 3-4 | 5-6 |
| Scroll events | 2 | 5 | 10 |
| Booking flow attempts | 1 | 2 | 4 |
| Text input events | 1 | 3 | 5 |
| Back presses | 2 | 4 | 8 |
| **Approx events** | **~30** | **~80** | **~180** |
| **Duration** | ~10s | ~30s | ~60s |

Pacing: small `Thread.sleep()` calls between actions (200-500ms) to let auto-instrumentation fire (screen views, page spans, breadcrumbs). Not so slow it drags.

Ends with a `buffer.snapshot` event recording RAM/disk counts.

### Phase 2 — Chaos Escalation

| Action | light | medium | heavy |
|--------|-------|--------|-------|
| Battery drain steps (100% → 5%) | 3 | 5 | 10 |
| Thermal escalation (0 → SEVERE) | skip | 2 steps | 4 steps |
| Memory pressure trims | 1 | 2 | 3 (CRITICAL) |
| Jank triggers | 1 | 2 | 3 |
| ANR triggers (6s block) | 0 | 1 | 2 |
| HTTP 500 errors | 1 | 3 | 5 |
| Navigation between faults | 2 | 4 | 8 |
| **Real crash + auto-recovery** | **1** | **1** | **2** |
| **Approx events** | **~25** | **~70** | **~150** |
| **Duration** | ~15s | ~40s | ~90s |

**Crash sequence:**
1. Emit breadcrumb navigation trail (3-4 events)
2. `throw RuntimeException(...)` — process dies
3. Espresso runner detects death, relaunches app
4. SDK boots, detects crash recovery, disk buffer intact
5. Continue to next fault (heavy mode does this twice)

Between faults, user keeps navigating — telemetry is a mix of normal UI events interleaved with degrading conditions.

Ends with `buffer.snapshot`. At this point in heavy mode: ~330 events buffered across RAM + disk.

### Phase 3 — Final Crash

No intensity variation — always the same quick punch:

1. Navigate through 3 screens rapidly
2. Emit 5-10 rapid taps
3. Emit `buffer.snapshot` (the "before" count for narration)
4. `throw RuntimeException("Fatal: appointment service unrecoverable")`
5. Process dies. Espresso exits.

~15 events added.

## Implementation

### New Files

| File | Purpose |
|------|---------|
| `examples/demo-app/android/src/androidTest/.../scenarios/OfflineStormScenario.kt` | Espresso test class with single orchestrator + optional phase-only entry points |
| `scripts/demo/run-offline-storm.sh` | Orchestrator script with `--intensity` flag |

### OfflineStormScenario.kt

Extends `DemoScenarioBase`. Three `@Test` methods:

```kotlin
@Test fun offlineUserSession()   // Phase 1
@Test fun chaosEscalation()      // Phase 2
@Test fun finalCrash()           // Phase 3
```

**Intensity configuration:** Read from instrumentation runner args:
```kotlin
val intensity = InstrumentationRegistry.getArguments()
    .getString("intensity", "heavy")
```

Maps to a sealed class with the preset values:
```kotlin
sealed class Intensity(
    val navCycles: Int,
    val tapsPerScreen: Int,
    val scrollEvents: Int,
    val bookingAttempts: Int,
    val textInputs: Int,
    val backPresses: Int,
    val batterySteps: Int,
    val thermalSteps: Int,
    val memoryTrims: Int,
    val jankTriggers: Int,
    val anrTriggers: Int,
    val http500s: Int,
    val navBetweenFaults: Int,
    val crashRecoveries: Int,
    val actionDelayMs: Long
) {
    object Light : Intensity(1, 2, 2, 1, 1, 2, 3, 0, 1, 1, 0, 1, 2, 1, 200L)
    object Medium : Intensity(3, 4, 5, 2, 3, 4, 5, 2, 2, 2, 1, 3, 4, 1, 350L)
    object Heavy : Intensity(5, 6, 10, 4, 5, 8, 10, 4, 3, 3, 2, 5, 8, 2, 500L)
}
```

**Airplane mode:** Toggled via `UiAutomation.executeShellCommand()` (same pattern as `OfflineResilienceScenarios`).

**Emulator state manipulation:** Same `dumpsys battery`, `cmd thermalservice`, `am send-trim-memory` commands as `EmulatorStressScenarios`.

**Crash mechanism:** Direct `throw RuntimeException(...)`. Espresso runner auto-relaunches the app. SDK's crash-safe disk buffer survives.

**Jank trigger:** Busy-wait on main thread ~200ms (same as `FaultScenarios.jankDetection`).

**ANR trigger:** Block main thread 6s (same as `FaultScenarios.anrDetection`). Short enough to avoid OS ANR dialog killing instrumentation.

**@Before / @After:** 
- `@Before`: Record initial buffer state, enable airplane mode
- `@After`: Reset emulator state (battery, thermal, memory). Do NOT disable airplane mode — that's the script's job after the final crash.

**Test ordering:** Use `@FixMethodOrder(MethodSorters.NAME_ASCENDING)` with method names prefixed `a_`, `b_`, `c_` to ensure execution order, OR use a single `@Test` orchestrator that calls private phase methods (simpler, avoids state issues between test methods).

**Recommended: Single orchestrator approach:**
```kotlin
@Test
fun offlineStorm() {
    enableAirplaneMode()
    phaseOneOfflineUserSession()
    phaseTwoChaosEscalation()
    phaseThreeFinalCrash()  // process dies here
}
```

This avoids test-method-ordering issues and ensures airplane mode stays on across all phases. The script runs this single test method. If the presenter wants to run just phase 1 for a quick demo, a separate `@Test fun offlineUserSessionOnly()` method can be provided.

### run-offline-storm.sh

```bash
#!/usr/bin/env bash
# Offline Storm — the showstopper demo scenario.
# Usage:
#   ./run-offline-storm.sh                    # heavy intensity (default)
#   ./run-offline-storm.sh --intensity medium  # medium intensity
#   ./run-offline-storm.sh --intensity light   # light intensity
```

**Flow:**
1. Parse `--intensity` and `--device` args
2. Verify emulator running, app installed
3. Run `OfflineStormScenario#offlineStorm` via Gradle with intensity arg
4. Espresso exits (process crashed)
5. Print summary:
   ```
     App crashed (estimated 330+ events buffered on disk)
   
     Ready to show the flood?
     Press Enter to relaunch and reconnect...
   ```
6. Wait for Enter keypress
7. Disable airplane mode: `adb shell cmd connectivity airplane-mode disable`
8. Wait 2s for network
9. Relaunch app: `adb shell am start -n $PKG/.SchedulingActivity`
10. Print Dash0 viewing instructions:
    ```
      App launched — SDK flushing buffered telemetry to Dash0
    
      Switch to Dash0 now. Filter: dataset=otel-mobile, last 5 minutes.
      You should see 330+ events arriving including:
        - UI events (taps, screen views, scrolls, text input)
        - Device health signals (battery, thermal, memory)
        - Fault events (jank, ANR, HTTP 500)
        - Crash/recovery events with full breadcrumb trails
    ```

**Root forwarder:** `./run-offline-storm.sh` → `scripts/demo/run-offline-storm.sh`

### Emulator State Cleanup

The script's `--cleanup` flag (or a trap on EXIT) resets:
- `dumpsys battery reset`
- `cmd thermalservice reset`
- `cmd connectivity airplane-mode disable`

This ensures the emulator isn't left in a degraded state after the demo.

## Telemetry Breakdown (heavy mode, ~345 events)

| Category | Event types | Count |
|----------|------------|-------|
| UI navigation | `ui.screen_view`, page spans | ~50 |
| UI interaction | `ui.tap`, `ui.scroll`, `ui.text_input`, `ui.back_press` | ~90 |
| Booking flows | `user.transaction`, `api.request` | ~40 |
| Device health | battery gauge, thermal gauge, memory gauge, heartbeat | ~60 |
| Stress signals | `stress.battery_level_set`, `stress.thermal_level_set`, `stress.memory_trim` | ~30 |
| Faults | `ui.jank`, `anr.risk`, `http.error` | ~20 |
| Crashes | `app.crash`, `app.crash_recovery` | ~8 |
| Buffer diagnostics | `buffer.snapshot` | ~5 |
| Breadcrumbs | `navigation.*` | ~40 |
| **Total** | | **~345** |

## What the Audience Sees in Dash0

After relaunch + reconnect:

1. **Timeline view**: A wall of events arriving in a ~2s burst, timestamped across the full offline window
2. **Journey traces**: Complete parent-child span hierarchies (journey → page → ui.tap) preserved across crash boundaries
3. **Crash forensics**: The breadcrumb trail leading up to each crash, with device health context (battery at 5%, thermal SEVERE, memory CRITICAL)
4. **Health degradation curve**: Battery/thermal/memory metrics showing the progressive decline
5. **Session continuity**: Same `mobile.session.id` across crash recovery boundaries (SDK preserves session across process death via disk)

## Testing

- Unit tests: Not needed — this is a demo scenario, not SDK logic
- Manual validation: Run at each intensity level, verify event counts in Dash0
- CI: Add `OfflineStormScenario` to the demo suite but default to `light` intensity to keep CI fast

## Integration with Existing Demo

Add to `run-demo-single.sh` scenario map:
```
offlineStorm → OfflineStormScenario#offlineStorm
```

Add to HOW_TO_DEMO.md quick reference.

Update `run-demo-full.sh` to optionally include offline storm (behind `--storm` flag, not by default — it adds 2-3 min).
