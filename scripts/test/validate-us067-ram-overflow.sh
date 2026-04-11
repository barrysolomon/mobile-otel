#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-067: RAM overflow to disk"

# Buffer snapshots showing disk events > 0 (overflow occurred)
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots present"
assert_pattern_exists "$LOGS" "buffer.disk.events" "disk event count attribute"

# Timestamp ordering preserved across overflow
assert_timestamp_monotonic "$LOGS" "timestamps monotonic across RAM overflow"

assert_summary "US-067 ram-overflow"
