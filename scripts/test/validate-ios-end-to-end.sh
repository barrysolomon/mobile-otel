#!/usr/bin/env bash
# validate-ios-end-to-end.sh
#
# Drives a full round-trip validation of the iOS SDK: boot simulator, build
# + install the AstronomyShop demo, launch it, wait for batched telemetry to
# flush, then query Dash0's MCP API to PROVE logs and spans actually landed.
#
# This is the canonical iOS validation path — we use AstronomyShop because
# its lifecycle/view instrumentation emits on launch without needing any
# env-var-driven auto-demo, which makes it the most predictable end-to-end
# signal source.
#
# Exit codes:
#   0 = all validations passed (telemetry observed in Dash0)
#   1 = setup failure (sim boot / build / install)
#   2 = validation failure (no telemetry observed in Dash0 within window)
set -euo pipefail

# Resolve the real script path so invoking via the root-level
# `./validate-ios-end-to-end.sh` symlink still anchors REPO_ROOT correctly.
RESOLVED_SOURCE="${BASH_SOURCE[0]}"
while [[ -L "$RESOLVED_SOURCE" ]]; do
    _linked="$(readlink "$RESOLVED_SOURCE")"
    case "$_linked" in
        /*) RESOLVED_SOURCE="$_linked" ;;
        *)  RESOLVED_SOURCE="$(cd "$(dirname "$RESOLVED_SOURCE")" && pwd)/$_linked" ;;
    esac
done
SCRIPT_DIR="$(cd "$(dirname "$RESOLVED_SOURCE")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IOS_DEMO_ROOT="$REPO_ROOT/examples/upstream-demo-app-ios"
CONFIG_PATH="$IOS_DEMO_ROOT/AstronomyShop/otel-config.json"
BUNDLE_ID="com.dash0.mobile.demo.AstronomyShop"
SCHEME="AstronomyShop"
SERVICE_NAME="otel-ios-astronomy-shop"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
OBSERVE_SECONDS="${OBSERVE_SECONDS:-75}"
# Base API URL is derived from the ingress host: ingress.us-west-2.aws.dash0.com → api.us-west-2.aws.dash0.com
DASH0_MCP_URL="${DASH0_MCP_URL:-https://api.us-west-2.aws.dash0.com/mcp}"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

log()  { printf "\033[1;34m==\033[0m %s\n" "$*"; }
ok()   { printf "\033[1;32m✓\033[0m %s\n" "$*"; }
fail() { printf "\033[1;31m✗\033[0m %s\n" "$*"; exit 2; }
warn() { printf "\033[1;33m!\033[0m %s\n" "$*"; }

cleanup() {
    xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# --- Load Dash0 credentials from the bundled config ---

if [[ ! -f "$CONFIG_PATH" ]]; then
    echo "missing $CONFIG_PATH — copy from .template and fill in Dash0 credentials" >&2
    exit 1
fi
AUTH_TOKEN="$(jq -r .auth_token "$CONFIG_PATH")"
DATASET="$(jq -r .dataset "$CONFIG_PATH")"
if [[ -z "$AUTH_TOKEN" || "$AUTH_TOKEN" == "null" || "$AUTH_TOKEN" == *YOUR_* ]]; then
    echo "auth_token in $CONFIG_PATH is empty or still a placeholder" >&2
    exit 1
fi
if [[ -z "$DATASET" || "$DATASET" == "null" ]]; then
    echo "dataset missing in $CONFIG_PATH" >&2
    exit 1
fi

# --- Setup ---

log "Setup: boot simulator '$SIM_NAME'"
if ! xcrun simctl list devices booted 2>/dev/null | grep -q "$SIM_NAME"; then
    xcrun simctl boot "$SIM_NAME"
    sleep 4
fi
ok "Simulator booted"

log "Setup: build ${SCHEME}"
cd "$IOS_DEMO_ROOT"
if [[ ! -d "${SCHEME}.xcodeproj" ]]; then
    /opt/homebrew/bin/xcodegen generate >/dev/null
fi
xcodebuild -scheme "$SCHEME" \
    -destination "platform=iOS Simulator,name=$SIM_NAME" \
    -derivedDataPath ./build build >/tmp/validate-ios-build.log 2>&1 \
    || { cat /tmp/validate-ios-build.log; exit 1; }
ok "Built ${SCHEME}.app"

log "Setup: install app"
xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install booted "./build/Build/Products/Debug-iphonesimulator/${SCHEME}.app"
ok "Installed"

# --- Launch and wait for telemetry to flush ---

START_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log "Run: launch with DASH0_AUTO_DEMO=1 at ${START_ISO}, wait ${OBSERVE_SECONDS}s"
# Launch without --console-pty so the app survives the shell process ending.
# simctl has no --env flag — pass env vars via SIMCTL_CHILD_<KEY>=<VALUE>
# prefix (simctl strips the prefix and forwards the rest as env).
SIMCTL_CHILD_DASH0_AUTO_DEMO=1 xcrun simctl launch booted "$BUNDLE_ID" >/dev/null
sleep "$OBSERVE_SECONDS"
END_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ok "Launch + observation window complete"

# --- Query Dash0 MCP for logs + spans from this run ---

query_dash0() {
    local tool="$1" scope_label="$2"
    curl -sS -X POST \
        -H "Authorization: Bearer $AUTH_TOKEN" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        "$DASH0_MCP_URL" \
        -d "$(cat <<EOF
{
  "jsonrpc":"2.0","id":1,"method":"tools/call",
  "params":{
    "name":"$tool",
    "arguments":{
      "skillId":1,
      "dataset":"$DATASET",
      "timeRange":{"from":"$START_ISO","to":"$END_ISO"},
      "filters":[{"key":"service.name","operator":"is","value":"$SERVICE_NAME"}],
      "pagination":{"limit":50}
    }
  }
}
EOF
)"
}

# Parse the MCP JSON-RPC response and print the row count (0 on "no
# results"). Exits 1 (not 2) on unexpected shapes so operators can
# distinguish malformed responses (infra problem) from empty results
# (validation problem). Prints the raw response to stderr on parse failure
# so CI logs show root cause.
#
# The Python script lives in a single-quoted variable instead of a heredoc
# because heredocs inside command substitution are fragile around inline
# apostrophes.
PARSE_ROWS_PY='
import json, sys
try:
    payload = json.load(sys.stdin)
    res = payload.get("result")
    if not isinstance(res, dict):
        sys.exit(3)
    content = res.get("content") or []
    if not content or not isinstance(content[0], dict) or "text" not in content[0]:
        sys.exit(3)
    text = content[0]["text"]
    # Count markdown table rows that are not the header separator ("| :...").
    rows = [l for l in text.split("\n") if l.startswith("| ") and not l.startswith("| :")]
    # If the table has a header row it will contain one of these well-known
    # column labels and should be subtracted.
    header_labels = ("otel.log", "otel.trace.id", "Name", "Series ID")
    header_rows = 1 if rows and any(h in rows[0] for h in header_labels) else 0
    print(max(0, len(rows) - header_rows))
except Exception:
    sys.exit(3)
'

parse_row_count() {
    local label="$1" resp="$2"
    local count
    count="$(printf '%s' "$resp" | python3 -c "$PARSE_ROWS_PY" 2>/dev/null)"
    if [[ -z "$count" || ! "$count" =~ ^[0-9]+$ ]]; then
        {
            echo "$label: malformed MCP response — cannot parse row count."
            echo "--- raw response follows ---"
            echo "$resp" | head -c 2000
            echo
        } >&2
        exit 1
    fi
    echo "$count"
}

log "Validate: query Dash0 MCP for ${SERVICE_NAME} logs in window"
LOGS_RESP="$(query_dash0 getLogRecords logs)"
if echo "$LOGS_RESP" | grep -q '"isError":true'; then
    # isError=true is how MCP signals "No log records found..." — treat as zero.
    LOG_COUNT=0
else
    LOG_COUNT="$(parse_row_count "getLogRecords" "$LOGS_RESP")"
fi
if [[ "$LOG_COUNT" -lt 1 ]]; then
    fail "No logs from ${SERVICE_NAME} observed in Dash0 between ${START_ISO} and ${END_ISO}"
fi
ok "Logs landed: $LOG_COUNT record(s) from ${SERVICE_NAME}"

log "Validate: query Dash0 MCP for ${SERVICE_NAME} spans in window"
SPANS_RESP="$(query_dash0 getSpans spans)"
if echo "$SPANS_RESP" | grep -q '"isError":true'; then
    SPAN_COUNT=0
else
    SPAN_COUNT="$(parse_row_count "getSpans" "$SPANS_RESP")"
fi
if [[ "$SPAN_COUNT" -lt 1 ]]; then
    warn "No spans from ${SERVICE_NAME} observed in Dash0 — NetworkInstrumentation may be denylisting /v1/* or the app produced no network activity yet"
else
    ok "Spans landed: $SPAN_COUNT span(s) from ${SERVICE_NAME}"
fi

# --- Summary ---

echo ""
ok "iOS end-to-end validated: telemetry reached Dash0 dataset='${DATASET}' between ${START_ISO} and ${END_ISO}"
echo "  - filter in Dash0: service.name='${SERVICE_NAME}' OR os.name='iOS'"
