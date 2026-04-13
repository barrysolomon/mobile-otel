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
  # RetryableExporter exhausts retries while offline (3 attempts, gives up).
  # Force-restart the app so RecoveryTracker does a fresh forceFlush from disk.
  log "Restarting app to trigger fresh export from disk buffer"
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  sleep 2
  adb -s "$SERIAL" shell am start -n "$PACKAGE/.SchedulingActivity" > /dev/null 2>&1
  log "Waiting for recovery flush to export (20s)"
  sleep 20
  prompt_continue "Network restored. App restarted. Events should have landed."
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
    # ── Gather live status ──────────────────────────────────────────────────
    local target_label collector_status backend_status app_status test_status airplane_status
    target_label=$(get_export_target_label)

    if docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" ps 2>/dev/null | grep -q "Up"; then
      collector_status="\033[1;32mrunning\033[0m"
    else
      collector_status="\033[1;31mstopped\033[0m"
    fi

    if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
      backend_status="\033[1;32mrunning\033[0m"
    else
      backend_status="\033[1;31mstopped\033[0m"
    fi

    if adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE$"; then
      app_status="\033[1;32minstalled\033[0m"
    else
      app_status="\033[1;31mmissing\033[0m"
    fi

    if adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE.test"; then
      test_status="\033[1;32minstalled\033[0m"
    else
      test_status="\033[1;31mmissing\033[0m"
    fi

    local airplane
    airplane=$(adb -s "$SERIAL" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')
    if [ "$airplane" = "1" ]; then
      airplane_status="\033[1;33mON\033[0m"
    else
      airplane_status="\033[1;32moff\033[0m"
    fi

    # ── Render menu ─────────────────────────────────────────────────────────
    echo ""
    echo "┌─────────────────────────────────────────────────────────────┐"
    echo "│  Dash0 Mobile Observability — Demo Control Center           │"
    echo "├─────────────────────────────────────────────────────────────┤"
    echo -e "│  Emulator:  $SERIAL                            │"
    echo -e "│  Collector: $collector_status   Backend: $backend_status   Airplane: $airplane_status"
    echo -e "│  App: $app_status   Test APK: $test_status"
    echo -e "│  Export to: $target_label"
    echo "├─────────────────────────────────────────────────────────────┤"
    echo "│                                                             │"
    echo "│  STEP 1 — PREPARE                                          │"
    echo "│  s) Full status check (diagnose + auto-fix)                 │"
    echo "│  b) Build + install app & test APK                          │"
    echo "│  e) Select export endpoint (Dash0 / local / custom)         │"
    echo "│  c) Start local collector   x) Stop collector               │"
    echo "│                                                             │"
    echo "│  STEP 2 — RUN A DEMO                                       │"
    echo "│  1) Automated crash + recovery (CI mode, no prompts)        │"
    echo "│  2) Interactive crash demo (step-by-step with prompts)      │"
    echo "│  3) Airplane mode demo (offline crash → reconnect → flush)  │"
    echo "│  4) Full narrated demo (2 then 3, for meetings)             │"
    echo "│                                                             │"
    echo "│  STEP 3 — INSPECT RESULTS                                   │"
    echo "│  v) Validate telemetry from last run                        │"
    echo "│  d) Dump raw telemetry (JSON)                               │"
    echo "│  r) Clear collector output (reset for next run)             │"
    echo "│                                                             │"
    echo "│  q) Quit                                                    │"
    echo "└─────────────────────────────────────────────────────────────┘"
    echo -n "  > "
    read -r choice

    case "$choice" in
      s) status_check ;;
      b) build_and_install ;;
      e) select_export_target ;;
      c) start_collector ;;
      x) stop_collector ;;
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
