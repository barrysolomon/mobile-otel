#!/usr/bin/env bash
# Dash0 Mobile Observability — Demo Control Center
#
# Central hub for demos, testing, and telemetry validation.
# Manages export targets, export modes, airplane mode, crash scenarios,
# and validates telemetry in both local collector and Dash0.
#
# Usage:
#   ./demo-control-center.sh                    # interactive menu
#   ./demo-control-center.sh --ci               # automated CI mode
#   ./demo-control-center.sh --interactive      # interactive crash demo
#   ./demo-control-center.sh --airplane         # airplane mode crash demo
#   ./demo-control-center.sh --full-demo        # full demo (crash + airplane)
#   ./demo-control-center.sh --status           # status check only
#   ./demo-control-center.sh --dump             # dump last telemetry
#   ./demo-control-center.sh --validate-dash0   # validate telemetry in Dash0
#   ./demo-control-center.sh --start-emu --ci   # start emulator + CI mode
#   ./demo-control-center.sh --dash0            # switch to Dash0 endpoint
#   ./demo-control-center.sh --local            # switch to local collector
#   ./demo-control-center.sh --endpoint         # select endpoint interactively
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
    --validate-dash0) MODE="validate-dash0" ;;
    --dash0)        MODE="set-dash0" ;;
    --local)        MODE="set-local" ;;
    --endpoint)     MODE="set-endpoint" ;;
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
  ci)            run_ci_mode ;;
  interactive)   run_interactive_crash ;;
  airplane)      run_airplane_mode_crash ;;
  full-demo)     run_full_demo ;;
  status)        status_check ;;
  dump)          dump_telemetry ;;
  validate-dash0) "$SCRIPT_DIR/validate-dash0.sh" "$@" ;;
  set-dash0)     write_dash0_prefs && ok "Switched to Dash0" && adb -s "$SERIAL" shell am force-stop "$PACKAGE" ;;
  set-local)     write_collector_prefs && ok "Switched to Local Collector" && adb -s "$SERIAL" shell am force-stop "$PACKAGE" ;;
  set-endpoint)  select_export_target ;;
  *)             show_menu ;;
esac
