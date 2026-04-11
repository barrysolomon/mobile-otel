#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-073: Service identity resource attributes"

assert_resource_attribute "$LOGS" "service.name" "" "service.name present"
assert_resource_attribute "$LOGS" "service.version" "" "service.version present"
assert_resource_attribute "$LOGS" "device.id" "" "device.id present"
assert_resource_attribute "$LOGS" "device.manufacturer" "" "device.manufacturer present"
assert_resource_attribute "$LOGS" "device.model.name" "" "device.model.name present"
assert_pattern_exists "$LOGS" "os.name\|android" "os.name present"
assert_pattern_exists "$LOGS" "os.version" "os.version present"
assert_pattern_exists "$LOGS" "telemetry.sdk" "telemetry.sdk attributes present"

assert_summary "US-073 resource-attributes"
