#!/usr/bin/env bash
# Full demo — 2 emulators, backend, all tests, screenshots + wireframes.
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

# 3. Unit tests
run_unit_tests

# 4. Build & install
build_and_install

# 5. Enable incubating modules
for emu in "${EMULATORS[@]}"; do enable_incubating "$emu"; done

# 6. Launch
launch_app

# 7. Demo scenarios
run_scenarios

# 8. SDK instrumented tests
run_sdk_tests

print_dash0_summary
