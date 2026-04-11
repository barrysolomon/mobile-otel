#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"

log "US-053: Get directions"

assert_event_exists "$LOGS" "ui.screen_view" "screen_view events"
assert_pattern_exists "$LOGS" "DirectionsFragment\|directions" "directions screen visited"

# HTTP spans for geocode + route
assert_span_exists "$TRACES" "http\|GET" "HTTP spans" false

# Location-related event
assert_pattern_exists "$LOGS" "directions.fetched\|location\|directions" "directions fetched event" false

assert_summary "US-053 get-directions"
