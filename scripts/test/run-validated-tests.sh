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
#   1. Starts a local OTel Collector (Docker) with file exporters
#   2. Configures the demo app to export to the local collector
#   3. Runs Dash0 scenario tests (on emulator)
#   4. Validates that expected telemetry was received
#   5. Stops the collector
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

docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" up -d 2>/dev/null || \
  docker-compose -f "$COLLECTOR_DIR/docker-compose.yaml" up -d 2>/dev/null
sleep 3

# Verify collector is accepting connections
if curl -sf http://localhost:4318/v1/traces > /dev/null 2>&1 || \
   curl -sf -o /dev/null -w "%{http_code}" http://localhost:4318/ 2>/dev/null | grep -q "405\|200"; then
  ok "Collector running on ports 4317 (gRPC) + 4318 (HTTP)"
else
  # Collector may not respond to plain HTTP — check docker is running
  if docker ps 2>/dev/null | grep -q "otel.*collector"; then
    ok "Collector container running"
  else
    err "Collector failed to start. Check: docker compose -f $COLLECTOR_DIR/docker-compose.yaml logs"
    exit 1
  fi
fi

# ── 3. Configure demo app for local collector ───────────────────────────────

if [ "$SKIP_SCENARIOS" = false ]; then
  log "Configuring demo app for local collector"

  # Create a temporary otel-config.json pointing to local collector
  local_config="$DEMO_APP/android/src/debug/assets/otel-config.local.json"
  cat > "$local_config" <<'JSON'
{
  "endpoint": "http://10.0.2.2:4317",
  "headers": {}
}
JSON

  # Back up existing config and swap in local
  original_config="$DEMO_APP/android/src/debug/assets/otel-config.json"
  backup_config="$DEMO_APP/android/src/debug/assets/otel-config.json.bak"
  if [ -f "$original_config" ]; then
    cp "$original_config" "$backup_config"
  fi
  cp "$local_config" "$original_config"
  ok "Demo app configured to export to localhost"

  # ── 4. Start backend + build + run scenarios ──────────────────────────────

  log "Starting demo backend"
  if ! curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    cd "$REPO_ROOT/examples/demo-backend"
    npm run dev > /tmp/demo-backend.log 2>&1 &
    sleep 3
  fi
  ok "Backend running"

  log "Building and installing demo app"
  cd "$DEMO_APP"
  ./gradlew installDebug --quiet
  ok "Installed"

  log "Running scenario tests → local collector"
  ./gradlew :android:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.scenarios.UserJourneyScenarios
  ok "Scenarios complete"

  # Wait for collector to flush
  log "Waiting for collector to flush (5s)"
  sleep 5

  # Restore original config
  if [ -f "$backup_config" ]; then
    mv "$backup_config" "$original_config"
    ok "Restored original otel-config.json"
  fi
  rm -f "$local_config"
fi

# ── 5. Validate received telemetry ──────────────────────────────────────────

"$SCRIPT_DIR/validate-telemetry.sh"

# ── 6. Stop collector ───────────────────────────────────────────────────────

log "Stopping collector"
docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" down 2>/dev/null || \
  docker-compose -f "$COLLECTOR_DIR/docker-compose.yaml" down 2>/dev/null
ok "Collector stopped"

echo ""
ok "Validated test run complete"
