#!/usr/bin/env bash
# Unit tests for lib-uat-assertions.sh. Pure-bash, no external deps.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib-uat-assertions.sh"

PASSED=0
FAILED=0

assert_exit_code() {
    local expected=$1 actual=$2 label=$3
    if [[ "$expected" == "$actual" ]]; then
        PASSED=$((PASSED + 1))
        echo "  PASS  $label"
    else
        FAILED=$((FAILED + 1))
        echo "  FAIL  $label  (expected exit $expected, got $actual)"
    fi
}

# --- must::eq ---
( must::eq "test_eq_match" 5 5 ) >/dev/null 2>&1
assert_exit_code 0 $? "must::eq returns 0 when values equal"

( must::eq "test_eq_mismatch" 5 6 ) >/dev/null 2>&1
assert_exit_code 1 $? "must::eq returns 1 when values differ"

# --- must::ge ---
( must::ge "test_ge_equal" 3 3 ) >/dev/null 2>&1
assert_exit_code 0 $? "must::ge returns 0 when observed == expected"

( must::ge "test_ge_above" 5 3 ) >/dev/null 2>&1
assert_exit_code 0 $? "must::ge returns 0 when observed > expected"

( must::ge "test_ge_below" 2 3 ) >/dev/null 2>&1
assert_exit_code 1 $? "must::ge returns 1 when observed < expected"

# --- must::zero ---
( must::zero "test_zero_match" 0 ) >/dev/null 2>&1
assert_exit_code 0 $? "must::zero returns 0 when value is 0"

( must::zero "test_zero_nonzero" 1 ) >/dev/null 2>&1
assert_exit_code 1 $? "must::zero returns 1 when value is nonzero"

# --- warn::eq does NOT exit ---
( warn::eq "test_warn_eq_mismatch" 5 6 ) >/dev/null 2>&1
assert_exit_code 0 $? "warn::eq never returns nonzero (mismatch is just a warning)"

# --- JSONL emission ---
output=$(must::eq "test_jsonl" 5 5 2>&1 || true)
case "$output" in
    *'"tier":"must"'*'"observed":5'*) PASSED=$((PASSED + 1)); echo "  PASS  must::eq emits expected JSONL fields" ;;
    *) FAILED=$((FAILED + 1)); echo "  FAIL  must::eq JSONL missing expected fields:  $output" ;;
esac

echo
echo "Results: $PASSED passed, $FAILED failed"
[[ $FAILED -eq 0 ]] && exit 0 || exit 1
