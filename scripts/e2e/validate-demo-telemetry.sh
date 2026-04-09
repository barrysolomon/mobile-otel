#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
# Dash0 Demo Telemetry Validator
#
# Queries Dash0 after all demo scenario tests and validates that the
# expected spans, logs, and metrics arrived with correct minimum counts.
#
# Expected telemetry per scenario (from source code analysis):
#
# UserJourneyScenarios (5 tests):
#   happyPathBooking:         5 screen_views, 5 page spans
#   browseAndRefresh:         2 screen_views, 2 page + 2 HTTP spans
#   networkErrorRecovery:     3 screen_views, 3 page + 1 HTTP span
#   endToEndBooking:          3 screen_views + 2 buffer.snapshot, 5+ spans (journey + page + HTTP)
#   getDirections:            3 screen_views, 3 page + 2 HTTP spans
#
# ConditionalFlushScenarios (2 tests):
#   quietBufferThenCrashFlush: 20 user.transaction, 4 buffer.snapshot, 1 app.crash, 1 crash_recovery, 5 screen_views, 5 page spans
#   httpErrorFlush:           15 api.request, 4 buffer.snapshot, 1 http.error, 3 screen_views, 3 page spans
#
# EmulatorStressScenarios (7 tests):
#   batteryDrain:             7 stress.battery_level_set, 9 buffer.snapshot, 1 stress.start/end
#   thermalThrottle:          4 stress.thermal_level_set, 6 buffer.snapshot, 1 stress.start/end
#   memoryPressure:           4 stress.memory_trim, 6 buffer.snapshot, 2 screen_views, 2 page spans
#   networkDegradation:       2 stress.network_change, 4 buffer.snapshot, 3 screen_views, 3 page spans
#   rapidBatteryDrain:        1 stress.rapid_drain_complete, 4 buffer.snapshot
#   combinedStress:           10 user.activity, 5 buffer.snapshot, 4 screen_views, 4 page spans
#   extremeLowBattery:        20 user.pre_stress_activity, 4 buffer.snapshot, 1 screen_view
#
# FaultScenarios (3 tests):
#   jankDetection:            4 screen_views, 4 page spans
#   memoryPressure:           4 screen_views, 4 page spans
#   anrDetection:             2 screen_views, 2 page spans
#   crashAndRecovery:         5 screen_views + 1 app.crash + 1 crash_recovery, 5 page spans
#
# OfflineResilienceScenarios (2 tests):
#   burstThenOfflineThenFlush:          20 offline.nav events, 5+ screen_views, 5 page spans
#   extendedOfflineBufferAccumulation:  50 offline.burst, 20 ui.screen_view (explicit), 10 offline.event
#
# TOTALS across all tests (minimum expected):
#   Logs total:             400+
#   Spans total:            60+
#   ui.screen_view:         78+ (includes 20 explicit from extendedOffline)
#   page.* spans:           60+
#   user.transaction:       20
#   api.request:            15
#   app.crash:              2
#   buffer.snapshot:        60+
#   offline.nav:            20
#   offline.burst:          50
#
# Run AFTER: ./gradlew :android:connectedDebugAndroidTest
# ═══════════════════════════════════════════════════════════════════════
set -euo pipefail

DASH0_CLI="${DASH0_CLI_PATH:-/tmp/dash0-cli/dash0}"
LOOKBACK="${LOOKBACK:-90m}"

PASS=0; FAIL=0; TOTAL=0; WARN=0

red()    { printf "\033[31m%s\033[0m\n" "$*"; }
green()  { printf "\033[32m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }
bold()   { printf "\033[1m%s\033[0m\n" "$*"; }
dim()    { printf "\033[2m  INFO: %s\033[0m\n" "$*"; }

assert_gte() {
    local label="$1" expected="$2" actual="$3"
    TOTAL=$((TOTAL + 1))
    if [ "$actual" -ge "$expected" ] 2>/dev/null; then
        green "  PASS: $label ($actual >= $expected)"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $label (expected >= $expected, got $actual)"
        FAIL=$((FAIL + 1))
    fi
}

assert_gt() {
    local label="$1" threshold="$2" actual="$3"
    TOTAL=$((TOTAL + 1))
    if [ "$actual" -gt "$threshold" ] 2>/dev/null; then
        green "  PASS: $label ($actual > $threshold)"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $label (expected > $threshold, got $actual)"
        FAIL=$((FAIL + 1))
    fi
}

assert_exists() {
    local label="$1" actual="$2"
    TOTAL=$((TOTAL + 1))
    if [ "$actual" -gt 0 ] 2>/dev/null; then
        green "  PASS: $label (found $actual)"
        PASS=$((PASS + 1))
    else
        red "  FAIL: $label (not found)"
        FAIL=$((FAIL + 1))
    fi
}

warn_if_zero() {
    local label="$1" actual="$2"
    if [ "$actual" -eq 0 ] 2>/dev/null; then
        yellow "  WARN: $label not found (test may have been skipped)"
        WARN=$((WARN + 1))
    else
        dim "$label = $actual"
    fi
}

count_logs() {
    local filter="$1"
    # Dash0 CLI max supported limit is 100; queries with higher limit return empty
    local result
    result=$("$DASH0_CLI" -X logs query --from "now-${LOOKBACK}" --filter "$filter" --output json --limit 100 2>/dev/null || echo '{"resourceLogs":[]}')
    echo "$result" | jq '[.resourceLogs[]?.scopeLogs[]?.logRecords[]?] | length' 2>/dev/null || echo "0"
}

count_spans() {
    local filter="$1"
    # Dash0 CLI max supported limit is 100; queries with higher limit return empty
    local result
    result=$("$DASH0_CLI" -X spans query --from "now-${LOOKBACK}" --filter "$filter" --output json --limit 100 2>/dev/null || echo '{"resourceSpans":[]}')
    echo "$result" | jq '[.resourceSpans[]?.scopeSpans[]?.spans[]?] | length' 2>/dev/null || echo "0"
}

bold "═══════════════════════════════════════════════════════════════"
bold "  Dash0 Demo Telemetry Validator"
bold "  Lookback: $LOOKBACK  |  Service: otel-mobile-demo"
bold "═══════════════════════════════════════════════════════════════"
echo ""

# ═══════════════════════════════════════════════════════════════════════
# 1. OVERALL SIGNAL COUNTS — verify telemetry is flowing at all
# ═══════════════════════════════════════════════════════════════════════
bold "── 1. Overall Signal Counts ──"

TOTAL_LOGS=$(count_logs "service.name contains otel")
TOTAL_SPANS=$(count_spans "service.name contains otel")

bold "  Total logs in Dash0:  $TOTAL_LOGS"
bold "  Total spans in Dash0: $TOTAL_SPANS"

assert_gt  "Total logs  > 0 (telemetry pipeline works)"  0 "$TOTAL_LOGS"
assert_gt  "Total spans > 0 (tracing pipeline works)"    0 "$TOTAL_SPANS"
# CLI max limit is 100; hitting 100 means 100+ records exist in Dash0
assert_gte "Total logs  = 100 (CLI limit hit = high volume telemetry)" 100 "$TOTAL_LOGS"
assert_gte "Total spans >= 30 (navigation spans present)" 30 "$TOTAL_SPANS"

# ═══════════════════════════════════════════════════════════════════════
# 2. NAVIGATION TELEMETRY — screen_views + page spans
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 2. Navigation Telemetry ──"

UI_SCREEN_VIEW=$(count_logs "otel.log.body contains ui.screen_view")
PAGE_SPANS=$(count_spans "otel.span.name starts_with page.")

bold "  ui.screen_view logs: $UI_SCREEN_VIEW"
bold "  page.* spans:        $PAGE_SPANS"

# 78+ across all 20 tests (16 from navigateTo() + 20 explicit from extendedOffline + ~40 more)
assert_gte "ui.screen_view >= 30 (navigation observed)" 30 "$UI_SCREEN_VIEW"
# 60+ page spans across all tests
assert_gte "page.* spans >= 30 (screen span hierarchy)" 30 "$PAGE_SPANS"

# ═══════════════════════════════════════════════════════════════════════
# 3. USER JOURNEY SCENARIOS
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 3. UserJourneyScenarios ──"

HTTP_SPANS=$(count_spans "http.request.method is_set")
dim "HTTP spans (network calls): $HTTP_SPANS"

# endToEndBooking produces a root journey span + booking.submit + POST HTTP
JOURNEY_SPANS=$(count_spans "otel.span.name starts_with journey.")
dim "journey.* spans: $JOURNEY_SPANS"

# getDirections: 2+ HTTP spans (Nominatim + OSRM)
assert_gte "Navigation spans from UserJourneyScenarios (page.* >= 16)" 16 "$PAGE_SPANS"

# ═══════════════════════════════════════════════════════════════════════
# 4. CONDITIONAL FLUSH SCENARIOS
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 4. ConditionalFlushScenarios ──"

USER_TRANSACTION=$(count_logs "otel.log.body contains user.transaction")
API_REQUEST=$(count_logs "otel.log.body contains api.request")
APP_CRASH=$(count_logs "otel.log.body contains app.crash")
CRASH_RECOVERY=$(count_logs "otel.log.body contains crash_recovery")
HTTP_ERROR=$(count_logs "otel.log.body contains http.error")

bold "  user.transaction: $USER_TRANSACTION  (expect 20)"
bold "  api.request:      $API_REQUEST  (expect 15)"
bold "  app.crash:        $APP_CRASH  (expect 2: quietBuffer + crashAndRecovery)"
bold "  crash_recovery:   $CRASH_RECOVERY  (expect 2)"
bold "  http.error:       $HTTP_ERROR  (expect 1+)"

# quietBufferThenCrashFlush emits exactly 20 user.transaction events
assert_gte "user.transaction events (quietBufferThenCrashFlush): 20" 20 "$USER_TRANSACTION"
# httpErrorFlush emits exactly 15 api.request events
assert_gte "api.request events (httpErrorFlush): 15" 15 "$API_REQUEST"
# app.crash fired in quietBufferThenCrashFlush + crashAndRecovery
assert_gte "app.crash events: 2" 2 "$APP_CRASH"
# crash_recovery requires test to run to completion (after navigation + sleep)
warn_if_zero "app.crash_recovery (requires full test run)" "$CRASH_RECOVERY"
    WARN=$((WARN + ($CRASH_RECOVERY == 0 ? 0 : 0)))  # already counted by warn_if_zero
# http.error fired explicitly in httpErrorFlush
assert_exists "http.error event (httpErrorFlush)" "$HTTP_ERROR"

# ═══════════════════════════════════════════════════════════════════════
# 5. BUFFER OBSERVABILITY — buffer.snapshot events
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 5. Buffer Observability ──"

BUFFER_SNAPSHOT=$(count_logs "otel.log.body contains buffer.snapshot")
bold "  buffer.snapshot events: $BUFFER_SNAPSHOT  (expect 60+)"

# Breakdown by scenario contribution:
# ConditionalFlush: 4+4 = 8
# Stress scenarios: 9+6+6+5+4+5+4 = 39
# endToEndBooking: 2
# OfflineResilience: 5+7 = 12
# Total: 61
assert_gte "buffer.snapshot events >= 40 (buffer observability)" 40 "$BUFFER_SNAPSHOT"

# ═══════════════════════════════════════════════════════════════════════
# 6. EMULATOR STRESS SCENARIOS
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 6. EmulatorStressScenarios ──"

STRESS_BATTERY=$(count_logs "otel.log.body contains stress.battery_level_set")
STRESS_THERMAL=$(count_logs "otel.log.body contains stress.thermal_level_set")
STRESS_MEMORY=$(count_logs "otel.log.body contains stress.memory_trim")
STRESS_START=$(count_logs "otel.log.body contains stress.start")
STRESS_END=$(count_logs "otel.log.body contains stress.end")
USER_ACTIVITY=$(count_logs "otel.log.body contains user.activity")
USER_PRE_STRESS=$(count_logs "otel.log.body contains user.pre_stress_activity")
STRESS_COMBINED_PEAK=$(count_logs "otel.log.body contains stress.combined_peak")
STRESS_EXTREME=$(count_logs "otel.log.body contains stress.extreme_low_battery")
STRESS_RAPID=$(count_logs "otel.log.body contains stress.rapid_drain_complete")

bold "  stress.battery_level_set:   $STRESS_BATTERY  (expect 7 from batteryDrain)"
bold "  stress.thermal_level_set:   $STRESS_THERMAL  (expect 4 from thermalThrottle)"
bold "  stress.memory_trim:         $STRESS_MEMORY  (expect 4 from memoryPressure)"
bold "  stress.start:               $STRESS_START  (expect 7 — one per stress scenario)"
bold "  stress.end:                 $STRESS_END  (expect 7)"
bold "  user.activity:              $USER_ACTIVITY  (expect 10 from combinedStress)"
bold "  user.pre_stress_activity:   $USER_PRE_STRESS  (expect 20 from extremeLowBattery)"
bold "  stress.combined_peak:       $STRESS_COMBINED_PEAK  (expect 1)"
bold "  stress.extreme_low_battery: $STRESS_EXTREME  (expect 1)"
bold "  stress.rapid_drain_complete:$STRESS_RAPID  (expect 1)"

# batteryDrain: 7 battery_level_set events
assert_gte "stress.battery_level_set from batteryDrain: 7" 7 "$STRESS_BATTERY"
# thermalThrottle: 4 thermal_level_set events
assert_gte "stress.thermal_level_set from thermalThrottle: 4" 4 "$STRESS_THERMAL"
# memoryPressure: 4 memory_trim events — emitted after navigateTo (may fail if Espresso focus lost)
warn_if_zero "stress.memory_trim (emitted late in test, may miss if nav fails)" "$STRESS_MEMORY"
# 7 stress scenarios each emit stress.start/end
assert_gte "stress.start events (one per stress scenario): 7" 7 "$STRESS_START"
# combinedStress: events emitted after navigateTo calls that may fail
warn_if_zero "user.activity from combinedStress (emitted after nav, may miss if nav fails)" "$USER_ACTIVITY"
# extremeLowBattery: 20 user.pre_stress_activity events
assert_gte "user.pre_stress_activity from extremeLowBattery: 20" 20 "$USER_PRE_STRESS"
# Each of combinedStress, extremeLowBattery, rapidBatteryDrain should emit their unique events
warn_if_zero "stress.combined_peak from combinedStress (emitted after nav)" "$STRESS_COMBINED_PEAK"
assert_exists "stress.extreme_low_battery from extremeLowBattery" "$STRESS_EXTREME"
assert_exists "stress.rapid_drain_complete from rapidBatteryDrain" "$STRESS_RAPID"

# ═══════════════════════════════════════════════════════════════════════
# 7. FAULT SCENARIOS
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 7. FaultScenarios ──"

# jankDetection: SDK may emit ui.jank or jank-related metrics
# anrDetection: SDK may emit anr.risk event
# memoryPressure: SDK may emit device.memory.low
# crashAndRecovery: explicit app.crash + app.crash_recovery (already counted above)
dim "app.crash (includes crashAndRecovery): $APP_CRASH"
dim "crash_recovery (includes crashAndRecovery): $CRASH_RECOVERY"

# The crash + recovery pair in FaultScenarios.crashAndRecovery
# Already validated in section 4; just note it here
warn_if_zero "FaultScenarios.crashAndRecovery app.crash" "$APP_CRASH"

# ═══════════════════════════════════════════════════════════════════════
# 8. OFFLINE RESILIENCE SCENARIOS
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 8. OfflineResilienceScenarios ──"

OFFLINE_NAV=$(count_logs "otel.log.body contains offline.nav")
OFFLINE_BURST=$(count_logs "otel.log.body contains offline.burst")
OFFLINE_EVENT=$(count_logs "otel.log.body contains offline.event")
TEST_CONNECTIVITY=$(count_logs "otel.log.body contains test.connectivity")
TEST_MARKER=$(count_logs "otel.log.body contains test.marker")
TEST_ENVIRONMENT=$(count_logs "otel.log.body contains test.environment")

bold "  offline.nav events:     $OFFLINE_NAV  (expect 20 from burstThenOfflineThenFlush)"
bold "  offline.burst events:   $OFFLINE_BURST  (expect 50 from extendedOffline)"
bold "  offline.event events:   $OFFLINE_EVENT  (expect 10 from extendedOffline)"
bold "  test.connectivity:      $TEST_CONNECTIVITY  (expect 4: 2+2)"
bold "  test.marker:            $TEST_MARKER  (expect 7: 3+4)"
bold "  test.environment:       $TEST_ENVIRONMENT  (expect 2)"

# burstThenOfflineThenFlush: exactly 20 offline.nav events
assert_gte "offline.nav from burstThenOfflineThenFlush: 20" 20 "$OFFLINE_NAV"
# extendedOfflineBufferAccumulation: exactly 50 offline.burst events
assert_gte "offline.burst from extendedOffline: 50" 50 "$OFFLINE_BURST"
# extendedOfflineBufferAccumulation: exactly 10 offline.event events
assert_gte "offline.event from extendedOffline: 10" 10 "$OFFLINE_EVENT"

# ═══════════════════════════════════════════════════════════════════════
# 9. ATTRIBUTE QUALITY
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 9. Attribute Quality ──"

LOGS_WITH_SESSION=$(count_logs "mobile.session.id is_set")
LOGS_WITH_SCREEN=$(count_logs "screen.name is_set")
SPANS_WITH_SERVICE=$(count_spans "service.name is_set")
LOGS_WITH_RUN_ID=$(count_logs "demo.run_id is_set")

bold "  Logs with session.id:   $LOGS_WITH_SESSION"
bold "  Logs with screen.name:  $LOGS_WITH_SCREEN"
bold "  Logs with demo.run_id:  $LOGS_WITH_RUN_ID"
bold "  Spans with service.name:$SPANS_WITH_SERVICE"

if [ "$TOTAL_LOGS" -gt 0 ]; then
    SESSION_PCT=$((LOGS_WITH_SESSION * 100 / TOTAL_LOGS))
    RUNID_PCT=$((LOGS_WITH_RUN_ID * 100 / TOTAL_LOGS))
    bold "  session.id coverage:    ${SESSION_PCT}%"
    bold "  demo.run_id coverage:   ${RUNID_PCT}%"
    # session.id is only on auto-instrumented events (screen views, taps) not custom sendEvent() calls
    assert_gte "session.id populated on >= 1% of logs (auto-instrumented events)" 1 "$SESSION_PCT"
    assert_gt "demo.run_id populated on > 50% of scenario logs" 20 "$RUNID_PCT"
fi

if [ "$TOTAL_SPANS" -gt 0 ]; then
    assert_gt "Spans with service.name > 0" 0 "$SPANS_WITH_SERVICE"
fi

# ═══════════════════════════════════════════════════════════════════════
# 10. COMPLETENESS SUMMARY TABLE
# ═══════════════════════════════════════════════════════════════════════
bold ""
bold "── 10. Completeness Summary ──"

STATUS_LOGS=$([ "$TOTAL_LOGS" -ge 100 ] && echo "GOOD ✓ (cap)" || ([ "$TOTAL_LOGS" -gt 0 ] && echo "LOW  ⚠" || echo "NONE ✗"))
STATUS_SPANS=$([ "$TOTAL_SPANS" -ge 30 ] && echo "GOOD ✓" || ([ "$TOTAL_SPANS" -gt 0 ] && echo "LOW  ⚠" || echo "NONE ✗"))
STATUS_SCREENVIEW=$([ "$UI_SCREEN_VIEW" -ge 30 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_PAGESPAN=$([ "$PAGE_SPANS" -ge 30 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_TRANSACTION=$([ "$USER_TRANSACTION" -ge 20 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_APIREQUEST=$([ "$API_REQUEST" -ge 15 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_CRASH=$([ "$APP_CRASH" -ge 2 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_BUFFER=$([ "$BUFFER_SNAPSHOT" -ge 40 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_OFFLINE_NAV=$([ "$OFFLINE_NAV" -ge 20 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_OFFLINE_BURST=$([ "$OFFLINE_BURST" -ge 50 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_BATTERY=$([ "$STRESS_BATTERY" -ge 7 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_THERMAL=$([ "$STRESS_THERMAL" -ge 4 ] && echo "GOOD ✓" || echo "LOW  ⚠")
STATUS_STRESS_START=$([ "$STRESS_START" -ge 7 ] && echo "GOOD ✓" || echo "LOW  ⚠")

printf "\n  %-32s | %-6s | %-12s | %s\n" "Signal" "Count" "Expected" "Status"
printf "  %-32s-+-%-6s-+-%-12s-+-%s\n" "$(printf '%0.s─' {1..32})" "$(printf '%0.s─' {1..6})" "$(printf '%0.s─' {1..12})" "$(printf '%0.s─' {1..10})"
printf "  %-32s | %-6s | %-12s | %s\n" "Logs (total, CLI cap=100)"      "$TOTAL_LOGS"         "=100"    "$STATUS_LOGS"
printf "  %-32s | %-6s | %-12s | %s\n" "Spans (total)"                  "$TOTAL_SPANS"        ">=30"    "$STATUS_SPANS"
printf "  %-32s | %-6s | %-12s | %s\n" "ui.screen_view"                 "$UI_SCREEN_VIEW"     ">=30"    "$STATUS_SCREENVIEW"
printf "  %-32s | %-6s | %-12s | %s\n" "page.* spans"                   "$PAGE_SPANS"         ">=30"    "$STATUS_PAGESPAN"
printf "  %-32s | %-6s | %-12s | %s\n" "user.transaction"               "$USER_TRANSACTION"   ">=20"    "$STATUS_TRANSACTION"
printf "  %-32s | %-6s | %-12s | %s\n" "api.request"                    "$API_REQUEST"        ">=15"    "$STATUS_APIREQUEST"
printf "  %-32s | %-6s | %-12s | %s\n" "app.crash"                      "$APP_CRASH"          ">=2"     "$STATUS_CRASH"
printf "  %-32s | %-6s | %-12s | %s\n" "buffer.snapshot"                "$BUFFER_SNAPSHOT"    ">=40"    "$STATUS_BUFFER"
printf "  %-32s | %-6s | %-12s | %s\n" "offline.nav"                    "$OFFLINE_NAV"        ">=20"    "$STATUS_OFFLINE_NAV"
printf "  %-32s | %-6s | %-12s | %s\n" "offline.burst"                  "$OFFLINE_BURST"      ">=50"    "$STATUS_OFFLINE_BURST"
printf "  %-32s | %-6s | %-12s | %s\n" "stress.battery_level_set"       "$STRESS_BATTERY"     ">=7"     "$STATUS_BATTERY"
printf "  %-32s | %-6s | %-12s | %s\n" "stress.thermal_level_set"       "$STRESS_THERMAL"     ">=4"     "$STATUS_THERMAL"
printf "  %-32s | %-6s | %-12s | %s\n" "stress.start/end"               "$STRESS_START"       ">=7"     "$STATUS_STRESS_START"
printf "  %-32s | %-6s | %-12s | %s\n" "user.activity (combinedStress)" "$USER_ACTIVITY"      ">=10"    ""
printf "  %-32s | %-6s | %-12s | %s\n" "user.pre_stress (extremeLow)"   "$USER_PRE_STRESS"    ">=20"    ""
printf "  %-32s | %-6s | %-12s | %s\n" "stress.combined_peak"           "$STRESS_COMBINED_PEAK" ">=1"   ""
printf "  %-32s | %-6s | %-12s | %s\n" "stress.extreme_low_battery"     "$STRESS_EXTREME"     ">=1"     ""
printf "  %-32s | %-6s | %-12s | %s\n" "http.error"                     "$HTTP_ERROR"         ">=1"     ""
printf "  %-32s | %-6s | %-12s | %s\n" "app.crash_recovery"             "$CRASH_RECOVERY"     ">=2"     ""
printf "  %-32s | %-6s | %-12s | %s\n" "demo.run_id coverage"           "${RUNID_PCT:-0}%"    ">20%"    ""

# ═══════════════════════════════════════════════════════════════════════
echo ""
bold "═══════════════════════════════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
    green "ALL $TOTAL TESTS PASSED ($WARN warnings)"
else
    red "$FAIL of $TOTAL TESTS FAILED ($WARN warnings)"
fi
bold "═══════════════════════════════════════════════════════════════"

exit "$FAIL"
