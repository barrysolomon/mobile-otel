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
