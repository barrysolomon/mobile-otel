#!/usr/bin/env bash
# RN Android primitive library for the UAT matrix runner.
#
# Sourced by `run-uat-cell.sh` when --platform=rn-android. Mirrors
# lib-uat-platform-android.sh but with RN-specific package names, APK
# paths, and activity component names. All buffering/policy/export runs
# in the same Android SDK — only the app shell differs.
#
# Requires:
#   - `adb` on PATH
#   - At least one booted emulator/device
#   - APKs already built via the RN demo's gradle flavors
#
# Environment variables:
#   UAT_REPO_ROOT    Repo root (defaults to $PWD if unset)
#   UAT_ADB_SERIAL   Optional `-s <serial>` for specific device

set -u

# ─────────────────────────────────────────────────────────────────────
# Internal: per-export-mode lookup tables
# ─────────────────────────────────────────────────────────────────────

__uat_rna_pkg_for_mode() {
    case "$1" in
        cont) echo "com.astronomyshoprn.dash0.cont" ;;
        cond) echo "com.astronomyshoprn.dash0.cond" ;;
        hyb)  echo "com.astronomyshoprn.dash0.hyb" ;;
        *) echo "ERROR: unknown export mode: $1" >&2; return 1 ;;
    esac
}

__uat_rna_apk_for_mode() {
    local mode="$1"
    local repo_root="${UAT_REPO_ROOT:-$(pwd)}"
    local base="$repo_root/examples/upstream-demo-app-rn/AstronomyShopRN/android/app/build/outputs/apk"
    case "$mode" in
        cont) echo "$base/dash0Continuous/debug/app-dash0Continuous-debug.apk" ;;
        cond) echo "$base/dash0Conditional/debug/app-dash0Conditional-debug.apk" ;;
        hyb)  echo "$base/dash0Hybrid/debug/app-dash0Hybrid-debug.apk" ;;
        *) echo "ERROR: unknown export mode: $1" >&2; return 1 ;;
    esac
}

__uat_rna_adb() {
    if [[ -n "${UAT_ADB_SERIAL:-}" ]]; then
        adb -s "$UAT_ADB_SERIAL" "$@"
    else
        adb "$@"
    fi
}

# ─────────────────────────────────────────────────────────────────────
# Primitives — every cell composes from these
# ─────────────────────────────────────────────────────────────────────

uat::install() {
    local mode="$1"
    local apk pkg
    apk=$(__uat_rna_apk_for_mode "$mode") || return 1
    pkg=$(__uat_rna_pkg_for_mode "$mode") || return 1
    if [[ ! -f "$apk" ]]; then
        echo "ERROR: APK not found: $apk" >&2
        echo "       Build with: cd examples/upstream-demo-app-rn/AstronomyShopRN/android && ./gradlew app:assembleDash0$(__uat_rna_flavor_suffix "$mode")Debug" >&2
        return 2
    fi

    __uat_rna_adb shell am force-stop "$pkg" >/dev/null 2>&1 || true
    sleep 1

    local i out
    for i in 1 2 3; do
        out=$(__uat_rna_adb uninstall "$pkg" 2>&1)
        if [[ "$out" == *"Success"* || "$out" == *"not installed"* ]]; then
            break
        fi
        if [[ "$i" -lt 3 ]]; then
            sleep 2
        fi
    done

    out=$(__uat_rna_adb install -r "$apk" 2>&1)
    if [[ "$out" != *"Success"* ]]; then
        echo "ERROR: install failed: $out" >&2
        return 1
    fi
}

uat::launch() {
    local mode="$1" cell_id="$2"
    local pkg
    pkg=$(__uat_rna_pkg_for_mode "$mode") || return 1
    if [[ -z "$cell_id" ]]; then
        echo "ERROR: uat::launch requires non-empty cell_id" >&2
        return 1
    fi
    __uat_rna_adb shell am force-stop "$pkg" >/dev/null 2>&1 || true
    sleep 1
    __uat_rna_adb shell am start \
        -n "${pkg}/com.astronomyshoprn.MainActivity" \
        --es DASH0_CELL_ID "$cell_id" >/dev/null
}

uat::offline() {
    __uat_rna_adb shell settings put global airplane_mode_on 1
    __uat_rna_adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1
    __uat_rna_adb shell svc wifi disable
    __uat_rna_adb shell svc data disable
}

uat::online() {
    __uat_rna_adb shell settings put global airplane_mode_on 0
    __uat_rna_adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1
    __uat_rna_adb shell svc wifi enable
    __uat_rna_adb shell svc data enable
}

uat::cycle_lifecycle() {
    local mode="$1"
    local pkg
    pkg=$(__uat_rna_pkg_for_mode "$mode") || return 1
    __uat_rna_adb shell input keyevent KEYCODE_HOME
    sleep 3
    __uat_rna_adb shell am start -n "${pkg}/com.astronomyshoprn.MainActivity" >/dev/null
    sleep 2
}

uat::trigger_crash() {
    local mode="$1"
    local pkg
    pkg=$(__uat_rna_pkg_for_mode "$mode") || return 1
    __uat_rna_adb shell am start \
        --activity-single-top \
        -n "${pkg}/com.astronomyshoprn.MainActivity" \
        --ez gate3_crash true >/dev/null
}

uat::force_stop() {
    local mode="$1"
    local pkg
    pkg=$(__uat_rna_pkg_for_mode "$mode") || return 1
    __uat_rna_adb shell am force-stop "$pkg" >/dev/null 2>&1 || true
}

uat::cleanup() {
    local mode="$1"
    local pkg
    pkg=$(__uat_rna_pkg_for_mode "$mode") || return 1
    __uat_rna_adb uninstall "$pkg" >/dev/null 2>&1 || true
}

uat::probe_disk_buffer() {
    local mode="$1"
    local pkg tmpdb count
    pkg=$(__uat_rna_pkg_for_mode "$mode") || return 1
    if ! command -v sqlite3 >/dev/null 2>&1; then
        echo "0"
        return
    fi
    tmpdb="/tmp/uat-buffer-${pkg}-$$.db"
    if __uat_rna_adb shell "run-as $pkg cat databases/otel_log_buffer.db" > "$tmpdb" 2>/dev/null && [[ -s "$tmpdb" ]]; then
        count=$(sqlite3 "$tmpdb" 'SELECT COUNT(*) FROM log_records' 2>/dev/null)
    else
        count="0"
    fi
    rm -f "$tmpdb"
    case "$count" in
        ''|*[!0-9]*) echo "0" ;;
        *)           echo "$count" ;;
    esac
}

__uat_rna_flavor_suffix() {
    case "$1" in
        cont) echo "Continuous" ;;
        cond) echo "Conditional" ;;
        hyb)  echo "Hybrid" ;;
        *)    echo "<unknown>" ;;
    esac
}
