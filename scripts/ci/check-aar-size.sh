#!/usr/bin/env bash
# AAR size budget gate (TEST_HARDENING_PLAN P1).
#
# Catches dependency bloat before consumers do: a new transitive dependency
# or accidentally-bundled resource shows up here as a hard CI failure, not as
# a surprised integrator's bug report. The umbrella AAR measured 564 KiB on
# 2026-06-12; the budget leaves ~20% headroom for organic growth.
#
# To raise the budget: change AAR_SIZE_BUDGET_BYTES below IN THE SAME PR that
# grows the AAR, with the justification in the PR description — the point is
# that growth is a reviewed decision, never a drive-by.
#
# Usage: check-aar-size.sh <path-to-aar>
set -euo pipefail

AAR="${1:?usage: check-aar-size.sh <path-to-aar>}"
AAR_SIZE_BUDGET_BYTES=700000

if [ ! -f "$AAR" ]; then
    echo "::error::AAR not found at $AAR — did assembleRelease run?"
    exit 1
fi

SIZE=$(wc -c < "$AAR" | tr -d ' ')
PCT=$((SIZE * 100 / AAR_SIZE_BUDGET_BYTES))
echo "AAR size: ${SIZE} bytes (${PCT}% of the ${AAR_SIZE_BUDGET_BYTES}-byte budget) — $AAR"

if [ "$SIZE" -gt "$AAR_SIZE_BUDGET_BYTES" ]; then
    echo "::error::AAR exceeds its size budget: ${SIZE} > ${AAR_SIZE_BUDGET_BYTES} bytes. \
Mobile SDK weight is a consumer-facing cost. If the growth is intentional, raise \
AAR_SIZE_BUDGET_BYTES in scripts/ci/check-aar-size.sh in this PR and justify it there."
    exit 1
fi
echo "AAR size budget OK."
