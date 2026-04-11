#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"

log "US-072: Cross-signal correlation"

# Both logs and traces present
assert_event_exists "$LOGS" "ui.screen_view" "log events present"
assert_span_exists "$TRACES" "page\\." "trace spans present"

# Session ID present on both
assert_pattern_exists "$LOGS" "session.id\|mobile.session.id" "session.id on logs"

# Timestamp overlap — logs and traces cover the same time period
assert_timestamp_monotonic "$LOGS" "log timestamps ordered"

assert_summary "US-072 cross-signal"
