#!/usr/bin/env bash
# validate-ios-end-to-end.sh
#
# Backwards-compat entry point — forwards to the UI-driven path.
# Prefer `validate-ios-uidriven.sh` directly in new automation.
#
# Historical context: this script used to drive a Timer-based
# `AutoDemoDriver` via `SIMCTL_CHILD_DASH0_AUTO_DEMO=1`, bypassing the
# SwiftUI UI entirely. That gave fast, deterministic emissions but
# wasn't true UI-driven telemetry — it short-circuited the
# CartViewModel state machine and the gesture system. We retired that
# path and now drive every emission via XCUIApplication taps on real
# SwiftUI buttons (`AstronomyShopUITests/AstronomyShopJourneyUITest`).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/validate-ios-uidriven.sh" "$@"
