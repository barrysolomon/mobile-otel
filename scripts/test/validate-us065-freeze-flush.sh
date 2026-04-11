#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-065: UI freeze-triggered flush"

# Freeze/jank event
assert_pattern_exists "$LOGS" "ui.freeze\|ui.jank\|jank" "freeze/jank event"

# Freeze duration attribute
assert_pattern_exists "$LOGS" "frame_duration\|freeze.duration\|duration_ms" "freeze duration attribute" false

assert_summary "US-065 freeze-flush"
