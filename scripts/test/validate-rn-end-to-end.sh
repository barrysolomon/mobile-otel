#!/usr/bin/env bash
# validate-rn-end-to-end.sh
#
# React Native end-to-end validation. Mirrors validate-ios-end-to-end.sh in
# shape but covers the @dash0/mobile-react-native bridge and the
# AstronomyShop-RN demo.
#
# Modes:
#   --mode=jest    (default) — run Jest + typecheck for the RN package and
#                  demo app. No simulator required. This is the CI gate.
#   --mode=device  — boot a simulator/emulator, install the AstronomyShopRN
#                  demo, drive the auto-demo loop, assert telemetry lands
#                  in Dash0. Requires RN-003 host project scaffolding to be
#                  complete; exits with a clear message otherwise.
#
# Exit codes:
#   0 = all phases green
#   1 = setup failure (missing tools, missing config)
#   2 = Jest / typecheck failure OR telemetry assertion failure
#
# Usage:
#   ./scripts/test/validate-rn-end-to-end.sh
#   ./scripts/test/validate-rn-end-to-end.sh --mode=device

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

MODE="jest"
for arg in "$@"; do
    case "$arg" in
        --mode=jest|--mode=device) MODE="${arg#--mode=}" ;;
        --help|-h)
            head -25 "$0" | tail -24
            exit 0
            ;;
        *)
            echo "unknown arg: $arg (use --mode=jest|device)" >&2
            exit 1
            ;;
    esac
done

RN_PKG_DIR="$REPO_ROOT/packages/react-native"
RN_DEMO_DIR="$REPO_ROOT/examples/upstream-demo-app-rn/AstronomyShopRN"
SERVICE_NAME="otel-rn-astronomy-shop"
CONFIG_PATH="$RN_DEMO_DIR/otel-config.json"

# Colour helpers — duplicated from lib/dash0-mcp.sh so --mode=jest doesn't
# need to source the MCP lib (no credentials required for the Jest path).
log()  { printf "\033[1;34m==\033[0m %s\n" "$*"; }
ok()   { printf "\033[1;32m✓\033[0m %s\n" "$*"; }
fail() { printf "\033[1;31m✗\033[0m %s\n" "$*"; exit 2; }
warn() { printf "\033[1;33m!\033[0m %s\n" "$*"; }

# ─── Phase 1: Jest + typecheck ───────────────────────────────────────────────

log "Phase 1: Jest + typecheck for @dash0/mobile-react-native"
(
    cd "$RN_PKG_DIR"
    if [[ ! -d node_modules ]]; then
        log "installing RN package deps"
        npm install --no-audit --no-fund --silent
    fi
    npx tsc --noEmit && npx jest --runInBand --silent
) || fail "RN package Jest/typecheck failed"
ok "RN package: typecheck + jest green"

log "Phase 2: Jest + typecheck for AstronomyShop-RN demo"
(
    cd "$RN_DEMO_DIR"
    if [[ ! -d node_modules ]]; then
        log "installing demo deps"
        npm install --no-audit --no-fund --silent
    fi
    npx tsc --noEmit && npx jest --runInBand --silent
) || fail "Demo app Jest/typecheck failed"
ok "Demo app: typecheck + jest green"

if [[ "$MODE" == "jest" ]]; then
    echo ""
    ok "RN validation passed (mode=jest): bridge contract + ShopTelemetry shapes + AutoDemoDriver behavior"
    echo "  For end-to-end telemetry assertions against Dash0, re-run with --mode=device"
    echo "  once the AstronomyShop-RN host project (RN-003 remainder) is wired up."
    exit 0
fi

# ─── Mode=device: simulator/emulator drive + Dash0 MCP assertions ────────────

if [[ ! -f "$CONFIG_PATH" ]]; then
    fail "missing $CONFIG_PATH — copy from .template and fill in Dash0 credentials"
fi

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/dash0-mcp.sh"
export SERVICE_NAME

# The host RN project (a native Xcode workspace + Android Gradle wrapper)
# lands as the remainder of RN-003. Until then this path is a spec, not a
# runnable. Fail loudly so nobody mistakes a skipped phase for a pass.
if [[ ! -d "$RN_DEMO_DIR/ios" || ! -d "$RN_DEMO_DIR/android" ]]; then
    warn "AstronomyShop-RN host projects not yet scaffolded (expected $RN_DEMO_DIR/{ios,android})"
    warn "Complete RN-003 host project scaffolding before running --mode=device"
    fail "device mode unavailable — RN-003 host projects missing"
fi

# What this phase SHOULD do once the host projects exist:
#
#   iOS:
#     cd "$RN_DEMO_DIR/ios" && pod install
#     xcodebuild -workspace AstronomyShopRN.xcworkspace \
#         -scheme AstronomyShopRN \
#         -destination "platform=iOS Simulator,name=iPhone 17" \
#         build install
#     xcrun simctl launch --setenv DASH0_AUTO_DEMO=1 ...
#
#   Android:
#     cd "$RN_DEMO_DIR/android" && ./gradlew installDebug
#     adb shell am start -e DASH0_AUTO_DEMO 1 ...
#
#   Then (both):
#     START_ISO="$(iso_now)"; sleep 45; END_ISO="$(iso_now)"
#     dash0_log_count + dash0_span_count + dash0_span_names assertions,
#     using the same SERVICE_NAME filter.
#
# The MCP assertion layer (lib/dash0-mcp.sh) is already platform-agnostic
# so the only platform-specific code stays inside the boot/install block.

fail "device mode not yet implemented — see inline spec in this script"
