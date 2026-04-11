# Phase 7: Real Crash Scenarios — Design Specification

**Date:** 2026-04-10
**Status:** Approved
**Scope:** Replace faked crash scenarios with real process death, proving dual-tier buffer survives and recovery flush exports complete pre-crash context.
**Parent Epic:** `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (Phase 7, US-042 through US-044)

---

## 1. Problem

The existing `ConditionalFlushScenarios` and `FaultScenarios.crashAndRecovery` fake crashes by calling `MobileOtel.sendEvent("app.crash", ...)`. The process never dies. This means:
- We never prove the disk buffer survives process death
- We never prove RecoveryTracker detects the crash on restart
- We never prove the recovery flush exports pre-crash context from a previous session
- The demo is unconvincing — "we faked a crash and it worked" vs "we killed the app and everything survived"

## 2. Architecture

Two-layer approach:

**Layer 1 — Espresso + Orchestrator:** An instrumented test runs in a separate process from the app (via `androidx.test:orchestrator`). The test generates a rich pre-crash event sequence, triggers a real crash, then relaunches the app and verifies recovery behavior in-process.

**Layer 2 — Validated telemetry:** After the Espresso crash test completes, a bash script validates that the local OTel Collector received the complete pre-crash context + recovery event with correct ordering and attributes. Reuses the Phase 8 infrastructure (Docker collector, file exporters, `validate-telemetry.sh` pattern).

```
Espresso Orchestrator (separate process)
    │
    ├─ Phase 1: Launch app, generate events, trigger crash
    │     └─ App process dies (ErrorInstrumentation + RecoveryTracker fire)
    │
    ├─ Phase 2: Relaunch app, verify recovery
    │     └─ RecoveryTracker detects crash, emits app.recovery, flushes disk buffer
    │
    └─ Phase 3: Assert recovery state in-process
          └─ OTelMobile.getLastRecoveryType() == "crash"

Bash validator (after Espresso completes)
    │
    └─ Read collector/output/*.json
         ├─ Assert pre-crash events present (ui.tap, ui.screen_view, http, etc.)
         ├─ Assert app.crash event present with exception details
         ├─ Assert app.recovery event present with recovery_type=crash
         ├─ Assert chronological ordering
         └─ Assert session.id consistency
```

## 3. Scenario: Uncaught Exception Crash + Recovery (US-042, US-044)

This is the primary scenario. The test proves the complete crash→death→restart→recovery→flush pipeline.

### Pre-crash event sequence (~15-20 events over ~30 seconds)

1. **App launch** — lifecycle events: `app.foreground`, `ui.screen_view` for CalendarFragment
2. **Navigate 3 screens** — Calendar → Appointments → Book → Directions (3x `ui.screen_view`, 3x `ui.tap` on nav bar)
3. **Booking flow** — tap provider, tap time slot, type notes (3x `ui.tap`, 1x `ui.text_input`)
4. **API call** — hit booking endpoint (`http` span via OkHttp interceptor)
5. **Buffer snapshot** — `buffer.snapshot` event capturing RAM/disk stats pre-crash
6. **CRASH** — `throw RuntimeException("Booking service fatal error")` on main thread

### What happens at crash time (existing SDK behavior, no changes needed)

1. RecoveryTracker's uncaught handler fires first → sets `KEY_CRASH_MARKER` in SharedPreferences → chains to ErrorInstrumentation
2. ErrorInstrumentation captures exception → emits `app.crash` log with stack trace, breadcrumbs, vitals → triggers `flushOnError` → chains to system handler
3. System handler kills the process
4. The 2-second crash-safety mirror has already persisted recent RAM events to disk

### Post-restart verification

1. Orchestrator relaunches the app
2. RecoveryTracker reads `KEY_CRASH_MARKER` from SharedPreferences
3. Sets `lastRecoveryType = "crash"`
4. Emits `app.recovery` event with `recovery_type=crash` and `downtime_ms`
5. Triggers `forceFlush(30)` — exports all remaining disk-buffered events
6. Test asserts `OTelMobile.getLastRecoveryType() == "crash"`

### Collector validation

After Espresso completes, the bash validator checks `collector/output/logs.json` for:
- All pre-crash events present (ui.tap × 6, ui.screen_view × 4, ui.text_input × 1, buffer.snapshot × 1)
- `app.crash` event with `exception.type=RuntimeException`, `exception.message` containing "Booking service fatal error"
- `app.recovery` event with `recovery_type=crash`
- All events have `session.id` attribute
- `service.name=validated-test` (proving SharedPreferences config override survived)
- Events in chronological order (`observedTimeUnixNano` monotonically increasing within scope)

## 4. Triggering the Real Crash

The crash must happen on the main thread to trigger the standard Android crash flow. Two options:

**Option A — Debug toolbar button (recommended):** Add a "Real Crash" button to the existing debug toolbar in SchedulingActivity. The button calls:

```kotlin
throw RuntimeException("Booking service fatal error [demo crash]")
```

The Espresso test clicks this button via `clickDebugButton(R.id.btn_real_crash)`. This is consistent with how FaultScenarios already triggers jank, ANR, and memory pressure — all via debug toolbar buttons.

**Option B — Direct throw from test:** The test calls `runOnMainSync { throw ... }` but this would crash the test process too, even with orchestrator, because `runOnMainSync` runs in the app's main thread which is the app process. This doesn't work.

**Decision: Option A.** Add a debug toolbar button. The throw happens in the app process; the Espresso runner (in orchestrator mode) survives because it's in a separate process.

### Debug toolbar addition

In `SchedulingActivity` (or wherever the debug toolbar lives), add:

```kotlin
findViewById<Button>(R.id.btn_real_crash).setOnClickListener {
    // Let the SDK capture a real uncaught exception with full context
    throw RuntimeException("Booking service fatal error [demo crash]")
}
```

The button is only visible when `TelemetryFlags.showDebugToolbar=true` (existing guard).

## 5. Orchestrator Setup

### Gradle configuration

In `examples/demo-app/android/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "false"
    }
}

dependencies {
    androidTestUtil("androidx.test:orchestrator:1.5.1")
}
```

The key setting: `clearPackageData = false`. By default, orchestrator clears app data between tests. We need SharedPreferences (crash marker + config override) to survive across the crash→restart boundary.

### Running with orchestrator

```bash
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.scenarios.RealCrashScenarios \
  -Ptest.orchestrator=true
```

Or in the `run-validated-tests.sh` script:

```bash
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.scenarios.RealCrashScenarios \
  -Pandroid.testInstrumentationRunnerArguments.useTestStorageService=false
```

### Test class structure

```kotlin
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealCrashScenarios : DemoScenarioBase() {

    @Test
    fun realCrashAndRecovery() {
        // Phase 1: Generate pre-crash event sequence
        navigateTo(R.id.nav_calendar)
        navigateTo(R.id.nav_appointments)
        navigateTo(R.id.nav_book)
        tapProvider()
        tapTimeSlot()
        typeNotes("Test booking for crash scenario")
        submitBooking()
        Thread.sleep(3000)  // Let crash-safety mirror persist to disk

        // Phase 2: Trigger real crash
        emitBufferStats("pre_crash")
        clickDebugButton(R.id.btn_real_crash)
        // App process dies here. Test continues because orchestrator runs in separate process.

        // Phase 3: Relaunch and verify recovery
        // Orchestrator automatically relaunches for next assertion
        Thread.sleep(5000)  // Wait for app restart + recovery flush

        // Verify recovery happened
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val recoveryType = OTelMobile.getLastRecoveryType()
        assertEquals("crash", recoveryType?.name)
    }
}
```

**Important nuance:** With orchestrator, when the app process crashes, the test method fails with a process death exception. The orchestrator marks it as a "crash" and moves to the next test. To handle the two-phase nature (crash then verify), we need **two test methods** that run in order:

```kotlin
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealCrashScenarios : DemoScenarioBase() {

    @Test
    fun test1_generateEventsAndCrash() {
        // Generate events, trigger crash
        // This test will "fail" because the process dies — that's expected
    }

    @Test
    fun test2_verifyRecoveryAfterCrash() {
        // App was relaunched by orchestrator
        // RecoveryTracker should have detected the crash
        // Verify recovery_type == "crash"
        // Verify disk buffer was flushed
    }
}
```

The orchestrator runs each `@Test` in a fresh instrumentation invocation. `test1` crashes the app. `test2` launches into a fresh app instance where RecoveryTracker has already fired.

## 6. ANR Scenario (US-043) — Roadmap

**Not in scope for initial implementation.** Added to epic roadmap.

ANR (Application Not Responding) requires blocking the main thread for >5 seconds. On API 33+, the system aggressively kills ANR'd apps. The challenge:
- ANR dialog may appear (blocks UI automation)
- System may or may not kill the process (user can "Wait" or "Close")
- Timing is non-deterministic

**Approach when implemented:** Use `adb shell am hang` or a deliberate main-thread sleep >10s combined with `adb shell input keyevent KEYCODE_HOME` to dismiss the ANR dialog. The process should be killed by the system. RecoveryTracker detects `recovery_type=anr_force_kill`.

**Prerequisite:** The ANR marker (`KEY_ANR_MARKER`) must be set by the SDK's ANR watchdog before the system kills the process. Currently, `VitalsInstrumentation` detects ANR risk but may not set the marker fast enough if the system kills immediately. Need to verify timing.

## 7. OOM Scenario (US-043 variant) — Roadmap

**Not in scope for initial implementation.** Added to epic roadmap.

OOM (Out of Memory) kill is the hardest to test because:
- Memory allocation rate affects when the system intervenes
- The system may kill other processes first (LRU order)
- The OOM killer is non-deterministic

**Approach when implemented:** Allocate large byte arrays in a background thread until the system kills the process. The low-memory marker (`KEY_LOW_MEMORY_MARKER`) should be set by the SDK's `onTrimMemory(TRIM_MEMORY_COMPLETE)` callback before death. On restart, RecoveryTracker detects `recovery_type=low_memory_kill`.

**Risk:** The system may kill a different process, not ours. May need to use `adb shell am memory-pressure` to target our app specifically.

## 8. Validated Test Script Integration

### run-real-crash-test.sh

New script at `scripts/test/run-real-crash-test.sh` that combines the Espresso orchestrator test with collector validation:

```
1. Start local OTel Collector (Docker, reuse existing config)
2. Build + install demo app
3. Write SharedPreferences override → localhost:14317 (reuse Phase 8 pattern)
4. Run RealCrashScenarios with orchestrator (test1 crashes, test2 verifies)
5. Wait for collector flush (10s — longer than normal because recovery flush has latency)
6. Validate collector output:
   - Pre-crash events present
   - app.crash event with exception details
   - app.recovery event with recovery_type=crash
   - Chronological ordering
   - Session continuity
7. Stop collector
```

### Validation additions

Add to `validate-telemetry.sh` (or a new `validate-crash-recovery.sh`):

```bash
# Crash-specific validations
check_signal "$OUTPUT_DIR/logs.json" "app.crash" \
  "app.crash event (real crash captured)"

check_signal "$OUTPUT_DIR/logs.json" "app.recovery" \
  "app.recovery event (crash recovery on restart)"

check_signal "$OUTPUT_DIR/logs.json" "recovery_type.*crash" \
  "recovery_type=crash attribute on recovery event"

check_signal "$OUTPUT_DIR/logs.json" "exception.type.*RuntimeException" \
  "exception.type=RuntimeException on crash event"

check_signal "$OUTPUT_DIR/logs.json" "Booking service fatal error" \
  "exception.message contains crash message"
```

## 9. Files

### New Files (mobile-otel/)
| File | Purpose |
|------|---------|
| `examples/demo-app/android/src/androidTest/.../scenarios/RealCrashScenarios.kt` | Orchestrator-based real crash + recovery test |
| `scripts/test/run-real-crash-test.sh` | End-to-end script: orchestrator + collector validation |
| `scripts/test/validate-crash-recovery.sh` | Crash-specific telemetry validation |

### Modified Files (mobile-otel/)
| File | Change |
|------|--------|
| `examples/demo-app/android/build.gradle.kts` | Add orchestrator dependency, `clearPackageData=false` |
| `examples/demo-app/android/src/main/.../SchedulingActivity.kt` (or debug toolbar layout) | Add "Real Crash" button to debug toolbar |
| `examples/demo-app/android/src/main/res/layout/debug_toolbar.xml` (or equivalent) | Add button layout for real crash |

### Epic Updates
| File | Change |
|------|--------|
| `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` | Update US-042 status, add ANR/OOM to roadmap notes |

## 10. What's NOT in Scope

- ANR scenario implementation (roadmap — see section 6)
- OOM scenario implementation (roadmap — see section 7)
- Modifying the SDK crash handling code (existing ErrorInstrumentation + RecoveryTracker are sufficient)
- iOS crash scenarios (separate spec when iOS SDK is implemented)
- Phase 9 assertion framework (US-049) — this test uses simple grep-based validation
