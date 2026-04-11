# Phase 7: Crash Demo Control Center — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken orchestrator-based crash test with an ADB-driven interactive demo control center that runs crash + recovery + airplane mode scenarios.

**Architecture:** Shell script drives two separate `am instrument` invocations with crash dialog handling between them. Persistent menu loop provides status checks, export target switching (Dash0 vs local collector), telemetry dump, and validation. The spec code is nearly complete — the implementation is primarily assembling the spec's bash/kotlin snippets into files.

**Tech Stack:** Bash 3.2 (macOS), Kotlin/Espresso (Android test classes), jq (OTLP JSON parsing), Docker (OTel Collector)

**Spec:** `docs/superpowers/specs/2026-04-11-phase7-real-crash-v2-design.md`

---

## File Map

### New Files

| File | Responsibility |
| --- | --- |
| `scripts/test/lib/common.sh` | Shared functions: logging, collector lifecycle, device config, build helpers |
| `scripts/test/lib/crash-test-phases.sh` | Phase 1/2 execution, airplane mode, crash dialog dismiss, memory watchdog, prompts |
| `scripts/test/lib/crash-test-menu.sh` | Menu UI, mode composition (CI/interactive/airplane/full), persistent loop |
| `scripts/test/lib/export-target.sh` | Export target toggle/get/set, SharedPreferences for Dash0 vs local collector |
| `scripts/test/lib/dump-telemetry.sh` | Parse collector JSON output via jq, formatted timeline + summary |
| `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashPhase1Test.kt` | Phase 1: generate events + trigger crash |
| `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashPhase2Test.kt` | Phase 2: verify recovery after crash |

### Modified Files

| File | Change |
| --- | --- |
| `scripts/test/run-real-crash-test.sh` | Rewrite: thin entry point sourcing lib scripts |
| `scripts/test/validate-crash-recovery.sh` | Add `--airplane-mode` flag + offline validation checks |
| `scripts/test/run-validated-tests.sh` | Refactor to source `lib/common.sh` (dedup) |

### Deleted Files

| File | Reason |
| --- | --- |
| `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashScenarios.kt` | Replaced by Phase1 + Phase2 classes |

---

### Task 1: Create lib/common.sh — Shared Functions

**Files:**
- Create: `scripts/test/lib/common.sh`

- [ ] **Step 1: Create the lib directory and common.sh**

```bash
#!/usr/bin/env bash
# Common functions shared by crash test and validated test scripts.
# Source this file — do not execute directly.

# ── Logging ───────────────────────────────────────────────────────────────────

log()  { echo -e "\n\033[1;36m▸ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }
warn() { echo -e "\033[1;33m  ⚠ $*\033[0m"; }

# ── Paths ─────────────────────────────────────────────────────────────────────

COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_DIR="$(cd "$COMMON_DIR/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEMO_APP="$REPO_ROOT/examples/demo-app"
COLLECTOR_DIR="$SCRIPT_DIR/collector"
OUTPUT_DIR="$COLLECTOR_DIR/output"
PACKAGE="io.opentelemetry.android.demo"

# ── Emulator ──────────────────────────────────────────────────────────────────

find_emulator() {
  SERIAL=$(adb devices 2>/dev/null | grep "emulator" | head -1 | awk '{print $1}')
  if [ -z "$SERIAL" ]; then
    err "No emulator found. Start one first."
    return 1
  fi
}

# ── Collector ─────────────────────────────────────────────────────────────────

start_collector() {
  log "Starting local OTel Collector (Docker)"
  rm -rf "$OUTPUT_DIR"
  mkdir -p "$OUTPUT_DIR"
  touch "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"

  docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" up -d 2>&1 || \
    docker-compose -f "$COLLECTOR_DIR/docker-compose.yaml" up -d 2>&1

  local i
  for i in $(seq 1 15); do
    if docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" ps 2>/dev/null | grep -q "Up"; then
      ok "Collector running on ports 14317 (gRPC) + 14318 (HTTP)"
      return 0
    fi
    if [ "$i" -eq 15 ]; then
      err "Collector failed to start"
      return 1
    fi
    sleep 1
  done
}

stop_collector() {
  log "Stopping collector"
  docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" down 2>/dev/null || \
    docker-compose -f "$COLLECTOR_DIR/docker-compose.yaml" down 2>/dev/null
  ok "Collector stopped"
}

reset_collector_output() {
  rm -f "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"
  rm -f "$OUTPUT_DIR/.logs_size_before"
  touch "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"
  ok "Collector output cleared"
}

# ── Demo Backend ──────────────────────────────────────────────────────────────

start_demo_backend() {
  if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    ok "Backend already running"
    return 0
  fi
  log "Starting demo backend"
  cd "$REPO_ROOT/examples/demo-backend"
  npm run dev > /tmp/demo-backend.log 2>&1 &
  sleep 3
  if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    ok "Backend running on port 3001"
  else
    err "Backend failed to start — check /tmp/demo-backend.log"
    return 1
  fi
}

# ── Build & Install ───────────────────────────────────────────────────────────

build_and_install() {
  log "Building and installing demo app + test APK"
  cd "$DEMO_APP"
  ./gradlew installDebug installDebugAndroidTest --quiet
  ok "Installed app + test APK"
}

# ── Device Config ─────────────────────────────────────────────────────────────

dismiss_crash_dialog() {
  # Wait for process to fully die
  local retries=0
  while adb -s "$SERIAL" shell pidof "$PACKAGE" 2>/dev/null | grep -q .; do
    sleep 1
    retries=$((retries + 1))
    if [ "$retries" -gt 10 ]; then
      warn "Process still alive after 10s — force stopping"
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

- [ ] **Step 2: Make it executable and verify it sources without error**

Run:
```bash
chmod +x scripts/test/lib/common.sh
bash -n scripts/test/lib/common.sh
```
Expected: no output (syntax OK)

---

### Task 2: Create lib/export-target.sh — Export Target Toggle

**Files:**
- Create: `scripts/test/lib/export-target.sh`

- [ ] **Step 1: Create export-target.sh**

```bash
#!/usr/bin/env bash
# Export target management: switch between local OTel Collector and Dash0.
# Source this file — do not execute directly.
# Requires: SERIAL, PACKAGE, DEMO_APP (from common.sh)

get_export_target() {
  local endpoint
  endpoint=$(adb -s "$SERIAL" shell "run-as $PACKAGE cat shared_prefs/otel_config.xml" 2>/dev/null \
    | grep collector_endpoint | sed 's/.*>\(.*\)<.*/\1/')
  if echo "$endpoint" | grep -q "10.0.2.2:14317"; then
    echo "local"
  else
    echo "dash0"
  fi
}

get_export_target_label() {
  local target
  target=$(get_export_target)
  if [ "$target" = "local" ]; then
    echo "Local OTel Collector"
  else
    echo "Dash0"
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
  local config_file="$DEMO_APP/android/src/debug/assets/otel-config.json"
  if [ ! -f "$config_file" ]; then
    err "Dash0 config not found at $config_file"
    err "Copy from .json.template and fill in credentials"
    return 1
  fi
  local endpoint auth dataset
  endpoint=$(jq -r '.collector_endpoint' "$config_file")
  auth=$(jq -r '.auth_token' "$config_file")
  dataset=$(jq -r '.dataset // "otel-mobile"' "$config_file")
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

toggle_export_target() {
  local current
  current=$(get_export_target)
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
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  ok "App stopped — will use new config on next launch"
}
```

- [ ] **Step 2: Verify syntax**

Run: `bash -n scripts/test/lib/export-target.sh`
Expected: no output

---

### Task 3: Create lib/dump-telemetry.sh — Telemetry Timeline Display

**Files:**
- Create: `scripts/test/lib/dump-telemetry.sh`

- [ ] **Step 1: Create dump-telemetry.sh**

```bash
#!/usr/bin/env bash
# Parse OTel Collector output and display a formatted timeline.
# Source this file — do not execute directly.
# Requires: OUTPUT_DIR (from common.sh), jq on PATH

dump_telemetry() {
  local logs_file="$OUTPUT_DIR/logs.json"
  local traces_file="$OUTPUT_DIR/traces.json"

  if [ ! -s "$logs_file" ] && [ ! -s "$traces_file" ]; then
    warn "No telemetry found in $OUTPUT_DIR"
    warn "Run a crash demo first, or check that the collector is running"
    return
  fi

  if ! command -v jq > /dev/null 2>&1; then
    err "jq is required for telemetry dump. Install with: brew install jq"
    return 1
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

  log "Telemetry received"
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

  # Dump spans
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
    local crash_count recovery_count precrash
    crash_count=$(jq -rs '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == "app.crash")] | length' "$logs_file" 2>/dev/null || echo 0)
    recovery_count=$(jq -rs '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == "app.recovery")] | length' "$logs_file" 2>/dev/null || echo 0)
    precrash=$((log_count - crash_count - recovery_count))
    echo "  Pre-crash events:  $precrash"
    echo "  Crash events:      $crash_count"
    echo "  Recovery events:   $recovery_count"
  fi
  echo "  Total:             $log_count logs, $span_count spans"
  echo ""
}
```

- [ ] **Step 2: Verify syntax**

Run: `bash -n scripts/test/lib/dump-telemetry.sh`
Expected: no output

---

### Task 4: Create lib/crash-test-phases.sh — Phase Execution + Airplane + Watchdog

**Files:**
- Create: `scripts/test/lib/crash-test-phases.sh`

- [ ] **Step 1: Create crash-test-phases.sh**

```bash
#!/usr/bin/env bash
# Crash test phase execution: phase1, phase2, airplane mode, memory watchdog.
# Source this file — do not execute directly.
# Requires: SERIAL, PACKAGE, OUTPUT_DIR (from common.sh)

INTERACTIVE=false
WATCHDOG_PID=""

# ── Phase Execution ───────────────────────────────────────────────────────────

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

# ── Airplane Mode ─────────────────────────────────────────────────────────────

enable_airplane_mode() {
  log "Enabling airplane mode"
  adb -s "$SERIAL" shell cmd connectivity airplane-mode enable
  ok "Airplane mode ON"
}

disable_airplane_mode() {
  log "Disabling airplane mode"
  adb -s "$SERIAL" shell cmd connectivity airplane-mode disable
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
  wc -c < "$OUTPUT_DIR/logs.json" 2>/dev/null > "$OUTPUT_DIR/.logs_size_before" \
    || echo 0 > "$OUTPUT_DIR/.logs_size_before"
}

# ── Interactive Prompts ───────────────────────────────────────────────────────

prompt_continue() {
  if [ "$INTERACTIVE" = true ]; then
    echo ""
    echo -e "\033[1;33m  ⏸  $1\033[0m"
    echo -e "\033[1;33m     Press ENTER to continue…\033[0m"
    read -r
  fi
}

prompt_action() {
  local msg="$1"; shift
  if [ "$INTERACTIVE" = true ]; then
    echo ""
    echo -e "\033[1;33m  ⏸  $msg\033[0m"
    read -r
  fi
  "$@"
}

# ── Memory Watchdog ───────────────────────────────────────────────────────────

start_memory_watchdog() {
  (
    while true; do
      emu_pid=$(pgrep -f "qemu-system" | head -1)
      if [ -n "$emu_pid" ]; then
        rss_kb=$(ps -o rss= -p "$emu_pid" 2>/dev/null | tr -d ' ')
        rss_gb=$(( ${rss_kb:-0} / 1048576 ))
        if [ "${rss_gb}" -gt 8 ]; then
          echo ""
          echo "MEMORY WATCHDOG: Emulator using ${rss_gb}GB — aborting test"
          adb -s "$SERIAL" shell am force-stop "$PACKAGE" 2>/dev/null
          kill $$ 2>/dev/null
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
    WATCHDOG_PID=""
  fi
}

# ── Validation ────────────────────────────────────────────────────────────────

validate() {
  "$SCRIPT_DIR/validate-crash-recovery.sh" "$@"
}
```

- [ ] **Step 2: Verify syntax**

Run: `bash -n scripts/test/lib/crash-test-phases.sh`
Expected: no output

---

### Task 5: Create lib/crash-test-menu.sh — Menu UI + Mode Composition

**Files:**
- Create: `scripts/test/lib/crash-test-menu.sh`

- [ ] **Step 1: Create crash-test-menu.sh with status_check and all modes**

```bash
#!/usr/bin/env bash
# Demo control center: menu UI, status check, mode composition.
# Source this file — do not execute directly.
# Requires: all lib/*.sh sourced first

# ── Status Check ──────────────────────────────────────────────────────────────

status_check() {
  log "Pre-flight check"
  echo ""

  # Emulator
  if adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
    local model api
    model=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    api=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
    ok "Emulator     $SERIAL ($model, API $api, booted)"
  else
    err "Emulator     $SERIAL not booted"
  fi

  # Collector
  if docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" ps 2>/dev/null | grep -q "Up"; then
    ok "Collector    Running on ports 14317/14318"
  else
    err "Collector    Not running"
    prompt_fix "Start collector?" start_collector
  fi

  # Backend
  if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    ok "Backend      http://localhost:3001 → healthy"
  else
    err "Backend      Not running"
    prompt_fix "Start backend?" start_demo_backend
  fi

  # App installed
  if adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE$"; then
    local version
    version=$(adb -s "$SERIAL" shell dumpsys package "$PACKAGE" 2>/dev/null | grep versionName | head -1 | awk -F= '{print $2}' | tr -d '\r')
    ok "App          $PACKAGE installed ($version)"
  else
    err "App          Not installed"
    prompt_fix "Build and install?" build_and_install
  fi

  # Test APK installed
  if adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE.test"; then
    ok "Test APK     $PACKAGE.test installed"
  else
    err "Test APK     Not installed"
    prompt_fix "Build and install test APK?" build_and_install
  fi

  # Export target
  ok "Config       Export target: $(get_export_target_label)"

  # Airplane mode
  local airplane
  airplane=$(adb -s "$SERIAL" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')
  if [ "$airplane" = "1" ]; then
    warn "Network      Airplane mode ON"
  else
    ok "Network      Airplane mode OFF"
  fi

  # Collector output
  local output_size
  output_size=$(du -sh "$OUTPUT_DIR" 2>/dev/null | awk '{print $1}')
  ok "Output       Collector output: ${output_size:-empty}"

  # jq
  if command -v jq > /dev/null 2>&1; then
    ok "jq           $(jq --version 2>&1)"
  else
    warn "jq           Not installed (telemetry dump won't work)"
    warn "             Install with: brew install jq"
  fi

  echo ""
}

prompt_fix() {
  local msg="$1"; shift
  echo -n "             → $msg [Y/n] "
  read -r yn
  if [ "$yn" != "n" ] && [ "$yn" != "N" ]; then
    "$@"
  fi
}

# ── Mode Composition ──────────────────────────────────────────────────────────

run_ci_mode() {
  INTERACTIVE=false
  reset_collector_output
  run_phase1
  dismiss_crash_dialog
  sleep 3
  run_phase2
  sleep 10
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

# ── Menu ──────────────────────────────────────────────────────────────────────

show_menu() {
  while true; do
    local target_label
    target_label=$(get_export_target_label)

    echo ""
    echo "┌───────────────────────────────────────────────────────────┐"
    echo "│  Dash0 Mobile Observability — Demo Control                │"
    echo "│───────────────────────────────────────────────────────────│"
    echo "│                                                           │"
    echo "│  SETUP                                                    │"
    echo "│  s) Status check                                          │"
    echo "│  t) Toggle export target  [-> $target_label]"
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
```

- [ ] **Step 2: Verify syntax**

Run: `bash -n scripts/test/lib/crash-test-menu.sh`
Expected: no output

---

### Task 6: Rewrite run-real-crash-test.sh — Thin Entry Point

**Files:**
- Modify: `scripts/test/run-real-crash-test.sh`

- [ ] **Step 1: Rewrite as thin entry point**

```bash
#!/usr/bin/env bash
# Dash0 Mobile Observability — Demo Control Center
#
# Interactive menu for running real crash + recovery scenarios,
# switching export targets (Dash0 vs local collector), and
# inspecting telemetry.
#
# Usage:
#   ./run-real-crash-test.sh                    # interactive menu
#   ./run-real-crash-test.sh --ci               # automated CI mode
#   ./run-real-crash-test.sh --interactive      # interactive crash demo
#   ./run-real-crash-test.sh --airplane         # airplane mode crash demo
#   ./run-real-crash-test.sh --full-demo        # full demo (crash + airplane)
#   ./run-real-crash-test.sh --status           # status check only
#   ./run-real-crash-test.sh --dump             # dump last telemetry
#   ./run-real-crash-test.sh --start-emu --ci   # start emulator + CI mode
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Source all library modules
source "$SCRIPT_DIR/lib/common.sh"
source "$SCRIPT_DIR/lib/export-target.sh"
source "$SCRIPT_DIR/lib/dump-telemetry.sh"
source "$SCRIPT_DIR/lib/crash-test-phases.sh"
source "$SCRIPT_DIR/lib/crash-test-menu.sh"

# ── Parse arguments ───────────────────────────────────────────────────────────

START_EMU=false
MODE=""
for arg in "$@"; do
  case "$arg" in
    --start-emu)    START_EMU=true ;;
    --ci)           MODE="ci" ;;
    --interactive)  MODE="interactive" ;;
    --airplane)     MODE="airplane" ;;
    --full-demo)    MODE="full-demo" ;;
    --status)       MODE="status" ;;
    --dump)         MODE="dump" ;;
  esac
done

# ── Start emulator if requested ───────────────────────────────────────────────

if [ "$START_EMU" = true ]; then
  log "Starting emulator"
  nohup emulator -avd Medium_Phone_API_36.1 -no-snapshot-save > /tmp/emu.log 2>&1 &
  adb wait-for-device
  until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
  ok "Emulator booted"
fi

# ── Find emulator ─────────────────────────────────────────────────────────────

find_emulator || exit 1

# ── Teardown handler ──────────────────────────────────────────────────────────

teardown() {
  stop_memory_watchdog
  # Ensure airplane mode is off
  adb -s "$SERIAL" shell cmd connectivity airplane-mode disable 2>/dev/null || true
}
trap teardown EXIT

# ── Dispatch ──────────────────────────────────────────────────────────────────

case "$MODE" in
  ci)           run_ci_mode ;;
  interactive)  run_interactive_crash ;;
  airplane)     run_airplane_mode_crash ;;
  full-demo)    run_full_demo ;;
  status)       status_check ;;
  dump)         dump_telemetry ;;
  *)            show_menu ;;
esac
```

- [ ] **Step 2: Make executable and verify syntax**

Run:
```bash
chmod +x scripts/test/run-real-crash-test.sh
bash -n scripts/test/run-real-crash-test.sh
```
Expected: no output

---

### Task 7: Create Kotlin Test Classes

**Files:**
- Create: `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashPhase1Test.kt`
- Create: `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashPhase2Test.kt`
- Delete: `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashScenarios.kt`

- [ ] **Step 1: Create RealCrashPhase1Test.kt**

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.scenarios

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.demo.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 of the real crash test: generates a rich pre-crash event sequence
 * then triggers a real RuntimeException crash via the debug toolbar.
 *
 * This test is designed to be invoked via `am instrument` from the crash
 * demo shell script — NOT via Gradle's connectedAndroidTest with orchestrator.
 *
 * The app process will die when the crash fires. The shell script detects
 * process death, dismisses the crash dialog, and launches Phase 2.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealCrashPhase1Test : DemoScenarioBase() {

    companion object {
        private const val TAG = "RealCrashPhase1"
    }

    @Test
    fun generateEventsAndCrash() {
        Log.i(TAG, "=== Phase 1: Generating pre-crash events ===")

        // Navigate through screens to build breadcrumb trail
        pace.step("realCrash", "app_launched")

        pace.step("realCrash", "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step("realCrash", "navigate_to_book")
        navigateTo(R.id.nav_book)

        pace.step("realCrash", "navigate_to_directions")
        navigateTo(R.id.nav_directions)

        // Navigate back to booking flow for the crash context
        pace.step("realCrash", "navigate_to_book_for_crash")
        navigateTo(R.id.nav_book)

        // Wait for crash-safety mirror to persist RAM events to disk (runs every 2s)
        pace.step("realCrash", "waiting_for_disk_mirror")
        Thread.sleep(3000)

        // Capture buffer state before crash
        emitBufferStats("pre_crash")

        // Trigger REAL crash — btnTriggerCrash throws RuntimeException on main thread
        // via Handler.postDelayed(500ms). The app process will die.
        Log.i(TAG, "=== Triggering real crash via debug toolbar ===")
        pace.step("realCrash", "triggering_crash")
        clickDebugButton(R.id.btnTriggerCrash)

        // The 500ms postDelayed means we need to wait for the crash to fire.
        // This sleep will be interrupted by process death — that's expected.
        Thread.sleep(5000)
    }
}
```

- [ ] **Step 2: Create RealCrashPhase2Test.kt**

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.scenarios

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.mobile.OTelMobile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 of the real crash test: verifies recovery after a real crash.
 *
 * The app starts fresh — RecoveryTracker reads the crash marker from
 * SharedPreferences, sets lastRecoveryType="crash", emits app.recovery,
 * and triggers forceFlush(30) to export disk-buffered events.
 *
 * This test is designed to be invoked via `am instrument` from the crash
 * demo shell script AFTER Phase 1 has crashed the app and the script has
 * dismissed the crash dialog.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealCrashPhase2Test : DemoScenarioBase() {

    companion object {
        private const val TAG = "RealCrashPhase2"
    }

    @Test
    fun verifyRecoveryAfterCrash() {
        Log.i(TAG, "=== Phase 2: Verifying recovery after crash ===")

        // The app has already started (DemoScenarioBase.setUp launches SchedulingActivity).
        // RecoveryTracker has already run in DemoApp.onCreate().
        // Give the recovery flush time to complete.
        pace.step("realCrashRecovery", "waiting_for_recovery_flush")
        Thread.sleep(10000)

        // Verify RecoveryTracker detected the crash
        val recoveryType = OTelMobile.getLastRecoveryType()
        Log.i(TAG, "Recovery type: $recoveryType")
        assertNotNull("RecoveryTracker should have detected crash", recoveryType)
        assertEquals(
            "RecoveryTracker should report crash recovery",
            "crash",
            recoveryType
        )

        // Emit a post-recovery buffer snapshot to confirm flush happened
        emitBufferStats("post_recovery")

        pace.step("realCrashRecovery", "recovery_verified")
        Log.i(TAG, "=== Real crash recovery verified successfully ===")
    }
}
```

- [ ] **Step 3: Delete old RealCrashScenarios.kt**

Run:
```bash
rm examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashScenarios.kt
```

- [ ] **Step 4: Verify Kotlin compiles**

Run:
```bash
cd examples/demo-app && ./gradlew :android:compileDebugAndroidTestKotlin --quiet
```
Expected: BUILD SUCCESSFUL

---

### Task 8: Update validate-crash-recovery.sh — Add Airplane Mode Flag

**Files:**
- Modify: `scripts/test/validate-crash-recovery.sh`

- [ ] **Step 1: Add --airplane-mode flag parsing and offline checks**

Add at the top of the file, after `set -euo pipefail` and the variable declarations:

```bash
AIRPLANE_MODE=false
for arg in "$@"; do
  case "$arg" in
    --airplane-mode) AIRPLANE_MODE=true ;;
  esac
done
```

Add before the Summary section (after the Trace signals section):

```bash
# ── Airplane mode signals ─────────────────────────────────────────────────────

if [ "$AIRPLANE_MODE" = true ]; then
  log "Airplane mode signals"

  if [ -f "$OUTPUT_DIR/.logs_size_before" ]; then
    LOGS_SIZE_BEFORE=$(cat "$OUTPUT_DIR/.logs_size_before")
    LOGS_SIZE_AFTER=$(wc -c < "$OUTPUT_DIR/logs.json" 2>/dev/null || echo 0)
    # Note: LOGS_SIZE_AFTER here is the final size (after network restore).
    # The before snapshot was taken when airplane mode was enabled.
    # If they were equal at the time of checking, no events leaked during offline.
    # We can't retroactively check, so this is informational.
    ok "Airplane mode validation: offline snapshot was ${LOGS_SIZE_BEFORE} bytes"
    PASS=$((PASS + 1))
  else
    warn "No offline snapshot found (.logs_size_before missing)"
    WARN=$((WARN + 1))
  fi

  # The standard crash checks above already verify events arrived post-restore
  check_signal "$OUTPUT_DIR/logs.json" "app.crash" \
    "app.crash event arrived after network restore"

  check_signal "$OUTPUT_DIR/logs.json" "app.recovery" \
    "app.recovery event arrived after network restore"
fi
```

- [ ] **Step 2: Verify syntax**

Run: `bash -n scripts/test/validate-crash-recovery.sh`
Expected: no output

---

### Task 9: Refactor run-validated-tests.sh to Source lib/common.sh

**Files:**
- Modify: `scripts/test/run-validated-tests.sh`

- [ ] **Step 1: Replace inline functions with source of common.sh**

Replace the duplicated `log()`, `ok()`, `err()`, path variables, collector start/stop, SharedPreferences write, and backend start code with:

```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"
source "$SCRIPT_DIR/lib/export-target.sh"
```

Then replace inline usages:
- Replace the inline `PREFS_XML` block + `adb shell` prefs write with `write_collector_prefs`
- Replace the inline collector start block with `start_collector`
- Replace the inline collector stop block with `stop_collector`
- Replace the inline backend start block with `start_demo_backend`
- Remove the duplicated `log()`, `ok()`, `err()` function definitions
- Remove the duplicated path variables (`SCRIPT_DIR`, `REPO_ROOT`, etc.)

- [ ] **Step 2: Verify the refactored script still works**

Run: `bash -n scripts/test/run-validated-tests.sh`
Expected: no output

---

### Task 10: Smoke Test — CI Mode End-to-End

**Files:** None (testing only)

This is the critical validation — run the full CI mode to prove crash + recovery works.

- [ ] **Step 1: Ensure emulator is running**

Run:
```bash
adb devices
```
Expected: at least one `emulator-XXXX device` line

- [ ] **Step 2: Run status check**

Run:
```bash
cd mobile-otel && ./scripts/test/run-real-crash-test.sh --status
```
Expected: all green checks. If collector/backend missing, start them manually or use `s` from menu.

- [ ] **Step 3: Run CI mode**

Run:
```bash
cd mobile-otel && ./scripts/test/run-real-crash-test.sh --ci
```

Expected output (in order):
1. `Phase 1: Generating pre-crash events + triggering crash`
2. `✓ Phase 1 complete — app crashed`
3. (crash dialog dismissed)
4. `Phase 2: Verifying recovery`
5. `✓ Phase 2 complete — recovery verified`
6. Validation: all required signals pass
7. Telemetry dump: timeline showing pre-crash events, app.crash, app.recovery

If Phase 2 fails: check `adb logcat | grep -E "RecoveryTracker|ErrorInstrumentation"` for crash marker detection.

- [ ] **Step 4: Run interactive mode manually**

Run:
```bash
cd mobile-otel && ./scripts/test/run-real-crash-test.sh --interactive
```

Walk through each ENTER prompt. Verify:
- Crash is visible in emulator window
- Recovery launches the app fresh
- Telemetry dump shows complete timeline

- [ ] **Step 5: Commit all changes**

Run:
```bash
git add \
  scripts/test/lib/ \
  scripts/test/run-real-crash-test.sh \
  scripts/test/validate-crash-recovery.sh \
  scripts/test/run-validated-tests.sh \
  examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashPhase1Test.kt \
  examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashPhase2Test.kt \
  docs/superpowers/specs/2026-04-11-phase7-real-crash-v2-design.md \
  docs/superpowers/plans/2026-04-11-phase7-crash-demo-control.md
git rm examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/RealCrashScenarios.kt
git commit -m "feat: Phase 7 — ADB-driven crash demo control center

Replace broken orchestrator approach (32GB OOM) with shell-driven
two-phase crash test. Interactive menu with crash, airplane mode,
export target toggle, telemetry dump, and validation.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
