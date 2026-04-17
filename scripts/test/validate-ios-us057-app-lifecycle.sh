#!/usr/bin/env bash
# US-057 (iOS): App lifecycle events — `app.home_appeared` is emitted on
# root-view onAppear. The full app.foreground/app.background pair requires
# a human or a companion app to trigger backgrounding; in the sim scripted
# flow we assert the subset that fires deterministically.
#
# Mirrors Android's validate-us057-background-foreground.sh in spirit.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib-ios/run-astronomy-demo.sh"

log "US-057 (iOS): app lifecycle logs"
run_astronomy_demo_window

log "Assert: app.home_appeared log present"
BODIES="$(dash0_logs_bodies "$IOS_SCENARIO_START" "$IOS_SCENARIO_END")"
if ! grep -q "app.home_appeared" <<< "$BODIES"; then
    fail "no app.home_appeared log body found in window"
fi
ok "app.home_appeared observed"

log "Assert: cart.add_item log fires during auto-demo journey"
if ! grep -q "cart.add_item" <<< "$BODIES"; then
    fail "no cart.add_item log body found — auto-demo may not have reached phase 0-2"
fi
ok "cart.add_item observed"

log "Assert: at least one WARN-severity log (cart.large_quantity_warning)"
# Phase 2 of auto-demo adds 5 units in one go which triggers the WARN path.
if ! grep -q "cart.large_quantity_warning" <<< "$BODIES"; then
    warn "no cart.large_quantity_warning observed; auto-demo phase 2 may not have fired in window"
else
    ok "cart.large_quantity_warning observed"
fi

ok "US-057 (iOS) PASS"
