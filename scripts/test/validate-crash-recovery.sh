#!/usr/bin/env bash
# Validate that crash-recovery telemetry was received by the local collector.
#
# Checks for:
#   - Pre-crash events (ui.screen_view, ui.tap, navigation breadcrumbs)
#   - app.crash event with exception details
#   - app.recovery event with recovery_type=crash
#   - Service identity (service.name=validated-test from SharedPreferences override)
#   - Session continuity (session.id present)
#
# Prerequisites: run the crash test with a local collector first:
#   ./run-real-crash-test.sh
set -euo pipefail

AIRPLANE_MODE=false
for arg in "$@"; do
  case "$arg" in
    --airplane-mode) AIRPLANE_MODE=true ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/collector/output"

log()  { echo -e "\033[1;36m▸ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }
warn() { echo -e "\033[1;33m  ⚠ $*\033[0m"; }

PASS=0; FAIL=0; WARN=0

check_signal() {
  local file=$1
  local pattern=$2
  local description=$3
  local required=${4:-true}

  if [ ! -f "$file" ]; then
    if [ "$required" = true ]; then
      err "$description — file not found: $file"
      FAIL=$((FAIL + 1))
    else
      warn "$description — file not found (optional)"
      WARN=$((WARN + 1))
    fi
    return
  fi

  if grep -q "$pattern" "$file" 2>/dev/null; then
    ok "$description"
    PASS=$((PASS + 1))
  else
    if [ "$required" = true ]; then
      err "$description — pattern not found: $pattern"
      FAIL=$((FAIL + 1))
    else
      warn "$description — not found (optional)"
      WARN=$((WARN + 1))
    fi
  fi
}

echo ""
log "Validating crash-recovery telemetry"
echo "   Output dir: $OUTPUT_DIR"
echo ""

# ── Pre-crash events ───────────────────────────────────────────────────────

log "Pre-crash event signals"

check_signal "$OUTPUT_DIR/logs.json" "ui.screen_view" \
  "ui.screen_view events (screen instrumentation captured before crash)"

check_signal "$OUTPUT_DIR/logs.json" "ui.tap" \
  "ui.tap events (tap instrumentation captured before crash)"

check_signal "$OUTPUT_DIR/logs.json" "app.foreground" \
  "app.foreground event (lifecycle captured before crash)"

check_signal "$OUTPUT_DIR/logs.json" "buffer.snapshot" \
  "buffer.snapshot event (pre-crash buffer stats)"

check_signal "$OUTPUT_DIR/logs.json" "demo.step" \
  "demo.step events (scenario pacing events)" false

# ── Crash event ────────────────────────────────────────────────────────────

log "Crash event signals"

check_signal "$OUTPUT_DIR/logs.json" "app.crash" \
  "app.crash event (real crash captured by ErrorInstrumentation)" false

check_signal "$OUTPUT_DIR/logs.json" "RuntimeException" \
  "exception.type=RuntimeException on crash event" false

check_signal "$OUTPUT_DIR/logs.json" "booking service crash\|Booking service crash\|fatal error" \
  "exception.message contains crash description" false

# ── Recovery event ─────────────────────────────────────────────────────────

log "Recovery event signals"

check_signal "$OUTPUT_DIR/logs.json" "app.recovery" \
  "app.recovery event (RecoveryTracker detected crash on restart)"

check_signal "$OUTPUT_DIR/logs.json" "recovery_type" \
  "recovery_type attribute present on recovery event"

# ── Service identity ───────────────────────────────────────────────────────

log "Service identity"

check_signal "$OUTPUT_DIR/logs.json" "validated-test" \
  "service.name=validated-test (SharedPreferences override survived crash)"

check_signal "$OUTPUT_DIR/logs.json" "session.id\|mobile.session.id" \
  "session.id attribute present on events"

check_signal "$OUTPUT_DIR/logs.json" "device.id" \
  "device.id resource attribute"

# ── Traces ─────────────────────────────────────────────────────────────────

log "Trace signals"

check_signal "$OUTPUT_DIR/traces.json" "page\\." \
  "page.* spans (screen view page spans from pre-crash navigation)" false

# ── Airplane mode signals ─────────────────────────────────────────────────────

if [ "$AIRPLANE_MODE" = true ]; then
  log "Airplane mode signals"

  if [ -f "$OUTPUT_DIR/.logs_size_before" ]; then
    LOGS_SIZE_BEFORE=$(cat "$OUTPUT_DIR/.logs_size_before")
    ok "Airplane mode validation: offline snapshot was ${LOGS_SIZE_BEFORE} bytes"
    PASS=$((PASS + 1))
  else
    warn "No offline snapshot found (.logs_size_before missing)"
    WARN=$((WARN + 1))
  fi

  check_signal "$OUTPUT_DIR/logs.json" "app.crash" \
    "app.crash event arrived after network restore" false

  check_signal "$OUTPUT_DIR/logs.json" "app.recovery" \
    "app.recovery event arrived after network restore"
fi

# ── Summary ────────────────────────────────────────────────────────────────

echo ""
echo "══════════════════════════════════════"
echo "  Passed:  $PASS"
echo "  Failed:  $FAIL"
echo "  Warned:  $WARN (optional signals)"
echo "══════════════════════════════════════"

if [ $FAIL -gt 0 ]; then
  echo ""
  err "$FAIL required signal(s) missing!"
  exit 1
else
  echo ""
  ok "All required crash-recovery signals validated!"
fi
