#!/usr/bin/env bash
# Shared helpers for all demo scripts.
# Source this file — do not execute directly.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEMO_APP="$REPO_ROOT/examples/demo-app"
DEMO_BACKEND="$REPO_ROOT/examples/demo-backend"
PKG="io.opentelemetry.android.demo"

# ── Output helpers ───────────────────────────────────────────────────────────

log()  { echo -e "\n\033[1;36m▸ $*\033[0m"; }
warn() { echo -e "\033[1;33m  ⚠ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }

# ── Emulator helpers ─────────────────────────────────────────────────────────

wait_for_boot() {
  local serial=$1
  log "Waiting for $serial to boot…"
  adb -s "$serial" wait-for-device
  until adb -s "$serial" shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do
    sleep 5
  done
  ok "$serial booted"
}

# Collect all connected emulator serials into EMULATORS array.
collect_emulators() {
  EMULATORS=()
  while IFS= read -r line; do
    local serial
    serial=$(echo "$line" | awk '{print $1}')
    if [[ "$serial" == emulator-* ]]; then
      EMULATORS+=("$serial")
    fi
  done < <(adb devices | tail -n +2)
}

require_emulators() {
  collect_emulators
  if [ ${#EMULATORS[@]} -eq 0 ]; then
    err "No emulators found. Start one with: emulator -avd Pixel_7 -no-snapshot-save &"
    exit 1
  fi
  ok "${#EMULATORS[@]} emulator(s): ${EMULATORS[*]}"
}

start_emulators() {
  local headless=${1:-false}
  local avd1=${2:-Pixel_7}
  local avd2=${3:-Pixel_3a}

  log "Starting emulators: $avd1, $avd2"
  local opts="-no-snapshot-save"
  if [ "$headless" = true ]; then
    opts="$opts -no-window -no-audio"
  fi
  nohup emulator -avd "$avd1" $opts > /tmp/emu1.log 2>&1 &
  nohup emulator -avd "$avd2" $opts > /tmp/emu2.log 2>&1 &

  wait_for_boot emulator-5554
  wait_for_boot emulator-5556
  require_emulators
}

start_single_emulator() {
  local headless=${1:-false}
  local avd=${2:-Pixel_7}

  log "Starting emulator: $avd"
  local opts="-no-snapshot-save"
  if [ "$headless" = true ]; then
    opts="$opts -no-window -no-audio"
  fi
  nohup emulator -avd "$avd" $opts > /tmp/emu1.log 2>&1 &

  wait_for_boot emulator-5554
  require_emulators
}

# ── SharedPreferences helpers ────────────────────────────────────────────────

# Write incubating prefs by merging into existing otel_config.xml.
# Must be called AFTER installDebug and BEFORE launching the app.
# Strategy: launch the app briefly to create the prefs file, force-stop,
# then overwrite with merged prefs.
enable_incubating() {
  local serial=$1

  # Ensure prefs dir exists by launching + stopping the app once.
  adb -s "$serial" shell am start -n "$PKG/.SchedulingActivity" > /dev/null 2>&1
  sleep 2
  adb -s "$serial" shell am force-stop "$PKG" > /dev/null 2>&1
  sleep 1

  # Read existing prefs, merge in incubating flags, write back.
  # Uses a helper script pushed to the device to avoid escaping issues.
  local tmp="/data/local/tmp/otel_merge_prefs.sh"
  adb -s "$serial" shell "cat > $tmp" <<'DEVICE_SCRIPT'
#!/system/bin/sh
PKG="io.opentelemetry.android.demo"
PREFS_DIR="/data/data/$PKG/shared_prefs"
PREFS="$PREFS_DIR/otel_config.xml"

# Create prefs file if it doesn't exist
if [ ! -f "$PREFS" ]; then
  mkdir -p "$PREFS_DIR"
  echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' > "$PREFS"
  echo '<map>' >> "$PREFS"
  echo '</map>' >> "$PREFS"
fi

# Remove existing incubating entries (if any) and closing </map>
CONTENT=$(cat "$PREFS" | grep -v 'screenshot_enabled' | grep -v 'screenshot_on_screen_view' | grep -v 'wireframe_enabled' | grep -v '</map>')

# Write back with incubating entries
echo "$CONTENT" > "$PREFS"
echo '    <boolean name="screenshot_enabled" value="true" />' >> "$PREFS"
echo '    <boolean name="screenshot_on_screen_view" value="true" />' >> "$PREFS"
echo '    <boolean name="wireframe_enabled" value="true" />' >> "$PREFS"
echo '</map>' >> "$PREFS"

# Fix ownership so the app can read it
chmod 660 "$PREFS"
DEVICE_SCRIPT

  adb -s "$serial" shell "run-as $PKG sh $tmp" 2>/dev/null || \
    adb -s "$serial" shell "su 0 sh $tmp" 2>/dev/null || \
    warn "$serial: could not write prefs (try enabling via Settings UI)"

  adb -s "$serial" shell "rm -f $tmp" 2>/dev/null

  # Verify
  local result
  result=$(adb -s "$serial" shell "run-as $PKG cat shared_prefs/otel_config.xml" 2>/dev/null || echo "")
  if echo "$result" | grep -q "screenshot_enabled"; then
    ok "$serial: screenshot (+ on screen view) + wireframe enabled"
  else
    warn "$serial: prefs write may have failed — enable via Settings UI instead"
  fi
}

# ── Backend helpers ──────────────────────────────────────────────────────────

start_backend() {
  log "Starting demo backend on port 3001"
  if curl -sf http://localhost:3001/api/doctors > /dev/null 2>&1; then
    ok "Backend already running"
    return
  fi
  cd "$DEMO_BACKEND"
  npm install --silent 2>/dev/null
  npm run dev > /tmp/demo-backend.log 2>&1 &
  local pid=$!
  for _ in $(seq 1 30); do
    curl -sf http://localhost:3001/api/doctors > /dev/null 2>&1 && break
    sleep 1
  done
  if curl -sf http://localhost:3001/api/doctors > /dev/null 2>&1; then
    ok "Backend started (PID $pid)"
  else
    warn "Backend failed to start — check /tmp/demo-backend.log"
  fi
}

stop_backend() {
  local pids
  pids=$(lsof -ti :3001 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "$pids" | xargs kill 2>/dev/null || true
    ok "Backend stopped"
  fi
}

# ── Build helpers ────────────────────────────────────────────────────────────

run_unit_tests() {
  log "Running unit tests (all modules)"
  cd "$DEMO_APP"
  ./gradlew \
    :otel-android-mobile:testDebugUnitTest \
    :otel-android-mobile-core:testDebugUnitTest \
    :instrumentation-tap:testDebugUnitTest \
    :instrumentation-freeze:testDebugUnitTest \
    :instrumentation-back-press:testDebugUnitTest \
    :instrumentation-vitals:testDebugUnitTest \
    :instrumentation-screenshot:testDebugUnitTest \
    :instrumentation-wireframe:testDebugUnitTest \
    --quiet
  ok "Unit tests passed"
}

build_and_install() {
  log "Building and installing demo app"
  cd "$DEMO_APP"
  ./gradlew installDebug --quiet
  ok "Installed on all connected emulators"
}

launch_app() {
  log "Launching demo app"
  for emu in "${EMULATORS[@]}"; do
    adb -s "$emu" shell am force-stop "$PKG" 2>/dev/null || true
    adb -s "$emu" shell am start -n "$PKG/.SchedulingActivity"
    ok "$emu: launched"
  done
  sleep 3
}

run_scenarios() {
  log "Running demo scenario suite (~8 min)"
  cd "$DEMO_APP"
  ./gradlew :android:connectedDebugAndroidTest 2>&1 | tail -5
  ok "Demo scenarios complete"
}

run_sdk_tests() {
  log "Running SDK instrumented tests (~30s)"
  cd "$DEMO_APP"
  ./gradlew :otel-android-mobile:connectedDebugAndroidTest 2>&1 | tail -5
  ok "SDK instrumented tests complete"
}

print_dash0_summary() {
  echo ""
  log "Done! Check Dash0 (dataset: otel-mobile) for:"
  echo "  • ui.tap, ui.screen_view, ui.scroll events"
  echo "  • journey → page → ui.tap span hierarchy"
  echo "  • Stress signals: device.health, thermal.status"
  echo "  • Conditional flush: 20+ events after crash trigger"
  if [ "${INCUBATING:-false}" = true ]; then
    echo "  • ui.screenshot events (base64 data URL in mobile.screenshot.data_url)"
    echo "  • ui.wireframe events (JSON tree in mobile.wireframe.data)"
  fi
}
