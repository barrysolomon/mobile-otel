#!/usr/bin/env bash
# US-071 (iOS): Span parent-child integrity. The 14-span checkout trace
# should have the `checkout` root as a parentless span and 13 children
# with otel.parent.id set. Mirrors Android's validate-us071-span-hierarchy.sh.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib-ios/run-astronomy-demo.sh"

log "US-071 (iOS): checkout span hierarchy"
run_astronomy_demo_window

log "Assert: expected checkout span names present"
NAMES="$(dash0_span_names "$IOS_SCENARIO_START" "$IOS_SCENARIO_END")"
MISSING=()
for name in checkout checkout.validate_cart checkout.inventory_check \
            inventory.check_item checkout.calculate_totals totals.subtotal \
            totals.tax totals.shipping checkout.charge payment.validate_card \
            payment.authorize checkout.send_confirmation email.render email.send \
            checkout.analytics.report; do
    grep -qxF "$name" <<< "$NAMES" || MISSING+=("$name")
done
if (( ${#MISSING[@]} > 0 )); then
    printf "  ✗ missing: %s\n" "${MISSING[@]}"
    fail "checkout span tree is incomplete"
fi
ok "All 15 checkout span names observed"

log "Assert: child spans have non-empty otel.parent.id"
PARENT_COUNT="$(dash0_span_parent_count "$IOS_SCENARIO_START" "$IOS_SCENARIO_END")"
# One full checkout has 13 child spans (1 parent + 13 descendants). Auto-demo
# emits at least one checkout in a 75s window, so we expect >= 13.
if [[ "$PARENT_COUNT" -lt 13 ]]; then
    fail "expected >= 13 spans with otel.parent.id, got $PARENT_COUNT"
fi
ok "Parent-child integrity: $PARENT_COUNT child spans observed"

ok "US-071 (iOS) PASS"
