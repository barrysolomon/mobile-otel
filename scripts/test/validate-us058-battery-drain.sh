#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-058: Battery drain progression"

assert_pattern_exists "$LOGS" "battery\|device.battery" "battery level signals"
assert_pattern_exists "$LOGS" "prediction\|mobile.prediction" "predictive health signals"

# Buffer snapshot showing pre-emptive flush
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots during drain"

# Stress markers
assert_pattern_exists "$LOGS" "stress.battery\|battery_level_set" "stress battery markers" false

assert_summary "US-058 battery-drain"
