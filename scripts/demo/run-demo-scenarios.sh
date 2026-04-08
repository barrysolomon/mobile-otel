#!/usr/bin/env bash
# Scenarios only — app must already be installed. Runs Espresso scenarios on connected emulators.
# Usage:
#   ./run-demo-scenarios.sh                          # all scenario suites
#   ./run-demo-scenarios.sh --suite UserJourney       # single suite
#   ./run-demo-scenarios.sh --suite Fault             # fault scenarios only
#   ./run-demo-scenarios.sh --incubating              # enable screenshot + wireframe first
#
# Available suites: UserJourney, EmulatorStress, Fault, ConditionalFlush
set -euo pipefail
source "$(dirname "$0")/../lib/demo-common.sh"

SUITE=""; INCUBATING=false
for arg in "$@"; do
  case "$arg" in
    --incubating) INCUBATING=true ;;
    --suite) ;; # value captured below
    *)
      # Capture value after --suite
      if [ "${PREV_ARG:-}" = "--suite" ]; then
        SUITE="$arg"
      fi
      ;;
  esac
  PREV_ARG="$arg"
done

require_emulators

# Optionally enable incubating
if [ "$INCUBATING" = true ]; then
  for emu in "${EMULATORS[@]}"; do enable_incubating "$emu"; done
  # Restart app to pick up new prefs
  launch_app
fi

if [ -n "$SUITE" ]; then
  # Map short names to full class paths
  case "$SUITE" in
    UserJourney|userjourney)       CLASS="io.opentelemetry.android.demo.scenarios.UserJourneyScenarios" ;;
    EmulatorStress|emulatorstress) CLASS="io.opentelemetry.android.demo.scenarios.EmulatorStressScenarios" ;;
    Fault|fault)                   CLASS="io.opentelemetry.android.demo.scenarios.FaultScenarios" ;;
    ConditionalFlush|conditionalflush) CLASS="io.opentelemetry.android.demo.scenarios.ConditionalFlushScenarios" ;;
    *) CLASS="$SUITE" ;;  # allow full class path
  esac

  log "Running scenario suite: $CLASS"
  cd "$DEMO_APP"
  ./gradlew :android:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$CLASS" \
    2>&1 | tail -10
else
  log "Running all scenario suites"
  cd "$DEMO_APP"
  ./gradlew :android:connectedDebugAndroidTest 2>&1 | tail -10
fi

ok "Scenarios complete"
print_dash0_summary
