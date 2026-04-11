#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-060: Memory pressure cascade"

assert_pattern_exists "$LOGS" "memory\|device.memory\|trim_level" "memory pressure signals"
assert_pattern_exists "$LOGS" "prediction\|mobile.prediction" "predictive health signals"
assert_event_exists "$LOGS" "buffer.snapshot" "buffer snapshots"
assert_pattern_exists "$LOGS" "stress.memory\|memory_trim" "stress memory markers" false

assert_summary "US-060 memory-pressure"
