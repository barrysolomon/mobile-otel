#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-074: Dynamic sampling"

# Events present (some were sampled in)
assert_event_exists "$LOGS" "ui.screen_view" "sampled events present"

# Sampling attributes if present
assert_pattern_exists "$LOGS" "sampl\|sample" "sampling-related attributes" false

assert_summary "US-074 dynamic-sampling"
