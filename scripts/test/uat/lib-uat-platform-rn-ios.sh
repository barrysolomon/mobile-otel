#!/usr/bin/env bash
# RN-iOS primitive library for the UAT matrix runner.
#
# Sourced by `run-uat-cell.sh` when --platform=rn-ios. Same single-binary
# launch-arg approach as ios-native — one .app, three launch-arg sets.
#
# The RN iOS app reads -DASH0_EXPORT_MODE and -DASH0_CELL_ID in
# OTelMobileCallSink.start() to override the JS-provided config.
#
# Requires:
#   - Xcode CLI tools (`xcodebuild`, `xcrun simctl`)
#   - At least one booted iOS Simulator
#   - .app already built (via `xcodebuild -workspace ...`)
#
# Environment variables:
#   UAT_REPO_ROOT       Repo root (defaults to $PWD if unset)
#   UAT_IOS_SIMULATOR   Simulator device name or UDID (default: "iPhone 17 Pro")
#   DEVELOPER_DIR       Xcode path (default: /Applications/Xcode.app/Contents/Developer)

set -u

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

# ─────────────────────────────────────────────────────────────────────
# Internal helpers
# ─────────────────────────────────────────────────────────────────────

__uat_rnios_repo_root() {
    echo "${UAT_REPO_ROOT:-$(pwd)}"
}

__uat_rnios_sim() {
    echo "${UAT_IOS_SIMULATOR:-iPhone 17 Pro}"
}

__uat_rnios_sim_udid() {
    xcrun simctl list devices available -j \
        | python3 -c "
import json, sys
data = json.load(sys.stdin)
target = '$(__uat_rnios_sim)'
for runtime, devs in data.get('devices', {}).items():
    for d in devs:
        if (d['name'] == target or d['udid'] == target) and d['isAvailable']:
            print(d['udid'])
            sys.exit(0)
print('ERROR: simulator not found: ' + target, file=sys.stderr)
sys.exit(1)
"
}

__uat_rnios_bundle_id() {
    echo "org.reactjs.native.example.AstronomyShopRN"
}

__uat_rnios_app_path() {
    local repo_root
    repo_root="$(__uat_rnios_repo_root)"
    local build_dir="$repo_root/examples/upstream-demo-app-rn/AstronomyShopRN/ios/build/Build/Products/Debug-iphonesimulator"
    echo "$build_dir/AstronomyShopRN.app"
}

__uat_rnios_export_mode_long() {
    case "$1" in
        cont) echo "continuous" ;;
        cond) echo "conditional" ;;
        hyb)  echo "hybrid" ;;
        *) echo "ERROR: unknown mode: $1" >&2; return 1 ;;
    esac
}

# ─────────────────────────────────────────────────────────────────────
# Primitives (called by run-uat-cell.sh)
# ─────────────────────────────────────────────────────────────────────

uat::install() {
    local mode="$1"
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    local app_path
    app_path="$(__uat_rnios_app_path)"

    if [[ ! -d "$app_path" ]]; then
        echo "ERROR: .app not found at $app_path — build first" >&2
        return 1
    fi

    # Uninstall first to clear stale crash markers from previous runs
    local bundle_id
    bundle_id="$(__uat_rnios_bundle_id)"
    xcrun simctl uninstall "$udid" "$bundle_id" >/dev/null 2>&1 || true
    xcrun simctl install "$udid" "$app_path" >/dev/null 2>&1
}

uat::launch() {
    local mode="$1"
    local cell_id="${2:-}"
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    local bundle_id
    bundle_id="$(__uat_rnios_bundle_id)"
    local mode_long
    mode_long="$(__uat_rnios_export_mode_long "$mode")" || return 1

    xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true

    local args=(-DASH0_EXPORT_MODE "$mode_long")
    if [[ -n "$cell_id" ]]; then
        args+=(-DASH0_CELL_ID "$cell_id")
    fi

    xcrun simctl launch "$udid" "$bundle_id" "${args[@]}" >/dev/null
}

uat::cycle_lifecycle() {
    local mode="$1"
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    local bundle_id
    bundle_id="$(__uat_rnios_bundle_id)"

    # Background: open Settings (simctl ui home removed in iOS 26+)
    xcrun simctl openurl "$udid" "App-prefs:root=General" >/dev/null 2>&1 || true
    sleep 2
    # Foreground: re-launch (same PID, does NOT restart)
    xcrun simctl launch "$udid" "$bundle_id" >/dev/null 2>&1 || true
}

uat::trigger_crash() {
    local mode="$1"
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    local bundle_id
    bundle_id="$(__uat_rnios_bundle_id)"
    local mode_long
    mode_long="$(__uat_rnios_export_mode_long "$mode")" || return 1

    xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true
    sleep 1
    # RN iOS crash fires 5s after boot (vs native's 1.5s) — JS bridge + bundle
    # eval + Dash0Mobile.start() must complete before the signal handler is installed
    xcrun simctl launch "$udid" "$bundle_id" \
        -DASH0_EXPORT_MODE "$mode_long" \
        -DASH0_CRASH_NOW \
        >/dev/null 2>&1 || true
}

uat::force_stop() {
    local mode="$1"
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    local bundle_id
    bundle_id="$(__uat_rnios_bundle_id)"
    xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true
}

uat::offline() {
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    xcrun simctl io "$udid" setnetwork off 2>/dev/null || \
        xcrun simctl spawn "$udid" /usr/sbin/networksetup -setairportpower en0 off 2>/dev/null || \
        echo "WARN: simctl network isolation not available" >&2
    sudo -n pfctl -t uat_block -T add 0.0.0.0/0 2>/dev/null || true
}

uat::online() {
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    xcrun simctl io "$udid" setnetwork on 2>/dev/null || \
        xcrun simctl spawn "$udid" /usr/sbin/networksetup -setairportpower en0 on 2>/dev/null || true
    sudo -n pfctl -t uat_block -T flush 2>/dev/null || true
}

uat::cleanup() {
    local mode="$1"
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    local bundle_id
    bundle_id="$(__uat_rnios_bundle_id)"

    xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true
    xcrun simctl uninstall "$udid" "$bundle_id" >/dev/null 2>&1 || true
    xcrun simctl io "$udid" setnetwork on 2>/dev/null || true
    sudo -n pfctl -t uat_block -T flush 2>/dev/null || true
}

uat::probe_disk_buffer() {
    local mode="$1"
    local udid
    udid="$(__uat_rnios_sim_udid)" || return 1
    local bundle_id
    bundle_id="$(__uat_rnios_bundle_id)"

    local sim_data
    sim_data="$(xcrun simctl get_app_container "$udid" "$bundle_id" data 2>/dev/null)" || {
        echo "0"
        return 0
    }
    local db_path="$sim_data/Library/Caches/dash0_disk_log_buffer.sqlite"
    if [[ -f "$db_path" ]]; then
        sqlite3 "$db_path" "SELECT COUNT(*) FROM buffered_events;" 2>/dev/null || echo "0"
    else
        echo "0"
    fi
}
