# Phase 7: Real Crash Scenarios v2 — Design Specification

**Date:** 2026-04-11
**Status:** Draft
**Supersedes:** `2026-04-10-phase7-real-crash-design.md` (orchestrator approach failed — emulator OOM)
**Scope:** ADB-driven real crash + recovery tests with interactive demo control center, airplane mode offline scenario, export target switching, and telemetry inspection.
**Parent Epic:** `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (Phase 7, US-042 through US-044)

---

## 1. Problem

The previous Phase 7 spec used Espresso orchestrator to survive process death. In practice, the orchestrator approach caused the emulator to consume 32GB of host memory and freeze. Root cause: the crash→restart cycle triggers Android crash dialogs that the orchestrator cannot dismiss, creating a tight restart loop that balloons the emulator's qemu process.

Additionally, the crash test should serve as the **primary demo control center** — an interactive menu-driven tool for narrating crash→recovery during meetings, switching between Dash0 and a local OTel Collector, inspecting received telemetry, and running both online and airplane mode scenarios.

## 2. Architecture: ADB-Driven Two-Phase Test

Replace the orchestrator with a shell script that drives two separate `am instrument` invocations with explicit crash dialog handling between them.

```text
┌──────────────────────────────────────────────────────────────┐
│  run-real-crash-test.sh (HOST)                               │
│                                                              │
│  Phase 1: am instrument → RealCrashPhase1Test                │
│    ├─ Generates 15-20 pre-crash events (nav, taps, buffer)   │
│    ├─ Triggers real RuntimeException via debug toolbar        │
│    └─ Process dies → am instrument returns non-zero           │
│                                                              │
│  Interstitial (script-controlled):                           │
│    ├─ Detect process death (pidof returns empty)              │
│    ├─ Dismiss crash dialog (input keyevent BACK x 3)         │
│    ├─ Wait 3s for Android to settle                          │
│    └─ [Interactive] PAUSE for narration                       │
│                                                              │
│  Phase 2: am instrument → RealCrashPhase2Test                │
│    ├─ App launches fresh → RecoveryTracker fires              │
│    ├─ Waits for recovery flush (10s)                          │
│    ├─ Asserts lastRecoveryType == "crash"                     │
│    └─ Emits post-recovery buffer snapshot                     │
│                                                              │
│  [Airplane only] Network restore:                            │
│    ├─ Disable airplane mode                                   │
│    ├─ Wait for connectivity + RetryableExporter retry         │
│    └─ Wait for collector to receive events                    │
│                                                              │
│  Validation + Telemetry Dump:                                │
│    ├─ validate-crash-recovery.sh checks collector output      │
│    └─ dump-telemetry.sh shows formatted event timeline        │
└──────────────────────────────────────────────────────────────┘
```

### Why Not Orchestrator

The orchestrator runs each `@Test` in a fresh instrumentation invocation, but:

- It cannot dismiss system crash dialogs (Espresso only sees the app's view hierarchy)
- It has no backpressure — if the app crashes during init, orchestrator retries immediately
- It provides no hook for interactive pauses between tests
- The tight crash→restart loop caused the emulator to OOM (32GB host memory, frozen)

The ADB-driven approach gives us:

- **Full control** over timing between crash and restart
- **Crash dialog dismissal** via `adb shell input keyevent`
- **Interactive pauses** for demo narration
- **Network control** for airplane mode scenarios
- **Memory watchdog** to prevent the death spiral

## 3. Demo Control Center

The script is a persistent menu loop — after each action, it returns to the menu. Only `q` exits.

```text
┌───────────────────────────────────────────────────────────┐
│  Dash0 Mobile Observability — Demo Control                │
│───────────────────────────────────────────────────────────│
│                                                           │
│  SETUP                                                    │
│  s) Status check (emulator, collector, backend, app)      │
│  t) Toggle export target  [-> Local OTel Collector]       │
│                                                           │
│  CRASH DEMOS                                              │
│  1) Full automated run (CI mode)                          │
│  2) Interactive crash demo                                │
│  3) Airplane mode crash demo                              │
│  4) Full demo (2 then 3, narrated)                        │
│                                                           │
│  TELEMETRY                                                │
│  v) Validate last run (check collector output)            │
│  d) Dump telemetry (show events from collector)           │
│  r) Reset collector (clear output, fresh start)           │
│                                                           │
│  q) Quit                                                  │
└───────────────────────────────────────────────────────────┘
```

Command-line flags bypass the menu for CI or scripted use:

```bash
./run-real-crash-test.sh                    # shows menu (persistent loop)
./run-real-crash-test.sh --ci               # mode 1, no prompts, exits after
./run-real-crash-test.sh --interactive      # mode 2
./run-real-crash-test.sh --airplane         # mode 3
./run-real-crash-test.sh --full-demo        # mode 4
./run-real-crash-test.sh --start-emu --ci   # start emulator + CI mode
./run-real-crash-test.sh --status           # just run status check
./run-real-crash-test.sh --dump             # just dump last telemetry
```

### s) Pre-Flight Status Check

Verifies all dependencies before running a demo. Offers to fix missing pieces.

```text
▸ Pre-flight check

  Emulator     ✓ emulator-5554 (Pixel_7, API 36, booted)
  Collector    ✓ Running on ports 14317/14318 (3 pipelines)
  Backend      ✓ http://localhost:3001/health → 200 OK
  App          ✓ io.opentelemetry.android.demo installed (v1.1.0)
  Test APK     ✓ io.opentelemetry.android.demo.test installed
  Config       ✓ Export target: Local Collector (10.0.2.2:14317)
  Network      ✓ Airplane mode OFF
  Disk space   ✓ Collector output: 0 bytes (clean)
```

If something is missing:

```
  Collector    ✗ Not running
               → Start collector? [Y/n]

  App          ✗ Not installed
               → Build and install? [Y/n]
```

Implementation:

```bash
status_check() {
  log "Pre-flight check"
  echo ""

  # Emulator
  if adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
    local model=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    local api=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
    ok "Emulator     $SERIAL ($model, API $api, booted)"
  else
    err "Emulator     $SERIAL not booted"
  fi

  # Collector
  if docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" ps 2>/dev/null | grep -q "Up"; then
    ok "Collector     Running on ports 14317/14318"
  else
    err "Collector     Not running"
    prompt_fix "Start collector?" start_collector
  fi

  # Backend
  if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    ok "Backend       http://localhost:3001 → healthy"
  else
    err "Backend       Not running"
    prompt_fix "Start backend?" start_demo_backend
  fi

  # App installed
  if adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE"; then
    local version=$(adb -s "$SERIAL" shell dumpsys package "$PACKAGE" 2>/dev/null | grep versionName | head -1 | awk -F= '{print $2}')
    ok "App           $PACKAGE installed ($version)"
  else
    err "App           Not installed"
    prompt_fix "Build and install?" build_and_install
  fi

  # Test APK installed
  if adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE.test"; then
    ok "Test APK      $PACKAGE.test installed"
  else
    err "Test APK      Not installed"
    prompt_fix "Build and install test APK?" build_and_install
  fi

  # Export target
  local target=$(get_export_target)
  ok "Config        Export target: $target"

  # Airplane mode
  local airplane=$(adb -s "$SERIAL" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')
  if [ "$airplane" = "1" ]; then
    warn "Network       Airplane mode ON"
  else
    ok "Network       Airplane mode OFF"
  fi

  # Collector output
  local output_size=$(du -sh "$OUTPUT_DIR" 2>/dev/null | awk '{print $1}')
  ok "Output        Collector output: ${output_size:-empty}"

  # jq (required for telemetry dump)
  if command -v jq > /dev/null 2>&1; then
    ok "jq            $(jq --version)"
  else
    warn "jq            Not installed (telemetry dump won't work)"
    warn "              Install with: brew install jq"
  fi

  echo ""
}

prompt_fix() {
  local msg="$1"; shift
  echo -n "               → $msg [Y/n] "
  read -r yn
  if [ "$yn" != "n" ] && [ "$yn" != "N" ]; then
    "$@"
  fi
}
```

### t) Export Target Toggle

Switches the device between exporting to the local OTel Collector or Dash0.

```bash
toggle_export_target() {
  local current=$(get_export_target)
  echo ""
  if [ "$current" = "local" ]; then
    log "Switching export target: Local Collector → Dash0"
    write_dash0_prefs
    ok "Now exporting to Dash0"
  else
    log "Switching export target: Dash0 → Local Collector"
    write_collector_prefs
    ok "Now exporting to Local Collector (10.0.2.2:14317)"
  fi
  # Force-stop so app picks up new config on relaunch
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  ok "App stopped — will use new config on next launch"
}

get_export_target() {
  # Read current SharedPreferences to determine target
  local endpoint=$(adb -s "$SERIAL" shell "run-as $PACKAGE cat shared_prefs/otel_config.xml" 2>/dev/null \
    | grep collector_endpoint | sed 's/.*>\(.*\)<.*/\1/')
  if echo "$endpoint" | grep -q "10.0.2.2:14317"; then
    echo "local"
  else
    echo "dash0"
  fi
}

write_collector_prefs() {
  local prefs='<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
  <string name="collector_endpoint">http://10.0.2.2:14317</string>
  <string name="export_mode">CONTINUOUS</string>
  <string name="service_name">validated-test</string>
  <string name="service_version">1.0.0</string>
  <string name="auth_token">local-test</string>
  <boolean name="config_loaded_from_bundle" value="true" />
</map>'
  adb -s "$SERIAL" shell "run-as $PACKAGE mkdir -p shared_prefs"
  echo "$prefs" | adb -s "$SERIAL" shell "run-as $PACKAGE sh -c 'cat > shared_prefs/otel_config.xml'"
}

write_dash0_prefs() {
  # Read real Dash0 credentials from otel-config.json template on host
  local config_file="$DEMO_APP/android/src/debug/assets/otel-config.json"
  if [ ! -f "$config_file" ]; then
    err "Dash0 config not found at $config_file"
    err "Copy from .json.template and fill in credentials"
    return 1
  fi
  local endpoint=$(jq -r '.collector_endpoint' "$config_file")
  local auth=$(jq -r '.auth_token' "$config_file")
  local dataset=$(jq -r '.dataset // "otel-mobile"' "$config_file")
  local prefs="<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
  <string name=\"collector_endpoint\">$endpoint</string>
  <string name=\"export_mode\">CONTINUOUS</string>
  <string name=\"service_name\">otel-mobile-demo</string>
  <string name=\"service_version\">1.0.0</string>
  <string name=\"auth_token\">$auth</string>
  <string name=\"dataset\">$dataset</string>
  <boolean name=\"config_loaded_from_bundle\" value=\"true\" />
</map>"
  adb -s "$SERIAL" shell "run-as $PACKAGE mkdir -p shared_prefs"
  echo "$prefs" | adb -s "$SERIAL" shell "run-as $PACKAGE sh -c 'cat > shared_prefs/otel_config.xml'"
}
```

### d) Telemetry Dump

Parses collector output files and displays a formatted timeline. This is the **demo proof** — Barry can show the terminal output directly instead of switching to Dash0.

```text
▸ Telemetry received (23 log events, 4 spans)

  LOGS (23 events)
  ──────────────────────────────────────────────────────────
  09:14:01.234  app.foreground         session=a3f2...
  09:14:01.891  ui.screen_view         screen=CalendarFragment
  09:14:03.112  ui.tap                 target=nav_appointments
  09:14:03.645  ui.screen_view         screen=AppointmentsFragment
  09:14:05.223  ui.tap                 target=nav_book
  09:14:05.801  ui.screen_view         screen=BookFragment
  09:14:07.334  ui.tap                 target=nav_directions
  09:14:07.890  ui.screen_view         screen=DirectionsFragment
  09:14:09.112  ui.tap                 target=nav_book
  09:14:09.667  ui.screen_view         screen=BookFragment
  09:14:15.456  buffer.snapshot        label=pre_crash ram=18 disk=18
  09:14:16.001  app.crash              exception=RuntimeException
                                       message="Booking service fatal error"
  09:14:22.334  app.recovery           recovery_type=crash downtime=6333ms
  09:14:23.112  buffer.snapshot        label=post_recovery ram=0 disk=0

  SPANS (4)
  ──────────────────────────────────────────────────────────
  09:14:01-09:14:16  journey.realCrashAndRecovery
    09:14:01-09:14:03  page.CalendarFragment
    09:14:03-09:14:05  page.AppointmentsFragment
    09:14:05-09:14:16  page.BookFragment

  SUMMARY
  ──────────────────────────────────────────────────────────
  Pre-crash events:  15
  Crash event:        1 (RuntimeException)
  Recovery event:     1 (type=crash, downtime=6.3s)
  Post-recovery:      1
  Total:             18 logs, 4 spans
  Timeline:          09:14:01 → 09:14:23 (22s)
  Zero events lost:  ✓
```

Implementation approach: parse the OTLP JSON lines in `logs.json` and `traces.json` using `jq`. Each line is a JSON object with `resourceLogs[].scopeLogs[].logRecords[]`. Extract `body.stringValue`, `observedTimeUnixNano`, and key attributes.

```bash
dump_telemetry() {
  log "Telemetry received"
  echo ""

  local logs_file="$OUTPUT_DIR/logs.json"
  local traces_file="$OUTPUT_DIR/traces.json"

  if [ ! -s "$logs_file" ] && [ ! -s "$traces_file" ]; then
    warn "No telemetry found in $OUTPUT_DIR"
    warn "Run a crash demo first, or check that the collector is running"
    return
  fi

  # Count events
  local log_count=0
  local span_count=0
  if [ -s "$logs_file" ]; then
    log_count=$(jq -s '[.[].resourceLogs[].scopeLogs[].logRecords[]] | length' "$logs_file" 2>/dev/null || echo 0)
  fi
  if [ -s "$traces_file" ]; then
    span_count=$(jq -s '[.[].resourceSpans[].scopeSpans[].spans[]] | length' "$traces_file" 2>/dev/null || echo 0)
  fi
  echo "  $log_count log events, $span_count spans"
  echo ""

  # Dump logs as timeline
  if [ "$log_count" -gt 0 ]; then
    echo "  LOGS"
    echo "  ──────────────────────────────────────────────────────────"
    jq -rs '
      [.[].resourceLogs[].scopeLogs[].logRecords[]] |
      sort_by(.observedTimeUnixNano) |
      .[] |
      {
        time: (.observedTimeUnixNano | tonumber / 1e9 | strftime("%H:%M:%S")),
        body: .body.stringValue,
        attrs: ([.attributes[]? | {(.key): .value.stringValue // .value.intValue // .value.boolValue}] | add // {})
      } |
      "  \(.time)  \(.body)\t\(.attrs | to_entries | map("\(.key)=\(.value)") | join(" ") | .[0:60])"
    ' "$logs_file" 2>/dev/null || warn "  (could not parse logs — jq error)"
    echo ""
  fi

  # Dump spans as tree
  if [ "$span_count" -gt 0 ]; then
    echo "  SPANS"
    echo "  ──────────────────────────────────────────────────────────"
    jq -rs '
      [.[].resourceSpans[].scopeSpans[].spans[]] |
      sort_by(.startTimeUnixNano) |
      .[] |
      {
        start: (.startTimeUnixNano | tonumber / 1e9 | strftime("%H:%M:%S")),
        end: (.endTimeUnixNano | tonumber / 1e9 | strftime("%H:%M:%S")),
        name: .name
      } |
      "  \(.start)-\(.end)  \(.name)"
    ' "$traces_file" 2>/dev/null || warn "  (could not parse traces — jq error)"
    echo ""
  fi

  # Summary
  echo "  SUMMARY"
  echo "  ──────────────────────────────────────────────────────────"
  if [ -s "$logs_file" ]; then
    local crash_count=$(jq -rs '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == "app.crash")] | length' "$logs_file" 2>/dev/null || echo 0)
    local recovery_count=$(jq -rs '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == "app.recovery")] | length' "$logs_file" 2>/dev/null || echo 0)
    local precrash=$((log_count - crash_count - recovery_count))
    echo "  Pre-crash events:  $precrash"
    echo "  Crash events:      $crash_count"
    echo "  Recovery events:   $recovery_count"
  fi
  echo "  Total:             $log_count logs, $span_count spans"
  echo ""
}
```

### r) Reset Collector Output

Clears collector output files for a fresh run:

```bash
reset_collector_output() {
  rm -f "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"
  rm -f "$OUTPUT_DIR/.logs_size_before"
  touch "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"
  ok "Collector output cleared"
}
```

### v) Validate Last Run

Runs the existing `validate-crash-recovery.sh` against the current collector output. Same as what runs automatically after each crash demo, but available on demand for re-checking.

## 4. Test Classes

### RealCrashPhase1Test.kt

Standalone Espresso test. Generates pre-crash events, triggers real crash.

```kotlin
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealCrashPhase1Test : DemoScenarioBase() {

    @Test
    fun generateEventsAndCrash() {
        // Navigate through screens to build breadcrumb trail
        navigateTo(R.id.nav_appointments)
        navigateTo(R.id.nav_book)
        navigateTo(R.id.nav_directions)
        navigateTo(R.id.nav_book)

        // Wait for crash-safety mirror to persist RAM events to disk (runs every 2s)
        Thread.sleep(3000)

        // Capture buffer state before crash
        emitBufferStats("pre_crash")

        // Trigger REAL crash via debug toolbar
        clickDebugButton(R.id.btnTriggerCrash)

        // Wait for crash to fire (500ms postDelayed in SchedulingActivity)
        Thread.sleep(5000)
    }
}
```

Invoked via:

```bash
adb shell am instrument -w \
  -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase1Test \
  io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

Expected result: non-zero exit code (process death kills the instrumentation).

### RealCrashPhase2Test.kt

Standalone Espresso test. Verifies recovery state after crash.

```kotlin
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealCrashPhase2Test : DemoScenarioBase() {

    @Test
    fun verifyRecoveryAfterCrash() {
        // App launched by DemoScenarioBase.setUp()
        // RecoveryTracker already fired in DemoApp.onCreate()

        // Wait for recovery flush to complete
        Thread.sleep(10000)

        // Verify RecoveryTracker detected the crash
        val recoveryType = OTelMobile.getLastRecoveryType()
        assertNotNull("RecoveryTracker should have detected crash", recoveryType)
        assertEquals("crash", recoveryType)

        // Emit post-recovery buffer snapshot
        emitBufferStats("post_recovery")
    }
}
```

Invoked via:

```bash
adb shell am instrument -w \
  -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase2Test \
  io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner
```

Expected result: zero exit code (pass).

### Existing RealCrashScenarios.kt

Replaced by the two new classes. Delete.

## 5. Crash Dialog Handling

Between Phase 1 and Phase 2, the script must dismiss the Android crash dialog.

```bash
dismiss_crash_dialog() {
  # Wait for process to fully die
  local retries=0
  while adb -s "$SERIAL" shell pidof "$PACKAGE" 2>/dev/null | grep -q .; do
    sleep 1
    retries=$((retries + 1))
    if [ "$retries" -gt 10 ]; then
      echo "  Process still alive after 10s — force stopping"
      adb -s "$SERIAL" shell am force-stop "$PACKAGE"
      break
    fi
  done

  # Dismiss crash dialog(s) — send BACK keyevent multiple times.
  # On different API levels, the crash dialog may have 1-2 buttons or
  # may auto-dismiss. Multiple BACKs are harmless if no dialog is showing.
  sleep 1
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  sleep 0.5
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  sleep 0.5
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  sleep 1
}
```

## 6. Airplane Mode Scenario

Proves the buffer survives crash + network outage combined. Events only arrive once connectivity is restored. Device is offline for the entire scenario — airplane mode is enabled before any events are generated.

### Sequence

1. **Enable airplane mode** — device goes fully offline before any events are generated:

   ```bash
   adb -s "$SERIAL" shell cmd connectivity airplane-mode enable
   ```

2. **[Interactive] Pause:** "Device is in airplane mode. No network at all."
3. **Phase 1:** Generate events + crash. All events go to RAM buffer, crash-safety mirror writes to SQLite. No export possible (offline). App crashes.
4. **Dismiss crash dialog**
5. **[Interactive] Pause:** "Crashed while offline. Dead process, no network. Worst case."
6. **Phase 2:** App restarts, RecoveryTracker fires, `forceFlush(30)` attempts export → fails (no network). Events remain in SQLite disk buffer. Test still passes (recovery type is correct regardless of export success).
7. **[Interactive] Pause:** "Recovery detected. Flush attempted but failed — no network. Events safe in buffer."
8. **Disable airplane mode:**

   ```bash
   adb -s "$SERIAL" shell cmd connectivity airplane-mode disable
   ```

9. **Wait for periodic flush:** In CONTINUOUS export mode, `MobileLogRecordProcessor` runs `forceFlush()` every 30 seconds. After network restores, the next periodic flush exports all buffered events. Wait 30-40s for events to arrive at collector. (Note: `RetryableExporter` does NOT have a connectivity listener — it only retries during an active export call. The periodic CONTINUOUS flush is what picks up events after network restore.)
10. **Validate + dump:** All pre-crash events + crash + recovery present in collector output. Display formatted timeline.

### Validation Additions for Airplane Mode

The validation script checks:

- **Collector output was empty during airplane mode** — snapshot file size before airplane mode, compare after
- **Events arrived after network restore** — all events present in final validation
- **Same event count as standard crash test** — no events lost during offline period
- **RetryableExporter retry evidence** — optional check for retry-related log entries

```bash
validate-crash-recovery.sh --airplane-mode
```

Adds these checks on top of the standard crash validation:

```text
Airplane mode signals
  ✓ No events received while offline (collector output unchanged during airplane mode)
  ✓ All events arrived after network restore
  ✓ Event count matches expected (pre-crash + crash + recovery)
```

## 7. Memory Watchdog

Prevents the 32GB death spiral from recurring. Runs as a background process during the test.

```bash
start_memory_watchdog() {
  (
    while true; do
      # Check host-side emulator process memory (RSS in KB)
      emu_pid=$(pgrep -f "qemu-system" | head -1)
      if [ -n "$emu_pid" ]; then
        rss_kb=$(ps -o rss= -p "$emu_pid" 2>/dev/null | tr -d ' ')
        rss_gb=$(( ${rss_kb:-0} / 1048576 ))
        if [ "${rss_gb}" -gt 8 ]; then
          echo ""
          echo "MEMORY WATCHDOG: Emulator using ${rss_gb}GB — aborting test"
          adb -s "$SERIAL" shell am force-stop "$PACKAGE" 2>/dev/null
          kill $$  # kill parent script
          exit 1
        fi
      fi
      sleep 5
    done
  ) &
  WATCHDOG_PID=$!
}

stop_memory_watchdog() {
  if [ -n "${WATCHDOG_PID:-}" ]; then
    kill "$WATCHDOG_PID" 2>/dev/null || true
  fi
}
```

Threshold: 8GB. If the emulator exceeds this, the script force-stops the app and aborts. Normal operation uses 2-4GB.

## 8. Script Structure

### File Layout

```text
scripts/test/
├── run-real-crash-test.sh          ← main entry point (rewritten, persistent menu loop)
├── validate-crash-recovery.sh      ← existing, enhanced with --airplane-mode
├── lib/
│   ├── common.sh                   ← shared: logging, collector, device config, build
│   ├── crash-test-menu.sh          ← menu UI, mode dispatch, persistent loop
│   ├── crash-test-phases.sh        ← phase1, phase2, airplane, dismiss, watchdog
│   ├── export-target.sh            ← toggle, get/set Dash0 vs local collector prefs
│   └── dump-telemetry.sh           ← parse collector output, formatted timeline
├── collector/
│   ├── docker-compose.yaml         ← existing
│   └── collector.yaml              ← existing
└── run-validated-tests.sh          ← existing (refactored to source lib/common.sh)
```

### Shared Functions (lib/common.sh)

Extracted from the duplicate code in `run-validated-tests.sh` and `run-real-crash-test.sh`:

```bash
# Logging
log() ok() err() warn()

# Collector
start_collector()        # Docker compose up + wait for healthy
stop_collector()         # Docker compose down
reset_collector_output() # rm + mkdir + touch output files

# Device
find_emulator()          # Returns SERIAL of first connected emulator
start_demo_backend()     # npm run dev if not already running
dismiss_crash_dialog()   # BACK keyevents to dismiss crash dialog
build_and_install()      # gradlew installDebug + installDebugAndroidTest
```

### Phase Functions (lib/crash-test-phases.sh)

```bash
run_phase1() {
  log "Phase 1: Generating pre-crash events + triggering crash"
  start_memory_watchdog
  adb -s "$SERIAL" shell am instrument -w \
    -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase1Test \
    io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner \
    || true  # expected non-zero (process death)
  stop_memory_watchdog
  ok "Phase 1 complete — app crashed"
}

run_phase2() {
  log "Phase 2: Verifying recovery"
  adb -s "$SERIAL" shell am instrument -w \
    -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase2Test \
    io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner
  local rc=$?
  if [ $rc -ne 0 ]; then
    err "Phase 2 failed (exit code $rc)"
    return $rc
  fi
  ok "Phase 2 complete — recovery verified"
}

enable_airplane_mode() {
  log "Enabling airplane mode"
  adb -s "$SERIAL" shell cmd connectivity airplane-mode enable
  ok "Airplane mode ON"
}

disable_airplane_mode() {
  log "Disabling airplane mode"
  adb -s "$SERIAL" shell cmd connectivity airplane-mode disable
  # Wait for connectivity to restore
  local retries=0
  while ! adb -s "$SERIAL" shell ping -c 1 -W 2 10.0.2.2 > /dev/null 2>&1; do
    retries=$((retries + 1))
    if [ "$retries" -gt 15 ]; then
      err "Network did not restore after 30s"
      return 1
    fi
    sleep 2
  done
  ok "Network restored"
}

snapshot_collector_output() {
  # Record current file size for airplane mode validation
  wc -c < "$OUTPUT_DIR/logs.json" 2>/dev/null > "$OUTPUT_DIR/.logs_size_before" \
    || echo 0 > "$OUTPUT_DIR/.logs_size_before"
}

prompt_continue() {
  if [ "$INTERACTIVE" = true ]; then
    echo ""
    echo -e "\033[1;33m  ⏸  $1\033[0m"
    echo -e "\033[1;33m     Press ENTER to continue…\033[0m"
    read -r
  fi
}

prompt_action() {
  # Interactive: show message, wait for ENTER, then execute the action
  # CI: just execute the action immediately
  local msg="$1"; shift
  if [ "$INTERACTIVE" = true ]; then
    echo ""
    echo -e "\033[1;33m  ⏸  $msg\033[0m"
    read -r
  fi
  "$@"
}
```

### Mode Composition (lib/crash-test-menu.sh)

```bash
show_menu() {
  while true; do
    local target=$(get_export_target)
    local target_label
    if [ "$target" = "local" ]; then
      target_label="Local OTel Collector"
    else
      target_label="Dash0"
    fi

    echo ""
    echo "┌───────────────────────────────────────────────────────────┐"
    echo "│  Dash0 Mobile Observability — Demo Control                │"
    echo "│───────────────────────────────────────────────────────────│"
    echo "│                                                           │"
    echo "│  SETUP                                                    │"
    echo "│  s) Status check                                          │"
    echo "│  t) Toggle export target  [-> $target_label]              │"
    echo "│                                                           │"
    echo "│  CRASH DEMOS                                              │"
    echo "│  1) Full automated run (CI mode)                          │"
    echo "│  2) Interactive crash demo                                │"
    echo "│  3) Airplane mode crash demo                              │"
    echo "│  4) Full demo (2 then 3, narrated)                        │"
    echo "│                                                           │"
    echo "│  TELEMETRY                                                │"
    echo "│  v) Validate last run                                     │"
    echo "│  d) Dump telemetry                                        │"
    echo "│  r) Reset collector output                                │"
    echo "│                                                           │"
    echo "│  q) Quit                                                  │"
    echo "└───────────────────────────────────────────────────────────┘"
    echo -n "  > "
    read -r choice

    case "$choice" in
      s) status_check ;;
      t) toggle_export_target ;;
      1) run_ci_mode ;;
      2) run_interactive_crash ;;
      3) run_airplane_mode_crash ;;
      4) run_full_demo ;;
      v) validate ;;
      d) dump_telemetry ;;
      r) reset_collector_output ;;
      q) teardown; exit 0 ;;
      *) echo "  Unknown option: $choice" ;;
    esac
  done
}

run_ci_mode() {
  INTERACTIVE=false
  reset_collector_output
  run_phase1
  dismiss_crash_dialog
  sleep 3
  run_phase2
  sleep 10  # wait for collector flush
  validate
  dump_telemetry
}

run_interactive_crash() {
  INTERACTIVE=true
  reset_collector_output
  prompt_action "Press ENTER to start Phase 1 (generate events + crash)" run_phase1
  dismiss_crash_dialog
  prompt_action "App crashed. Process is dead. Press ENTER to trigger recovery" run_phase2
  sleep 10
  prompt_continue "Recovery complete. All events exported."
  validate
  dump_telemetry
}

run_airplane_mode_crash() {
  INTERACTIVE=true
  reset_collector_output
  prompt_action "Press ENTER to enable airplane mode" enable_airplane_mode
  snapshot_collector_output
  prompt_action "Press ENTER to start Phase 1 (generate events + crash)" run_phase1
  dismiss_crash_dialog
  prompt_action "Press ENTER to restart app (still offline — recovery flush will fail)" run_phase2
  prompt_action "Press ENTER to disable airplane mode (network restores, events flush)" disable_airplane_mode
  log "Waiting for periodic CONTINUOUS flush to export (35s)"
  sleep 35
  prompt_continue "Network restored. Events should have landed."
  validate --airplane-mode
  dump_telemetry
}

run_full_demo() {
  run_interactive_crash
  echo ""
  log "═══ Now: Airplane Mode Scenario ═══"
  echo ""
  run_airplane_mode_crash
}
```

## 9. Validation Script Enhancements

### Standard Mode (existing, unchanged)

Checks for:
- Pre-crash events: `ui.screen_view`, `ui.tap`, `app.foreground`, `buffer.snapshot`
- Crash event: `app.crash`, `RuntimeException`, exception message
- Recovery event: `app.recovery`, `recovery_type=crash`
- Service identity: `service.name=validated-test`, `session.id`, `device.id`
- Trace signals: `page.*` spans (optional)

### Airplane Mode Additions (--airplane-mode flag)

```bash
if [ "$AIRPLANE_MODE" = true ]; then
  log "Airplane mode signals"

  # Check that no events arrived while offline
  LOGS_SIZE_AFTER=$(wc -c < "$OUTPUT_DIR/logs.json" 2>/dev/null || echo 0)
  if [ -f "$OUTPUT_DIR/.logs_size_before" ]; then
    LOGS_SIZE_BEFORE=$(cat "$OUTPUT_DIR/.logs_size_before")
    if [ "$LOGS_SIZE_BEFORE" -eq "$LOGS_SIZE_AFTER" ]; then
      ok "No events received while offline (collector output unchanged)"
      PASS=$((PASS + 1))
    else
      warn "Collector output changed during airplane mode — possible buffered write"
      WARN=$((WARN + 1))
    fi
  fi

  # All standard checks still apply (events arrived after network restore)
  check_signal "$OUTPUT_DIR/logs.json" "app.crash" \
    "app.crash event arrived after network restore"

  check_signal "$OUTPUT_DIR/logs.json" "app.recovery" \
    "app.recovery event arrived after network restore"
fi
```

The snapshot file size is written to `$OUTPUT_DIR/.logs_size_before` by `snapshot_collector_output()` so the validation script can read it.

## 10. Files

### New Files (mobile-otel/)

| File | Purpose |
| --- | --- |
| `.../scenarios/RealCrashPhase1Test.kt` | Phase 1: generate events + crash |
| `.../scenarios/RealCrashPhase2Test.kt` | Phase 2: verify recovery |
| `scripts/test/lib/common.sh` | Shared: logging, collector, device config, build |
| `scripts/test/lib/crash-test-menu.sh` | Menu UI, mode dispatch, persistent loop |
| `scripts/test/lib/crash-test-phases.sh` | Phase functions: phase1, phase2, airplane, dismiss |
| `scripts/test/lib/export-target.sh` | Export target toggle: Dash0 vs local collector |
| `scripts/test/lib/dump-telemetry.sh` | Parse collector output, formatted timeline + summary |

Test classes live under `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/`.

### Modified Files (mobile-otel/)

| File | Change |
| --- | --- |
| `scripts/test/run-real-crash-test.sh` | Rewrite: source lib scripts, persistent menu loop + CLI flags |
| `scripts/test/validate-crash-recovery.sh` | Add `--airplane-mode` flag + offline checks |
| `scripts/test/run-validated-tests.sh` | Refactor to source `lib/common.sh` (reduce duplication) |

### Deleted Files

| File | Action |
|------|--------|
| `examples/demo-app/android/src/androidTest/.../scenarios/RealCrashScenarios.kt` | Delete (replaced by Phase1 + Phase2 classes) |

### No SDK Changes

The existing crash handling infrastructure (`RecoveryTracker`, `ErrorInstrumentation`, `MobileLogRecordProcessor`, `DiskLogBuffer`, `RetryableExporter`) requires **no modifications**. The entire fix is in the test infrastructure and scripts.

## 11. What's NOT in Scope

- ANR scenario (roadmap — requires main thread blocking + system kill timing)
- OOM scenario (roadmap — non-deterministic, system may kill other processes)
- Modifying SDK crash handling code (existing infrastructure is correct)
- SR-004 `persistedToDisk` fix (separate backlog item — not the cause of the 32GB issue)
- iOS crash scenarios (separate spec when iOS SDK is implemented)
- Phase 9 assertion framework (US-049) — this test uses grep/jq-based validation

## 12. Demo Talking Points

Built into the interactive modes as prompt messages:

### Interactive Crash Demo (mode 2)

1. **Before crash:** "Watch the emulator — real app, real user journey. 18 events buffered in RAM, mirrored to SQLite every 2 seconds."
2. **After crash:** "Process is dead. RAM is gone. But SQLite doesn't care about your process. Every event is safe on disk."
3. **After recovery:** "RecoveryTracker reads the crash marker, knows what happened, flushes everything."
4. **Telemetry dump:** "Here's every event — full timeline from launch to crash to recovery. Zero data loss."

### Airplane Mode Demo (mode 3)

1. **Before crash:** "Same journey, but now the device has no network."
2. **After crash (offline):** "Crashed with no network. Worst case scenario — dead process, no connectivity."
3. **After restart (still offline):** "App detected the crash, tried to flush, failed. Events are patient — they'll wait in SQLite."
4. **After network restore:** "Network's back. The SDK's periodic flush fires within 30 seconds, exports everything. Every event arrives. Zero data loss."
5. **Telemetry dump:** "Same event count as the online test. Nothing lost. That's dual-tier buffering."

### Export Target Switching

- **Local Collector:** "I'm sending to a local OTel Collector so we can inspect every byte. Standard OTLP, no vendor lock-in."
- **Switch to Dash0:** "Now let me switch to Dash0 — same OTLP protocol, just a different endpoint. Watch the dashboard."
- **After switch:** "Same events, same format, different backend. That's the power of OpenTelemetry."

## 13. Dependencies

- `jq` — required for telemetry dump (parsing OTLP JSON). Available via `brew install jq` on macOS. Script should check for it in status_check and warn if missing.
- `docker` / `docker compose` — required for local OTel Collector.
- `adb` — required for all device interaction.
- Existing demo app build toolchain (Gradle, JDK 17, Android SDK).
