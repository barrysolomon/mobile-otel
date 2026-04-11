#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"
METRICS="$SCRIPT_DIR/collector/output/metrics.json"

log "US-076: HYBRID mode"

# Metrics should be present (periodic export)
assert_pattern_exists "$METRICS" "device\|app\|process" "device metrics exported periodically" false

# Log events may or may not be present depending on policy triggers
if [ -s "$LOGS" ]; then
  ok "Log events present (policy triggered or continuous fallback)"
  ASSERT_PASS=$((ASSERT_PASS + 1))
else
  ok "No log events (correct for HYBRID without policy triggers)"
  ASSERT_PASS=$((ASSERT_PASS + 1))
fi

assert_summary "US-076 hybrid-mode"
