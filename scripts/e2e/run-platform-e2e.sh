#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
# run-platform-e2e.sh — receipt-gated E2E for the three platforms that
# run-e2e.sh does not cover: ios-native, rn-android, rn-ios.
# (android-native is driven + gated by scripts/e2e/run-e2e.sh.)
#
# For each platform this script:
#   1. stamps RUN_START_EPOCH (run-scoped gate window — a previous run's
#      telemetry can never green this one),
#   2. drives the platform's demo through the journey the receipt gate
#      expects: launch (app.start / app.launch + startup span) →
#      background/foreground cycle (app.foreground) → crash launch →
#      recovery launch (flushes the buffered crash as `app.crash`),
#   3. runs the REST receipt gate: verify-dash0.sh <platform>
#      --since $RUN_START_EPOCH.
#
# Green means the data is IN Dash0 — not that the steps merely executed.
# A platform whose telemetry does not arrive fails the run (exit 1).
#
# Demo ↔ service mapping (must match verify-dash0.sh expectations):
#   ios-native  → examples/demo-app-ios (Schedulr)          otel-ios-schedulr
#   rn-android  → AstronomyShopRN android, dash0<Mode> APK  otel-rn-android-astronomy-shop
#   rn-ios      → AstronomyShopRN ios simulator app         otel-rn-ios-astronomy-shop
#
# Service identity: the RN demos read otel-config.json through the JS
# bundle. For the Debug builds this script drives, the bundle is served by
# METRO AT RUNTIME — so the platform-specific service name must be stamped
# into examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json
# BEFORE the bundle is (pre-)warmed for that platform, and restored after
# the leg. Metro's file watcher picks the change up automatically.
#
# Prereqs: booted iOS simulator (or one bootable by name), adb-visible
# Android emulator/device for rn-android, Dash0 token (env or
# examples/demo-app/.env), per-demo otel-config.json with credentials.
# RN-android builds want the repo's RN toolchain (Gradle 8.14 / JDK 21).
#
# Usage:
#   ./run-platform-e2e.sh                          # all three platforms
#   ./run-platform-e2e.sh ios-native               # one platform
#   ./run-platform-e2e.sh rn-android rn-ios        # subset
#   ./run-platform-e2e.sh --build                  # force rebuild of demos
#   ./run-platform-e2e.sh --mode hyb               # RN export-mode flavor
#   DASH0_RETRY_FOR=180 ./run-platform-e2e.sh      # patient gate
# ═══════════════════════════════════════════════════════════════════════
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
UAT_DIR="$REPO_ROOT/scripts/test/uat"
export UAT_REPO_ROOT="$REPO_ROOT"

RN_APP_DIR="$REPO_ROOT/examples/upstream-demo-app-rn/AstronomyShopRN"
RN_CONFIG="$RN_APP_DIR/otel-config.json"
SCHEDULR_DIR="$REPO_ROOT/examples/demo-app-ios"
SCHEDULR_APP="$SCHEDULR_DIR/build/Build/Products/Debug-iphonesimulator/Schedulr.app"
SCHEDULR_BUNDLE_ID="com.dash0.mobile.demo.Schedulr"

WINDOW_MIN="${DASH0_WINDOW_MIN:-30}"
# 300s, not 120: rn-android's continuous-mode flush + Dash0 ingestion tail
# was observed delivering app.start/page.* ~3-4 min after the drive ended.
# dash0_assert exits early on pass, so patience costs nothing when fast.
RETRY_FOR="${DASH0_RETRY_FOR:-300}"
MODE="cont"               # RN export-mode flavor: cont | cond | hyb
FORCE_BUILD=0
SETTLE_NORMAL=25          # post-launch dwell: auto-instrumentation + first export
SETTLE_RECOVERY=30        # post-recovery dwell: crash log + buffered backlog flush
PLATFORMS_ARG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --build)        FORCE_BUILD=1; shift ;;
    --mode)         MODE="$2"; shift 2 ;;
    --window-min)   WINDOW_MIN="$2"; shift 2 ;;
    --retry-for)    RETRY_FOR="$2"; shift 2 ;;
    --settle)       SETTLE_NORMAL="$2"; shift 2 ;;
    -h|--help)      sed -n '2,46p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)              PLATFORMS_ARG="$PLATFORMS_ARG $1"; shift ;;
  esac
done
PLATFORMS="${PLATFORMS_ARG:-ios-native rn-android rn-ios}"

BLUE='\033[0;34m'; GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'
step() { echo -e "\n${BLUE}${BOLD}▸ $1${NC}"; }
ok()   { echo -e "  ${GREEN}✓ $1${NC}"; }
warn() { echo -e "  ${YELLOW}⚠ $1${NC}"; }
fail() { echo -e "  ${RED}✗ $1${NC}"; }

# Load the Dash0 token early so a missing token fails before any 10-minute
# build, not after. (verify-dash0.sh would also load it, but late.)
if [ -z "${DASH0_AUTH_TOKEN:-}" ] && [ -f "$REPO_ROOT/examples/demo-app/.env" ]; then
  set -a; . "$REPO_ROOT/examples/demo-app/.env"; set +a
fi
if [ -z "${DASH0_AUTH_TOKEN:-}" ]; then
  fail "DASH0_AUTH_TOKEN is not set and examples/demo-app/.env does not provide it."
  echo "  The receipt gate cannot run without it; refusing to drive demos un-gated."
  exit 1
fi

# ── Metro (RN dev server) ────────────────────────────────────────────────
# The RN demos are Debug builds: the JS bundle (which calls
# Dash0Mobile.start() and bakes in otel-config.json) is served by Metro at
# runtime, NOT embedded in the binary. Without Metro the app renders the
# red "can't connect to dev server" screen and emits NOTHING — the receipt
# gate then fails with every signal at 0. So: ensure Metro is up and the
# platform bundle is pre-warmed (first compile takes ~30-60s; warming it
# via curl keeps the app-launch settle windows honest).
METRO_PIDFILE="/tmp/mobile-otel-platform-e2e-metro.pid"
METRO_PORT=8081
# A port being OCCUPIED is not the same as Metro RUNNING (on one dev machine
# OrbStack squats on 8081 and silently ate the first version of this check).
# Only Metro's own status endpoint counts.
metro_running_on() {
  [ "$(curl -s --max-time 3 "http://localhost:$1/status" 2>/dev/null)" = "packager-status:running" ]
}
ensure_metro() {
  local platform="$1"   # ios | android
  local alt_port="${RN_METRO_PORT:-8231}"
  if metro_running_on 8081; then
    METRO_PORT=8081
    ok "Metro already running on :8081"
  elif metro_running_on "$alt_port"; then
    METRO_PORT="$alt_port"
    ok "Metro already running on :$alt_port"
  else
    if lsof -ti:8081 >/dev/null 2>&1; then
      METRO_PORT="$alt_port"
      warn "port 8081 is held by a non-Metro process — using :$METRO_PORT"
    else
      METRO_PORT=8081
    fi
    step "starting Metro (RN dev server) on :$METRO_PORT"
    (cd "$RN_APP_DIR" && nohup npx react-native start --port "$METRO_PORT" >/tmp/metro-platform-e2e.log 2>&1 & echo $! > "$METRO_PIDFILE")
    local i
    for i in $(seq 1 45); do
      metro_running_on "$METRO_PORT" && break
      sleep 1
    done
    metro_running_on "$METRO_PORT" || { fail "Metro did not start (see /tmp/metro-platform-e2e.log)"; return 1; }
    ok "Metro up on :$METRO_PORT (log: /tmp/metro-platform-e2e.log)"
  fi

  echo "  Pre-warming $platform JS bundle (cold compile can take minutes)..."
  if curl -sf --max-time 600 "http://localhost:$METRO_PORT/index.bundle?platform=${platform}&dev=true" -o /dev/null; then
    ok "bundle warm"
  else
    fail "bundle compile failed — the app would show the red screen and emit nothing (see /tmp/metro-platform-e2e.log)"
    return 1
  fi

  if [ "$platform" = "android" ]; then
    # Inside the emulator the app always dials localhost:8081; map it to
    # wherever Metro actually listens on the host.
    adb reverse tcp:8081 "tcp:$METRO_PORT" >/dev/null 2>&1 || warn "adb reverse failed — emulator may not reach Metro"
  fi
  # (For iOS, the per-app RCT_jsLocation default is written by the driver
  # AFTER install — reinstall wipes the app container that stores it.)
}
stop_metro_if_started() {
  if [ -f "$METRO_PIDFILE" ]; then
    kill "$(cat "$METRO_PIDFILE")" 2>/dev/null || true
    rm -f "$METRO_PIDFILE"
  fi
}

# ── RN config stamping (build-time service identity) ────────────────────
RN_CONFIG_BACKUP=""
stamp_rn_service() {
  local service="$1"
  [ -f "$RN_CONFIG" ] || { fail "missing $RN_CONFIG — copy from .template and add Dash0 creds"; return 1; }
  RN_CONFIG_BACKUP="$(mktemp /tmp/rn-otel-config-backup.XXXXXX)"
  cp "$RN_CONFIG" "$RN_CONFIG_BACKUP"
  python3 - "$RN_CONFIG" "$service" <<'PYEOF'
import json, sys
path, service = sys.argv[1], sys.argv[2]
with open(path) as f:
    cfg = json.load(f)
cfg["serviceName"] = service
with open(path, "w") as f:
    json.dump(cfg, f, indent=2)
    f.write("\n")
PYEOF
}
restore_rn_config() {
  if [ -n "$RN_CONFIG_BACKUP" ] && [ -f "$RN_CONFIG_BACKUP" ]; then
    mv "$RN_CONFIG_BACKUP" "$RN_CONFIG"
    RN_CONFIG_BACKUP=""
  fi
}
trap 'restore_rn_config; stop_metro_if_started' EXIT

# ── Platform drivers ─────────────────────────────────────────────────────
# Each drives: normal launch → lifecycle cycle → crash launch → recovery
# launch, using the same primitives as the UAT matrix where they exist.

drive_ios_native() {
  # Schedulr — service otel-ios-schedulr. Crash via -DASH0_CRASH_NOW
  # (the launch-arg hook in SchedulrApp.swift; fires ~1s after SDK start).
  if [ ! -f "$SCHEDULR_DIR/Schedulr/otel-config.json" ]; then
    fail "missing $SCHEDULR_DIR/Schedulr/otel-config.json (gitignored) — Schedulr will boot with no-export config"
    return 1
  fi

  # Source the UAT iOS lib ONLY for its simulator-UDID helper; Schedulr is
  # a different app than the lib's AstronomyShop primitives drive.
  # shellcheck disable=SC1091
  . "$UAT_DIR/lib-uat-platform-ios-native.sh"
  local udid
  udid="$(__uat_ios_sim_udid)" || { fail "no available simulator '$(__uat_ios_sim)' — set UAT_IOS_SIMULATOR"; return 1; }
  xcrun simctl bootstatus "$udid" -b >/dev/null 2>&1 || true

  if [ "$FORCE_BUILD" -eq 1 ] || [ ! -d "$SCHEDULR_APP" ]; then
    step "ios-native: building Schedulr (xcodebuild)"
    (cd "$SCHEDULR_DIR" && xcodebuild -project Schedulr.xcodeproj -scheme Schedulr \
        -configuration Debug -destination "id=$udid" \
        -derivedDataPath build build -quiet) || { fail "Schedulr build failed"; return 1; }
  fi
  [ -d "$SCHEDULR_APP" ] || { fail "Schedulr.app not found at $SCHEDULR_APP — run with --build"; return 1; }

  step "ios-native: driving Schedulr on simulator $udid"
  xcrun simctl uninstall "$udid" "$SCHEDULR_BUNDLE_ID" >/dev/null 2>&1 || true
  xcrun simctl install "$udid" "$SCHEDULR_APP" || { fail "install failed"; return 1; }

  # Normal: app.start log + app.startup span
  xcrun simctl launch "$udid" "$SCHEDULR_BUNDLE_ID" >/dev/null || { fail "launch failed"; return 1; }
  ok "launched (normal session)"
  sleep "$SETTLE_NORMAL"

  # Lifecycle: background via Settings, foreground via re-launch (same PID)
  xcrun simctl openurl "$udid" "App-prefs:root=General" >/dev/null 2>&1 || true
  sleep 3
  xcrun simctl launch "$udid" "$SCHEDULR_BUNDLE_ID" >/dev/null 2>&1 || true
  ok "background/foreground cycled"
  sleep 5

  # Crash: relaunch with the crash flag; trap fires ~1s after SDK start
  xcrun simctl terminate "$udid" "$SCHEDULR_BUNDLE_ID" >/dev/null 2>&1 || true
  sleep 1
  xcrun simctl launch "$udid" "$SCHEDULR_BUNDLE_ID" -DASH0_CRASH_NOW >/dev/null 2>&1 || true
  ok "crash launch issued (-DASH0_CRASH_NOW)"
  sleep 8

  # Recovery: next launch sees the crash marker → emits app.crash + drains buffer
  xcrun simctl launch "$udid" "$SCHEDULR_BUNDLE_ID" >/dev/null 2>&1 || true
  ok "recovery launch (flushes app.crash)"
  sleep "$SETTLE_RECOVERY"
  xcrun simctl terminate "$udid" "$SCHEDULR_BUNDLE_ID" >/dev/null 2>&1 || true
}

drive_rn_android() {
  # AstronomyShopRN Android — service otel-rn-android-astronomy-shop.
  # shellcheck disable=SC1091
  . "$UAT_DIR/lib-uat-platform-rn-android.sh"

  adb get-state >/dev/null 2>&1 || { fail "no adb device/emulator connected"; return 1; }

  local apk
  apk="$(__uat_rna_apk_for_mode "$MODE")" || return 1
  if [ "$FORCE_BUILD" -eq 1 ] || [ ! -f "$apk" ]; then
    step "rn-android: building dash0$(__uat_rna_flavor_suffix "$MODE") APK (service stamped for this platform)"
    stamp_rn_service "otel-rn-android-astronomy-shop" || return 1
    (cd "$RN_APP_DIR/android" && ./gradlew "app:assembleDash0$(__uat_rna_flavor_suffix "$MODE")Debug" -q) \
        || { restore_rn_config; fail "RN Android build failed"; return 1; }
    restore_rn_config
  fi

  # Stamp the per-platform service identity BEFORE warming the bundle —
  # Metro serves otel-config.json into the JS bundle at request time.
  stamp_rn_service "otel-rn-android-astronomy-shop" || return 1
  ensure_metro android || { restore_rn_config; return 1; }

  step "rn-android: driving AstronomyShopRN (mode=$MODE)"
  uat::install "$MODE" || { restore_rn_config; fail "install failed"; return 1; }
  local run_tag
  run_tag="e2e-$(date +%s)"

  uat::launch "$MODE" "$run_tag" || { fail "launch failed"; return 1; }
  ok "launched (normal session)"
  sleep "$SETTLE_NORMAL"

  uat::cycle_lifecycle "$MODE"
  ok "background/foreground cycled"
  sleep 5

  uat::trigger_crash "$MODE"
  ok "crash triggered (gate3_crash)"
  sleep 8

  uat::launch "$MODE" "${run_tag}-recovery" || true
  ok "recovery launch (flushes app.crash)"
  sleep "$SETTLE_RECOVERY"
  uat::force_stop "$MODE"
  restore_rn_config
}

drive_rn_ios() {
  # AstronomyShopRN iOS — service otel-rn-ios-astronomy-shop.
  # shellcheck disable=SC1091
  . "$UAT_DIR/lib-uat-platform-rn-ios.sh"

  local udid
  udid="$(__uat_rnios_sim_udid)" || { fail "no available simulator '$(__uat_rnios_sim)' — set UAT_IOS_SIMULATOR"; return 1; }
  xcrun simctl bootstatus "$udid" -b >/dev/null 2>&1 || true

  local app_path
  app_path="$(__uat_rnios_app_path)"
  if [ "$FORCE_BUILD" -eq 1 ] || [ ! -d "$app_path" ]; then
    step "rn-ios: building AstronomyShopRN.app (service stamped for this platform)"
    stamp_rn_service "otel-rn-ios-astronomy-shop" || return 1
    (cd "$RN_APP_DIR/ios" && xcodebuild -workspace AstronomyShopRN.xcworkspace \
        -scheme AstronomyShopRN -configuration Debug -destination "id=$udid" \
        -derivedDataPath build build -quiet) \
        || { restore_rn_config; fail "RN iOS build failed"; return 1; }
    restore_rn_config
  fi

  # Stamp the per-platform service identity BEFORE warming the bundle —
  # Metro serves otel-config.json into the JS bundle at request time.
  stamp_rn_service "otel-rn-ios-astronomy-shop" || return 1
  ensure_metro ios || { restore_rn_config; return 1; }

  step "rn-ios: driving AstronomyShopRN on simulator $udid (mode=$MODE)"
  uat::install "$MODE" || { restore_rn_config; fail "install failed"; return 1; }

  # Point the app's RN dev-client at OUR Metro. RCTBundleURLProvider reads
  # the RCT_jsLocation user default (the dev menu's "Configure Bundler"
  # setting) at runtime; the RCT_METRO_PORT env var is a build-time knob and
  # does NOT work on a prebuilt app. Must be re-written after every install:
  # uat::install uninstalls first, which wipes the app container holding it.
  xcrun simctl spawn "$udid" defaults write "$(__uat_rnios_bundle_id)" \
      RCT_jsLocation "localhost:$METRO_PORT" 2>/dev/null \
      || warn "could not set RCT_jsLocation — app may dial :8081"

  uat::launch "$MODE" "e2e-$(date +%s)" || { fail "launch failed"; return 1; }
  ok "launched (normal session)"
  sleep "$SETTLE_NORMAL"

  uat::cycle_lifecycle "$MODE"
  ok "background/foreground cycled"
  sleep 5

  # RN-iOS crash fires ~5s after boot (JS bridge must come up first)
  uat::trigger_crash "$MODE"
  ok "crash launch issued (-DASH0_CRASH_NOW)"
  sleep 12

  uat::launch "$MODE" "e2e-$(date +%s)-recovery" || true
  ok "recovery launch (flushes app.crash)"
  sleep "$SETTLE_RECOVERY"
  uat::force_stop "$MODE"
  restore_rn_config
}

# ── Main loop: drive, then gate, per platform ────────────────────────────
echo -e "${BOLD}"
echo "╔══════════════════════════════════════════════════════════╗"
echo "║   Receipt-gated platform E2E — green = data in Dash0     ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo -e "${NC}"
echo "  Platforms: $PLATFORMS"
echo "  RN mode:   $MODE     Gate: window=${WINDOW_MIN}m retry-for=${RETRY_FOR}s"

FAILURES=0
for plat in $PLATFORMS; do
  RUN_START_EPOCH=$(date +%s)
  case "$plat" in
    ios-native)  drive_ios_native ;;
    rn-android)  drive_rn_android ;;
    rn-ios)      drive_rn_ios ;;
    android-native)
      warn "android-native is driven + gated by scripts/e2e/run-e2e.sh — skipping here"
      continue ;;
    *) fail "unknown platform: $plat"; FAILURES=$((FAILURES + 1)); continue ;;
  esac
  drive_rc=$?
  if [ "$drive_rc" -ne 0 ]; then
    fail "$plat: demo drive failed — running the gate anyway to report what (if anything) landed"
  fi

  step "$plat: Dash0 receipt gate (run-scoped: --since $RUN_START_EPOCH)"
  if "$SCRIPT_DIR/verify-dash0.sh" "$plat" --window-min "$WINDOW_MIN" \
        --since "$RUN_START_EPOCH" --retry-for "$RETRY_FOR"; then
    ok "$plat: telemetry confirmed in Dash0"
    [ "$drive_rc" -ne 0 ] && FAILURES=$((FAILURES + 1))   # drive errors still fail the run
  else
    fail "$plat: receipt gate FAILED — telemetry did not arrive in Dash0"
    FAILURES=$((FAILURES + 1))
  fi
done

echo ""
echo -e "${BOLD}══════════════════════════════════════════════════════════${NC}"
if [ "$FAILURES" -eq 0 ]; then
  echo -e "${GREEN}${BOLD}  RECEIPT GATE PASSED: every platform's telemetry is in Dash0${NC}"
else
  echo -e "${RED}${BOLD}  RECEIPT GATE: $FAILURES platform(s) failed${NC}"
fi
echo -e "${BOLD}══════════════════════════════════════════════════════════${NC}"
[ "$FAILURES" -eq 0 ]
