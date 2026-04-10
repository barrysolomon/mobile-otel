#!/usr/bin/env bash
# Run SDK integration tests on emulator (no Dash0 needed).
# Tests buffer flow, exporter customizers, DSL initialization.
#
# Usage:
#   ./run-integration-tests.sh              # requires running emulator
#   ./run-integration-tests.sh --start-emu  # start emulator first
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEMO_APP="$REPO_ROOT/examples/demo-app"

log()  { echo -e "\n\033[1;36m▸ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }

if [ "${1:-}" = "--start-emu" ]; then
  log "Starting emulator"
  nohup emulator -avd Medium_Phone_API_36.1 -no-snapshot-save > /tmp/emu.log 2>&1 &
  adb wait-for-device
  until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
  ok "Emulator booted"
fi

# Verify emulator is running
if ! adb devices 2>/dev/null | grep -q "emulator"; then
  err "No emulator found. Start one or use --start-emu"
  exit 1
fi

log "Running SDK integration tests on emulator"
cd "$DEMO_APP"
./gradlew :otel-android-mobile:connectedDebugAndroidTest
ok "Integration tests passed"

echo ""
echo "Tests run:"
echo "  • BufferIntegrationTest (9) — RAM → disk → flush → export"
echo "  • ExporterCustomizerIntegrationTest (2) — customizer chain"
echo "  • DslIntegrationTest (5) — DSL → OpenTelemetryMobile"
