#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"

log "US-051: Browse and refresh"

assert_event_exists "$LOGS" "ui.screen_view" "screen_view events"
assert_pattern_exists "$LOGS" "ui.scroll\|ui.swipe" "scroll/swipe events" false
assert_pattern_exists "$LOGS" "AppointmentsFragment\|appointments" "appointments screen visited"

# HTTP spans from refresh
assert_span_exists "$TRACES" "http\|GET\|POST" "HTTP spans from refresh" false

assert_summary "US-051 browse-refresh"
