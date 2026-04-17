#!/usr/bin/env bash
# US-073 (iOS): Service-identity resource attributes on every record.
# Asserts that every log arriving from AstronomyShop has the resource
# attributes we advertise in IOS_SDK_GUIDE: service.*, os.*, device.*,
# telemetry.sdk.*. Mirrors Android's validate-us073-resource-attributes.sh.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib-ios/run-astronomy-demo.sh"

log "US-073 (iOS): resource attributes on emitted logs"
run_astronomy_demo_window

# Query the distinct attribute keys present on logs in the window. Dash0
# exposes resource attrs alongside scope/attribute keys through the same
# `getAttributeKeys` tool.
_attr_keys() {
    local scope="$1" from="$2" to="$3"
    local dataset; dataset="$(dash0_dataset)"
    local params
    params="$(python3 -c "
import json, sys
print(json.dumps({
    'scope': sys.argv[1],
    'dataset': sys.argv[2],
    'timeRange': {'from': sys.argv[3], 'to': sys.argv[4]},
    'filters': [{'key': 'service.name', 'operator': 'is', 'value': sys.argv[5]}],
    'pagination': {'limit': 50, 'offset': 0}
}))
" "$scope" "$dataset" "$from" "$to" "$SERVICE_NAME")"
    local resp; resp="$(dash0_mcp_query getAttributeKeys "$params")"
    python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    if d.get("isError"): sys.exit(0)
    content = d.get("result", {}).get("content", [])
    if content:
        t = content[0]["text"]
        for l in t.split("\n"):
            l = l.strip()
            if l and not l.startswith("Showing") and not l.startswith("#"):
                print(l)
except Exception:
    sys.exit(3)
' <<< "$resp"
}

log "Fetch: log attribute keys in window"
KEYS="$(_attr_keys logs "$IOS_SCENARIO_START" "$IOS_SCENARIO_END")"

MISSING=()
for attr in service.name service.version telemetry.sdk.name telemetry.sdk.language \
            telemetry.sdk.version os.name os.type os.version device.manufacturer \
            device.model.identifier device.model.name; do
    grep -qxF "$attr" <<< "$KEYS" || MISSING+=("$attr")
done

if (( ${#MISSING[@]} > 0 )); then
    echo "Observed keys:" >&2
    echo "$KEYS" | sed 's/^/  /' >&2
    printf "  ✗ missing: %s\n" "${MISSING[@]}"
    fail "resource attributes incomplete on iOS logs"
fi
ok "All required resource attributes observed (${#MISSING[@]} missing)"

# Spot-check that os.name actually resolves to "iOS" — catches the
# dash0.resource.type=browser classifier drift before it becomes load-bearing.
log "Assert: os.name value is exactly 'iOS'"
_attr_values() {
    local from="$1" to="$2"
    local dataset; dataset="$(dash0_dataset)"
    local params
    params="$(python3 -c "
import json, sys
print(json.dumps({
    'attributeKey': 'os.name',
    'scope': 'logs',
    'dataset': sys.argv[1],
    'timeRange': {'from': sys.argv[2], 'to': sys.argv[3]},
    'filters': [{'key': 'service.name', 'operator': 'is', 'value': sys.argv[4]}],
    'pagination': {'limit': 20, 'offset': 0}
}))
" "$dataset" "$from" "$to" "$SERVICE_NAME")"
    dash0_mcp_query getAttributeValues "$params" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    content = d.get("result", {}).get("content", [])
    if content:
        t = content[0]["text"]
        for l in t.split("\n"):
            l = l.strip()
            if l and not l.startswith("Showing") and not l.startswith("#"):
                print(l)
except Exception:
    sys.exit(3)
'
}
OS_VALUES="$(_attr_values "$IOS_SCENARIO_START" "$IOS_SCENARIO_END")"
if ! grep -qxF "iOS" <<< "$OS_VALUES"; then
    echo "os.name values observed: $OS_VALUES" >&2
    fail "os.name is not 'iOS' on emitted logs"
fi
ok "os.name=iOS confirmed"

ok "US-073 (iOS) PASS"
