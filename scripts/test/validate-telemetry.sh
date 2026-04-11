#!/usr/bin/env bash
# Validate that expected telemetry was received by the local collector.
#
# Prerequisites: run the collector + scenarios first:
#   ./run-validated-tests.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"

LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"
METRICS="$SCRIPT_DIR/collector/output/metrics.json"

echo ""
log "Validating telemetry received by local collector"
echo "   Output dir: $SCRIPT_DIR/collector/output"
echo ""

# ── Logs ─────────────────────────────────────────────────────────────────────

log "Log signals"

assert_event_exists "$LOGS" "ui.tap" \
  "ui.tap events (tap instrumentation)"

assert_event_exists "$LOGS" "ui.screen_view" \
  "ui.screen_view events (screen instrumentation)"

assert_event_exists "$LOGS" "ui.scroll" \
  "ui.scroll events (scroll instrumentation)" false

assert_event_exists "$LOGS" "ui.back_press" \
  "ui.back_press events (back-press instrumentation)" false

# OR pattern — assert_pattern_exists handles grep alternation
assert_pattern_exists "$LOGS" "app.foreground\|app.background" \
  "app.foreground/background events (lifecycle instrumentation)"

assert_event_exists "$LOGS" "device.orientation" \
  "device.orientation events (screen-orientation instrumentation)" false

assert_pattern_exists "$LOGS" "device.battery\|device.power\|device.storage" \
  "system events (system-events instrumentation)" false

assert_pattern_exists "$LOGS" "session.id" \
  "session.id attribute present on log events"

assert_pattern_exists "$LOGS" "view.id" \
  "view.id attribute present on log events"

assert_pattern_exists "$LOGS" "screen.name" \
  "screen.name attribute present on log events"

# ── Traces ───────────────────────────────────────────────────────────────────

log "Trace signals"

assert_span_exists "$TRACES" "page\\." \
  "page.* spans (screen view page spans)"

assert_span_exists "$TRACES" "http" \
  "HTTP spans (network instrumentation)" false

# ── Metrics ──────────────────────────────────────────────────────────────────

log "Metric signals"

assert_pattern_exists "$METRICS" "jvm\|process\|device\|app" \
  "Device/app metrics (vitals instrumentation)" false

# ── Service identity ─────────────────────────────────────────────────────────

log "Service identity"

assert_resource_attribute "$LOGS" "service.name" "" \
  "service.name resource attribute"

assert_resource_attribute "$LOGS" "device.id" "" \
  "device.id resource attribute"

# ── Summary ──────────────────────────────────────────────────────────────────

assert_summary "telemetry"
