#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
# Dash0 End-to-End Telemetry Validation
#
# Sends real OTLP data to Dash0, waits for propagation, then queries
# Dash0 CLI to verify the data arrived correctly.
#
# Requirements:
#   - Dash0 CLI (dash0 binary in PATH or DASH0_CLI_PATH)
#   - DASH0_API_URL, DASH0_AUTH_TOKEN, DASH0_DATASET env vars
#   - curl, jq
#
# Usage:
#   ./dash0-e2e-test.sh
# ═══════════════════════════════════════════════════════════════════════
set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────
DASH0_CLI="${DASH0_CLI_PATH:-/tmp/dash0-cli/dash0}"
DASH0_INGRESS="${DASH0_ENDPOINT:-https://ingress.us-west-2.aws.dash0.com:4318}"
DASH0_TOKEN="${DASH0_AUTH_TOKEN:?Set DASH0_AUTH_TOKEN env var}"
DASH0_DS="${DASH0_DATASET:-otel-mobile}"
QUERY_DELAY="${DASH0_QUERY_DELAY_SEC:-45}"
MAX_RETRIES=3
RETRY_INTERVAL=15

# Unique run ID to isolate this test's data
RUN_ID="e2e-$(date +%s)-$$"
TIMESTAMP_NS=$(python3 -c "import time; print(int(time.time() * 1e9))")

PASS=0
FAIL=0
TOTAL=0

# ── Helpers ───────────────────────────────────────────────────────────
red()   { printf "\033[31m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
bold()  { printf "\033[1m%s\033[0m\n" "$*"; }

assert_eq() {
    local label="$1" expected="$2" actual="$3"
    TOTAL=$((TOTAL + 1))
    if [ "$expected" = "$actual" ]; then
        green "  PASS: $label"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $label (expected '$expected', got '$actual')"
        FAIL=$((FAIL + 1))
    fi
}

assert_gt() {
    local label="$1" threshold="$2" actual="$3"
    TOTAL=$((TOTAL + 1))
    if [ "$actual" -gt "$threshold" ] 2>/dev/null; then
        green "  PASS: $label ($actual > $threshold)"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $label (expected > $threshold, got '$actual')"
        FAIL=$((FAIL + 1))
    fi
}

assert_contains() {
    local label="$1" haystack="$2" needle="$3"
    TOTAL=$((TOTAL + 1))
    if echo "$haystack" | grep -q "$needle"; then
        green "  PASS: $label"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $label (expected to contain '$needle')"
        FAIL=$((FAIL + 1))
    fi
}

dash0_query_logs() {
    local filter="$1"
    "$DASH0_CLI" -X logs query --from now-5m --filter "$filter" --output json --limit 100 2>/dev/null
}

dash0_query_spans() {
    local filter="$1"
    "$DASH0_CLI" -X spans query --from now-5m --filter "$filter" --output json --limit 100 2>/dev/null
}

wait_for_propagation() {
    bold "  Waiting ${QUERY_DELAY}s for Dash0 ingestion propagation..."
    sleep "$QUERY_DELAY"
}

# ── Pre-flight checks ────────────────────────────────────────────────
bold "═══ Dash0 E2E Validation Suite ═══"
bold "Run ID: $RUN_ID"
echo ""

if ! command -v "$DASH0_CLI" &>/dev/null; then
    red "ERROR: Dash0 CLI not found at $DASH0_CLI"
    red "Install: curl -sL https://github.com/dash0hq/dash0-cli/releases/latest/download/dash0_*_macos_arm64.tar.gz | tar xz"
    exit 1
fi

if ! command -v jq &>/dev/null; then
    red "ERROR: jq not found. Install: brew install jq"
    exit 1
fi

bold "Dash0 CLI: $($DASH0_CLI --version 2>&1)"
echo "Ingress:   $DASH0_INGRESS"
echo "Dataset:   $DASH0_DS"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# TEST 1: Send a log event via OTLP HTTP and verify it arrives in Dash0
# ═══════════════════════════════════════════════════════════════════════
bold "── TEST 1: Log Event Round-Trip ──"

# Send a log via OTLP HTTP /v1/logs
OTLP_LOG_PAYLOAD=$(cat <<EOJSON
{
  "resourceLogs": [{
    "resource": {
      "attributes": [
        {"key": "service.name", "value": {"stringValue": "mobile-e2e-test"}},
        {"key": "test.run_id", "value": {"stringValue": "$RUN_ID"}}
      ]
    },
    "scopeLogs": [{
      "scope": {"name": "e2e-test"},
      "logRecords": [{
        "timeUnixNano": "$TIMESTAMP_NS",
        "severityNumber": 9,
        "severityText": "INFO",
        "body": {"stringValue": "ui.tap"},
        "attributes": [
          {"key": "test.run_id", "value": {"stringValue": "$RUN_ID"}},
          {"key": "ui.element.id", "value": {"stringValue": "btn_book"}},
          {"key": "screen.name", "value": {"stringValue": "DetailScreen"}},
          {"key": "session.id", "value": {"stringValue": "sess-$RUN_ID"}}
        ]
      },
      {
        "timeUnixNano": "$TIMESTAMP_NS",
        "severityNumber": 13,
        "severityText": "WARN",
        "body": {"stringValue": "ui.freeze"},
        "attributes": [
          {"key": "test.run_id", "value": {"stringValue": "$RUN_ID"}},
          {"key": "mobile.freeze.duration_ms", "value": {"intValue": "1500"}},
          {"key": "screen.name", "value": {"stringValue": "HomeScreen"}}
        ]
      },
      {
        "timeUnixNano": "$TIMESTAMP_NS",
        "severityNumber": 17,
        "severityText": "ERROR",
        "body": {"stringValue": "app.crash"},
        "attributes": [
          {"key": "test.run_id", "value": {"stringValue": "$RUN_ID"}},
          {"key": "error.type", "value": {"stringValue": "NullPointerException"}},
          {"key": "error.message", "value": {"stringValue": "Booking failed"}}
        ]
      }]
    }]
  }]
}
EOJSON
)

echo "Sending 3 log events (ui.tap, ui.freeze, app.crash) to Dash0..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${DASH0_INGRESS}/v1/logs" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${DASH0_TOKEN}" \
    -H "Dash0-Dataset: ${DASH0_DS}" \
    -d "$OTLP_LOG_PAYLOAD")

assert_eq "OTLP log export HTTP status" "200" "$HTTP_STATUS"

# ═══════════════════════════════════════════════════════════════════════
# TEST 2: Send a span via OTLP HTTP and verify it arrives in Dash0
# ═══════════════════════════════════════════════════════════════════════
bold "── TEST 2: Span Round-Trip ──"

TRACE_ID=$(python3 -c "import uuid; print(uuid.uuid4().hex)")
SPAN_ID=$(python3 -c "import uuid; print(uuid.uuid4().hex[:16])")
END_NS=$(python3 -c "import time; print(int(time.time() * 1e9) + 340000000)")

OTLP_SPAN_PAYLOAD=$(cat <<EOJSON
{
  "resourceSpans": [{
    "resource": {
      "attributes": [
        {"key": "service.name", "value": {"stringValue": "mobile-e2e-test"}},
        {"key": "test.run_id", "value": {"stringValue": "$RUN_ID"}}
      ]
    },
    "scopeSpans": [{
      "scope": {"name": "e2e-test"},
      "spans": [{
        "traceId": "$TRACE_ID",
        "spanId": "$SPAN_ID",
        "name": "GET /api/appointments",
        "kind": 3,
        "startTimeUnixNano": "$TIMESTAMP_NS",
        "endTimeUnixNano": "$END_NS",
        "attributes": [
          {"key": "test.run_id", "value": {"stringValue": "$RUN_ID"}},
          {"key": "http.request.method", "value": {"stringValue": "GET"}},
          {"key": "http.response.status_code", "value": {"intValue": "200"}},
          {"key": "url.full", "value": {"stringValue": "https://api.example.com/api/appointments"}},
          {"key": "server.address", "value": {"stringValue": "api.example.com"}}
        ],
        "status": {"code": 1}
      }]
    }]
  }]
}
EOJSON
)

echo "Sending 1 span (GET /api/appointments) to Dash0..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${DASH0_INGRESS}/v1/traces" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${DASH0_TOKEN}" \
    -H "Dash0-Dataset: ${DASH0_DS}" \
    -d "$OTLP_SPAN_PAYLOAD")

assert_eq "OTLP span export HTTP status" "200" "$HTTP_STATUS"

# ═══════════════════════════════════════════════════════════════════════
# Wait for Dash0 to ingest and index the data
# ═══════════════════════════════════════════════════════════════════════
wait_for_propagation

# ═══════════════════════════════════════════════════════════════════════
# TEST 3: Query Dash0 to verify logs arrived
# ═══════════════════════════════════════════════════════════════════════
bold "── TEST 3: Verify Logs in Dash0 ──"

LOG_RESULT=$(dash0_query_logs "test.run_id is $RUN_ID" || echo '{"resourceLogs":[]}')

# Count log records
LOG_COUNT=$(echo "$LOG_RESULT" | jq '[.resourceLogs[]?.scopeLogs[]?.logRecords[]?] | length' 2>/dev/null || echo "0")
assert_eq "3 log events received by Dash0" "3" "$LOG_COUNT"

# Verify specific event bodies
TAP_COUNT=$(echo "$LOG_RESULT" | jq '[.resourceLogs[]?.scopeLogs[]?.logRecords[]? | select(.body.stringValue == "ui.tap")] | length' 2>/dev/null || echo "0")
assert_eq "ui.tap event present" "1" "$TAP_COUNT"

FREEZE_COUNT=$(echo "$LOG_RESULT" | jq '[.resourceLogs[]?.scopeLogs[]?.logRecords[]? | select(.body.stringValue == "ui.freeze")] | length' 2>/dev/null || echo "0")
assert_eq "ui.freeze event present" "1" "$FREEZE_COUNT"

CRASH_COUNT=$(echo "$LOG_RESULT" | jq '[.resourceLogs[]?.scopeLogs[]?.logRecords[]? | select(.body.stringValue == "app.crash")] | length' 2>/dev/null || echo "0")
assert_eq "app.crash event present" "1" "$CRASH_COUNT"

# Verify attributes on ui.tap
TAP_ELEMENT=$(echo "$LOG_RESULT" | jq -r '.resourceLogs[]?.scopeLogs[]?.logRecords[]? | select(.body.stringValue == "ui.tap") | .attributes[]? | select(.key == "ui.element.id") | .value.stringValue' 2>/dev/null || echo "")
assert_eq "ui.tap has ui.element.id=btn_book" "btn_book" "$TAP_ELEMENT"

TAP_SCREEN=$(echo "$LOG_RESULT" | jq -r '.resourceLogs[]?.scopeLogs[]?.logRecords[]? | select(.body.stringValue == "ui.tap") | .attributes[]? | select(.key == "screen.name") | .value.stringValue' 2>/dev/null || echo "")
assert_eq "ui.tap has screen.name=DetailScreen" "DetailScreen" "$TAP_SCREEN"

# Verify crash attributes
CRASH_TYPE=$(echo "$LOG_RESULT" | jq -r '.resourceLogs[]?.scopeLogs[]?.logRecords[]? | select(.body.stringValue == "app.crash") | .attributes[]? | select(.key == "error.type") | .value.stringValue' 2>/dev/null || echo "")
assert_eq "app.crash has error.type=NullPointerException" "NullPointerException" "$CRASH_TYPE"

# ═══════════════════════════════════════════════════════════════════════
# TEST 4: Query Dash0 to verify span arrived
# ═══════════════════════════════════════════════════════════════════════
bold "── TEST 4: Verify Span in Dash0 ──"

SPAN_RESULT=$(dash0_query_spans "test.run_id is $RUN_ID" || echo '{"resourceSpans":[]}')

SPAN_COUNT=$(echo "$SPAN_RESULT" | jq '[.resourceSpans[]?.scopeSpans[]?.spans[]?] | length' 2>/dev/null || echo "0")
assert_eq "1 span received by Dash0" "1" "$SPAN_COUNT"

SPAN_NAME=$(echo "$SPAN_RESULT" | jq -r '.resourceSpans[]?.scopeSpans[]?.spans[]?.name' 2>/dev/null || echo "")
assert_eq "Span name is GET /api/appointments" "GET /api/appointments" "$SPAN_NAME"

SPAN_METHOD=$(echo "$SPAN_RESULT" | jq -r '.resourceSpans[]?.scopeSpans[]?.spans[]?.attributes[]? | select(.key == "http.request.method") | .value.stringValue' 2>/dev/null || echo "")
assert_eq "Span has http.request.method=GET" "GET" "$SPAN_METHOD"

SPAN_STATUS=$(echo "$SPAN_RESULT" | jq -r '.resourceSpans[]?.scopeSpans[]?.spans[]?.attributes[]? | select(.key == "http.response.status_code") | .value.intValue' 2>/dev/null || echo "")
assert_eq "Span has http.response.status_code=200" "200" "$SPAN_STATUS"

# Dash0 CLI returns traceId as base64; convert our hex to base64 for comparison
EXPECTED_TRACE_B64=$(echo -n "$TRACE_ID" | xxd -r -p | base64)
SPAN_TRACE=$(echo "$SPAN_RESULT" | jq -r '.resourceSpans[]?.scopeSpans[]?.spans[]?.traceId' 2>/dev/null || echo "")
assert_eq "Span has correct traceId" "$EXPECTED_TRACE_B64" "$SPAN_TRACE"

# ═══════════════════════════════════════════════════════════════════════
# TEST 5: Verify service identity and test isolation
# ═══════════════════════════════════════════════════════════════════════
bold "── TEST 5: Service Identity & Isolation ──"

SERVICE_NAME=$(echo "$LOG_RESULT" | jq -r '.resourceLogs[0]?.resource?.attributes[]? | select(.key == "service.name") | .value.stringValue' 2>/dev/null || echo "")
assert_eq "service.name is mobile-e2e-test" "mobile-e2e-test" "$SERVICE_NAME"

RUN_ID_CHECK=$(echo "$LOG_RESULT" | jq -r '.resourceLogs[0]?.resource?.attributes[]? | select(.key == "test.run_id") | .value.stringValue' 2>/dev/null || echo "")
assert_eq "test.run_id matches our run" "$RUN_ID" "$RUN_ID_CHECK"

# ═══════════════════════════════════════════════════════════════════════
# Results
# ═══════════════════════════════════════════════════════════════════════
echo ""
bold "═══════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
    green "ALL $TOTAL TESTS PASSED"
else
    red "$FAIL of $TOTAL TESTS FAILED"
fi
bold "═══════════════════════════════════════"

exit "$FAIL"
