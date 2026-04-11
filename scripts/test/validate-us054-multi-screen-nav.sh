#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-054: Multi-screen navigation breadcrumbs"

# At least 4 different screens visited
assert_event_count "$LOGS" "ui.screen_view" 4 "" "at least 4 screen views"

# Screen names present
assert_pattern_exists "$LOGS" "CalendarFragment\|calendar" "CalendarFragment visited"
assert_pattern_exists "$LOGS" "AppointmentsFragment\|appointments" "AppointmentsFragment visited"
assert_pattern_exists "$LOGS" "BookFragment\|book" "BookFragment visited"

# Event ordering — screens visited in sequence
assert_event_order "$LOGS" "ui.screen_view" "ui.tap" "screen_view before first tap"

# Breadcrumb trail
assert_pattern_exists "$LOGS" "screen.name\|mobile.screen.name" "screen.name attribute present"

assert_summary "US-054 multi-screen-nav"
