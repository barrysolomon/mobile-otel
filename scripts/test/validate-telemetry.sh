#!/usr/bin/env bash
# Validate that expected telemetry was received by the local collector.
#
# Prerequisites: run the collector + scenarios first:
#   ./run-validated-tests.sh
#
# Or manually:
#   1. docker compose -f scripts/test/collector/docker-compose.yaml up -d
#   2. Run scenario tests with endpoint http://10.0.2.2:4317
#   3. ./scripts/test/validate-telemetry.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/collector/output"

log()  { echo -e "\033[1;36m▸ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }
warn() { echo -e "\033[1;33m  ⚠ $*\033[0m"; }

PASS=0; FAIL=0; WARN=0

check_signal() {
  local file=$1
  local pattern=$2
  local description=$3
  local required=${4:-true}

  if [ ! -f "$file" ]; then
    if [ "$required" = true ]; then
      err "$description — file not found: $file"
      FAIL=$((FAIL + 1))
    else
      warn "$description — file not found (optional)"
      WARN=$((WARN + 1))
    fi
    return
  fi

  if grep -q "$pattern" "$file" 2>/dev/null; then
    ok "$description"
    PASS=$((PASS + 1))
  else
    if [ "$required" = true ]; then
      err "$description — pattern not found: $pattern"
      FAIL=$((FAIL + 1))
    else
      warn "$description — not found (optional)"
      WARN=$((WARN + 1))
    fi
  fi
}

echo ""
log "Validating telemetry received by local collector"
echo "   Output dir: $OUTPUT_DIR"
echo ""

# ── Logs ─────────────────────────────────────────────────────────────────────

log "Log signals"

check_signal "$OUTPUT_DIR/logs.json" "ui.tap" \
  "ui.tap events (tap instrumentation)"

check_signal "$OUTPUT_DIR/logs.json" "ui.screen_view" \
  "ui.screen_view events (screen instrumentation)"

check_signal "$OUTPUT_DIR/logs.json" "ui.scroll" \
  "ui.scroll events (scroll instrumentation)" false

check_signal "$OUTPUT_DIR/logs.json" "ui.back_press" \
  "ui.back_press events (back-press instrumentation)" false

check_signal "$OUTPUT_DIR/logs.json" "app.foreground\|app.background" \
  "app.foreground/background events (lifecycle instrumentation)"

check_signal "$OUTPUT_DIR/logs.json" "device.orientation" \
  "device.orientation events (screen-orientation instrumentation)" false

check_signal "$OUTPUT_DIR/logs.json" "device.battery\|device.power\|device.storage" \
  "system events (system-events instrumentation)" false

check_signal "$OUTPUT_DIR/logs.json" "session.id" \
  "session.id attribute present on log events"

check_signal "$OUTPUT_DIR/logs.json" "view.id" \
  "view.id attribute present on log events"

check_signal "$OUTPUT_DIR/logs.json" "screen.name" \
  "screen.name attribute present on log events"

# ── Traces ───────────────────────────────────────────────────────────────────

log "Trace signals"

check_signal "$OUTPUT_DIR/traces.json" "page\\." \
  "page.* spans (screen view page spans)"

check_signal "$OUTPUT_DIR/traces.json" "http" \
  "HTTP spans (network instrumentation)" false

# ── Metrics ──────────────────────────────────────────────────────────────────

log "Metric signals"

check_signal "$OUTPUT_DIR/metrics.json" "jvm\|process\|device\|app" \
  "Device/app metrics (vitals instrumentation)" false

# ── Service identity ─────────────────────────────────────────────────────────

log "Service identity"

check_signal "$OUTPUT_DIR/logs.json" "service.name" \
  "service.name resource attribute"

check_signal "$OUTPUT_DIR/logs.json" "device.id" \
  "device.id resource attribute"

# ── Summary ──────────────────────────────────────────────────────────────────

echo ""
echo "══════════════════════════════════════"
echo "  Passed:  $PASS"
echo "  Failed:  $FAIL"
echo "  Warned:  $WARN (optional signals)"
echo "══════════════════════════════════════"

if [ $FAIL -gt 0 ]; then
  echo ""
  err "$FAIL required signal(s) missing!"
  exit 1
else
  echo ""
  ok "All required signals validated!"
fi
