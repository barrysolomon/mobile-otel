#!/usr/bin/env bash
# Interactive iOS demo control center — the iOS equivalent of demo-control-center.sh.
# Manages booting the iOS Simulator, building/installing AstronomyShop, launching
# it, triggering telemetry actions, and tailing its console output.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEMO_ROOT="$REPO_ROOT/examples/upstream-demo-app-ios"
BUNDLE_ID="com.dash0.mobile.demo.AstronomyShop"
SCHEME="AstronomyShop"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

if ! command -v xcrun >/dev/null 2>&1; then
    echo "ERROR: xcrun not found. Install Xcode or set DEVELOPER_DIR." >&2
    exit 1
fi

usage() {
    cat <<EOF
iOS Demo Control Center

Usage: $0 <command>

Commands:
  boot                 Boot the '${SIM_NAME}' simulator
  build                Build AstronomyShop
  install              Install the built app on the booted simulator
  launch               Launch the app
  terminate            Terminate the running app
  screenshot [file]    Capture a screenshot (default: /tmp/ios-demo.png)
  log                  Tail the app's log stream (Ctrl+C to stop)
  uninstall            Uninstall the app from the simulator
  open                 Open Simulator.app so you can see the running app
  full                 boot + build + install + launch + screenshot
  menu                 Interactive menu (default)

Env vars:
  IOS_SIM_NAME         Override simulator device name (default: iPhone 17)
  DEVELOPER_DIR        Override Xcode dev dir
EOF
}

boot_sim() {
    if xcrun simctl list devices booted 2>/dev/null | grep -q "$SIM_NAME"; then
        echo "Simulator '$SIM_NAME' already booted."
    else
        echo "Booting '$SIM_NAME'..."
        xcrun simctl boot "$SIM_NAME"
        sleep 3
    fi
    open -a Simulator 2>/dev/null || true
}

build_app() {
    echo "Building $SCHEME for iOS Simulator..."
    cd "$DEMO_ROOT"
    if [[ ! -d "${SCHEME}.xcodeproj" ]]; then
        /opt/homebrew/bin/xcodegen generate
    fi
    xcodebuild -scheme "$SCHEME" \
        -destination "platform=iOS Simulator,name=$SIM_NAME" \
        -derivedDataPath ./build build 2>&1 | tail -5
}

install_app() {
    local app_path="$DEMO_ROOT/build/Build/Products/Debug-iphonesimulator/$SCHEME.app"
    [[ -d "$app_path" ]] || { echo "ERROR: $app_path missing. Run build first." >&2; exit 1; }
    xcrun simctl install booted "$app_path"
    echo "Installed."
}

launch_app() {
    xcrun simctl launch booted "$BUNDLE_ID"
}

terminate_app() {
    xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true
    echo "Terminated (if it was running)."
}

screenshot() {
    local out="${1:-/tmp/ios-demo.png}"
    xcrun simctl io booted screenshot "$out"
    echo "Wrote $out"
}

tail_log() {
    echo "Tailing logs for $BUNDLE_ID (Ctrl+C to stop)..."
    xcrun simctl spawn booted log stream --predicate "process == \"$SCHEME\"" --level debug
}

uninstall_app() {
    xcrun simctl uninstall booted "$BUNDLE_ID" 2>/dev/null || true
    echo "Uninstalled."
}

open_sim() {
    open -a Simulator
}

full_flow() {
    boot_sim
    build_app
    install_app
    launch_app
    sleep 3
    screenshot
}

menu_loop() {
    while true; do
        echo ""
        echo "=== Dash0 iOS Demo Control Center ==="
        echo "  1) Boot simulator + open Simulator.app"
        echo "  2) Build app"
        echo "  3) Install app"
        echo "  4) Launch app"
        echo "  5) Screenshot"
        echo "  6) Terminate app"
        echo "  7) Tail logs"
        echo "  8) Uninstall app"
        echo "  9) Full flow (boot + build + install + launch + screenshot)"
        echo "  q) Quit"
        read -rp "Choice: " choice
        case "$choice" in
            1) boot_sim ;;
            2) build_app ;;
            3) install_app ;;
            4) launch_app ;;
            5) screenshot ;;
            6) terminate_app ;;
            7) tail_log ;;
            8) uninstall_app ;;
            9) full_flow ;;
            q|Q) exit 0 ;;
            *) echo "Unknown choice." ;;
        esac
    done
}

case "${1:-menu}" in
    boot) boot_sim ;;
    build) build_app ;;
    install) install_app ;;
    launch) launch_app ;;
    terminate) terminate_app ;;
    screenshot) screenshot "${2:-}" ;;
    log|logs) tail_log ;;
    uninstall) uninstall_app ;;
    open) open_sim ;;
    full) full_flow ;;
    menu) menu_loop ;;
    -h|--help|help) usage ;;
    *) usage; exit 1 ;;
esac
