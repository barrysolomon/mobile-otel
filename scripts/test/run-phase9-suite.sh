#!/usr/bin/env bash
# Phase 9: Run all validation scenarios against local OTel Collector.
#
# Usage:
#   ./run-phase9-suite.sh                    # run all batches
#   ./run-phase9-suite.sh --batch journeys   # run only journey batch
#   ./run-phase9-suite.sh --batch stress     # run only stress batch
#   ./run-phase9-suite.sh --batch policy     # run only policy batch
#   ./run-phase9-suite.sh --batch ordering   # run only ordering batch
#   ./run-phase9-suite.sh --validate-only    # skip scenarios, just validate
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"
source "$SCRIPT_DIR/lib/export-target.sh"

BATCH=""
VALIDATE_ONLY=false
while [ $# -gt 0 ]; do
  case "$1" in
    --batch) shift; BATCH="${1:-}" ;;
    --validate-only) VALIDATE_ONLY=true ;;
    journeys|stress|policy|fault|network|ordering|all) BATCH="$1" ;;
  esac
  shift
done
BATCH=${BATCH:-all}

find_emulator || exit 1

SUITE_FAIL=0

run_batch() {
  local name=$1 scenario_class=$2
  shift 2
  local validators
  validators=("$@")

  log "═══ Batch: $name ═══"

  if [ "$VALIDATE_ONLY" = false ]; then
    reset_collector_output
    log "Running $scenario_class"
    adb -s "$SERIAL" shell am instrument -w \
      -e class "$scenario_class" \
      io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner \
      || true
    log "Waiting for collector flush (5s)"
    sleep 5
  fi

  local batch_pass=0 batch_fail=0
  for validator in "${validators[@]}"; do
    if "$SCRIPT_DIR/$validator" 2>&1; then
      batch_pass=$((batch_pass + 1))
    else
      batch_fail=$((batch_fail + 1))
    fi
  done
  SUITE_FAIL=$((SUITE_FAIL + batch_fail))
  ok "Batch $name: $batch_pass passed, $batch_fail failed"
  echo ""
}

# ── Setup ─────────────────────────────────────────────────────────────────────

if [ "$VALIDATE_ONLY" = false ]; then
  start_collector
  start_demo_backend
  write_collector_prefs
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
fi

# ── Batches ───────────────────────────────────────────────────────────────────

JOURNEY_CLASS="io.opentelemetry.android.demo.scenarios.UserJourneyScenarios"
STRESS_CLASS="io.opentelemetry.android.demo.scenarios.EmulatorStressScenarios"
FLUSH_CLASS="io.opentelemetry.android.demo.scenarios.ConditionalFlushScenarios"
FAULT_CLASS="io.opentelemetry.android.demo.scenarios.FaultScenarios"

if [ "$BATCH" = "journeys" ] || [ "$BATCH" = "all" ]; then
  run_batch "Journeys" "$JOURNEY_CLASS" \
    validate-us050-happy-path.sh \
    validate-us051-browse-refresh.sh \
    validate-us052-network-error.sh \
    validate-us053-get-directions.sh \
    validate-us054-multi-screen-nav.sh \
    validate-us055-form-input.sh \
    validate-us057-background-foreground.sh \
    validate-us070-timestamp-monotonic.sh \
    validate-us071-span-hierarchy.sh \
    validate-us072-cross-signal.sh \
    validate-us073-resource-attributes.sh
fi

if [ "$BATCH" = "stress" ] || [ "$BATCH" = "all" ]; then
  run_batch "Stress" "$STRESS_CLASS" \
    validate-us058-battery-drain.sh \
    validate-us059-thermal-throttle.sh \
    validate-us060-memory-pressure.sh \
    validate-us061-combined-stress.sh
fi

if [ "$BATCH" = "policy" ] || [ "$BATCH" = "all" ]; then
  run_batch "Policy" "$FLUSH_CLASS" \
    validate-us063-crash-flush.sh \
    validate-us064-http-error-flush.sh
fi

if [ "$BATCH" = "fault" ] || [ "$BATCH" = "all" ]; then
  run_batch "Faults" "$FAULT_CLASS" \
    validate-us065-freeze-flush.sh
fi

# US-066: no-false-flush needs its own isolated batch — run UserJourneyScenarios
# in CONDITIONAL mode (no policy triggers fire), verify zero user events exported
if [ "$BATCH" = "no-false-flush" ] || [ "$BATCH" = "all" ]; then
  log "═══ Batch: No-false-flush (CONDITIONAL mode, isolated) ═══"
  if [ "$VALIDATE_ONLY" = false ]; then
    reset_collector_output
    # TODO: Write CONDITIONAL mode SharedPreferences before running
    # For now, run journeys and validate no policy-triggered flush occurred
    adb -s "$SERIAL" shell am instrument -w \
      -e class "$JOURNEY_CLASS#happyPathBooking" \
      io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner \
      || true
    sleep 5
  fi
  batch_pass=0; batch_fail=0
  if "$SCRIPT_DIR/validate-us066-no-false-flush.sh" 2>&1; then
    batch_pass=$((batch_pass + 1))
  else
    batch_fail=$((batch_fail + 1))
  fi
  SUITE_FAIL=$((SUITE_FAIL + batch_fail))
  ok "Batch No-false-flush: $batch_pass passed, $batch_fail failed"
  echo ""
fi

OFFLINE_CLASS="io.opentelemetry.android.demo.scenarios.OfflineResilienceScenarios"
if [ "$BATCH" = "network" ] || [ "$BATCH" = "all" ]; then
  run_batch "Network" "$OFFLINE_CLASS" \
    validate-us062-network-loss.sh
fi

# Ordering validations reuse the journey batch collector output (no new scenario run)
if [ "$BATCH" = "ordering" ] || [ "$BATCH" = "all" ]; then
  log "═══ Batch: Ordering (reusing journey collector output) ═══"
  batch_pass=0; batch_fail=0
  for v in validate-us070-timestamp-monotonic.sh validate-us071-span-hierarchy.sh \
           validate-us072-cross-signal.sh validate-us073-resource-attributes.sh \
           validate-us056-session-lifecycle.sh; do
    if "$SCRIPT_DIR/$v" 2>&1; then
      batch_pass=$((batch_pass + 1))
    else
      batch_fail=$((batch_fail + 1))
    fi
  done
  SUITE_FAIL=$((SUITE_FAIL + batch_fail))
  ok "Batch Ordering: $batch_pass passed, $batch_fail failed"
  echo ""
fi

# ── Cleanup ───────────────────────────────────────────────────────────────────

if [ "$VALIDATE_ONLY" = false ]; then
  stop_collector
fi

if [ $SUITE_FAIL -gt 0 ]; then
  err "Phase 9 suite: $SUITE_FAIL batch(es) had failures"
  exit 1
else
  ok "Phase 9 suite complete — all batches passed"
fi
