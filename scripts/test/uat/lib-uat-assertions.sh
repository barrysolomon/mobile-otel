#!/usr/bin/env bash
# UAT matrix assertion helpers — tiered must-pass / soft-warn.
# Each helper writes a JSONL line to UAT_EVIDENCE_FILE if it is set;
# must-pass helpers exit nonzero on failure, warn-pass helpers do not.
# All helpers are pure-bash 3.2 compatible.

set -u

# Internal: emit one JSONL assertion line.
__uat_emit() {
    local tier="$1" gate="$2" claim="$3" observed="$4" passed="$5"
    local line
    line="{\"tier\":\"${tier}\",\"gate\":\"${gate}\",\"claim\":\"${claim//\"/\\\"}\",\"observed\":${observed},\"passed\":${passed}}"
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
    if [[ "$observed" -ge "$expected" ]] 2>/dev/null; then
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
    if [[ "$observed" -eq 0 ]] 2>/dev/null; then
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

# warn::within <name> <observed> <expected> <tolerance_pct> — log only.
warn::within() {
    local name="$1" observed="$2" expected="$3" tol_pct="$4"
    # Integer math; tolerance as a whole-percent value (0-100).
    local hi=$(( expected + (expected * tol_pct / 100) ))
    local lo=$(( expected - (expected * tol_pct / 100) ))
    if [[ "$observed" -ge "$lo" && "$observed" -le "$hi" ]] 2>/dev/null; then
        __uat_emit "warn" "$name" "observed within ${tol_pct}% of $expected" "$observed" "true"
        echo "[ OK ] warn $name: $observed within ±${tol_pct}% of $expected"
    else
        __uat_emit "warn" "$name" "observed within ${tol_pct}% of $expected" "$observed" "false"
        echo "[WARN] warn $name: $observed outside ±${tol_pct}% of $expected" >&2
    fi
    return 0
}
