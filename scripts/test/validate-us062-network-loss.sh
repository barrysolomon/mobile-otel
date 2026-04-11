#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-062: Network loss and recovery"

# Connectivity change events
assert_pattern_exists "$LOGS" "connectivity\|airplane\|network" "connectivity change signals"

# Events accumulated during offline period
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots"

# Events eventually exported after reconnect
assert_event_exists "$LOGS" "ui.screen_view" "screen_view events (post-reconnect)"

assert_summary "US-062 network-loss"
