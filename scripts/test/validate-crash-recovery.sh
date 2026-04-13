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
#   ./demo-control-center.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"

LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"
OUTPUT_DIR="$SCRIPT_DIR/collector/output"

AIRPLANE_MODE=false
for arg in "$@"; do
  case "$arg" in
    --airplane-mode) AIRPLANE_MODE=true ;;
  esac
done

echo ""
log "Validating crash-recovery telemetry"
echo "   Output dir: $OUTPUT_DIR"
echo ""

# ── Pre-crash events ───────────────────────────────────────────────────────

log "Pre-crash event signals"

assert_event_exists "$LOGS" "ui.screen_view" \
  "ui.screen_view events (screen instrumentation captured before crash)"

assert_event_exists "$LOGS" "ui.tap" \
  "ui.tap events (tap instrumentation captured before crash)"

assert_event_exists "$LOGS" "app.foreground" \
  "app.foreground event (lifecycle captured before crash)"

assert_event_exists "$LOGS" "buffer.snapshot" \
  "buffer.snapshot event (pre-crash buffer stats)"

assert_event_exists "$LOGS" "demo.step" \
  "demo.step events (scenario pacing events)" false

# ── Crash event ────────────────────────────────────────────────────────────

log "Crash event signals"

assert_event_exists "$LOGS" "app.crash" \
  "app.crash event (real crash captured by ErrorInstrumentation)" false

assert_pattern_exists "$LOGS" "RuntimeException" \
  "exception.type=RuntimeException on crash event" false

assert_pattern_exists "$LOGS" "booking service crash\|Booking service crash\|fatal error" \
  "exception.message contains crash description" false

# ── Recovery event ─────────────────────────────────────────────────────────

log "Recovery event signals"

assert_event_exists "$LOGS" "app.recovery" \
  "app.recovery event (RecoveryTracker detected crash on restart)"

assert_pattern_exists "$LOGS" "recovery_type" \
  "recovery_type attribute present on recovery event"

# ── Service identity ───────────────────────────────────────────────────────

log "Service identity"

assert_resource_attribute "$LOGS" "service.name" "validated-test" \
  "service.name=validated-test (SharedPreferences override survived crash)"

assert_pattern_exists "$LOGS" "session.id\|mobile.session.id" \
  "session.id attribute present on events"

assert_resource_attribute "$LOGS" "device.id" "" \
  "device.id resource attribute"

# ── Traces ─────────────────────────────────────────────────────────────────

log "Trace signals"

assert_span_exists "$TRACES" "page\\." \
  "page.* spans (screen view page spans from pre-crash navigation)" false

# ── Airplane mode signals ─────────────────────────────────────────────────

if [ "$AIRPLANE_MODE" = true ]; then
  log "Airplane mode signals"

  assert_file_unchanged "$LOGS" "$OUTPUT_DIR/.logs_size_before" \
    "no events received while offline"

  assert_event_exists "$LOGS" "app.crash" \
    "app.crash event arrived after network restore" false

  assert_event_exists "$LOGS" "app.recovery" \
    "app.recovery event arrived after network restore"
fi

# ── Summary ────────────────────────────────────────────────────────────────

assert_summary "crash-recovery"
