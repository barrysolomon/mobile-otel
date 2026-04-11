#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-075: CONTINUOUS periodic flush"

# Multiple batches (lines) in the collector output
if [ -f "$LOGS" ]; then
  line_count=$(wc -l < "$LOGS" | tr -d ' ')
  if [ "$line_count" -ge 3 ]; then
    ok "Multiple export batches received ($line_count batches)"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    err "Expected >= 3 export batches, got $line_count"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
else
  err "logs.json not found"
  ASSERT_FAIL=$((ASSERT_FAIL + 1))
fi

# Events present
assert_event_exists "$LOGS" "ui.screen_view" "events in periodic batches"

assert_summary "US-075 continuous-periodic"
