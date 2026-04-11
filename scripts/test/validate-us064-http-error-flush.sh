#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-064: HTTP error-triggered flush"

# Silent events that were buffered
assert_pattern_exists "$LOGS" "api.request" "buffered api.request events"

# HTTP error trigger
assert_pattern_exists "$LOGS" "http.error\|500" "HTTP error trigger"

# Buffer snapshots
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots"

assert_summary "US-064 http-error-flush"
