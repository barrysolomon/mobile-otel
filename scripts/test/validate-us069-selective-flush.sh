#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-069: Selective time-window flush"

# Buffer snapshots showing pre/post flush counts
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots"
assert_event_count "$LOGS" "buffer.snapshot" 2 "" "at least 2 snapshots (pre + post flush)"

# Events are present (the flushed window contents)
assert_event_exists "$LOGS" "ui.screen_view" "flushed events present"

assert_summary "US-069 selective-flush"
