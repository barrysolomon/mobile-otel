#!/usr/bin/env bash
# Dual-platform telemetry demo:
#   - Boots an Android emulator AND an iPhone simulator
#   - Builds + installs + launches the full demo on each (AstronomyShop on
#     iOS via XCUITest, Android :upstream-demo-app driven by `monkey`)
#   - Both platforms run UI-driven: every emission originates from a
#     real synthetic touch event, not from a Timer that bypasses the UI.
#     iOS uses XCUIApplication taps on SwiftUI buttons; Android uses
#     `monkey` on Compose views. Telemetry flows through the same
#     CartViewModel / ShopTelemetry path either way.
#
# What you should see in Dash0:
#   - Filter on service.name =~ "otel-.*-astronomy-shop" + dash0.resource.type="mobile"
#   - Group by os.name — the two platforms' event streams appear side-by-side
#   - iOS: 14-span checkout traces from XCUITest journey loop
#   - Android: matching 14-span checkout traces from monkey-driven flows
#
# Exit clean with Ctrl+C. We kill both apps + optionally shut down the
# emulator/simulator (see --keep-running).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

ANDROID_DEMO_ROOT="$REPO_ROOT/examples/demo-app"
# :upstream-demo-app is the Android Astronomy Shop module included under
# demo-app. It implements the canonical cross-platform shop contract
# (docs/design/shop-telemetry-contract.md) so a single Dash0 dashboard
# can show bit-identical traces from Android + iOS side by side.
ANDROID_GRADLE_TARGET=":upstream-demo-app:installDash0Debug"
ANDROID_PKG="io.opentelemetry.android.demo.dash0"
ANDROID_ACTIVITY=".MainActivity"
IOS_DEMO_ROOT="$REPO_ROOT/examples/upstream-demo-app-ios"
IOS_SCHEME="AstronomyShop"
IOS_BUNDLE="com.dash0.mobile.demo.AstronomyShop"
IOS_SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
ANDROID_AVD="${ANDROID_AVD:-Pixel_7}"

KEEP_RUNNING=0
SKIP_ANDROID=0
SKIP_IOS=0
DURATION_SECONDS=0   # 0 = run until Ctrl+C

while [[ $# -gt 0 ]]; do
    case $1 in
        --keep-running) KEEP_RUNNING=1; shift ;;
        --ios-only) SKIP_ANDROID=1; shift ;;
        --android-only) SKIP_IOS=1; shift ;;
        --duration) DURATION_SECONDS="$2"; shift 2 ;;
        --avd) ANDROID_AVD="$2"; shift 2 ;;
        --sim) IOS_SIM_NAME="$2"; shift 2 ;;
        --help|-h)
            head -25 "$0" | sed 's/^# \?//'
            echo ""
            echo "Options:"
            echo "  --android-only         Skip iOS, run Android demo only"
            echo "  --ios-only             Skip Android, run iOS demo only"
            echo "  --duration SECONDS     Auto-exit after N seconds (0 = until Ctrl+C)"
            echo "  --keep-running         Leave apps + sim/emulator running after exit"
            echo "  --avd NAME             Android AVD name (default: Pixel_7)"
            echo "  --sim NAME             iOS simulator (default: iPhone 17)"
            exit 0
            ;;
        *) echo "Unknown: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

# ========================================================================
# Setup + shared helpers
# ========================================================================

log()  { printf "\033[1;34m==\033[0m %s\n" "$*"; }
ok()   { printf "\033[1;32m✓\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33m!\033[0m %s\n" "$*"; }
err()  { printf "\033[1;31m✗\033[0m %s\n" "$*" >&2; }

ANDROID_PID=""
IOS_PID=""

cleanup() {
    log "Shutting down..."
    if [[ "$SKIP_IOS" == "0" ]]; then
        xcrun simctl terminate booted "$IOS_BUNDLE" 2>/dev/null || true
        ok "iOS app terminated"
    fi
    if [[ "$SKIP_ANDROID" == "0" && -n "${ANDROID_SERIAL:-}" ]]; then
        adb -s "$ANDROID_SERIAL" shell am force-stop "$ANDROID_PKG" 2>/dev/null || true
        ok "Android app stopped"
    fi
    if [[ "$KEEP_RUNNING" == "0" ]]; then
        if [[ "$SKIP_IOS" == "0" ]]; then
            xcrun simctl shutdown booted 2>/dev/null || true
            ok "iOS simulator shut down"
        fi
        if [[ "$SKIP_ANDROID" == "0" && -n "${ANDROID_SERIAL:-}" ]]; then
            adb -s "$ANDROID_SERIAL" emu kill 2>/dev/null || true
            ok "Android emulator killed"
        fi
    fi
}
trap cleanup EXIT INT TERM

# ========================================================================
# Android side
# ========================================================================

start_android() {
    [[ "$SKIP_ANDROID" == "1" ]] && return 0
    log "Android: booting emulator '$ANDROID_AVD'"
    if ! adb devices | grep -q "emulator"; then
        nohup emulator -avd "$ANDROID_AVD" -no-snapshot-save > /tmp/dual-emu.log 2>&1 &
        log "Waiting for boot (up to 4 min)..."
        adb wait-for-device
        local serial
        serial=$(adb devices | awk '/emulator/{print $1; exit}')
        until adb -s "$serial" shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do
            sleep 5
        done
        ok "Emulator '$serial' booted"
    fi
    ANDROID_SERIAL=$(adb devices | awk '/emulator/{print $1; exit}')

    log "Android: building + installing Astronomy Shop (:upstream-demo-app dash0 flavor)"
    (cd "$ANDROID_DEMO_ROOT" && ./gradlew "$ANDROID_GRADLE_TARGET" -q)
    ok "APK installed"

    log "Android: launching Astronomy Shop + monkey auto-drive loop"
    # MainActivity is the entry point; it navigates into AstronomyShopActivity.
    adb -s "$ANDROID_SERIAL" shell am start -n "$ANDROID_PKG/$ANDROID_ACTIVITY" >/dev/null
    sleep 2

    # Monkey produces deterministic tap/scroll events. Combined with the
    # ShopTelemetry instrumentation wired into cart.add, checkout, and
    # product-view onAppear, each cycle produces the canonical cross-
    # platform shop signal (see docs/design/shop-telemetry-contract.md).
    (
        while true; do
            adb -s "$ANDROID_SERIAL" shell monkey -p "$ANDROID_PKG" \
                --throttle 500 --pct-touch 60 --pct-motion 30 --pct-nav 10 \
                -v 20 >/dev/null 2>&1 || true
            sleep 1
        done
    ) &
    ANDROID_PID=$!
    ok "Android auto-monkey loop started (PID $ANDROID_PID)"
}

# ========================================================================
# iOS side
# ========================================================================

start_ios() {
    [[ "$SKIP_IOS" == "1" ]] && return 0
    log "iOS: booting simulator '$IOS_SIM_NAME'"
    if ! xcrun simctl list devices booted 2>/dev/null | grep -q "$IOS_SIM_NAME"; then
        xcrun simctl boot "$IOS_SIM_NAME"
        sleep 4
    fi
    open -a Simulator 2>/dev/null || true
    ok "Simulator ready"

    log "iOS: regenerating xcodeproj (xcodegen)"
    (cd "$IOS_DEMO_ROOT" && /opt/homebrew/bin/xcodegen generate >/dev/null)
    ok "Project generated"

    # iOS side runs the AstronomyShopUITests journey loop in the
    # background — XCUIApplication taps real SwiftUI buttons in a loop
    # for the duration of the demo. This matches Android's `monkey`
    # semantics on the iOS side: every emission originates from a
    # synthetic touch event, not from a Timer that bypasses the UI.
    local journey_seconds=${DURATION_SECONDS:-3600}
    if [[ "$journey_seconds" -le 0 ]]; then journey_seconds=3600; fi
    log "iOS: launching XCUITest journey (${journey_seconds}s budget) in background"
    (
        JOURNEY_DURATION_SECONDS="$journey_seconds" \
            xcodebuild test \
                -scheme AstronomyShopUITests \
                -destination "platform=iOS Simulator,name=$IOS_SIM_NAME" \
                -derivedDataPath "$IOS_DEMO_ROOT/build" \
                -only-testing:AstronomyShopUITests/AstronomyShopJourneyUITest/testJourneyLoop \
                > /tmp/dual-ios-uitest.log 2>&1
    ) &
    IOS_PID=$!
    ok "iOS XCUITest journey started (PID $IOS_PID, log /tmp/dual-ios-uitest.log)"
}

# ========================================================================
# Run
# ========================================================================

start_android
start_ios

cat <<EOF

==============================================================================
  Dual-platform demo RUNNING
==============================================================================

Watch Dash0 — events should arrive from BOTH platforms concurrently.

Filter hints:
  Bracket both:  service.name =~ "otel-.*-astronomy-shop"
  Filter one:    service.name = "otel-android-astronomy-shop"  OR  "otel-ios-astronomy-shop"
  Separate by:   os.name  (values: "android", "iOS")
  Shared type:   dash0.resource.type="mobile"  (both sides)

iOS signals (XCUITest journey on real SwiftUI buttons):
  Logs:    app.home_appeared, shop.view_product, cart.add_item (INFO),
           cart.cleared (INFO), cart.large_quantity_warning (WARN)
  Traces:  shop.load_catalog (4-span tree), shop.view_product (3-span tree),
           checkout (14-span deep tree: validate → inventory × N → totals
           × 3 → charge × 2 → confirm × 2 → analytics)
  Metrics: shop.cart.items_added counter, shop.checkout.duration_ms histogram,
           shop.view_product.load_ms histogram

Android signals (~500ms throttle):
  Logs:    app.start / user.tap / user.scroll / http.request / error.*
  Traces:  ui.tap spans, page.* spans, network.* spans, nested workflow
  Metrics: demo.button_press counter, demo.request_duration_ms histogram,
           device.memory.used_mb gauge (if DeviceStats enabled)

Ctrl+C to stop. --keep-running leaves the sim/emu booted after we exit.

EOF

if [[ "$DURATION_SECONDS" -gt 0 ]]; then
    log "Auto-exit after $DURATION_SECONDS seconds"
    sleep "$DURATION_SECONDS"
else
    # Idle — trap cleanup fires on Ctrl+C.
    while true; do sleep 60; done
fi
