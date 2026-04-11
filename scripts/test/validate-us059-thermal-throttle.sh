#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-059: Thermal throttle escalation"

assert_pattern_exists "$LOGS" "thermal\|device.thermal" "thermal status signals"
assert_pattern_exists "$LOGS" "prediction\|mobile.prediction" "predictive health signals"
assert_pattern_exists "$LOGS" "stress.thermal\|thermal_level_set" "stress thermal markers" false

assert_summary "US-059 thermal-throttle"
