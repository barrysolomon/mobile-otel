#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-061: Combined stress"

# All three stress signals present
assert_pattern_exists "$LOGS" "battery\|device.battery" "battery signals"
assert_pattern_exists "$LOGS" "thermal\|device.thermal" "thermal signals"
assert_pattern_exists "$LOGS" "memory\|device.memory" "memory signals"

# Prediction with elevated risk
assert_pattern_exists "$LOGS" "prediction\|mobile.prediction" "combined prediction"

# Buffer flush under stress
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots during combined stress"

assert_summary "US-061 combined-stress"
