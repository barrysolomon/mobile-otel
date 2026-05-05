#!/usr/bin/env bash
# Android-native primitive library for the UAT matrix runner.
#
# Sourced by `run-uat-cell.sh` when --platform=android-native. Provides
# the per-mode install/launch/lifecycle/crash/probe primitives every
# matrix cell composes. All primitives are pure-bash 3.2 compatible
# (no associative arrays — uses `case` dispatch).
#
# Requires:
#   - `adb` on PATH
#   - At least one booted emulator/device (`adb devices` lists it)
#   - APKs already built via `:upstream-demo-app:assembleDash0<Mode>Debug`
#
# Environment variables:
#   UAT_REPO_ROOT    Repo root (defaults to $PWD if unset). APK paths are
#                    resolved relative to this.
#   UAT_ADB_SERIAL   Optional `-s <serial>` to target a specific device
#                    when multiple are connected. If unset, adb default.

set -u

# ─────────────────────────────────────────────────────────────────────
# Internal: per-export-mode lookup tables
# ─────────────────────────────────────────────────────────────────────

# AppIds match the build.gradle.kts applicationIdSuffix in the dash0*
# product flavors (commit 5dfd6cc).
__uat_android_pkg_for_mode() {
    case "$1" in
        cont) echo "io.opentelemetry.android.demo.dash0.cont" ;;
        cond) echo "io.opentelemetry.android.demo.dash0.cond" ;;
        hyb)  echo "io.opentelemetry.android.demo.dash0.hyb" ;;
        *) echo "ERROR: unknown export mode: $1" >&2; return 1 ;;
    esac
}

__uat_android_apk_for_mode() {
    local mode="$1"
    local repo_root="${UAT_REPO_ROOT:-$(pwd)}"
    local base="$repo_root/examples/upstream-demo-app/build/outputs/apk"
    case "$mode" in
        cont) echo "$base/dash0Continuous/debug/upstream-demo-app-dash0Continuous-debug.apk" ;;
        cond) echo "$base/dash0Conditional/debug/upstream-demo-app-dash0Conditional-debug.apk" ;;
        hyb)  echo "$base/dash0Hybrid/debug/upstream-demo-app-dash0Hybrid-debug.apk" ;;
        *) echo "ERROR: unknown export mode: $1" >&2; return 1 ;;
    esac
}

# Internal: dispatch adb with optional `-s <serial>`.
__uat_adb() {
    if [[ -n "${UAT_ADB_SERIAL:-}" ]]; then
        adb -s "$UAT_ADB_SERIAL" "$@"
    else
        adb "$@"
    fi
}

# ─────────────────────────────────────────────────────────────────────
# Primitives — every cell composes from these
# ─────────────────────────────────────────────────────────────────────

# uat::install <mode>
# Uninstall any prior version of the package to guarantee a fresh
# session/cell_id, then install the matching APK.
uat::install() {
    local mode="$1"
    local apk pkg
    apk=$(__uat_android_apk_for_mode "$mode") || return 1
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    if [[ ! -f "$apk" ]]; then
        echo "ERROR: APK not found: $apk" >&2
        echo "       Run: ./gradlew :upstream-demo-app:assembleDash0$(__uat_android_flavor_suffix "$mode")Debug" >&2
        return 2
    fi
    __uat_adb uninstall "$pkg" >/dev/null 2>&1 || true
    __uat_adb install -r "$apk" >/dev/null
}

# uat::launch <mode> <cell_id>
# Cold-launches MainActivity with --es DASH0_CELL_ID <cell_id>. The
# launcher activity reads it in onCreate and stamps it as a resource
# attribute on every emitted record (Task 0.3, commit d3c8dcd).
uat::launch() {
    local mode="$1" cell_id="$2"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    if [[ -z "$cell_id" ]]; then
        echo "ERROR: uat::launch requires non-empty cell_id" >&2
        return 1
    fi
    __uat_adb shell am start \
        -n "${pkg}/io.opentelemetry.android.demo.MainActivity" \
        --es DASH0_CELL_ID "$cell_id" >/dev/null
}

# uat::offline / uat::online — toggle wifi + cellular on the emulator.
uat::offline() {
    __uat_adb shell svc wifi disable
    __uat_adb shell svc data disable
}

uat::online() {
    __uat_adb shell svc wifi enable
    __uat_adb shell svc data enable
}

# uat::cycle_lifecycle <mode> — background → foreground once.
# Used by Gate 1 cells to exercise app.foreground / app.background emission.
#
# Sleeps must exceed ProcessLifecycleOwner's 700ms debounce (otherwise
# rapid HOME → am start collapses into a no-op, verified 2026-05-05 by
# observing fg=2/bg=1 instead of expected fg=3/bg=2 for two cycles).
uat::cycle_lifecycle() {
    local mode="$1"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    __uat_adb shell input keyevent KEYCODE_HOME
    sleep 3
    __uat_adb shell am start -n "${pkg}/io.opentelemetry.android.demo.MainActivity" >/dev/null
    sleep 2
}

# uat::trigger_crash <mode> — fire the demo's gate3_crash extra.
# Mirrors iOS's -DASH0_CRASH_NOW launch arg. The MainActivity reads the
# gate3_crash boolean extra in onResume and triggers multiThreadCrashing()
# 3s later. We do NOT use `am crash`: that signals the system, not the
# app, and produces no demo-app telemetry.
uat::trigger_crash() {
    local mode="$1"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    __uat_adb shell am start \
        -n "${pkg}/io.opentelemetry.android.demo.MainActivity" \
        --ez gate3_crash true >/dev/null
}

# uat::cleanup <mode> — uninstall the package; cell-end teardown.
uat::cleanup() {
    local mode="$1"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    __uat_adb uninstall "$pkg" >/dev/null 2>&1 || true
}

# uat::probe_disk_buffer <mode>
# Returns the count of buffered (not-yet-exported) log records in the
# SDK's on-device SQLite buffer. Used by Gate 4 cells to assert the
# disk-buffer drained after recovery, or stayed populated under offline.
#
# Schema (otel-android-mobile/.../DiskLogBuffer.kt):
#   - DB:    otel_log_buffer.db (Room)
#   - Table: log_records
#
# Requires the APK to be debuggable (it is — `:debug` flavor).
uat::probe_disk_buffer() {
    local mode="$1"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    local count
    count=$(__uat_adb shell "run-as $pkg sqlite3 databases/otel_log_buffer.db 'SELECT COUNT(*) FROM log_records'" 2>/dev/null | tr -d '\r')
    # `run-as` fails silently on non-debuggable APKs or wrong package;
    # treat empty/non-numeric as 0 so callers can `must::ge`/`must::eq`
    # without spurious assertion failures from infra noise.
    case "$count" in
        ''|*[!0-9]*) echo "0" ;;
        *)           echo "$count" ;;
    esac
}

# Internal: map mode → flavor suffix used in the gradle task name.
# Used for the build-it-first hint in uat::install's error message.
__uat_android_flavor_suffix() {
    case "$1" in
        cont) echo "Continuous" ;;
        cond) echo "Conditional" ;;
        hyb)  echo "Hybrid" ;;
        *)    echo "<unknown>" ;;
    esac
}
