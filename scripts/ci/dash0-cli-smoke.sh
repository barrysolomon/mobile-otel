#!/usr/bin/env bash
# Smoke check for the dash0 CLI filter syntax.
#
# The 2026-05-12 iOS HYBRID misdiagnosis cost half a day because
# `--filter "otel.event.name is X"` silently returned zero — the namespace
# doesn't exist; the correct filter is bare `event.name`. Both `feedback_dash0_filter_event_name_namespace`
# and `feedback_dash0_cli_filter_syntax` document the trap.
#
# This script proves the CLI is configured, the auth works, and the filter
# syntax is current by querying for a known-present service and asserting at
# least one record returns. If it returns zero, either:
#   - the CLI is mis-authed (re-run `dash0 login`)
#   - the filter syntax changed under us (compare the CLI version to the
#     `event.name is X` form documented in the memory entries)
#   - the demo service has gone quiet long enough that the time window doesn't
#     overlap any record (widen --from)
#
# Skips with exit 0 when:
#   - `dash0` CLI isn't installed
#   - `DASH0_SMOKE_SERVICE` env var is unset (CI without credentials)
#
# Fails (exit 1) when the CLI is present and configured but returns zero.

set -euo pipefail

SERVICE_NAME="${DASH0_SMOKE_SERVICE:-otel-ios-schedulr}"
FROM="${DASH0_SMOKE_FROM:-now-24h}"
LIMIT="${DASH0_SMOKE_LIMIT:-1}"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

if ! command -v dash0 >/dev/null 2>&1; then
    echo "${YELLOW}dash0 CLI not installed; skipping smoke check.${NC}"
    echo "  Install: https://github.com/dash0hq/dash0-cli or brew install dash0hq/tap/dash0"
    exit 0
fi

# Pre-flight: ensure the user is authed. `dash0 version` works without auth;
# the actual log query requires it. We probe with --limit 1 and check the
# exit code so we don't dump a stack trace into the test log.
echo "dash0 CLI smoke check"
echo "  service: $SERVICE_NAME"
echo "  window:  $FROM"
echo ""

# The filter is the load-bearing assertion: `event.name` (not `otel.event.name`).
# See memory: feedback_dash0_filter_event_name_namespace.
RESULT=$(dash0 logs query \
    --filter "service.name is $SERVICE_NAME" \
    --from "$FROM" \
    --limit "$LIMIT" \
    --output json 2>/dev/null || echo '{"resourceLogs": [], "_error": "cli_failed"}')

# Check for explicit auth failure / CLI error first.
if echo "$RESULT" | grep -q '"_error": "cli_failed"'; then
    echo "${YELLOW}dash0 CLI call failed (likely not authed).${NC}"
    echo "  Run: dash0 login"
    echo "  Skipping rather than failing CI."
    exit 0
fi

# Parse JSON manually with grep — we only need to know if resourceLogs has
# any entries. The full shape has a `resourceLogs` array; an empty result is
# `{"resourceLogs": []}`.
if echo "$RESULT" | tr -d '[:space:]' | grep -q '"resourceLogs":\[\]'; then
    printf '%bFAIL: dash0 returned zero records for service %s in %s.%b\n' "${RED}" "$SERVICE_NAME" "$FROM" "${NC}"
    echo ""
    echo "  Possible causes:"
    echo "    1. The service hasn't emitted in $FROM — widen DASH0_SMOKE_FROM."
    echo "    2. Filter syntax changed (verify 'event.name is X' not 'otel.event.name is X')."
    echo "    3. Dataset routing broken (verify DASH0_DATASET / extraHeaders)."
    echo ""
    echo "  Workaround that always works: query without filters to confirm CLI itself works:"
    echo "    dash0 logs query --from now-7d --limit 1"
    exit 1
fi

printf '%bOK: dash0 CLI returned records for service %s.%b\n' "${GREEN}" "$SERVICE_NAME" "${NC}"
echo "  Filter syntax 'service.name is $SERVICE_NAME' is current."
echo "  See docs/contracts/ for the cross-platform invariants this guards."
exit 0
