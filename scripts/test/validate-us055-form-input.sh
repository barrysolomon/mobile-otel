#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"

log "US-055: Form input lifecycle"

assert_event_exists "$LOGS" "ui.tap" "tap events (provider/slot selection)"
assert_pattern_exists "$LOGS" "ui.text_input\|text_input" "text input event" false
assert_pattern_exists "$LOGS" "BookFragment\|book" "BookFragment visited"

# Form submission
assert_pattern_exists "$LOGS" "form.submitted\|booking\|form" "form submission event" false

# Device context on booking
assert_pattern_exists "$LOGS" "device.model\|device.manufacturer" "device context attributes" false

assert_summary "US-055 form-input"
