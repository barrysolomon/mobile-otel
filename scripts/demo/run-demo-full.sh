#!/usr/bin/env bash
# Full demo — 2 emulators, backend, Dash0 scenario tests.
# Sends real telemetry to Dash0. No unit tests — those belong in run-tests.sh.
#
# Usage:
#   ./run-demo-full.sh              # windowed (for live demos)
#   ./run-demo-full.sh --headless   # headless (for CI)
#   ./run-demo-full.sh --skip-emu   # emulators already running
set -euo pipefail
source "$(dirname "$0")/../lib/demo-common.sh"

HEADLESS=false; SKIP_EMU=false
for arg in "$@"; do
  case "$arg" in
    --headless) HEADLESS=true ;;
    --skip-emu) SKIP_EMU=true ;;
  esac
done

INCUBATING=true

# 1. Emulators
if [ "$SKIP_EMU" = false ]; then
  start_emulators "$HEADLESS"
else
  log "Skipping emulator boot (--skip-emu)"
  require_emulators
fi

# 2. Backend
start_backend

# 3. Build & install
build_and_install

# 4. Enable incubating modules
for emu in "${EMULATORS[@]}"; do enable_incubating "$emu"; done

# 5. Launch
launch_app

# 6. Demo scenarios (sends data to Dash0)
run_scenarios

print_dash0_summary
