#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"

log "US-050: Happy path booking"

# Page spans for each screen visited
assert_event_exists "$LOGS" "ui.screen_view" "screen_view events"
assert_event_count "$LOGS" "ui.screen_view" 3 "" "at least 3 screen views"

# UI interactions
assert_event_exists "$LOGS" "ui.tap" "tap events during booking"

# Booking submission
assert_pattern_exists "$LOGS" "form.submitted\|booking" "booking submission event" false

# Page spans in traces
assert_span_exists "$TRACES" "page\\." "page spans"

# Journey span wrapping pages
assert_span_exists "$TRACES" "journey\\.\|endToEndBooking" "journey span" false

assert_summary "US-050 happy-path"
