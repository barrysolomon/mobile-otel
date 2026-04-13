#!/usr/bin/env bash
# Validate that telemetry reached Dash0 by querying the Prometheus API.
#
# Uses dash0_logs_total and dash0_spans_total synthetic metrics to confirm
# that the mobile SDK exported data to Dash0 in the expected time window.
#
# Prerequisites:
#   - otel-config.json with valid Dash0 credentials
#   - Telemetry recently sent (app was pointed at Dash0, not local collector)
#
# Usage:
#   ./validate-dash0.sh                  # check last 15 minutes
#   ./validate-dash0.sh --window 30      # check last 30 minutes
#   ./validate-dash0.sh --service X      # override service_name filter
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"

# ── Parse arguments ──────────────────────────────────────────────────────────

WINDOW_MINUTES=15
SERVICE_NAME=""

for arg in "$@"; do
  case "$arg" in
    --window) shift; WINDOW_MINUTES="${1:-15}" ;;
    --service) shift; SERVICE_NAME="${1:-}" ;;
    [0-9]*) WINDOW_MINUTES="$arg" ;;
  esac
  shift 2>/dev/null || true
done

# ── Read Dash0 credentials from otel-config.json ────────────────────────────

REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIG="$REPO_ROOT/examples/demo-app/android/src/debug/assets/otel-config.json"

if [ ! -f "$CONFIG" ]; then
  err "Dash0 config not found at $CONFIG"
  err "Copy from .json.template and fill in credentials"
  exit 1
fi

INGRESS=$(jq -r '.collectorEndpoint // empty' "$CONFIG" | sed 's|:4317||')
AUTH_TOKEN=$(jq -r '.headers.Authorization // empty' "$CONFIG")
DATASET=$(jq -r '.headers["Dash0-Dataset"] // "otel-mobile"' "$CONFIG")

if [ -z "$INGRESS" ] || [ -z "$AUTH_TOKEN" ]; then
  err "Missing collectorEndpoint or Authorization in $CONFIG"
  exit 1
fi

# Derive API base from ingress (ingress.region.dash0.com → api.region.dash0.com)
API_BASE=$(echo "$INGRESS" | sed 's|ingress\.|api.|')

if [ -z "$SERVICE_NAME" ]; then
  SERVICE_NAME="otel-mobile-demo"
fi

echo ""
log "Validating telemetry in Dash0"
echo "   API:      $API_BASE"
echo "   Dataset:  $DATASET"
echo "   Service:  $SERVICE_NAME"
echo "   Window:   last ${WINDOW_MINUTES}m"
echo ""

# ── Helper: query Dash0 Prometheus API ───────────────────────────────────────

dash0_query() {
  local query="$1"
  curl -sf -G \
    -H "Authorization: $AUTH_TOKEN" \
    -H "Dash0-Dataset: $DATASET" \
    --data-urlencode "query=$query" \
    "$API_BASE/api/prometheus/api/v1/query" 2>/dev/null
}

dash0_query_value() {
  local query="$1"
  local result
  result=$(dash0_query "$query")
  echo "$result" | jq -r '.data.result[0].value[1] // "0"' 2>/dev/null
}

dash0_query_labels() {
  local query="$1"
  dash0_query "$query" | jq -r '.data.result[0].metric' 2>/dev/null
}

# ── Counters ─────────────────────────────────────────────────────────────────

PASS=0; FAIL=0; WARN=0

dash0_assert() {
  local description="$1"
  local query="$2"
  local min_value="${3:-1}"
  local required="${4:-true}"

  local value
  value=$(dash0_query_value "$query")

  # Handle scientific notation (e.g., 3.5e+01 → 35)
  local int_value
  int_value=$(echo "$value" | awk '{printf "%d", $1}')

  if [ "$int_value" -ge "$min_value" ]; then
    ok "$description ($int_value events)"
    PASS=$((PASS + 1))
  elif [ "$required" = "true" ]; then
    err "$description — expected >= $min_value, got $int_value"
    FAIL=$((FAIL + 1))
  else
    warn "$description — $int_value events (optional)"
    WARN=$((WARN + 1))
  fi
}

dash0_assert_label() {
  local description="$1"
  local query="$2"
  local label="$3"
  local expected_value="${4:-}"

  local labels
  labels=$(dash0_query_labels "$query")
  local actual
  actual=$(echo "$labels" | jq -r ".\"$label\" // empty" 2>/dev/null)

  if [ -z "$actual" ]; then
    err "$description — label '$label' not found"
    FAIL=$((FAIL + 1))
  elif [ -n "$expected_value" ] && [ "$actual" != "$expected_value" ]; then
    err "$description — expected '$expected_value', got '$actual'"
    FAIL=$((FAIL + 1))
  else
    ok "$description ($actual)"
    PASS=$((PASS + 1))
  fi
}

# ── Validate ─────────────────────────────────────────────────────────────────

log "Log signals"

dash0_assert \
  "Logs received from $SERVICE_NAME" \
  "increase(dash0_logs_total{service_name=\"$SERVICE_NAME\"}[${WINDOW_MINUTES}m])" \
  1

log "Span signals"

dash0_assert \
  "Spans received from $SERVICE_NAME" \
  "increase(dash0_spans_total{service_name=\"$SERVICE_NAME\"}[${WINDOW_MINUTES}m])" \
  1 \
  false

log "Resource identity"

RESOURCE_QUERY="dash0_logs_total{service_name=\"$SERVICE_NAME\"}"

dash0_assert_label \
  "service.name resource attribute" \
  "$RESOURCE_QUERY" \
  "service_name" \
  "$SERVICE_NAME"

dash0_assert_label \
  "device.id resource attribute" \
  "$RESOURCE_QUERY" \
  "device_id"

dash0_assert_label \
  "device.platform resource attribute" \
  "$RESOURCE_QUERY" \
  "device_platform" \
  "android"

dash0_assert_label \
  "os.name resource attribute" \
  "$RESOURCE_QUERY" \
  "os_name" \
  "android"

dash0_assert_label \
  "telemetry.sdk.name resource attribute" \
  "$RESOURCE_QUERY" \
  "telemetry_sdk_name" \
  "opentelemetry"

# ── Summary ──────────────────────────────────────────────────────────────────

echo ""
echo "══════════════════════════════════════"
echo "  Passed:  $PASS"
echo "  Failed:  $FAIL"
echo "  Warned:  $WARN (optional signals)"
echo "══════════════════════════════════════"
echo ""

if [ "$FAIL" -gt 0 ]; then
  err "$FAIL required signal(s) missing!"
  exit 1
else
  ok "All required Dash0 signals validated!"
fi
