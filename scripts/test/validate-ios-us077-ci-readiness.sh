#!/usr/bin/env bash
# US-077 (iOS): CI readiness — fast-running gates the CI pipeline must pass
# before merging the iPhone branch. Runs the iOS SDK unit tests, bash-syntax-
# checks all iOS scenario scripts, and verifies xcodegen + xcodebuild are
# present. Mirrors Android's validate-us077-ci-readiness.sh.
#
# This script does NOT launch the simulator — it's the "everything except
# real telemetry" gate. Run the other validate-ios-us*.sh scripts to cover
# the telemetry path.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib-ios/dash0-mcp.sh"

log "US-077 (iOS): CI readiness gates"

# --- 1. Toolchain ---
log "Gate: Xcode + xcodegen available"
command -v xcodebuild >/dev/null || fail "xcodebuild not on PATH"
command -v /opt/homebrew/bin/xcodegen >/dev/null || fail "xcodegen missing (install: brew install xcodegen)"
command -v jq >/dev/null || fail "jq missing"
command -v python3 >/dev/null || fail "python3 missing"
ok "Toolchain present"

# --- 2. iOS SDK unit tests (host, fast) ---
log "Gate: iOS SDK unit tests"
(
    cd "$REPO_ROOT/otel-ios-mobile"
    ./run-tests.sh
) >/tmp/ios-ci-tests.log 2>&1
if ! grep -q "Test run with .* tests in .* suites passed" /tmp/ios-ci-tests.log; then
    tail -40 /tmp/ios-ci-tests.log >&2
    fail "iOS SDK unit tests did not pass"
fi
TEST_COUNT="$(grep -oE 'Test run with [0-9]+ tests' /tmp/ios-ci-tests.log | grep -oE '[0-9]+' | head -1)"
ok "iOS SDK unit tests pass (${TEST_COUNT} tests)"

# --- 3. Bash-syntax-check all iOS scenario scripts ---
# macOS default bash is 3.2 — no `mapfile` / `readarray`. Use a NUL-separated
# stream + while-read loop instead.
log "Gate: scenario scripts are syntactically valid"
SCRIPT_COUNT=0; FAIL_COUNT=0; FAILED_LIST=""
while IFS= read -r -d '' s; do
    SCRIPT_COUNT=$((SCRIPT_COUNT + 1))
    if ! bash -n "$s" 2>/dev/null; then
        FAIL_COUNT=$((FAIL_COUNT + 1))
        FAILED_LIST="$FAILED_LIST $s"
    fi
done < <(find "$REPO_ROOT/scripts/test" -maxdepth 2 \( -name 'validate-ios-*.sh' -o -name 'validate-ios-end-to-end.sh' \) -print0)
if [[ "$FAIL_COUNT" -gt 0 ]]; then
    printf "  ✗ %s\n" $FAILED_LIST
    fail "$FAIL_COUNT scenario script(s) failed bash -n"
fi
ok "All $SCRIPT_COUNT scenario scripts parse clean"

# --- 4. Lib-ios helpers syntactically valid ---
log "Gate: lib-ios helpers parse clean"
for lib in "$REPO_ROOT/scripts/test/lib-ios/"*.sh; do
    bash -n "$lib" || fail "$lib has syntax errors"
done
ok "lib-ios/*.sh parse clean"

# --- 5. otel-config.json is present (even if only the template) ---
log "Gate: AstronomyShop has an otel-config.json or template"
if [[ ! -f "$REPO_ROOT/examples/upstream-demo-app-ios/AstronomyShop/otel-config.json" ]] && \
   [[ ! -f "$REPO_ROOT/examples/upstream-demo-app-ios/AstronomyShop/otel-config.json.template" ]]; then
    fail "no otel-config.json{,.template} in AstronomyShop"
fi
ok "config file present"

ok "US-077 (iOS) PASS — ${TEST_COUNT:-0} tests green, $SCRIPT_COUNT scenario scripts valid"
