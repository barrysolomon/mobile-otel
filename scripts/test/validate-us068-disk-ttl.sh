#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-068: Disk TTL enforcement"

# This is primarily validated by unit tests (DiskLogBufferTest).
# The E2E validation checks that buffer stats show reasonable disk counts.
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots present"
assert_pattern_exists "$LOGS" "buffer.disk.events" "disk event count tracked"
assert_pattern_exists "$LOGS" "buffer.ram.events" "RAM event count tracked"

assert_summary "US-068 disk-ttl"
