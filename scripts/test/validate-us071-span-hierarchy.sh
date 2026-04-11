#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
TRACES="$SCRIPT_DIR/collector/output/traces.json"

log "US-071: Span parent-child integrity"

# Page spans exist
assert_span_exists "$TRACES" "page\\." "page spans present"

# Journey → page hierarchy (only if journey spans are present in output)
if grep -q "journey" "$TRACES" 2>/dev/null; then
  assert_span_hierarchy "$TRACES" "journey\\..*" "page\\..*" "pages under journey span"
fi

# All spans have traceId
assert_pattern_exists "$TRACES" "traceId" "traceId present on all spans"

# All spans have spanId
assert_pattern_exists "$TRACES" "spanId" "spanId present on all spans"

assert_summary "US-071 span-hierarchy"
