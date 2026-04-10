#!/usr/bin/env bash
# End-to-end validated test: starts local collector, runs scenarios,
# then validates the received telemetry.
#
# Usage:
#   ./run-validated-tests.sh              # requires running emulator
#   ./run-validated-tests.sh --start-emu  # start emulator first
#   ./run-validated-tests.sh --skip-scenarios  # just validate (collector already has data)
#
# What it does:
#   1. Starts emulator (if --start-emu)
#   2. Starts a local OTel Collector (Docker) with file exporters
#   3. Builds and installs the demo app (normal build)
#   4. Writes SharedPreferences override → local collector (no rebuild needed)
#   5. Runs scenario tests (on emulator)
#   6. Restores device config (removes SharedPreferences override)
#   7. Validates that expected telemetry was received
#   8. Stops the collector
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEMO_APP="$REPO_ROOT/examples/demo-app"
COLLECTOR_DIR="$SCRIPT_DIR/collector"
OUTPUT_DIR="$COLLECTOR_DIR/output"

log()  { echo -e "\n\033[1;36m▸ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }

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

log "Starting local OTel Collector (Docker)"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
# Ensure output files exist so the collector can write to them
touch "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"

docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" up -d 2>&1 || \
  docker-compose -f "$COLLECTOR_DIR/docker-compose.yaml" up -d 2>&1

# Wait for collector to be ready
for i in $(seq 1 15); do
  if docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" ps 2>/dev/null | grep -q "Up"; then
    ok "Collector running on ports 14317 (gRPC) + 14318 (HTTP)"
    break
  fi
  if [ "$i" -eq 15 ]; then
    err "Collector failed to start. Check: docker compose -f $COLLECTOR_DIR/docker-compose.yaml logs"
    exit 1
  fi
  sleep 1
done

# ── 3. Build and install demo app ──────────────────────────────────────────

if [ "$SKIP_SCENARIOS" = false ]; then
  PACKAGE="io.opentelemetry.android.demo"

  log "Starting demo backend"
  if ! curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    cd "$REPO_ROOT/examples/demo-backend"
    npm run dev > /tmp/demo-backend.log 2>&1 &
    sleep 3
  fi
  ok "Backend running"

  log "Building and installing demo app (normal build, no config swap)"
  cd "$DEMO_APP"
  ./gradlew installDebug --quiet
  ok "Installed"

  # ── 4. Write SharedPreferences override → local collector ────────────────
  # ConfigManager reads SharedPreferences with highest priority.
  # Writing here AFTER install bypasses Gradle's APK cache entirely.

  log "Writing SharedPreferences override → localhost:14317"

  # auth_token must be non-blank so isDash0Configured() returns true and
  # scenario tests don't skip. The local collector ignores auth headers.
  PREFS_XML='<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
  <string name="collector_endpoint">http://10.0.2.2:14317</string>
  <string name="export_mode">CONTINUOUS</string>
  <string name="service_name">validated-test</string>
  <string name="service_version">1.0.0</string>
  <string name="auth_token">local-test</string>
  <boolean name="config_loaded_from_bundle" value="true" />
</map>'

  for serial in $(adb devices | grep "emulator" | awk '{print $1}'); do
    adb -s "$serial" shell "run-as $PACKAGE mkdir -p shared_prefs"
    echo "$PREFS_XML" | adb -s "$serial" shell "run-as $PACKAGE sh -c 'cat > shared_prefs/otel_config.xml'"
    # Force-stop so app picks up new config on relaunch
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

log "Stopping collector"
docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" down 2>/dev/null || \
  docker-compose -f "$COLLECTOR_DIR/docker-compose.yaml" down 2>/dev/null
ok "Collector stopped"

echo ""
ok "Validated test run complete"
