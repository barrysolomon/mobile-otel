#!/usr/bin/env bash
# Quick demo — 1 emulator, backend, build + launch. No tests.
# Fastest path to a running app with telemetry flowing.
# Usage:
#   ./run-demo-quick.sh              # windowed
#   ./run-demo-quick.sh --headless   # headless
#   ./run-demo-quick.sh --skip-emu   # emulator already running
#   ./run-demo-quick.sh --incubating # enable screenshot + wireframe
set -euo pipefail
source "$(dirname "$0")/scripts/demo-common.sh"

HEADLESS=false; SKIP_EMU=false; INCUBATING=false
for arg in "$@"; do
  case "$arg" in
    --headless)   HEADLESS=true ;;
    --skip-emu)   SKIP_EMU=true ;;
    --incubating) INCUBATING=true ;;
  esac
done

# 1. Single emulator
if [ "$SKIP_EMU" = false ]; then
  start_single_emulator "$HEADLESS"
else
  log "Skipping emulator boot (--skip-emu)"
  require_emulators
fi

# 2. Backend
start_backend

# 3. Build & install
build_and_install

# 4. Optionally enable incubating
if [ "$INCUBATING" = true ]; then
  for emu in "${EMULATORS[@]}"; do enable_incubating "$emu"; done
fi

# 5. Launch
launch_app

echo ""
log "App running! Interact with it manually or run scenarios with:"
echo "  cd examples/demo-app && ./gradlew :android:connectedDebugAndroidTest"

print_dash0_summary
