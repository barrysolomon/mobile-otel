#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-057: App background/foreground"

assert_event_exists "$LOGS" "app.foreground" "app.foreground event"
assert_pattern_exists "$LOGS" "app.background\|app.foreground" "lifecycle events"

# Timestamp ordering
assert_timestamp_monotonic "$LOGS" "lifecycle timestamps monotonic"

assert_summary "US-057 background-foreground"
