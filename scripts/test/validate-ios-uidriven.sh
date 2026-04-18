#!/usr/bin/env bash
# validate-ios-uidriven.sh
#
# UI-driven end-to-end validation. Runs the AstronomyShopUITests journey
# (XCUIApplication taps real SwiftUI buttons → ViewModel state mutates →
# ShopTelemetry emits via the natural user-code path → OTLP/HTTP →
# Dash0), then asserts the expected logs and spans landed in Dash0.
#
# This is the iOS analog of running Android's `monkey` against the
# Astronomy Shop. Both platforms drive the app through real input
# events; neither bypasses the UI.
#
# Replaces the legacy `DASH0_AUTO_DEMO=1`-driven validate script. The
# Timer-based AutoDemoDriver is gone — every emission now originates
# from a synthetic UITouch event delivered by XCTest.
#
# Exit codes:
#   0 = UITest passed AND telemetry observed in Dash0
#   1 = setup failure (sim boot, build, lib missing)
#   2 = telemetry assertion failure (UITest may have passed but Dash0
#       didn't see the expected counts)
set -euo pipefail

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
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib-ios/dash0-mcp.sh"

ASTRONOMY_ROOT="$REPO_ROOT/examples/upstream-demo-app-ios"
CONFIG_PATH="$ASTRONOMY_ROOT/AstronomyShop/otel-config.json"
SCHEME="AstronomyShopUITests"
HOST_SCHEME="AstronomyShop"
SERVICE_NAME="otel-ios-astronomy-shop"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
JOURNEY_DURATION_SECONDS="${JOURNEY_DURATION_SECONDS:-45}"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

if [[ ! -f "$CONFIG_PATH" ]]; then
    echo "missing $CONFIG_PATH — copy from .template and fill in Dash0 credentials" >&2
    exit 1
fi

export SERVICE_NAME

log "Setup: boot simulator '$SIM_NAME'"
if ! xcrun simctl list devices booted 2>/dev/null | grep -q "$SIM_NAME"; then
    xcrun simctl boot "$SIM_NAME"
    sleep 4
fi
ok "Simulator booted"

log "Setup: regenerate xcodeproj (xcodegen — picks up any source/target additions)"
(cd "$ASTRONOMY_ROOT" && /opt/homebrew/bin/xcodegen generate >/dev/null)
ok "Project generated"

START_ISO="$(iso_now)"
log "Run: xcodebuild test (UITest journey, ${JOURNEY_DURATION_SECONDS}s budget) at ${START_ISO}"
# Capture the test output so failures surface a useful summary.
LOG_FILE="$(mktemp -t validate-ios-uidriven.XXXXXX)"
(
    cd "$ASTRONOMY_ROOT"
    JOURNEY_DURATION_SECONDS="$JOURNEY_DURATION_SECONDS" \
        xcodebuild test \
            -scheme "$SCHEME" \
            -destination "platform=iOS Simulator,name=$SIM_NAME" \
            -derivedDataPath "$ASTRONOMY_ROOT/build" \
            -only-testing:"$SCHEME/AstronomyShopJourneyUITest/testJourneyLoop"
) > "$LOG_FILE" 2>&1 || {
    echo "--- xcodebuild test summary ---" >&2
    grep -E "Test Case|passed|failed|error" "$LOG_FILE" | head -20 >&2
    fail "UITest journey did not pass. Full log: $LOG_FILE"
}
JOURNEY_COUNT="$(grep -oE "Completed [0-9]+ journey iterations" "$LOG_FILE" | grep -oE '[0-9]+' | head -1 || echo 0)"
ok "UITest passed ($JOURNEY_COUNT journey iterations)"
END_ISO="$(iso_now)"

# --- Dash0 assertions ---

log "Validate: query Dash0 MCP for ${SERVICE_NAME} logs in window"
LOG_COUNT="$(dash0_log_count "$START_ISO" "$END_ISO")"
# A single journey emits >= 1 cart.add_item + >= 1 shop.view_product.
# 3 iterations * 4 emits = ~12 minimum.
if [[ "$LOG_COUNT" -lt 5 ]]; then
    fail "expected >= 5 logs, got $LOG_COUNT (UITest passed but no telemetry landed?)"
fi
ok "Logs landed: $LOG_COUNT record(s)"

log "Validate: query Dash0 MCP for ${SERVICE_NAME} spans in window"
SPAN_COUNT="$(dash0_span_count "$START_ISO" "$END_ISO")"
# A single checkout emits 14 spans; 3 iterations * 14 + browse spans + catalog = >= 30.
if [[ "$SPAN_COUNT" -lt 10 ]]; then
    fail "expected >= 10 spans, got $SPAN_COUNT"
fi
ok "Spans landed: $SPAN_COUNT span(s)"

# Verify the canonical span names — proves the contract held end-to-end.
log "Validate: contract span names present"
NAMES="$(dash0_span_names "$START_ISO" "$END_ISO")"
MISSING=()
for name in checkout checkout.validate_cart inventory.check_item \
            payment.authorize email.send shop.view_product; do
    grep -qxF "$name" <<< "$NAMES" || MISSING+=("$name")
done
if (( ${#MISSING[@]} > 0 )); then
    printf "  ✗ missing: %s\n" "${MISSING[@]}"
    fail "expected canonical span names not observed"
fi
ok "Canonical span names observed"

echo ""
ok "iOS UI-driven validation passed: telemetry from real taps reached Dash0 dataset='$(dash0_dataset)' between ${START_ISO} and ${END_ISO}"
echo "  - filter in Dash0: service.name='${SERVICE_NAME}' AND dash0.resource.type='mobile'"
