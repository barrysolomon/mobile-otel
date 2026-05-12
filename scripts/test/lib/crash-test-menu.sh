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

# ── Smart Validate ────────────────────────────────────────────────────────────
# Auto-detects export target and validates against the right backend.

smart_validate() {
  local target
  target=$(get_export_target)
  case "$target" in
    local)
      validate "$@"
      ;;
    dash0)
      log "Export target is Dash0 — validating via Dash0 API"
      "$SCRIPT_DIR/validate-dash0.sh" --window 5
      ;;
    *)
      warn "Export target is '$target' — skipping validation (no validator for this target)"
      ;;
  esac
}

# ── Export Mode ───────────────────────────────────────────────────────────────

get_current_export_mode() {
  adb -s "$SERIAL" shell "run-as $PACKAGE cat shared_prefs/otel_config.xml" 2>/dev/null \
    | grep export_mode | sed 's/.*>\(.*\)<.*/\1/' || echo "unknown"
}

set_export_mode() {
  local mode="$1"
  # Read current prefs, update export_mode, write back
  local current_prefs
  current_prefs=$(adb -s "$SERIAL" shell "run-as $PACKAGE cat shared_prefs/otel_config.xml" 2>/dev/null)
  if [ -z "$current_prefs" ]; then
    err "No SharedPreferences found — configure export endpoint first"
    return 1
  fi
  local updated
  updated=$(echo "$current_prefs" | sed "s|<string name=\"export_mode\">[^<]*</string>|<string name=\"export_mode\">$mode</string>|")
  echo "$updated" | adb -s "$SERIAL" shell "run-as $PACKAGE sh -c 'cat > shared_prefs/otel_config.xml'"
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  sleep 1
  adb -s "$SERIAL" shell am start -n "$PACKAGE/.SchedulingActivity" > /dev/null 2>&1
  ok "Export mode set to $mode — app restarted"
}

select_export_mode() {
  local current
  current=$(get_current_export_mode)
  echo ""
  log "Select export mode"
  echo ""
  echo -e "  Current: ${_WH}${current}${_R}"
  echo ""
  echo -e "  ${_WH}1${_R})  CONTINUOUS  ${_D}— periodic export every 10s${_R}"
  echo -e "  ${_WH}2${_R})  CONDITIONAL ${_D}— export only on error/crash/freeze trigger${_R}"
  echo -e "  ${_WH}3${_R})  HYBRID      ${_D}— heartbeats stream + conditional flush on triggers${_R}"
  echo -e "  ${_WH}q${_R})  Cancel"
  echo ""
  echo -ne "  > "
  read -r choice
  case "$choice" in
    1) set_export_mode "CONTINUOUS" ;;
    2) set_export_mode "CONDITIONAL" ;;
    3) set_export_mode "HYBRID" ;;
    q|"") return 0 ;;
    *) err "Unknown option: $choice"; return 1 ;;
  esac
}

# ── Live Controls ─────────────────────────────────────────────────────────────

toggle_airplane_mode() {
  local current
  current=$(adb -s "$SERIAL" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')
  if [ "$current" = "1" ]; then
    log "Disabling airplane mode"
    adb -s "$SERIAL" shell cmd connectivity airplane-mode disable
    # Wait for network
    for i in $(seq 1 10); do
      if adb -s "$SERIAL" shell ping -c 1 -W 1 10.0.2.2 > /dev/null 2>&1; then
        ok "Airplane mode OFF — network restored"
        return
      fi
      sleep 1
    done
    ok "Airplane mode OFF"
  else
    log "Enabling airplane mode"
    adb -s "$SERIAL" shell cmd connectivity airplane-mode enable
    sleep 1
    ok "Airplane mode ON — device is offline"
  fi
}

force_crash_app() {
  log "Triggering real crash (RuntimeException)"
  # Use the test APK's RealCrashPhase1Test which generates pre-crash events then crashes
  if ! adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE.test"; then
    err "Test APK not installed — run 'b' to build and install first"
    return 1
  fi
  adb -s "$SERIAL" shell am instrument -w -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase1Test \
    "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" > /dev/null 2>&1 || true
  sleep 2
  dismiss_crash_dialog
  ok "App crashed — process is dead"
  echo -e "  ${_D}Use 'l' to relaunch (triggers recovery flush)${_R}"
}

launch_app() {
  adb -s "$SERIAL" shell am start -n "$PACKAGE/.SchedulingActivity" > /dev/null 2>&1
  ok "App launched"
}

kill_app() {
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  ok "App killed"
}

# ── Mode Composition ──────────────────────────────────────────────────────────

run_ci_mode() {
  INTERACTIVE=false
  local target
  target=$(get_export_target)
  if [ "$target" = "local" ]; then
    reset_collector_output
  fi
  run_phase1
  dismiss_crash_dialog
  sleep 3
  run_phase2
  sleep 10
  smart_validate
  dump_telemetry
}

run_interactive_crash() {
  INTERACTIVE=true
  local target
  target=$(get_export_target)
  if [ "$target" = "local" ]; then
    reset_collector_output
  fi
  prompt_action "Press ENTER to start Phase 1 (generate events + crash)" run_phase1
  dismiss_crash_dialog
  prompt_action "App crashed. Process is dead. Press ENTER to trigger recovery" run_phase2
  sleep 10
  prompt_continue "Recovery complete. All events exported."
  smart_validate
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
  smart_validate --airplane-mode
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

# Color constants
_R="\033[0m"     # reset
_B="\033[1m"     # bold
_D="\033[2m"     # dim
_GR="\033[1;32m" # green
_RD="\033[1;31m" # red
_YL="\033[1;33m" # yellow
_CY="\033[1;36m" # cyan
_WH="\033[1;37m" # bright white
_BG="\033[48;5;236m" # dark gray bg

_status() {
  # _status <label> <value_colored>
  local padded
  padded=$(printf "%-12s" "$1")
  echo -e "  ${_D}${padded}${_R} $2"
}

_section() {
  echo ""
  echo -e "  ${_CY}${_B}$1${_R}"
  echo -e "  ${_D}$(printf '%.0s─' {1..54})${_R}"
}

_item() {
  # _item <key> <description>
  echo -e "  ${_WH}$1${_R})  $2"
}

show_menu() {
  while true; do
    # ── Gather live status ──────────────────────────────────────────────────
    local collector_status backend_status app_status test_status airplane_status
    local export_colored output_size

    if docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" ps 2>/dev/null | grep -q "Up"; then
      collector_status="${_GR}running${_R}"
    else
      collector_status="${_RD}stopped${_R}"
    fi

    if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
      backend_status="${_GR}running${_R}"
    else
      backend_status="${_RD}stopped${_R}"
    fi

    if adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE$"; then
      app_status="${_GR}installed${_R}"
    else
      app_status="${_RD}missing${_R}"
    fi

    if adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE.test"; then
      test_status="${_GR}installed${_R}"
    else
      test_status="${_RD}missing${_R}"
    fi

    local airplane
    airplane=$(adb -s "$SERIAL" shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')
    if [ "$airplane" = "1" ]; then
      airplane_status="${_YL}ON${_R}"
    else
      airplane_status="${_GR}off${_R}"
    fi

    export_colored=$(get_export_target_colored)
    output_size=$(du -sh "$OUTPUT_DIR" 2>/dev/null | awk '{print $1}')

    # Check Dash0 connectivity (quick probe of Prometheus API)
    local dash0_status
    local config_file="$DEMO_APP/android/src/debug/assets/otel-config.json"
    if [ -f "$config_file" ]; then
      local d0_ingress d0_auth d0_dataset d0_api d0_code
      d0_ingress=$(jq -r '.collectorEndpoint // empty' "$config_file" | sed 's|:4317||')
      d0_auth=$(jq -r '.headers.Authorization // empty' "$config_file")
      d0_dataset=$(jq -r '.headers["Dash0-Dataset"] // "otel-mobile"' "$config_file")
      d0_api=$(echo "$d0_ingress" | sed 's|ingress\.|api.|')
      if [ -n "$d0_api" ] && [ -n "$d0_auth" ]; then
        d0_code=$(curl -sf -o /dev/null -w "%{http_code}" -G \
          -H "Authorization: $d0_auth" -H "Dash0-Dataset: $d0_dataset" \
          --data-urlencode "query=up" \
          "$d0_api/api/prometheus/api/v1/query" 2>/dev/null || echo "000")
        if [ "$d0_code" = "200" ]; then
          dash0_status="${_GR}connected${_R}"
        elif [ "$d0_code" = "401" ] || [ "$d0_code" = "403" ]; then
          dash0_status="${_RD}auth failed${_R}"
        else
          dash0_status="${_RD}unreachable${_R} ${_D}(HTTP $d0_code)${_R}"
        fi
      else
        dash0_status="${_YL}no credentials${_R}"
      fi
    else
      dash0_status="${_YL}no config${_R}"
    fi

    # ── Render ──────────────────────────────────────────────────────────────
    clear
    echo ""
    echo -e "  ${_B}${_CY}Dash0 Mobile Observability${_R}  ${_D}—  Demo Control Center${_R}"
    echo -e "  ${_D}$(printf '%.0s═' {1..54})${_R}"
    echo ""
    _status "Emulator"   "${_WH}${SERIAL}${_R}"
    _status "Collector"  "$collector_status"
    _status "Backend"    "$backend_status"
    _status "Dash0"      "$dash0_status"
    _status "App"        "$app_status  ${_D}│${_R}  Test APK: $test_status"
    _status "Airplane"   "$airplane_status"
    local export_mode
    export_mode=$(get_current_export_mode)
    local mode_colored
    case "$export_mode" in
      CONTINUOUS)  mode_colored="${_GR}CONTINUOUS${_R}" ;;
      CONDITIONAL) mode_colored="${_YL}CONDITIONAL${_R}" ;;
      HYBRID)      mode_colored="${_CY}HYBRID${_R}" ;;
      *)           mode_colored="${_D}${export_mode}${_R}" ;;
    esac

    _status "Export to"  "$export_colored"
    _status "Mode"       "$mode_colored"
    if [ -n "$output_size" ] && [ "$output_size" != "0B" ]; then
      _status "Output"    "${_D}${output_size} captured${_R}"
    fi

    _section "PREPARE"
    _item "s" "Full status check ${_D}(diagnose + auto-fix)${_R}"
    _item "b" "Build + install app & test APK"
    _item "e" "Select export endpoint ${_D}(Dash0 / local / custom)${_R}"
    _item "m" "Select export mode ${_D}(Continuous / Conditional / Hybrid)${_R}"
    _item "c" "Start local collector"
    _item "x" "Stop local collector"

    _section "LIVE CONTROLS"
    _item "a" "Toggle airplane mode ${_D}(currently: $(if [ "$airplane" = "1" ]; then echo -e "${_YL}ON${_D}"; else echo -e "${_GR}off${_D}"; fi))${_R}"
    _item "!" "Force crash the app ${_D}(real RuntimeException → process death)${_R}"
    _item "l" "Launch app"
    _item "k" "Kill app ${_D}(force-stop)${_R}"

    _section "RUN A DEMO"
    _item "1" "Automated crash + recovery ${_D}(CI mode, no prompts)${_R}"
    _item "2" "Interactive crash demo ${_D}(step-by-step with prompts)${_R}"
    _item "3" "Airplane mode demo ${_D}(offline crash → reconnect → flush)${_R}"
    _item "4" "Full narrated demo ${_D}(2 then 3, for meetings)${_R}"
    _item "5" "Network-restored toggle ${_D}(NF-001…NF-011 demo moment)${_R}"
    _item "S" "Scenario library… ${_D}(journeys, UAT cells, iOS/RN smokes)${_R}"

    _section "INSPECT RESULTS"
    _item "v" "Validate telemetry ${_D}(auto-detect: local or Dash0)${_R}"
    _item "V" "Validate Dash0 ${_D}(force Dash0 API check)${_R}"
    _item "d" "Dump raw telemetry ${_D}(JSON)${_R}"
    _item "r" "Clear collector output ${_D}(reset for next run)${_R}"

    echo ""
    echo -e "  ${_D}$(printf '%.0s─' {1..54})${_R}"
    _item "q" "Quit"
    echo ""
    echo -ne "  ${_CY}›${_R} "
    read -r choice

    case "$choice" in
      s) status_check ;;
      b) build_and_install ;;
      e) select_export_target ;;
      m) select_export_mode ;;
      c) start_collector ;;
      x) stop_collector ;;
      a) toggle_airplane_mode ;;
      !) force_crash_app ;;
      l) launch_app ;;
      k) kill_app ;;
      1) run_ci_mode ;;
      2) run_interactive_crash ;;
      3) run_airplane_mode_crash ;;
      4) run_full_demo ;;
      5) run_network_restored_toggle ;;
      S) show_scenario_library ;;
      v) smart_validate ;;
      V) "$SCRIPT_DIR/validate-dash0.sh" ;;
      d) dump_telemetry ;;
      r) reset_collector_output ;;
      q) teardown; exit 0 ;;
      *) echo -e "  ${_RD}Unknown option:${_R} $choice" ;;
    esac

    # Pause after action so user can read output before menu redraws
    echo ""
    echo -ne "  ${_D}Press ENTER to return to menu${_R}"
    read -r
  done
}
