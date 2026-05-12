#!/usr/bin/env bash
# Common functions shared by crash test and validated test scripts.
# Source this file — do not execute directly.

# ── Logging ───────────────────────────────────────────────────────────────────

log()  { echo -e "\n\033[1;36m▸ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }
warn() { echo -e "\033[1;33m  ⚠ $*\033[0m"; }

# ── Paths ─────────────────────────────────────────────────────────────────────

COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_DIR="$(cd "$COMMON_DIR/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEMO_APP="$REPO_ROOT/examples/demo-app"
COLLECTOR_DIR="$SCRIPT_DIR/collector"
OUTPUT_DIR="$COLLECTOR_DIR/output"
PACKAGE="io.opentelemetry.android.demo"

# ── Emulator ──────────────────────────────────────────────────────────────────

find_emulator() {
  # If SERIAL is already set (e.g. by TUI fan-out via --serial= or env), keep it
  # — but verify it's actually a known adb device. This lets multiple instances
  # of this script run in parallel, each pinned to a different emulator.
  if [ -n "${SERIAL:-}" ]; then
    if adb devices 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "$SERIAL"; then
      return 0
    fi
    warn "SERIAL=$SERIAL not visible in 'adb devices' — falling back to auto-pick"
  fi
  SERIAL=$(adb devices 2>/dev/null | grep "emulator" | head -1 | awk '{print $1}')
  if [ -z "$SERIAL" ]; then
    err "No emulator found. Start one first."
    return 1
  fi
}

# ── Collector ─────────────────────────────────────────────────────────────────

start_collector() {
  log "Starting local OTel Collector (Docker)"
  rm -rf "$OUTPUT_DIR"
  mkdir -p "$OUTPUT_DIR"
  touch "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"

  docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" up -d 2>&1 || \
    docker-compose -f "$COLLECTOR_DIR/docker-compose.yaml" up -d 2>&1

  local i
  for i in $(seq 1 15); do
    if docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" ps 2>/dev/null | grep -q "Up"; then
      ok "Collector running on ports 14317 (gRPC) + 14318 (HTTP)"
      return 0
    fi
    if [ "$i" -eq 15 ]; then
      err "Collector failed to start"
      return 1
    fi
    sleep 1
  done
}

stop_collector() {
  log "Stopping collector"
  docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" down 2>/dev/null || \
    docker-compose -f "$COLLECTOR_DIR/docker-compose.yaml" down 2>/dev/null
  ok "Collector stopped"
}

reset_collector_output() {
  rm -f "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"
  rm -f "$OUTPUT_DIR/.logs_size_before"
  touch "$OUTPUT_DIR/logs.json" "$OUTPUT_DIR/traces.json" "$OUTPUT_DIR/metrics.json"
  # Restart collector so it picks up fresh file handles (Docker bind mount
  # breaks if host files are deleted and recreated while container is running)
  if docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" ps 2>/dev/null | grep -q "Up"; then
    docker compose -f "$COLLECTOR_DIR/docker-compose.yaml" restart 2>&1 >/dev/null || true
    sleep 2
  fi
  ok "Collector output cleared"
}

# ── Demo Backend ──────────────────────────────────────────────────────────────

start_demo_backend() {
  if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    ok "Backend already running"
    return 0
  fi
  log "Starting demo backend"
  cd "$REPO_ROOT/examples/demo-backend"
  npm run dev > /tmp/demo-backend.log 2>&1 &
  sleep 3
  if curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    ok "Backend running on port 3001"
  else
    err "Backend failed to start — check /tmp/demo-backend.log"
    return 1
  fi
}

# ── Build & Install ───────────────────────────────────────────────────────────

build_and_install() {
  log "Building and installing demo app + test APK"
  cd "$DEMO_APP"
  ./gradlew installDebug installDebugAndroidTest --quiet
  ok "Installed app + test APK"
}

# ── Device Config ─────────────────────────────────────────────────────────────

dismiss_crash_dialog() {
  # Wait for process to fully die
  local retries=0
  while adb -s "$SERIAL" shell pidof "$PACKAGE" 2>/dev/null | grep -q .; do
    sleep 1
    retries=$((retries + 1))
    if [ "$retries" -gt 10 ]; then
      warn "Process still alive after 10s — force stopping"
      adb -s "$SERIAL" shell am force-stop "$PACKAGE"
      break
    fi
  done

  # Dismiss crash dialog(s) — send BACK keyevent multiple times.
  # On different API levels, the crash dialog may have 1-2 buttons or
  # may auto-dismiss. Multiple BACKs are harmless if no dialog is showing.
  sleep 1
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  sleep 0.5
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  sleep 0.5
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK
  sleep 1
}
