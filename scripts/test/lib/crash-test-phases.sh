#!/usr/bin/env bash
# Crash test phase execution: phase1, phase2, airplane mode, memory watchdog.
# Source this file — do not execute directly.
# Requires: SERIAL, PACKAGE, OUTPUT_DIR (from common.sh)

INTERACTIVE=false
WATCHDOG_PID=""

# ── Phase Execution ───────────────────────────────────────────────────────────

run_phase1() {
  log "Phase 1: Generating pre-crash events + triggering crash"
  start_memory_watchdog
  adb -s "$SERIAL" shell am instrument -w \
    -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase1Test \
    io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner \
    || true  # expected non-zero (process death)
  stop_memory_watchdog
  ok "Phase 1 complete — app crashed"
}

run_phase2() {
  log "Phase 2: Verifying recovery"
  adb -s "$SERIAL" shell am instrument -w \
    -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase2Test \
    io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner
  local rc=$?
  if [ $rc -ne 0 ]; then
    err "Phase 2 failed (exit code $rc)"
    return $rc
  fi
  ok "Phase 2 complete — recovery verified"
}

# ── Airplane Mode ─────────────────────────────────────────────────────────────

enable_airplane_mode() {
  log "Enabling airplane mode"
  adb -s "$SERIAL" shell cmd connectivity airplane-mode enable
  ok "Airplane mode ON"
}

disable_airplane_mode() {
  log "Disabling airplane mode"
  adb -s "$SERIAL" shell cmd connectivity airplane-mode disable
  local retries=0
  while ! adb -s "$SERIAL" shell ping -c 1 -W 2 10.0.2.2 > /dev/null 2>&1; do
    retries=$((retries + 1))
    if [ "$retries" -gt 15 ]; then
      err "Network did not restore after 30s"
      return 1
    fi
    sleep 2
  done
  ok "Network restored"
}

snapshot_collector_output() {
  wc -c < "$OUTPUT_DIR/logs.json" 2>/dev/null > "$OUTPUT_DIR/.logs_size_before" \
    || echo 0 > "$OUTPUT_DIR/.logs_size_before"
}

# ── Interactive Prompts ───────────────────────────────────────────────────────

prompt_continue() {
  if [ "$INTERACTIVE" = true ]; then
    echo ""
    echo -e "\033[1;33m  ⏸  $1\033[0m"
    echo -e "\033[1;33m     Press ENTER to continue…\033[0m"
    read -r
  fi
}

prompt_action() {
  local msg="$1"; shift
  if [ "$INTERACTIVE" = true ]; then
    echo ""
    echo -e "\033[1;33m  ⏸  $msg\033[0m"
    read -r
  fi
  "$@"
}

# ── Memory Watchdog ───────────────────────────────────────────────────────────

start_memory_watchdog() {
  (
    while true; do
      emu_pid=$(pgrep -f "qemu-system" | head -1)
      if [ -n "$emu_pid" ]; then
        rss_kb=$(ps -o rss= -p "$emu_pid" 2>/dev/null | tr -d ' ')
        rss_gb=$(( ${rss_kb:-0} / 1048576 ))
        if [ "${rss_gb}" -gt 8 ]; then
          echo ""
          echo "MEMORY WATCHDOG: Emulator using ${rss_gb}GB — aborting test"
          adb -s "$SERIAL" shell am force-stop "$PACKAGE" 2>/dev/null
          kill $$ 2>/dev/null
          exit 1
        fi
      fi
      sleep 5
    done
  ) &
  WATCHDOG_PID=$!
}

stop_memory_watchdog() {
  if [ -n "${WATCHDOG_PID:-}" ]; then
    kill "$WATCHDOG_PID" 2>/dev/null || true
    WATCHDOG_PID=""
  fi
}

# ── Validation ────────────────────────────────────────────────────────────────

validate() {
  "$SCRIPT_DIR/validate-crash-recovery.sh" "$@"
}
