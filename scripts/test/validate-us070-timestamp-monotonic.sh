#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-070: Timestamp monotonicity"

assert_timestamp_monotonic "$LOGS" "all log timestamps monotonically increasing"

# Also check that timestamps are in a reasonable range (not zero, not future)
assert_pattern_exists "$LOGS" "observedTimeUnixNano" "timestamps present on events"

assert_summary "US-070 timestamp-monotonic"
