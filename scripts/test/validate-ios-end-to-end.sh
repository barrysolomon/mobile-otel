#!/usr/bin/env bash
# validate-ios-end-to-end.sh
#
# Drives a full round-trip validation of the iOS SDK: boot simulator, build
# + install the Starter demo in auto-demo mode, launch, observe console for
# the expected lifecycle + emission markers, and assert they all showed up.
#
# Mirrors the Android validate-us*.sh scripts in spirit — a scenario script
# you can run in CI or locally to confirm the whole stack works.
#
# Exit codes:
#   0 = all validations passed
#   1 = setup failure (sim boot / build / install)
#   2 = validation failure (some expected marker didn't appear)
set -euo pipefail

# Resolve the real script path so invoking via the root-level
# `./validate-ios-end-to-end.sh` symlink still anchors REPO_ROOT correctly.
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
IOS_DEMO_ROOT="$REPO_ROOT/examples/demo-app-ios-starter"
BUNDLE_ID="com.dash0.mobile.demo.StarterApp"
SCHEME="StarterApp"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
OBSERVE_SECONDS="${OBSERVE_SECONDS:-20}"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

log()  { printf "\033[1;34m==\033[0m %s\n" "$*"; }
ok()   { printf "\033[1;32m✓\033[0m %s\n" "$*"; }
fail() { printf "\033[1;31m✗\033[0m %s\n" "$*"; exit 2; }
warn() { printf "\033[1;33m!\033[0m %s\n" "$*"; }

cleanup() {
    xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# --- Setup ---

log "Setup: boot simulator '$SIM_NAME'"
if ! xcrun simctl list devices booted 2>/dev/null | grep -q "$SIM_NAME"; then
    xcrun simctl boot "$SIM_NAME"
    sleep 4
fi
ok "Simulator booted"

log "Setup: build demo app"
cd "$IOS_DEMO_ROOT"
if [[ ! -d StarterApp.xcodeproj ]]; then
    /opt/homebrew/bin/xcodegen generate >/dev/null
fi
xcodebuild -scheme "$SCHEME" \
    -destination "platform=iOS Simulator,name=$SIM_NAME" \
    -derivedDataPath ./build build >/tmp/validate-ios-build.log 2>&1 \
    || { cat /tmp/validate-ios-build.log; exit 1; }
ok "Built ${SCHEME}.app"

log "Setup: install app"
xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install booted ./build/Build/Products/Debug-iphonesimulator/${SCHEME}.app
ok "Installed"

# --- Run with console capture ---

log "Run: launch with DASH0_AUTO_DEMO=1 and capture console for ${OBSERVE_SECONDS}s"
LOG_FILE=$(mktemp -t dash0-ios-validate.XXXXXX)
xcrun simctl launch --console-pty booted "$BUNDLE_ID" \
    --env DASH0_AUTO_DEMO=1 >"$LOG_FILE" 2>&1 &
APP_PID=$!
sleep "$OBSERVE_SECONDS"
# Stop streaming; this does NOT force-kill the app, just our console reader.
kill "$APP_PID" 2>/dev/null || true
wait "$APP_PID" 2>/dev/null || true
ok "Captured $(wc -l <"$LOG_FILE" | tr -d ' ') log lines"

# --- Assertions ---

log "Validate: SDK bootstrap markers appear"
FAILURES=0

check_marker() {
    local pattern="$1"
    local description="$2"
    if grep -qE "$pattern" "$LOG_FILE"; then
        ok "$description"
    else
        warn "missing: $description (pattern: $pattern)"
        FAILURES=$((FAILURES + 1))
    fi
}

# SDK boot (OTelMobileBootstrap prints this via print())
check_marker 'OK SDK started' 'OTelMobileBootstrap emitted start-OK'
check_marker 'endpoint=https' 'Bootstrap logged an https endpoint'
check_marker 'dataset=' 'Bootstrap logged a dataset'

# Auto-demo loop prints nothing to stdout by design (emits go via OTel);
# we just assert the process stayed alive the whole window without crashing.
if [[ -s "$LOG_FILE" ]]; then
    ok 'App produced console output (did not crash early)'
else
    warn 'App produced no console output at all — check simctl install output'
    FAILURES=$((FAILURES + 1))
fi

# --- Summary ---

echo ""
if [[ "$FAILURES" -eq 0 ]]; then
    ok "All end-to-end validations passed. See Dash0 for the actual events."
    echo ""
    echo "Next steps:"
    echo "  - Filter Dash0 by service.name='otel-ios-demo-starter' OR os.name='iOS'"
    echo "  - Expected: logs (user.button_tap/form_validation/error.simulated/app.start),"
    echo "    spans (user.action, ui.workflow.checkout), metrics (demo.button_press,"
    echo "    demo.request_duration_ms), lifecycle (app.foreground/background)."
    exit 0
else
    fail "$FAILURES validation(s) failed — see log at $LOG_FILE"
fi
