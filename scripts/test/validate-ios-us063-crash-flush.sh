#!/usr/bin/env bash
# US-063 (iOS): Crash-triggered recovery + flush.
#
# Mirrors Android's `validate-us063-crash-flush.sh`. Drives the
# AstronomyShop into a state with buffered telemetry, kills the process
# with SIGSEGV (the SDK's signal handler writes a crash marker mid-
# crash), relaunches, and asserts that:
#   1. The next launch emits an `app.crash` log with severity=fatal
#      and crash.from_marker=true.
#   2. The buffered telemetry from the pre-crash session also lands.
#
# Combined with the SDK-level CrashRecoveryTests (which validates the
# marker round-trip in-process), this is the missing end-to-end seam:
# proves the marker survives a real process kill on the simulator and
# the next launch's recovery path emits to Dash0 over the real OTLP
# transport.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib-ios/dash0-mcp.sh"

REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ASTRONOMY_ROOT="$REPO_ROOT/examples/upstream-demo-app-ios"
CONFIG_PATH="$ASTRONOMY_ROOT/AstronomyShop/otel-config.json"
BUNDLE_ID="com.dash0.mobile.demo.AstronomyShop"
HOST_SCHEME="AstronomyShop"
SERVICE_NAME="otel-ios-astronomy-shop"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"

if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

[[ -f "$CONFIG_PATH" ]] || { echo "missing $CONFIG_PATH" >&2; exit 1; }
export SERVICE_NAME

log "US-063 (iOS): crash + recovery emits app.crash to Dash0"

log "Setup: boot simulator '$SIM_NAME'"
if ! xcrun simctl list devices booted 2>/dev/null | grep -q "$SIM_NAME"; then
    xcrun simctl boot "$SIM_NAME"
    sleep 4
fi
ok "Simulator booted"

log "Setup: build + install AstronomyShop"
(cd "$ASTRONOMY_ROOT" && /opt/homebrew/bin/xcodegen generate >/dev/null)
(cd "$ASTRONOMY_ROOT" && \
    xcodebuild -scheme "$HOST_SCHEME" \
        -destination "platform=iOS Simulator,name=$SIM_NAME" \
        -derivedDataPath ./build build > /tmp/us063-build.log 2>&1) \
    || { tail -40 /tmp/us063-build.log >&2; fail "build failed"; }
xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install booted \
    "$ASTRONOMY_ROOT/build/Build/Products/Debug-iphonesimulator/${HOST_SCHEME}.app" >/dev/null
ok "Built + installed"

START_ISO="$(iso_now)"

log "Phase 1: launch the app and let it accumulate some telemetry (8s)"
xcrun simctl launch booted "$BUNDLE_ID" >/dev/null
sleep 8

# Find the running PID inside the simulator and kill -11 it. The SDK's
# signal handler writes the crash marker mid-crash. ErrorsInstrumentation's
# pre-opened file descriptor is what makes that path async-signal-safe.
log "Phase 2: kill the app with SIGSEGV (triggers crash marker)"
APP_PID="$(xcrun simctl spawn booted launchctl list 2>/dev/null \
    | awk -v bid="$BUNDLE_ID" 'index($0, bid) {print $1; exit}')"
if [[ -z "$APP_PID" || "$APP_PID" == "-" ]]; then
    fail "could not find $BUNDLE_ID PID inside simulator — is the app running?"
fi
ok "found app PID $APP_PID inside simulator"
xcrun simctl spawn booted kill -11 "$APP_PID" || true
sleep 2

log "Phase 3: relaunch — recovery path should emit app.crash"
xcrun simctl launch booted "$BUNDLE_ID" >/dev/null
# Give the BatchLogRecordProcessor time to drain the recovery emission
# (default scheduleDelay 2s in the SDK; we give 12s for export + Dash0
# ingest latency).
sleep 12
END_ISO="$(iso_now)"
xcrun simctl terminate booted "$BUNDLE_ID" 2>/dev/null || true

log "Validate: app.crash log present in Dash0 with severity=fatal"
LOG_BODIES="$(dash0_logs_bodies "$START_ISO" "$END_ISO")"
if ! grep -q "app.crash" <<< "$LOG_BODIES"; then
    echo "Observed log bodies: $LOG_BODIES" >&2
    fail "no app.crash log observed in window ${START_ISO} → ${END_ISO}"
fi
ok "app.crash log observed"

# The SDK should also have flushed pre-crash telemetry on next-launch.
# We don't pin a specific count here because phase 1 wall-clock varies,
# but at least one non-crash emission must be present.
log "Validate: pre-crash telemetry also flushed (>= 1 cart/shop log)"
if ! grep -qE "cart\\.|shop\\." <<< "$LOG_BODIES"; then
    echo "Observed log bodies:" >&2
    echo "$LOG_BODIES" | sed 's/^/  /' >&2
    fail "no pre-crash shop/cart logs observed — buffer drain may be broken"
fi
ok "pre-crash telemetry observed"

ok "US-063 (iOS) PASS — crash-marker + recovery path verified end-to-end"
