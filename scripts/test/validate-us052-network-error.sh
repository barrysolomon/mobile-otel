#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"

log "US-052: Network error recovery"

assert_event_exists "$LOGS" "ui.screen_view" "screen_view events"

# HTTP 500 error
assert_pattern_exists "$LOGS" "500\|http.error\|http_error" "HTTP 500 error signal"
assert_pattern_exists "$TRACES" "500\|ERROR\|error" "error span in traces" false

# Recovery — subsequent successful navigation
assert_event_count "$LOGS" "ui.screen_view" 2 "" "at least 2 screens (error + recovery)"

assert_summary "US-052 network-error"
