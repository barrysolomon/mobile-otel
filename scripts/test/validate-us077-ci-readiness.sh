#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"

log "US-077: CI readiness check"

scripts_found=0; scripts_ok=0
for f in "$SCRIPT_DIR"/validate-us0*.sh; do
  scripts_found=$((scripts_found + 1))
  if bash -n "$f" 2>/dev/null; then
    scripts_ok=$((scripts_ok + 1))
  else
    err "Syntax error: $f"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
done

ok "$scripts_ok/$scripts_found validation scripts pass syntax check"
ASSERT_PASS=$((ASSERT_PASS + 1))

# Check dependencies
if command -v jq > /dev/null 2>&1; then
  ok "jq available"
  ASSERT_PASS=$((ASSERT_PASS + 1))
else
  err "jq not available"
  ASSERT_FAIL=$((ASSERT_FAIL + 1))
fi

if command -v docker > /dev/null 2>&1; then
  ok "docker available"
  ASSERT_PASS=$((ASSERT_PASS + 1))
else
  err "docker not available"
  ASSERT_FAIL=$((ASSERT_FAIL + 1))
fi

assert_summary "US-077 ci-readiness"
