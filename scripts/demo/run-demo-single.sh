#!/usr/bin/env bash
# Single-scenario runner — run one specific test by short name.
# Usage:
#   ./run-demo-single.sh happyPath
#   ./run-demo-single.sh crashFlush
#   ./run-demo-single.sh batteryDrain
#   ./run-demo-single.sh jank
#   ./run-demo-single.sh --list              # show all available scenarios
#   ./run-demo-single.sh --incubating jank   # enable screenshot + wireframe first
set -euo pipefail
source "$(dirname "$0")/../lib/demo-common.sh"

# ── Scenario map ─────────────────────────────────────────────────────────────

# Scenario map (bash 3.2 compatible — no declare -A)
get_scenario() {
  case "$1" in
    # UserJourneyScenarios
    happyPath)          echo "UserJourneyScenarios#happyPathBooking" ;;
    errorRecovery)      echo "UserJourneyScenarios#errorRecoveryFlow" ;;
    multiScreen)        echo "UserJourneyScenarios#multiScreenNavigation" ;;
    breadcrumbs)        echo "UserJourneyScenarios#navigationBreadcrumbs" ;;
    # EmulatorStressScenarios
    batteryDrain)       echo "EmulatorStressScenarios#batteryDrain" ;;
    thermal)            echo "EmulatorStressScenarios#thermalThrottle" ;;
    memoryPressure)     echo "EmulatorStressScenarios#memoryPressure" ;;
    networkDegradation) echo "EmulatorStressScenarios#networkDegradation" ;;
    # FaultScenarios
    jank)               echo "FaultScenarios#jankDetection" ;;
    anr)                echo "FaultScenarios#anrTrigger" ;;
    memoryFault)        echo "FaultScenarios#memoryPressureFault" ;;
    # ConditionalFlushScenarios
    crashFlush)         echo "ConditionalFlushScenarios#quietBufferThenCrashFlush" ;;
    conditionalFlush)   echo "ConditionalFlushScenarios#conditionalFlushDemo" ;;
    *) return 1 ;;
  esac
}

SCENARIO_KEYS="anr batteryDrain breadcrumbs conditionalFlush crashFlush errorRecovery happyPath jank memoryFault memoryPressure multiScreen networkDegradation thermal"

BASE_PKG="io.opentelemetry.android.demo.scenarios"

# ── Parse args ───────────────────────────────────────────────────────────────

INCUBATING=false; SCENARIO_KEY=""
for arg in "$@"; do
  case "$arg" in
    --incubating) INCUBATING=true ;;
    --list)
      echo "Available scenarios:"
      for key in $SCENARIO_KEYS; do
        printf "  %-22s %s\n" "$key" "$(get_scenario "$key")"
      done
      exit 0
      ;;
    -*) warn "Unknown flag: $arg" ;;
    *)  SCENARIO_KEY="$arg" ;;
  esac
done

if [ -z "$SCENARIO_KEY" ]; then
  err "Usage: $0 [--incubating] <scenario-name>"
  echo "Run '$0 --list' to see available scenarios."
  exit 1
fi

# Resolve scenario
SCENARIO_VALUE="$(get_scenario "$SCENARIO_KEY" 2>/dev/null || true)"
if [ -n "$SCENARIO_VALUE" ]; then
  CLASS="$BASE_PKG.${SCENARIO_VALUE}"
else
  # Allow full class#method path
  CLASS="$SCENARIO_KEY"
fi

require_emulators

if [ "$INCUBATING" = true ]; then
  for emu in "${EMULATORS[@]}"; do enable_incubating "$emu"; done
  launch_app
fi

log "Running: $CLASS"
cd "$DEMO_APP"
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$CLASS" \
  2>&1 | tail -15

ok "Scenario complete"
