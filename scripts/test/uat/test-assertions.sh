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

# --- warn::within ---
( warn::within "test_within_inside" 105 100 10 ) >/dev/null 2>&1
assert_exit_code 0 $? "warn::within returns 0 when observed inside tolerance"

( warn::within "test_within_outside" 200 100 10 ) >/dev/null 2>&1
assert_exit_code 0 $? "warn::within returns 0 even when observed outside tolerance (warn-tier)"

( warn::within "test_within_nonnumeric" "" 100 10 ) >/dev/null 2>&1
assert_exit_code 0 $? "warn::within returns 0 on non-numeric input (warn-tier)"

# --- numeric guards on must:: helpers ---
( must::ge "test_ge_empty" "" 3 ) >/dev/null 2>&1
assert_exit_code 1 $? "must::ge returns 1 on empty observed"

( must::zero "test_zero_text" "abc" ) >/dev/null 2>&1
assert_exit_code 1 $? "must::zero returns 1 on non-numeric observed"

# --- JSONL is valid JSON for numeric, string, and empty observed ---
validate_json() {
    local label="$1" line="$2"
    if printf '%s' "$line" | python3 -c 'import sys,json; json.loads(sys.stdin.read())' 2>/dev/null; then
        PASSED=$((PASSED + 1)); echo "  PASS  $label"
    else
        FAILED=$((FAILED + 1)); echo "  FAIL  $label: not valid JSON: $line"
    fi
}

# Capture only stdout (the emitted JSONL line) — strip the trailing [PASS]/[FAIL] log line.
emit_line=$(must::eq "test_jsonl_num" 5 5 2>/dev/null | head -n 1)
validate_json "must::eq emits valid JSON for numeric observed" "$emit_line"

emit_line=$(must::eq "test_jsonl_str" "hello" "hello" 2>/dev/null | head -n 1)
validate_json "must::eq emits valid JSON for string observed" "$emit_line"

emit_line=$(must::ge "test_jsonl_empty" "" 3 2>/dev/null | head -n 1)
validate_json "must::ge emits valid JSON for empty observed" "$emit_line"

emit_line=$(must::eq "test_jsonl_quotes" 'has"quote' 'other' 2>/dev/null | head -n 1)
validate_json "must::eq emits valid JSON when observed contains quotes" "$emit_line"

# --- field shape: tier/gate/observed present and observed is JSON-string ---
output=$(must::eq "test_jsonl" 5 5 2>/dev/null | head -n 1)
case "$output" in
    *'"tier":"must"'*'"observed":"5"'*) PASSED=$((PASSED + 1)); echo "  PASS  must::eq JSONL has expected fields and string-quoted observed" ;;
    *) FAILED=$((FAILED + 1)); echo "  FAIL  must::eq JSONL fields wrong:  $output" ;;
esac

echo
echo "Results: $PASSED passed, $FAILED failed"
[[ $FAILED -eq 0 ]] && exit 0 || exit 1
