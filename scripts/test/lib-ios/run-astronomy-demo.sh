#!/usr/bin/env bash
# Shared: boot the iOS simulator, install AstronomyShop, launch it with
# DASH0_AUTO_DEMO=1, sleep for ${OBSERVE_SECONDS}, and return start/end ISO
# timestamps as `IOS_SCENARIO_START` / `IOS_SCENARIO_END` in the caller's
# environment. The caller is responsible for Dash0 assertions.
#
# Sources `dash0-mcp.sh` helpers, so scenario scripts can just:
#   source "$SCRIPT_DIR/lib-ios/run-astronomy-demo.sh"
#   run_astronomy_demo_window   # boot+install+launch+wait
#   dash0_log_count "$IOS_SCENARIO_START" "$IOS_SCENARIO_END"

set -euo pipefail

_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$_HERE/dash0-mcp.sh"

# Resolve the AstronomyShop demo root. Scenario scripts live at
# scripts/test/validate-ios-us*.sh so the demo root is 3 levels up.
ASTRONOMY_ROOT_DEFAULT="$(cd "$_HERE/../../../examples/upstream-demo-app-ios" 2>/dev/null && pwd || echo "")"
ASTRONOMY_ROOT="${ASTRONOMY_ROOT:-$ASTRONOMY_ROOT_DEFAULT}"

CONFIG_PATH="${CONFIG_PATH:-$ASTRONOMY_ROOT/AstronomyShop/otel-config.json}"
BUNDLE_ID="${BUNDLE_ID:-com.dash0.mobile.demo.AstronomyShop}"
SCHEME="${SCHEME:-AstronomyShop}"
SERVICE_NAME="${SERVICE_NAME:-otel-ios-astronomy-shop}"
DATASET="$(dash0_dataset 2>/dev/null || echo otel-mobile)"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
OBSERVE_SECONDS="${OBSERVE_SECONDS:-75}"

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

    log "Setup: build ${SCHEME}"
    (
        cd "$ASTRONOMY_ROOT"
        if [[ ! -d "${SCHEME}.xcodeproj" ]]; then
            /opt/homebrew/bin/xcodegen generate >/dev/null
        fi
        xcodebuild -scheme "$SCHEME" \
            -destination "platform=iOS Simulator,name=$SIM_NAME" \
            -derivedDataPath ./build build >/tmp/validate-ios-build.log 2>&1 \
            || { cat /tmp/validate-ios-build.log; exit 1; }
    )
    ok "Built ${SCHEME}.app"

    log "Setup: install"
    xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true
    xcrun simctl install booted \
        "$ASTRONOMY_ROOT/build/Build/Products/Debug-iphonesimulator/${SCHEME}.app"
    ok "Installed"

    IOS_SCENARIO_START="$(iso_now)"
    log "Launch at ${IOS_SCENARIO_START}, wait ${OBSERVE_SECONDS}s for telemetry to flush"
    SIMCTL_CHILD_DASH0_AUTO_DEMO=1 xcrun simctl launch booted "$BUNDLE_ID" >/dev/null
    sleep "$OBSERVE_SECONDS"
    IOS_SCENARIO_END="$(iso_now)"
    ok "Observation window complete: ${IOS_SCENARIO_START} → ${IOS_SCENARIO_END}"
}
