#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
# verify-dash0.sh — "green = data in Dash0" gate, all platforms.
#
# Asserts (via the Dash0 REST API — no `dash0` CLI needed) that the telemetry
# each platform's demo is expected to emit across NORMAL / CRASH / OFFLINE has
# actually arrived in Dash0. Exits non-zero if any required signal is missing,
# so it drops straight into CI or an E2E script as a pass/fail gate.
#
# Run it AFTER driving the demos (manually or via the instrumented scenario
# suites) within the look-back window.
#
# Env:
#   DASH0_AUTH_TOKEN   (required) read-capable token
#   DASH0_API_HOST     default api.us-west-2.aws.dash0.com
#   DASH0_DATASET      default otel-mobile
#
# Usage:
#   ./verify-dash0.sh                       # all platforms, 30m window
#   ./verify-dash0.sh --window-min 60       # wider window
#   ./verify-dash0.sh android-native        # one platform
#   PLATFORMS="android-native rn-ios" ./verify-dash0.sh
# ═══════════════════════════════════════════════════════════════════════
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSERT="$SCRIPT_DIR/dash0_assert.py"
WINDOW_MIN="${DASH0_WINDOW_MIN:-30}"
# Poll Dash0 for up to this long before declaring signals missing — absorbs
# ingestion latency instead of failing a query that ran too early.
RETRY_FOR="${DASH0_RETRY_FOR:-90}"
# Run correlation: only count telemetry emitted after this epoch instant
# (export DASH0_SINCE=$(date +%s) before driving the demos). Without it,
# leftovers from a previous run inside the window can green a broken SDK.
SINCE="${DASH0_SINCE:-0}"
PLATFORMS_ARG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --window-min) WINDOW_MIN="$2"; shift 2 ;;
    --retry-for)  RETRY_FOR="$2"; shift 2 ;;
    --since)      SINCE="$2"; shift 2 ;;
    *) PLATFORMS_ARG="$PLATFORMS_ARG $1"; shift ;;
  esac
done
PLATFORMS="${PLATFORMS_ARG:-${PLATFORMS:-android-native ios-native rn-android rn-ios}}"

# Load .env (gitignored) if the token isn't already in the environment.
if [ -z "${DASH0_AUTH_TOKEN:-}" ] && [ -f "$SCRIPT_DIR/../../examples/demo-app/.env" ]; then
  set -a; . "$SCRIPT_DIR/../../examples/demo-app/.env"; set +a
fi

# Per-platform expected telemetry. These are the signals each demo emits across
# normal use + a crash + an offline burst; the service names are fixed by each
# demo's SDK init. `:N` sets a minimum count (default 1).
assert_platform() {
  case "$1" in
    android-native)
      python3 "$ASSERT" --retry-for "$RETRY_FOR" --since "$SINCE" --label android-native --window-min "$WINDOW_MIN" \
        --service otel-mobile-demo \
        --log app.start --log ui.tap --log app.crash \
        --span screen.render --span page.CalendarFragment ;;
    ios-native)
      python3 "$ASSERT" --retry-for "$RETRY_FOR" --since "$SINCE" --label ios-native --window-min "$WINDOW_MIN" \
        --service otel-ios-schedulr \
        --log app.start --log app.foreground --log app.crash \
        --span app.startup ;;
    rn-android)
      python3 "$ASSERT" --retry-for "$RETRY_FOR" --since "$SINCE" --label rn-android --window-min "$WINDOW_MIN" \
        --service otel-rn-android-astronomy-shop \
        --log app.start --log ui.screen_view --log app.crash \
        --span screen.render ;;
    rn-ios)
      python3 "$ASSERT" --retry-for "$RETRY_FOR" --since "$SINCE" --label rn-ios --window-min "$WINDOW_MIN" \
        --service otel-rn-ios-astronomy-shop \
        --log app.launch --log app.foreground --log app.crash ;;
    *) echo "Unknown platform: $1 (android-native|ios-native|rn-android|rn-ios)"; return 2 ;;
  esac
}

echo "════════════════════════════════════════════════════════════"
echo "  Dash0 receipt gate — window=${WINDOW_MIN}m  platforms:$PLATFORMS"
echo "════════════════════════════════════════════════════════════"
fails=0
for plat in $PLATFORMS; do
  assert_platform "$plat" || fails=$((fails + 1))
  echo
done

if [ "$fails" -ne 0 ]; then
  echo "GATE FAILED: $fails platform(s) missing expected telemetry in Dash0."
  exit 1
fi
echo "GATE PASSED: all platforms' telemetry confirmed in Dash0."
