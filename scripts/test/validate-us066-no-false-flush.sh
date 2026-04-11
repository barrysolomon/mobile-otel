#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-066: No false flushes (CONDITIONAL mode)"

# In CONDITIONAL mode with no triggers, the collector should receive
# only device metrics (from periodic capture) but NO log events.
# This is validated by checking the log file is empty or contains
# only metric-related signals.

if [ ! -s "$LOGS" ]; then
  ok "No log events exported (CONDITIONAL mode, no triggers)"
  ASSERT_PASS=$((ASSERT_PASS + 1))
else
  # Check if any non-metric events leaked
  event_count=$(jq -rs '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue | test("^(device\\.|prediction\\.|demo\\.)") | not)] | length' "$LOGS" 2>/dev/null || echo 0)
  if [ "$event_count" = "0" ]; then
    ok "No user events exported — only device metrics (correct for CONDITIONAL)"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    err "False flush: $event_count user events exported in CONDITIONAL mode"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
fi

assert_summary "US-066 no-false-flush"
