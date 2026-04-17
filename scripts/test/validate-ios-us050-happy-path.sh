#!/usr/bin/env bash
# US-050 (iOS): Happy path — AstronomyShop auto-demo produces telemetry
# across all three signal types (logs, spans, metrics) within a 75-second
# observation window. Mirrors Android's scripts/test/validate-us050-happy-path.sh
# but asserts against Dash0 via MCP rather than a local collector dump.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib-ios/run-astronomy-demo.sh"

log "US-050 (iOS): AstronomyShop happy path"
run_astronomy_demo_window

log "Assert: logs arrived"
LOG_COUNT="$(dash0_log_count "$IOS_SCENARIO_START" "$IOS_SCENARIO_END")"
if [[ "$LOG_COUNT" -lt 5 ]]; then
    fail "expected >= 5 logs, got $LOG_COUNT"
fi
ok "Logs: $LOG_COUNT"

log "Assert: spans arrived"
SPAN_COUNT="$(dash0_span_count "$IOS_SCENARIO_START" "$IOS_SCENARIO_END")"
if [[ "$SPAN_COUNT" -lt 10 ]]; then
    fail "expected >= 10 spans, got $SPAN_COUNT"
fi
ok "Spans: $SPAN_COUNT"

log "Assert: custom shop.* metrics present"
# Metric catalog is dataset-wide so we query a wider window to tolerate the
# periodic reader's 10s cadence landing just outside the tight run window.
MCAT_FROM="$(iso_minus_min 5)"
MCAT_TO="$(iso_now)"
METRICS="$(dash0_metric_names "$MCAT_FROM" "$MCAT_TO" | grep -E 'shop\.(cart|checkout|view_product)' || true)"
if [[ -z "$METRICS" ]]; then
    fail "expected shop.* metrics in Dash0 catalog; found none"
fi
echo "$METRICS" | sed 's/^/  /'
ok "Custom metrics landed"

ok "US-050 (iOS) PASS"
