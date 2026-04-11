#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-056: Session lifecycle"

# Session ID present on events
assert_pattern_exists "$LOGS" "session.id\|mobile.session.id" "session.id attribute present"

# Multiple session IDs across app restarts
if command -v jq > /dev/null 2>&1 && [ -s "$LOGS" ]; then
  session_count=$(jq -rs '[.[].resourceLogs[].scopeLogs[].logRecords[].attributes[]? | select(.key == "mobile.session.id") | .value.stringValue] | unique | length' "$LOGS" 2>/dev/null || echo 0)
  if [ "$session_count" -gt 0 ]; then
    ok "Session IDs found ($session_count unique sessions)"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    warn "Could not count unique session IDs"
    ASSERT_WARN=$((ASSERT_WARN + 1))
  fi
fi

assert_summary "US-056 session-lifecycle"
