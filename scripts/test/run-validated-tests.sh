#!/usr/bin/env bash
# End-to-end validated test: starts local collector, runs scenarios,
# then validates the received telemetry.
#
# Usage:
#   ./run-validated-tests.sh              # requires running emulator
#   ./run-validated-tests.sh --start-emu  # start emulator first
#   ./run-validated-tests.sh --skip-scenarios  # just validate (collector already has data)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"
source "$SCRIPT_DIR/lib/export-target.sh"

START_EMU=false; SKIP_SCENARIOS=false
for arg in "$@"; do
  case "$arg" in
    --start-emu) START_EMU=true ;;
    --skip-scenarios) SKIP_SCENARIOS=true ;;
  esac
done

# ── 1. Start emulator if requested ──────────────────────────────────────────

if [ "$START_EMU" = true ]; then
  log "Starting emulator"
  nohup emulator -avd Medium_Phone_API_36.1 -no-snapshot-save > /tmp/emu.log 2>&1 &
  adb wait-for-device
  until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
  ok "Emulator booted"
fi

if [ "$SKIP_SCENARIOS" = false ]; then
  if ! adb devices 2>/dev/null | grep -q "emulator"; then
    err "No emulator found. Start one or use --start-emu"
    exit 1
  fi
fi

# ── 2. Start local collector ────────────────────────────────────────────────

start_collector

# ── 3. Build and install + run scenarios ──────────────────────────────────────

if [ "$SKIP_SCENARIOS" = false ]; then
  find_emulator || exit 1

  start_demo_backend

  log "Building and installing demo app (normal build, no config swap)"
  cd "$DEMO_APP"
  ./gradlew installDebug --quiet
  ok "Installed"

  # ── 4. Write SharedPreferences override → local collector ────────────────

  log "Writing SharedPreferences override → localhost:14317"
  for serial in $(adb devices | grep "emulator" | awk '{print $1}'); do
    SERIAL="$serial" write_collector_prefs
    adb -s "$serial" shell am force-stop "$PACKAGE"
    ok "Configured $serial → localhost:14317"
  done

  # ── 5. Run scenario tests ───────────────────────────────────────────────

  log "Running scenario tests → local collector"
  ./gradlew :android:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.scenarios.UserJourneyScenarios
  ok "Scenarios complete"

  # Wait for collector to flush
  log "Waiting for collector to flush (5s)"
  sleep 5

  # ── 6. Restore device config ────────────────────────────────────────────

  log "Restoring device config (removing SharedPreferences override)"
  for serial in $(adb devices | grep "emulator" | awk '{print $1}'); do
    adb -s "$serial" shell "run-as $PACKAGE rm -f shared_prefs/otel_config.xml" 2>/dev/null || true
    ok "Restored $serial"
  done
fi

# ── 7. Validate received telemetry ──────────────────────────────────────────

"$SCRIPT_DIR/validate-telemetry.sh"

# ── 8. Stop collector ───────────────────────────────────────────────────────

stop_collector

echo ""
ok "Validated test run complete"
