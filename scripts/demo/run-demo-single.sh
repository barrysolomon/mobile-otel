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

declare -A SCENARIOS=(
  # UserJourneyScenarios
  [happyPath]="UserJourneyScenarios#happyPathBooking"
  [errorRecovery]="UserJourneyScenarios#errorRecoveryFlow"
  [multiScreen]="UserJourneyScenarios#multiScreenNavigation"
  [breadcrumbs]="UserJourneyScenarios#navigationBreadcrumbs"

  # EmulatorStressScenarios
  [batteryDrain]="EmulatorStressScenarios#batteryDrain"
  [thermal]="EmulatorStressScenarios#thermalThrottle"
  [memoryPressure]="EmulatorStressScenarios#memoryPressure"
  [networkDegradation]="EmulatorStressScenarios#networkDegradation"

  # FaultScenarios
  [jank]="FaultScenarios#jankDetection"
  [anr]="FaultScenarios#anrTrigger"
  [memoryFault]="FaultScenarios#memoryPressureFault"

  # ConditionalFlushScenarios
  [crashFlush]="ConditionalFlushScenarios#quietBufferThenCrashFlush"
  [conditionalFlush]="ConditionalFlushScenarios#conditionalFlushDemo"
)

BASE_PKG="io.opentelemetry.android.demo.scenarios"

# ── Parse args ───────────────────────────────────────────────────────────────

INCUBATING=false; SCENARIO_KEY=""
for arg in "$@"; do
  case "$arg" in
    --incubating) INCUBATING=true ;;
    --list)
      echo "Available scenarios:"
      for key in $(echo "${!SCENARIOS[@]}" | tr ' ' '\n' | sort); do
        printf "  %-22s %s\n" "$key" "${SCENARIOS[$key]}"
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
if [ -n "${SCENARIOS[$SCENARIO_KEY]+x}" ]; then
  CLASS="$BASE_PKG.${SCENARIOS[$SCENARIO_KEY]}"
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
