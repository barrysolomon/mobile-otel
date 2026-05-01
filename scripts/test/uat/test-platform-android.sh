#!/usr/bin/env bash
# Hermetic unit tests for lib-uat-platform-android.sh.
#
# We don't need a real adb here — we stub __uat_adb to record what each
# primitive *would* have done, then assert on the recorded call shape.
# This catches structural bugs (wrong package id, wrong flag name, wrong
# launcher activity) cheaply without booting an emulator.
#
# Pure-bash 3.2 compatible.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib-uat-platform-android.sh"

# ─────────────────────────────────────────────────────────────────────
# Test rig: stub out __uat_adb to record args.
#
# We write to a tempfile rather than a variable because some primitives
# call __uat_adb inside `$(...)` for stdout capture, and `$(...)` runs
# in a subshell where global-variable writes don't propagate back to
# the parent. A tempfile is visible across subshells.
# ─────────────────────────────────────────────────────────────────────

ADB_LOG_FILE="$(mktemp -t uat-adb-log.XXXXXX)"
trap 'rm -f "$ADB_LOG_FILE"' EXIT

__uat_adb() {
    echo "$*" >> "$ADB_LOG_FILE"
}

reset_log() { : > "$ADB_LOG_FILE"; }
adb_log() { cat "$ADB_LOG_FILE"; }

PASSED=0
FAILED=0

assert_exit_code() {
    local expected="$1" actual="$2" label="$3"
    if [[ "$expected" == "$actual" ]]; then
        PASSED=$((PASSED + 1))
        echo "  PASS  $label"
    else
        FAILED=$((FAILED + 1))
        echo "  FAIL  $label  (expected exit $expected, got $actual)"
    fi
}

assert_log_contains() {
    local needle="$1" label="$2"
    local log
    log="$(adb_log)"
    case "$log" in
        *"$needle"*) PASSED=$((PASSED + 1)); echo "  PASS  $label" ;;
        *) FAILED=$((FAILED + 1)); echo "  FAIL  $label  (log did not contain '$needle': $log)" ;;
    esac
}

assert_eq() {
    local expected="$1" actual="$2" label="$3"
    if [[ "$expected" == "$actual" ]]; then
        PASSED=$((PASSED + 1))
        echo "  PASS  $label"
    else
        FAILED=$((FAILED + 1))
        echo "  FAIL  $label  (expected '$expected', got '$actual')"
    fi
}

# ─────────────────────────────────────────────────────────────────────
# Per-mode lookup tables
# ─────────────────────────────────────────────────────────────────────

assert_eq "io.opentelemetry.android.demo.dash0.cont" \
    "$(__uat_android_pkg_for_mode cont)" \
    "pkg lookup: cont"
assert_eq "io.opentelemetry.android.demo.dash0.cond" \
    "$(__uat_android_pkg_for_mode cond)" \
    "pkg lookup: cond"
assert_eq "io.opentelemetry.android.demo.dash0.hyb" \
    "$(__uat_android_pkg_for_mode hyb)" \
    "pkg lookup: hyb"

( __uat_android_pkg_for_mode bogus ) >/dev/null 2>&1
assert_exit_code 1 $? "pkg lookup rejects unknown mode"

# APK paths must reference the per-flavor build outputs and the new
# (commit 5dfd6cc) flavor names.
UAT_REPO_ROOT=/r
case "$(__uat_android_apk_for_mode cont)" in
    */examples/upstream-demo-app/build/outputs/apk/dash0Continuous/debug/upstream-demo-app-dash0Continuous-debug.apk)
        PASSED=$((PASSED + 1)); echo "  PASS  apk path: cont resolves to dash0Continuous"
        ;;
    *) FAILED=$((FAILED + 1)); echo "  FAIL  apk path cont: $(__uat_android_apk_for_mode cont)" ;;
esac
case "$(__uat_android_apk_for_mode hyb)" in
    */dash0Hybrid/debug/upstream-demo-app-dash0Hybrid-debug.apk)
        PASSED=$((PASSED + 1)); echo "  PASS  apk path: hyb resolves to dash0Hybrid"
        ;;
    *) FAILED=$((FAILED + 1)); echo "  FAIL  apk path hyb: $(__uat_android_apk_for_mode hyb)" ;;
esac
unset UAT_REPO_ROOT

# ─────────────────────────────────────────────────────────────────────
# uat::install — missing APK must surface a useful error
# ─────────────────────────────────────────────────────────────────────

reset_log
UAT_REPO_ROOT=/nonexistent
output=$(uat::install cont 2>&1)
rc=$?
unset UAT_REPO_ROOT
assert_exit_code 2 $rc "install: missing APK returns rc=2"
case "$output" in
    *"APK not found"*"assembleDash0ContinuousDebug"*)
        PASSED=$((PASSED + 1))
        echo "  PASS  install: error message points at correct gradle task"
        ;;
    *) FAILED=$((FAILED + 1)); echo "  FAIL  install: error message wrong: $output" ;;
esac

# ─────────────────────────────────────────────────────────────────────
# uat::launch — must include cell_id as --es DASH0_CELL_ID
# ─────────────────────────────────────────────────────────────────────

reset_log
uat::launch cont "smoke-uuid-0001"
assert_log_contains "shell am start" "launch: invokes am start"
assert_log_contains "io.opentelemetry.android.demo.dash0.cont/io.opentelemetry.android.demo.MainActivity" \
    "launch: targets cont package's MainActivity"
assert_log_contains "--es DASH0_CELL_ID smoke-uuid-0001" \
    "launch: passes cell_id as DASH0_CELL_ID extra"

reset_log
( uat::launch cont "" ) >/dev/null 2>&1
assert_exit_code 1 $? "launch: empty cell_id rejected"

# ─────────────────────────────────────────────────────────────────────
# uat::offline / uat::online — both wifi and data toggled
# ─────────────────────────────────────────────────────────────────────

reset_log
uat::offline
assert_log_contains "svc wifi disable" "offline: disables wifi"
assert_log_contains "svc data disable" "offline: disables cellular"

reset_log
uat::online
assert_log_contains "svc wifi enable" "online: enables wifi"
assert_log_contains "svc data enable" "online: enables cellular"

# ─────────────────────────────────────────────────────────────────────
# uat::cycle_lifecycle — HOME keyevent followed by re-launch
# ─────────────────────────────────────────────────────────────────────

reset_log
uat::cycle_lifecycle cond
assert_log_contains "input keyevent KEYCODE_HOME" "cycle: backgrounds via HOME"
assert_log_contains "io.opentelemetry.android.demo.dash0.cond/io.opentelemetry.android.demo.MainActivity" \
    "cycle: re-foregrounds the cond package"

# ─────────────────────────────────────────────────────────────────────
# uat::trigger_crash — gate3_crash boolean extra to MainActivity (NOT `am crash`)
# ─────────────────────────────────────────────────────────────────────

reset_log
uat::trigger_crash hyb
assert_log_contains "io.opentelemetry.android.demo.dash0.hyb/io.opentelemetry.android.demo.MainActivity" \
    "crash: targets hyb's MainActivity"
assert_log_contains "--ez gate3_crash true" \
    "crash: uses gate3_crash extra (not am crash)"
case "$(adb_log)" in
    *"am crash"*) FAILED=$((FAILED + 1)); echo "  FAIL  crash: must NOT use 'am crash' — produces no demo-app telemetry" ;;
    *) PASSED=$((PASSED + 1)); echo "  PASS  crash: avoids 'am crash'" ;;
esac

# ─────────────────────────────────────────────────────────────────────
# uat::cleanup — uninstalls
# ─────────────────────────────────────────────────────────────────────

reset_log
uat::cleanup cont
assert_log_contains "uninstall io.opentelemetry.android.demo.dash0.cont" \
    "cleanup: uninstalls cont package"

# ─────────────────────────────────────────────────────────────────────
# uat::probe_disk_buffer — references correct DB + table names,
# coerces non-numeric output to 0 so must::ge can consume it
# ─────────────────────────────────────────────────────────────────────

reset_log
out=$(uat::probe_disk_buffer cont)
assert_log_contains "run-as io.opentelemetry.android.demo.dash0.cont sqlite3 databases/otel_log_buffer.db" \
    "probe_disk_buffer: queries otel_log_buffer.db (not buffer.db)"
assert_log_contains "SELECT COUNT(*) FROM log_records" \
    "probe_disk_buffer: queries log_records table (not buffered_events)"
# With our stub, __uat_adb produces no stdout, so probe falls through
# to the empty-string → "0" coercion path. That's exactly the behavior
# we want for `must::ge "buffer_drained" "$(uat::probe_disk_buffer cont)" 0`.
assert_eq "0" "$out" "probe_disk_buffer: coerces empty output to '0'"

echo
echo "Results: $PASSED passed, $FAILED failed"
[[ $FAILED -eq 0 ]] && exit 0 || exit 1
