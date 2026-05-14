#!/usr/bin/env bash
# UAT matrix assertion helpers — tiered must-pass / soft-warn.
# Each helper writes a JSONL line to UAT_EVIDENCE_FILE if it is set;
# must-pass helpers exit nonzero on failure, warn-pass helpers do not.
# All helpers are pure-bash 3.2 compatible.

set -u

# Internal: is the value a finite integer? (bash 3.2-safe, no regex flags.)
__uat_is_int() {
    case "$1" in
        ''|*[!0-9-]*) return 1 ;;
        -|*-*-*)      return 1 ;;
        -*[!0-9]*)    return 1 ;;
        *)            return 0 ;;
    esac
}

# Internal: emit one JSONL assertion line.
# `observed` is always serialized as a JSON string (quoted + escaped) so the
# row is valid JSON regardless of whether the caller passed a number, empty
# string, or arbitrary text. Downstream consumers can coerce with jq/tonumber.
__uat_emit() {
    local tier="$1" gate="$2" claim="$3" observed="$4" passed="$5"
    local esc_claim="${claim//\\/\\\\}"; esc_claim="${esc_claim//\"/\\\"}"
    local esc_obs="${observed//\\/\\\\}"; esc_obs="${esc_obs//\"/\\\"}"
    local line
    line="{\"tier\":\"${tier}\",\"gate\":\"${gate}\",\"claim\":\"${esc_claim}\",\"observed\":\"${esc_obs}\",\"passed\":${passed}}"
    if [[ -n "${UAT_EVIDENCE_FILE:-}" ]]; then
        echo "$line" >> "$UAT_EVIDENCE_FILE"
    fi
    echo "$line"
}

# must::eq <name> <observed> <expected> — exit 1 on mismatch.
must::eq() {
    local name="$1" observed="$2" expected="$3"
    if [[ "$observed" == "$expected" ]]; then
        __uat_emit "must" "$name" "observed == $expected" "$observed" "true"
        echo "[PASS] must $name: $observed == $expected"
    else
        __uat_emit "must" "$name" "observed == $expected" "$observed" "false"
        echo "[FAIL] must $name: $observed != $expected" >&2
        return 1
    fi
}

# must::ge <name> <observed> <expected> — exit 1 if observed < expected.
must::ge() {
    local name="$1" observed="$2" expected="$3"
    if ! __uat_is_int "$observed" || ! __uat_is_int "$expected"; then
        __uat_emit "must" "$name" "observed >= $expected" "$observed" "false"
        echo "[FAIL] must $name: non-numeric input (observed='$observed' expected='$expected')" >&2
        return 1
    fi
    if [[ "$observed" -ge "$expected" ]]; then
        __uat_emit "must" "$name" "observed >= $expected" "$observed" "true"
        echo "[PASS] must $name: $observed >= $expected"
    else
        __uat_emit "must" "$name" "observed >= $expected" "$observed" "false"
        echo "[FAIL] must $name: $observed < $expected" >&2
        return 1
    fi
}

# must::zero <name> <observed> — exit 1 if observed != 0.
must::zero() {
    local name="$1" observed="$2"
    if ! __uat_is_int "$observed"; then
        __uat_emit "must" "$name" "observed == 0" "$observed" "false"
        echo "[FAIL] must $name: non-numeric input (observed='$observed')" >&2
        return 1
    fi
    if [[ "$observed" -eq 0 ]]; then
        __uat_emit "must" "$name" "observed == 0" "$observed" "true"
        echo "[PASS] must $name: $observed == 0"
    else
        __uat_emit "must" "$name" "observed == 0" "$observed" "false"
        echo "[FAIL] must $name: $observed != 0" >&2
        return 1
    fi
}

# warn::eq <name> <observed> <expected> — log only, never returns nonzero.
warn::eq() {
    local name="$1" observed="$2" expected="$3"
    if [[ "$observed" == "$expected" ]]; then
        __uat_emit "warn" "$name" "observed == $expected" "$observed" "true"
        echo "[ OK ] warn $name: $observed == $expected"
    else
        __uat_emit "warn" "$name" "observed == $expected" "$observed" "false"
        echo "[WARN] warn $name: $observed != $expected (drift)" >&2
    fi
    return 0
}

# must::within_window <name> <outside_count> — exit 1 if any events fell
# outside the cell's [T0, T1] wall-clock window. `outside_count` is the
# number of events whose device timestamp lies before T0 - tolerance or
# after T1 + tolerance. The caller computes this from OTLP records via
# __uat_count_outside_window (run-uat-cell.sh).
#
# This gate catches: clock skew, batch-stamping at flush time, lost
# observedTimeUnixNano, and any future regression where the SDK reports
# a time other than when the event actually fired on-device.
must::within_window() {
    local name="$1" outside="$2"
    if ! __uat_is_int "$outside"; then
        __uat_emit "must" "$name" "outside_window == 0" "$outside" "false"
        echo "[FAIL] must $name: non-numeric input (outside='$outside')" >&2
        return 1
    fi
    if [[ "$outside" -eq 0 ]]; then
        __uat_emit "must" "$name" "outside_window == 0" "$outside" "true"
        echo "[PASS] must $name: 0 events outside [T0,T1]"
    else
        __uat_emit "must" "$name" "outside_window == 0" "$outside" "false"
        echo "[FAIL] must $name: $outside events outside [T0,T1]" >&2
        return 1
    fi
}

# warn::within <name> <observed> <expected> <tolerance_pct> — log only.
warn::within() {
    local name="$1" observed="$2" expected="$3" tol_pct="$4"
    if ! __uat_is_int "$observed" || ! __uat_is_int "$expected" || ! __uat_is_int "$tol_pct"; then
        __uat_emit "warn" "$name" "observed within ${tol_pct}% of $expected" "$observed" "false"
        echo "[WARN] warn $name: non-numeric input (observed='$observed' expected='$expected' tol='$tol_pct')" >&2
        return 0
    fi
    # Integer math; tolerance as a whole-percent value (0-100).
    local margin=$(( expected * tol_pct / 100 ))
    [[ $margin -lt 0 ]] && margin=$(( -margin ))
    local hi=$(( expected + margin ))
    local lo=$(( expected - margin ))
    if [[ "$observed" -ge "$lo" && "$observed" -le "$hi" ]]; then
        __uat_emit "warn" "$name" "observed within ${tol_pct}% of $expected" "$observed" "true"
        echo "[ OK ] warn $name: $observed within ±${tol_pct}% of $expected"
    else
        __uat_emit "warn" "$name" "observed within ${tol_pct}% of $expected" "$observed" "false"
        echo "[WARN] warn $name: $observed outside ±${tol_pct}% of $expected" >&2
    fi
    return 0
}
