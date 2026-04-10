#!/usr/bin/env bash
# Run Dash0 scenario tests — sends real telemetry to Dash0.
# Requires: running emulator, demo backend, otel-config.json credentials.
#
# Usage:
#   ./run-dash0-tests.sh                    # all 18 scenarios
#   ./run-dash0-tests.sh --journeys         # UserJourney suite only (5 tests)
#   ./run-dash0-tests.sh --faults           # Fault suite only (4 tests)
#   ./run-dash0-tests.sh --conditional      # ConditionalFlush suite only (2 tests)
#   ./run-dash0-tests.sh --stress           # EmulatorStress suite only (7 tests)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEMO_APP="$REPO_ROOT/examples/demo-app"
PKG="io.opentelemetry.android.demo.scenarios"

log()  { echo -e "\n\033[1;36m▸ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }

# Verify prerequisites
if ! adb devices 2>/dev/null | grep -q "emulator"; then
  err "No emulator found. Start one first."
  exit 1
fi

if ! curl -sf http://localhost:3001/health > /dev/null 2>&1; then
  log "Starting demo backend"
  cd "$REPO_ROOT/examples/demo-backend"
  npm run dev > /tmp/demo-backend.log 2>&1 &
  sleep 3
  if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    ok "Backend started"
  else
    err "Backend failed to start. Run: cd examples/demo-backend && npm run dev"
    exit 1
  fi
fi

# Build & install if needed
log "Building and installing demo app"
cd "$DEMO_APP"
./gradlew installDebug --quiet
ok "Installed"

# Determine which suite to run
SUITE=""
for arg in "$@"; do
  case "$arg" in
    --journeys)    SUITE="$PKG.UserJourneyScenarios" ;;
    --faults)      SUITE="$PKG.FaultScenarios" ;;
    --conditional) SUITE="$PKG.ConditionalFlushScenarios" ;;
    --stress)      SUITE="$PKG.EmulatorStressScenarios" ;;
  esac
done

if [ -n "$SUITE" ]; then
  log "Running: $SUITE"
  ./gradlew :android:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$SUITE"
else
  log "Running all 18 Dash0 scenarios (~8 min)"
  ./gradlew :android:connectedDebugAndroidTest
fi

ok "Dash0 scenarios complete"
echo ""
echo "Check Dash0 (dataset: otel-mobile) for:"
echo "  • ui.tap, ui.screen_view, ui.scroll events"
echo "  • journey → page → ui.tap span hierarchy"
echo "  • Stress signals: device.health, thermal.status"
echo "  • Conditional flush: burst of events after crash trigger"
