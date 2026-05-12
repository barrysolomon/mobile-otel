#!/usr/bin/env bash
# Dash0 Mobile Observability — Demo Control Center
#
# Central hub for demos, testing, and telemetry validation.
# Manages export targets, export modes, airplane mode, crash scenarios,
# and validates telemetry in both local collector and Dash0.
#
# Usage:
#   ./demo-control-center.sh                       # interactive menu
#
# Canonical 4 demos (also wired to single-letter hotkeys 1-4 in the menu):
#   ./demo-control-center.sh --ci                  # automated crash recovery
#   ./demo-control-center.sh --interactive         # step-by-step crash demo
#   ./demo-control-center.sh --airplane            # airplane mode crash demo
#   ./demo-control-center.sh --full-demo           # full demo (crash + airplane)
#
# Extended scenarios (Scenario Library submenu, hotkey 'S' in main menu):
#   ./demo-control-center.sh --network-restored      # NF-001..NF-011 toggle demo
#   ./demo-control-center.sh --network-restored-lite # clean transition probe
#   ./demo-control-center.sh --journey               # user-journey booking flow
#   ./demo-control-center.sh --selective-flush       # conditional flush showcase
#   ./demo-control-center.sh --uat-cell              # pick a UAT matrix cell
#   ./demo-control-center.sh --ios-smoke             # iOS native end-to-end
#   ./demo-control-center.sh --rn-android-smoke      # RN Android end-to-end
#   ./demo-control-center.sh --rn-ios-smoke          # RN iOS end-to-end
#   ./demo-control-center.sh --scenarios             # open the submenu directly
#
# Plumbing:
#   ./demo-control-center.sh --status              # pre-flight check
#   ./demo-control-center.sh --dump                # dump last telemetry
#   ./demo-control-center.sh --validate-dash0      # validate telemetry in Dash0
#   ./demo-control-center.sh --start-emu --ci      # start emulator + CI mode
#   ./demo-control-center.sh --dash0               # switch to Dash0 endpoint
#   ./demo-control-center.sh --local               # switch to local collector
#   ./demo-control-center.sh --endpoint            # select endpoint interactively
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Source all library modules
source "$SCRIPT_DIR/lib/common.sh"
source "$SCRIPT_DIR/lib/export-target.sh"
source "$SCRIPT_DIR/lib/dump-telemetry.sh"
source "$SCRIPT_DIR/lib/crash-test-phases.sh"
source "$SCRIPT_DIR/lib/crash-test-menu.sh"
source "$SCRIPT_DIR/lib/scenarios.sh"
# REPO_ROOT exposed so scenarios.sh can reach validate-ios-end-to-end.sh
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
export REPO_ROOT

# ── Parse arguments ───────────────────────────────────────────────────────────

START_EMU=false
MODE=""
# DCC_SERIAL allows TUI fan-out to target a specific device per spawned child.
# If unset (e.g. interactive bash run) the script falls back to find_emulator.
for arg in "$@"; do
  case "$arg" in
    --serial=*)     export SERIAL="${arg#*=}" ;;
    --start-emu)    START_EMU=true ;;
    --ci)           MODE="ci" ;;
    --interactive)  MODE="interactive" ;;
    --airplane)     MODE="airplane" ;;
    --full-demo)    MODE="full-demo" ;;
    --status)       MODE="status" ;;
    --dump)         MODE="dump" ;;
    --validate-dash0) MODE="validate-dash0" ;;
    --dash0)        MODE="set-dash0" ;;
    --local)        MODE="set-local" ;;
    --endpoint)     MODE="set-endpoint" ;;
    # Extended scenarios (scenarios.sh)
    --network-restored)      MODE="network-restored" ;;
    --network-restored-lite) MODE="network-restored-lite" ;;
    --journey)               MODE="journey" ;;
    --selective-flush)       MODE="selective-flush" ;;
    --uat-cell)              MODE="uat-cell" ;;
    --ios-smoke)             MODE="ios-smoke" ;;
    --rn-android-smoke)      MODE="rn-android-smoke" ;;
    --rn-ios-smoke)          MODE="rn-ios-smoke" ;;
    --scenarios)             MODE="scenarios" ;;
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

# ── Find emulator (offer to start one if none running) ───────────────────────

if ! find_emulator 2>/dev/null; then
  echo ""
  warn "No emulator running"
  echo -n "  Start one? [Y/n] "
  read -r yn
  if [ "$yn" != "n" ] && [ "$yn" != "N" ]; then
    log "Starting emulator (Pixel_7)"
    nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu.log 2>&1 &
    echo -n "  Waiting for boot"
    adb wait-for-device
    until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do
      echo -n "."
      sleep 5
    done
    echo ""
    ok "Emulator booted"
    find_emulator || { err "Still no emulator found"; exit 1; }
  else
    exit 0
  fi
fi

# ── Teardown handler ──────────────────────────────────────────────────────────

teardown() {
  _stop_spinner 2>/dev/null || true
  stop_memory_watchdog
  # Ensure airplane mode is off
  adb -s "$SERIAL" shell cmd connectivity airplane-mode disable 2>/dev/null || true
}
trap teardown EXIT

# ── Dispatch ──────────────────────────────────────────────────────────────────

case "$MODE" in
  ci)                     run_ci_mode ;;
  interactive)            run_interactive_crash ;;
  airplane)               run_airplane_mode_crash ;;
  full-demo)              run_full_demo ;;
  status)                 status_check ;;
  dump)                   dump_telemetry ;;
  validate-dash0)         "$SCRIPT_DIR/validate-dash0.sh" "$@" ;;
  set-dash0)              write_dash0_prefs && ok "Switched to Dash0" && adb -s "$SERIAL" shell am force-stop "$PACKAGE" ;;
  set-local)              write_collector_prefs && ok "Switched to Local Collector" && adb -s "$SERIAL" shell am force-stop "$PACKAGE" ;;
  set-endpoint)           select_export_target ;;
  network-restored)       run_network_restored_toggle ;;
  network-restored-lite)  run_network_restored_lite ;;
  journey)                run_user_journey_demo ;;
  selective-flush)        run_selective_flush_showcase ;;
  uat-cell)               run_uat_cell ;;
  ios-smoke)              run_ios_native_smoke ;;
  rn-android-smoke)       run_rn_android_smoke ;;
  rn-ios-smoke)           run_rn_ios_smoke ;;
  scenarios)              show_scenario_library ;;
  *)                      show_menu ;;
esac
