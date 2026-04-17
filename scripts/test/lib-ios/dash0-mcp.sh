#!/usr/bin/env bash
# Shared helpers for iOS scenario scripts to query Dash0's MCP JSON-RPC API.
#
# Source this from a scenario script after setting:
#   CONFIG_PATH — path to the AstronomyShop otel-config.json
#   SERVICE_NAME, DATASET — usually set from the config
#
# Exposes:
#   dash0_auth_token       — reads from CONFIG_PATH
#   dash0_dataset          — reads from CONFIG_PATH
#   dash0_mcp_query        — raw JSON-RPC call
#   dash0_row_count        — parse a getLogRecords/getSpans response for row count
#   dash0_metric_names     — return the list of metric names in a time range
#   dash0_logs_bodies      — return log.body values in a time range
#   dash0_span_names       — return span names in a time range
#   dash0_span_parent_count — count spans that have a non-empty parent id
#   dash0_log_attr_values  — list values of a given log attribute
#   iso_now, iso_minus_min — timestamp helpers

set -euo pipefail

# Host + credential lookup
DASH0_MCP_URL="${DASH0_MCP_URL:-https://api.us-west-2.aws.dash0.com/mcp}"

dash0_auth_token() {
    jq -r .auth_token "$CONFIG_PATH"
}

dash0_dataset() {
    jq -r .dataset "$CONFIG_PATH"
}

iso_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# BSD date (`date -v`) vs GNU date (`date -d`) — use the right flag.
iso_minus_min() {
    local minutes="$1"
    if date -v -0M >/dev/null 2>&1; then
        date -u -v "-${minutes}M" +%Y-%m-%dT%H:%M:%SZ
    else
        date -u -d "${minutes} minutes ago" +%Y-%m-%dT%H:%M:%SZ
    fi
}

# Low-level: POST a JSON-RPC payload to the MCP endpoint and return the raw
# response. Caller passes the `params` object as the first arg; this wraps
# it in the JSON-RPC envelope.
dash0_mcp_query() {
    local tool="$1" params_json="$2"
    local token; token="$(dash0_auth_token)"
    local body
    body="$(python3 -c "
import json, sys
params = json.loads(sys.argv[1])
params['skillId'] = 1
print(json.dumps({'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':sys.argv[2],'arguments':params}}))
" "$params_json" "$tool")"
    curl -sS -X POST \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        "$DASH0_MCP_URL" \
        -d "$body"
}

# Parse a getLogRecords / getSpans MCP response for row count. Returns 0 on
# "no results" (isError:true or empty table). Exits 1 on malformed JSON so
# scripts fail loudly instead of silently passing.
dash0_row_count() {
    local resp="$1"
    if echo "$resp" | grep -q '"isError":true'; then
        echo 0
        return
    fi
    local count
    count="$(python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    t = d["result"]["content"][0]["text"]
    rows = [l for l in t.split("\n") if l.startswith("| ") and not l.startswith("| :")]
    header_labels = ("otel.log", "otel.trace.id", "Name", "Series ID")
    header_rows = 1 if rows and any(h in rows[0] for h in header_labels) else 0
    print(max(0, len(rows) - header_rows))
except Exception:
    sys.exit(3)
' <<< "$resp")"
    if [[ -z "$count" || ! "$count" =~ ^[0-9]+$ ]]; then
        echo "dash0_row_count: malformed response" >&2
        echo "$resp" | head -c 1000 >&2
        exit 1
    fi
    echo "$count"
}

# Helper — query getLogRecords for service filter in a time window, return
# the count. Args: from_iso, to_iso [, extra_filters_json]
dash0_log_count() {
    local from="$1" to="$2" extra="${3:-[]}"
    local dataset; dataset="$(dash0_dataset)"
    local filters="[{\"key\":\"service.name\",\"operator\":\"is\",\"value\":\"$SERVICE_NAME\"}]"
    if [[ "$extra" != "[]" ]]; then
        filters="$(python3 -c 'import json,sys;a=json.loads(sys.argv[1]);b=json.loads(sys.argv[2]);print(json.dumps(a+b))' "$filters" "$extra")"
    fi
    local params
    params="$(python3 -c "
import json, sys
print(json.dumps({
    'dataset': sys.argv[1],
    'timeRange': {'from': sys.argv[2], 'to': sys.argv[3]},
    'filters': json.loads(sys.argv[4]),
    'pagination': {'limit': 50}
}))
" "$dataset" "$from" "$to" "$filters")"
    dash0_row_count "$(dash0_mcp_query getLogRecords "$params")"
}

# Helper — same for spans.
dash0_span_count() {
    local from="$1" to="$2"
    local dataset; dataset="$(dash0_dataset)"
    local params
    params="$(python3 -c "
import json, sys
print(json.dumps({
    'dataset': sys.argv[1],
    'timeRange': {'from': sys.argv[2], 'to': sys.argv[3]},
    'filters': [{'key': 'service.name', 'operator': 'is', 'value': sys.argv[4]}],
    'pagination': {'limit': 50}
}))
" "$dataset" "$from" "$to" "$SERVICE_NAME")"
    dash0_row_count "$(dash0_mcp_query getSpans "$params")"
}

# Return metric names present in the catalog for the time range (no service
# filter — the catalog is dataset-wide).
dash0_metric_names() {
    local from="$1" to="$2"
    local dataset; dataset="$(dash0_dataset)"
    local params
    params="$(python3 -c "
import json, sys
print(json.dumps({
    'dataset': sys.argv[1],
    'timeRange': {'from': sys.argv[2], 'to': sys.argv[3]},
    'pagination': {'limit': 50, 'offset': 0}
}))
" "$dataset" "$from" "$to")"
    local resp; resp="$(dash0_mcp_query getMetricCatalog "$params")"
    python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    t = d["result"]["content"][0]["text"]
    for l in t.split("\n"):
        if l.startswith("| ") and not l.startswith("| :") and "Name" not in l:
            name = l.split("|")[1].strip()
            if name: print(name)
except Exception:
    sys.exit(3)
' <<< "$resp"
}

# Return distinct log bodies in the window for this service.
dash0_logs_bodies() {
    local from="$1" to="$2"
    local dataset; dataset="$(dash0_dataset)"
    local params
    params="$(python3 -c "
import json, sys
print(json.dumps({
    'attributeKey': 'otel.log.body',
    'scope': 'logs',
    'dataset': sys.argv[1],
    'timeRange': {'from': sys.argv[2], 'to': sys.argv[3]},
    'filters': [{'key': 'service.name', 'operator': 'is', 'value': sys.argv[4]}],
    'pagination': {'limit': 50, 'offset': 0}
}))
" "$dataset" "$from" "$to" "$SERVICE_NAME")"
    local resp; resp="$(dash0_mcp_query getAttributeValues "$params")"
    python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    if d.get("result", {}).get("content", []):
        t = d["result"]["content"][0]["text"]
        for l in t.split("\n"):
            l = l.strip()
            if l and not l.startswith("Showing") and not l.startswith("#"):
                print(l)
except Exception:
    sys.exit(3)
' <<< "$resp"
}

# Return distinct span names in the window for this service.
dash0_span_names() {
    local from="$1" to="$2"
    local dataset; dataset="$(dash0_dataset)"
    local params
    params="$(python3 -c "
import json, sys
print(json.dumps({
    'attributeKey': 'otel.span.name',
    'scope': 'spans',
    'dataset': sys.argv[1],
    'timeRange': {'from': sys.argv[2], 'to': sys.argv[3]},
    'filters': [{'key': 'service.name', 'operator': 'is', 'value': sys.argv[4]}],
    'pagination': {'limit': 50, 'offset': 0}
}))
" "$dataset" "$from" "$to" "$SERVICE_NAME")"
    local resp; resp="$(dash0_mcp_query getAttributeValues "$params")"
    python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    if d.get("result", {}).get("content", []):
        t = d["result"]["content"][0]["text"]
        for l in t.split("\n"):
            l = l.strip()
            if l and not l.startswith("Showing") and not l.startswith("#"):
                print(l)
except Exception:
    sys.exit(3)
' <<< "$resp"
}

# Count spans with a non-empty otel.parent.id (proxy for child spans).
dash0_span_parent_count() {
    local from="$1" to="$2"
    local dataset; dataset="$(dash0_dataset)"
    local params
    params="$(python3 -c "
import json, sys
print(json.dumps({
    'dataset': sys.argv[1],
    'timeRange': {'from': sys.argv[2], 'to': sys.argv[3]},
    'filters': [
        {'key': 'service.name', 'operator': 'is', 'value': sys.argv[4]},
        {'key': 'otel.parent.id', 'operator': 'is_set'}
    ],
    'pagination': {'limit': 50}
}))
" "$dataset" "$from" "$to" "$SERVICE_NAME")"
    dash0_row_count "$(dash0_mcp_query getSpans "$params")"
}

# --- Colourful logging ---
log()  { printf "\033[1;34m==\033[0m %s\n" "$*"; }
ok()   { printf "\033[1;32m✓\033[0m %s\n" "$*"; }
fail() { printf "\033[1;31m✗\033[0m %s\n" "$*"; exit 2; }
warn() { printf "\033[1;33m!\033[0m %s\n" "$*"; }
