#!/usr/bin/env bash
# Demo control center: extended scenario library.
# Source this — do not execute directly.
# Requires: common.sh, export-target.sh, crash-test-phases.sh, dump-telemetry.sh
#
# Hosts the long-tail scenarios that don't fit on the main menu's single-letter
# hotkey bar — network-restored toggle, journey replays, full UAT matrix cells,
# iOS-native + RN-Android + RN-iOS one-shots. The main menu's "Run a Demo"
# section keeps the canonical 4 (CI / Interactive / Airplane / Full) — these
# are reached via the "Scenario Library" submenu (hotkey: S).

# ── Network-Restored Toggle (NETWORK_RESTORED_FLUSH_EPIC demo moment) ─────────

# Headline demo for NF-001…NF-011. Flips airplane on, drives a failing booking,
# flips airplane off, asserts events arrive within 5s. Default-on watcher hook
# — no DSL config required.
run_network_restored_toggle() {
  log "Network-Restored Toggle Demo (NF-001…NF-011)"
  echo ""
  echo "  This proves the demo gap reported on 2026-05-12 is closed."
  echo "  Sequence: airplane ON → failed booking → airplane OFF → events"
  echo "  arrive in Dash0 in ~3s. No app restart, no DSL config."
  echo ""

  # Sanity: app must be installed + launched
  if ! adb -s "$SERIAL" shell pm list packages 2>/dev/null | grep -q "$PACKAGE$"; then
    err "App not installed. Run 'b' from main menu first."
    return 1
  fi

  log "Phase 1: ensure app is foregrounded"
  launch_app
  sleep 2

  log "Phase 2: enable airplane mode"
  adb -s "$SERIAL" shell settings put global airplane_mode_on 1
  adb -s "$SERIAL" shell su 0 cmd connectivity airplane-mode enable 2>/dev/null || \
    adb -s "$SERIAL" shell am broadcast -a android.intent.action.AIRPLANE_MODE 2>/dev/null || true
  sleep 1
  ok "Airplane mode: ON"

  log "Phase 3: drive a failing booking (POST will fail — that's the point)"
  # Tap the booking button via UiAutomator — fall back to deeplink if available
  adb -s "$SERIAL" shell am broadcast -a io.opentelemetry.android.demo.action.DEMO_TAP \
    --es target booking 2>/dev/null || true
  echo "  ${_D}(Manual: tap 'Submit Booking' in the app now if needed.)${_R}"
  sleep 3

  log "Phase 4: disable airplane mode — watcher should fire flushWindow(60)"
  adb -s "$SERIAL" shell settings put global airplane_mode_on 0
  adb -s "$SERIAL" shell su 0 cmd connectivity airplane-mode disable 2>/dev/null || \
    adb -s "$SERIAL" shell am broadcast -a android.intent.action.AIRPLANE_MODE 2>/dev/null || true
  ok "Airplane mode: off"

  log "Phase 5: wait 5s for OS to settle + watcher to fire + flush to drain"
  for i in 5 4 3 2 1; do printf "  ${_D}%d ${_R}" "$i"; sleep 1; done
  echo ""

  log "Phase 6: validate — events should be in destination"
  smart_validate
}

# ── Network-Restored without crash (lighter variant) ─────────────────────────

run_network_restored_lite() {
  log "Network-Restored Lite — no failing operation, just transition probe"
  echo ""
  echo "  Verifies the watcher fires on a clean LOST → AVAILABLE transition."
  echo "  Use this when you just want to see the logcat 'Network restored —'"
  echo "  line appear without staging a booking failure."
  echo ""
  launch_app
  sleep 1
  adb -s "$SERIAL" logcat -c
  log "logcat cleared. Toggling airplane on, then off in 4s…"
  adb -s "$SERIAL" shell settings put global airplane_mode_on 1
  sleep 2
  adb -s "$SERIAL" shell settings put global airplane_mode_on 0
  sleep 4
  log "Searching logcat for the watcher trace…"
  if adb -s "$SERIAL" logcat -d -t 100 | grep -E "Network restored|MobileLoggerProvider.*flush hook" | head -3; then
    ok "Watcher fired."
  else
    err "No watcher trace found. Verify with: adb logcat | grep MobileLoggerProvider"
  fi
}

# ── User-Journey Booking Demo (UJ epic) ──────────────────────────────────────

run_user_journey_demo() {
  log "User-Journey Demo — wraps the whole booking flow in a journey span"
  echo ""
  echo "  Drives the canonical 3-screen booking flow. Each tap becomes a child"
  echo "  span of the journey root, with screenshots + wireframes captured at"
  echo "  key transitions. Look in Dash0 for the journey.* span hierarchy."
  echo ""
  launch_app
  sleep 2
  log "Dispatching journey trigger broadcast"
  adb -s "$SERIAL" shell am broadcast -a io.opentelemetry.android.demo.action.RUN_JOURNEY \
    --es journey booking 2>/dev/null || \
    echo "  ${_YL}Broadcast not wired in this build — drive manually via the UI.${_R}"
  sleep 5
  smart_validate
}

# ── Selective Flush Showcase ────────────────────────────────────────────────

run_selective_flush_showcase() {
  log "Selective Flush Showcase"
  echo ""
  echo "  20 events buffered silently → trigger fires → exactly the 2-minute"
  echo "  window flushes. Shows the 'conditional' mode value prop:"
  echo "  battery-efficient buffering with on-demand context."
  echo ""
  launch_app
  sleep 2
  log "Generating 20 baseline events over 30s…"
  for i in $(seq 1 20); do
    adb -s "$SERIAL" shell am broadcast -a io.opentelemetry.android.demo.action.EMIT_EVENT \
      --es kind tap --es target "showcase_$i" 2>/dev/null || true
    sleep 1.5
  done
  log "Triggering selective flush"
  adb -s "$SERIAL" shell am broadcast -a io.opentelemetry.android.demo.action.TRIGGER_FLUSH \
    --es reason demo 2>/dev/null || true
  sleep 5
  smart_validate
}

# ── UAT Matrix Cell Runner ───────────────────────────────────────────────────

# Drives one row of the 12-cell UAT matrix. Picks (mode × connectivity × crash)
# and delegates to the existing scripts/test/uat/run-uat-cell.sh.
run_uat_cell() {
  local mode="${1:-}" conn="${2:-}" crash="${3:-}"
  if [ -z "$mode" ] || [ -z "$conn" ] || [ -z "$crash" ]; then
    echo ""
    echo "  Pick a UAT matrix cell:"
    echo ""
    echo "    ${_B}Mode${_R}"
    echo "      1) CONT — Continuous"
    echo "      2) COND — Conditional (the default)"
    echo "      3) HYB  — Hybrid"
    echo ""
    echo "    ${_B}Connectivity${_R}"
    echo "      a) online"
    echo "      b) offline (airplane mode pre-flight)"
    echo ""
    echo "    ${_B}Crash${_R}"
    echo "      x) no crash"
    echo "      y) crash (real RuntimeException)"
    echo ""
    echo -ne "  ${_CY}›${_R} Three-char selection (e.g. ${_B}2ay${_R} = COND+online+crash): "
    read -r combo
    case "${combo:0:1}" in 1) mode=cont;; 2) mode=cond;; 3) mode=hyb;; *) err "Bad mode"; return 1;; esac
    case "${combo:1:1}" in a) conn=online;; b) conn=offline;; *) err "Bad connectivity"; return 1;; esac
    case "${combo:2:1}" in x) crash=no;; y) crash=yes;; *) err "Bad crash"; return 1;; esac
  fi
  log "Running UAT cell: platform=android-native, mode=$mode, connectivity=$conn, crash=$crash"
  "$SCRIPT_DIR/uat/run-uat-cell.sh" \
    --platform=android-native --mode="$mode" --connectivity="$conn" --crash="$crash"
}

# ── Cross-Platform One-Shots ─────────────────────────────────────────────────

# Each one-shot delegates to the platform's own validate script. They're not
# "run all matrix cells" — they're "smoke this platform end-to-end so I can
# say it works".

run_ios_native_smoke() {
  log "iOS Native Smoke Test (validate-ios-end-to-end.sh)"
  if [ ! -f "$REPO_ROOT/validate-ios-end-to-end.sh" ]; then
    err "validate-ios-end-to-end.sh not found at repo root"
    return 1
  fi
  "$REPO_ROOT/validate-ios-end-to-end.sh"
}

run_rn_android_smoke() {
  log "RN Android Smoke Test"
  if [ ! -f "$SCRIPT_DIR/validate-rn-end-to-end.sh" ]; then
    err "validate-rn-end-to-end.sh not found in scripts/test/"
    return 1
  fi
  "$SCRIPT_DIR/validate-rn-end-to-end.sh" --platform=android --mode=jest
}

run_rn_ios_smoke() {
  log "RN iOS Smoke Test"
  if [ ! -f "$SCRIPT_DIR/validate-rn-end-to-end.sh" ]; then
    err "validate-rn-end-to-end.sh not found in scripts/test/"
    return 1
  fi
  "$SCRIPT_DIR/validate-rn-end-to-end.sh" --platform=ios --mode=jest
}

# ── Submenu renderer ─────────────────────────────────────────────────────────

show_scenario_library() {
  while true; do
    clear
    echo ""
    echo -e "  ${_B}${_CY}Scenario Library${_R}  ${_D}—  beyond the canonical 4${_R}"
    echo -e "  ${_D}$(printf '%.0s═' {1..54})${_R}"
    echo ""

    _section "NETWORK-RESTORED FLUSH (NF-001…NF-011)"
    _item "n" "Airplane toggle demo ${_D}(boot → airplane on → fail booking → airplane off → flush)${_R}"
    _item "N" "Clean transition probe ${_D}(no booking, just verify watcher fires)${_R}"

    _section "JOURNEYS & FLUSH SHOWCASES"
    _item "j" "User-journey booking demo ${_D}(spans + screenshots + wireframes)${_R}"
    _item "S" "Selective flush showcase ${_D}(20 silent events → trigger → window flush)${_R}"

    _section "UAT MATRIX"
    _item "u" "Run one UAT cell ${_D}(pick mode × connectivity × crash)${_R}"
    _item "U" "Run full Android-native 12-cell matrix ${_D}(~15 min)${_R}"

    _section "CROSS-PLATFORM SMOKES"
    _item "i" "iOS native smoke ${_D}(validate-ios-end-to-end.sh)${_R}"
    _item "p" "RN Android smoke"
    _item "P" "RN iOS smoke"

    echo ""
    echo -e "  ${_D}$(printf '%.0s─' {1..54})${_R}"
    _item "q" "Back to main menu"
    echo ""
    echo -ne "  ${_CY}›${_R} "
    read -r choice

    case "$choice" in
      n) run_network_restored_toggle ;;
      N) run_network_restored_lite ;;
      j) run_user_journey_demo ;;
      S) run_selective_flush_showcase ;;
      u) run_uat_cell ;;
      U) "$SCRIPT_DIR/uat/run-uat-matrix.sh" --platform=android-native ;;
      i) run_ios_native_smoke ;;
      p) run_rn_android_smoke ;;
      P) run_rn_ios_smoke ;;
      q) return ;;
      *) echo -e "  ${_RD}Unknown option:${_R} $choice" ;;
    esac

    echo ""
    echo -ne "  ${_D}Press ENTER to return to scenario library${_R}"
    read -r
  done
}
