#!/usr/bin/env bash
# Shared: boot the iOS simulator, build the AstronomyShopUITests target,
# run the journey UITest (real XCUIApplication taps on SwiftUI buttons),
# and return start/end ISO timestamps as `IOS_SCENARIO_START` /
# `IOS_SCENARIO_END` in the caller's environment.
#
# Replaces the legacy `SIMCTL_CHILD_DASH0_AUTO_DEMO=1` Timer-driven path —
# every emission now originates from a real synthetic UITouch event
# delivered by XCTest, matching Android's `monkey` semantics.
#
# Usage from a scenario script:
#   source "$SCRIPT_DIR/lib-ios/run-astronomy-demo.sh"
#   run_astronomy_demo_window   # boot + build + UITest + wait
#   dash0_log_count "$IOS_SCENARIO_START" "$IOS_SCENARIO_END"

set -euo pipefail

_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$_HERE/dash0-mcp.sh"

# Scenario scripts live at scripts/test/validate-ios-us*.sh so the demo
# root is 3 levels up.
ASTRONOMY_ROOT_DEFAULT="$(cd "$_HERE/../../../examples/upstream-demo-app-ios" 2>/dev/null && pwd || echo "")"
ASTRONOMY_ROOT="${ASTRONOMY_ROOT:-$ASTRONOMY_ROOT_DEFAULT}"

CONFIG_PATH="${CONFIG_PATH:-$ASTRONOMY_ROOT/AstronomyShop/otel-config.json}"
BUNDLE_ID="${BUNDLE_ID:-com.dash0.mobile.demo.AstronomyShop}"
HOST_SCHEME="${HOST_SCHEME:-AstronomyShop}"
UITEST_SCHEME="${UITEST_SCHEME:-AstronomyShopUITests}"
SERVICE_NAME="${SERVICE_NAME:-otel-ios-astronomy-shop}"
DATASET="$(dash0_dataset 2>/dev/null || echo otel-mobile)"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
# How long the in-test journey loop runs. Total wall-clock = this +
# ~30-40s of xcodebuild / Xcode setup overhead.
JOURNEY_DURATION_SECONDS="${JOURNEY_DURATION_SECONDS:-${OBSERVE_SECONDS:-45}}"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

_cleanup_trap() {
    xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true
}
trap _cleanup_trap EXIT INT TERM

run_astronomy_demo_window() {
    if [[ ! -f "$CONFIG_PATH" ]]; then
        echo "missing $CONFIG_PATH" >&2
        exit 1
    fi

    log "Setup: boot simulator '$SIM_NAME'"
    if ! xcrun simctl list devices booted 2>/dev/null | grep -q "$SIM_NAME"; then
        xcrun simctl boot "$SIM_NAME"
        sleep 4
    fi
    ok "Simulator booted"

    log "Setup: regenerate xcodeproj (xcodegen)"
    (cd "$ASTRONOMY_ROOT" && /opt/homebrew/bin/xcodegen generate >/dev/null)
    ok "Project generated"

    IOS_SCENARIO_START="$(iso_now)"
    log "Run: xcodebuild test (${UITEST_SCHEME}, ${JOURNEY_DURATION_SECONDS}s journey budget) at ${IOS_SCENARIO_START}"
    local log_file
    log_file="$(mktemp -t astronomy-uitest.XXXXXX)"
    (
        cd "$ASTRONOMY_ROOT"
        JOURNEY_DURATION_SECONDS="$JOURNEY_DURATION_SECONDS" \
            xcodebuild test \
                -scheme "$UITEST_SCHEME" \
                -destination "platform=iOS Simulator,name=$SIM_NAME" \
                -derivedDataPath "$ASTRONOMY_ROOT/build" \
                -only-testing:"$UITEST_SCHEME/AstronomyShopJourneyUITest/testJourneyLoop"
    ) >"$log_file" 2>&1 || {
        echo "--- xcodebuild test summary ---" >&2
        grep -E "Test Case|passed|failed|error" "$log_file" | head -20 >&2
        echo "Full log: $log_file" >&2
        exit 2
    }
    IOS_SCENARIO_END="$(iso_now)"
    local journey_count
    journey_count="$(grep -oE "Completed [0-9]+ journey iterations" "$log_file" | grep -oE '[0-9]+' | head -1 || echo 0)"
    ok "UITest passed (${journey_count} journey iterations) — ${IOS_SCENARIO_START} → ${IOS_SCENARIO_END}"
}
