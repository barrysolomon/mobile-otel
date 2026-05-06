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
#
# Robustness against matrix mode: the prior cell's app process may still
# be alive when this runs (especially after a fast cell_lifecycle), and
# Android's package manager can return DELETE_FAILED_INTERNAL_ERROR if
# uninstall races with a running process. We force-stop, uninstall with
# a short retry, then install.
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

    # Stop any running instance — otherwise uninstall can fail and the
    # next launch attaches to the existing process (with the previous
    # cell's cell_id baked in).
    __uat_adb shell am force-stop "$pkg" >/dev/null 2>&1 || true
    sleep 1

    # Uninstall with retry; ignore "package not installed" but propagate
    # real failures so the runner aborts the cell instead of silently
    # running on stale state.
    local i out
    for i in 1 2 3; do
        out=$(__uat_adb uninstall "$pkg" 2>&1)
        if [[ "$out" == *"Success"* || "$out" == *"not installed"* ]]; then
            break
        fi
        if [[ "$i" -lt 3 ]]; then
            sleep 2
        fi
    done

    out=$(__uat_adb install -r "$apk" 2>&1)
    if [[ "$out" != *"Success"* ]]; then
        echo "ERROR: install failed: $out" >&2
        return 1
    fi
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
    # Explicit force-stop (-S on am start is unreliable on API 36 — it
    # logs "intent has been delivered to currently running top-most
    # instance" and skips cold-start). force-stop guarantees onCreate
    # fires and reads the new DASH0_CELL_ID.
    __uat_adb shell am force-stop "$pkg" >/dev/null 2>&1 || true
    sleep 1
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
    local pkg tmpdb count
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    # API 36 emulators don't ship `sqlite3` (verified 2026-05-05). We
    # exfiltrate the DB to the host and query it locally with the host's
    # sqlite3 (macOS ships it at /usr/bin/sqlite3). Earlier on-device
    # probe silently returned 0 even when the buffer had rows.
    if ! command -v sqlite3 >/dev/null 2>&1; then
        echo "0"
        return
    fi
    tmpdb="/tmp/uat-buffer-${pkg}-$$.db"
    if __uat_adb shell "run-as $pkg cat databases/otel_log_buffer.db" > "$tmpdb" 2>/dev/null && [[ -s "$tmpdb" ]]; then
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
