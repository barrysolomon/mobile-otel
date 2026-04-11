#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-063: Crash-triggered conditional flush"

# Silent events that were buffered
assert_pattern_exists "$LOGS" "user.transaction" "buffered user.transaction events"

# Crash trigger
assert_event_exists "$LOGS" "app.crash" "app.crash trigger event"

# Recovery — ConditionalFlushScenarios emits "app.crash_recovery"
assert_event_exists "$LOGS" "app.crash_recovery" "crash recovery event"

# Buffer snapshots showing flush
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots"

# All events arrived (buffered + crash + recovery)
assert_event_count "$LOGS" "buffer.snapshot" 2 "" "at least 2 buffer snapshots (pre + post)"

assert_summary "US-063 crash-flush"
