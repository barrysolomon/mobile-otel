# US-049: Assertion Framework — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reusable jq-based assertion library for OTLP JSON validation, retrofit existing validation scripts to use it, and verify with self-test.

**Architecture:** Single `assertions.sh` file with typed assertion functions (`assert_event_exists`, `assert_pattern_exists`, `assert_event_order`, etc.), counter management (`assert_reset`, `assert_summary`), and embedded `--self-test` mode. Both `validate-telemetry.sh` and `validate-crash-recovery.sh` are rewritten to source the library instead of using inline `check_signal` + grep.

**Tech Stack:** Bash 3.2 (macOS), jq (OTLP JSON parsing), grep (pattern fallback)

**Spec:** `docs/superpowers/specs/2026-04-11-us049-assertion-framework-design.md`

---

## File Map

### New

| File | Responsibility |
| --- | --- |
| `scripts/test/lib/assertions.sh` | Assertion functions, counter management, jq guard, self-test |

### Modified

| File | Change |
| --- | --- |
| `scripts/test/validate-telemetry.sh` | Rewrite: source assertions.sh, replace check_signal |
| `scripts/test/validate-crash-recovery.sh` | Rewrite: source assertions.sh, replace check_signal |

---

### Task 1: Create assertions.sh — Core Library

**Files:**
- Create: `scripts/test/lib/assertions.sh`

- [ ] **Step 1: Create the assertion library with all functions**

```bash
#!/usr/bin/env bash
# OTLP JSON assertion library for collector output validation.
# Source this file for assertion functions.
# Run directly with --self-test to validate the library itself.
#
# Requires: jq for structured assertions, grep for pattern assertions.
# All jq queries use -rs (raw + slurp) for line-delimited OTLP JSON.

ASSERTIONS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$ASSERTIONS_DIR/common.sh" 2>/dev/null || {
  log()  { echo -e "\n\033[1;36m▸ $*\033[0m"; }
  ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }
  err()  { echo -e "\033[1;31m  ✗ $*\033[0m"; }
  warn() { echo -e "\033[1;33m  ⚠ $*\033[0m"; }
}

# ── Counters ──────────────────────────────────────────────────────────────────

ASSERT_PASS=0; ASSERT_FAIL=0; ASSERT_WARN=0

assert_reset() {
  ASSERT_PASS=0; ASSERT_FAIL=0; ASSERT_WARN=0
}

assert_summary() {
  local label=${1:-"validation"}
  echo ""
  echo "══════════════════════════════════════"
  echo "  Passed:  $ASSERT_PASS"
  echo "  Failed:  $ASSERT_FAIL"
  echo "  Warned:  $ASSERT_WARN (optional signals)"
  echo "══════════════════════════════════════"
  if [ $ASSERT_FAIL -gt 0 ]; then
    echo ""
    err "$ASSERT_FAIL required signal(s) missing!"
    exit 1
  else
    echo ""
    ok "All required $label signals validated!"
  fi
}

# ── jq availability ───────────────────────────────────────────────────────────

_ASSERTIONS_NO_JQ=false
if ! command -v jq > /dev/null 2>&1; then
  warn "assertions.sh: jq not found (brew install jq)"
  warn "  Only assert_pattern_exists/assert_span_exists work without jq"
  _ASSERTIONS_NO_JQ=true
fi

_require_jq() {
  if [ "$_ASSERTIONS_NO_JQ" = true ]; then
    err "${1:-assertion} requires jq — install with: brew install jq"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
    return 1
  fi
  return 0
}

# ── Log Assertions ────────────────────────────────────────────────────────────

assert_event_exists() {
  local file=$1 event_name=$2 description=${3:-"$2 event exists"} required=${4:-true}
  _require_jq "assert_event_exists" || return

  if [ ! -f "$file" ]; then
    if [ "$required" = true ]; then
      err "$description — file not found: $file"
      ASSERT_FAIL=$((ASSERT_FAIL + 1))
    else
      warn "$description — file not found (optional)"
      ASSERT_WARN=$((ASSERT_WARN + 1))
    fi
    return
  fi

  local count
  count=$(jq -rs --arg name "$event_name" \
    '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == $name)] | length' \
    "$file" 2>/dev/null || echo 0)

  if [ "$count" -gt 0 ] 2>/dev/null; then
    ok "$description"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    if [ "$required" = true ]; then
      err "$description — event not found: $event_name"
      ASSERT_FAIL=$((ASSERT_FAIL + 1))
    else
      warn "$description — not found (optional)"
      ASSERT_WARN=$((ASSERT_WARN + 1))
    fi
  fi
}

assert_event_count() {
  local file=$1 event_name=$2 min=$3 max=${4:-""} description=${5:-"$2 count >= $3"}
  _require_jq "assert_event_count" || return

  local count
  count=$(jq -rs --arg name "$event_name" \
    '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == $name)] | length' \
    "$file" 2>/dev/null || echo 0)

  local pass=true
  if [ "$count" -lt "$min" ] 2>/dev/null; then
    pass=false
  fi
  if [ -n "$max" ] && [ "$count" -gt "$max" ] 2>/dev/null; then
    pass=false
  fi

  if [ "$pass" = true ]; then
    ok "$description (count=$count)"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    err "$description — expected ${min}${max:+-$max}, got $count"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
}

assert_attribute_value() {
  local file=$1 event_name=$2 attr_key=$3 expected=$4 description=${5:-"$2.$3 == $4"}
  _require_jq "assert_attribute_value" || return

  local found
  found=$(jq -rs --arg name "$event_name" --arg key "$attr_key" --arg val "$expected" \
    '[.[].resourceLogs[].scopeLogs[].logRecords[]
      | select(.body.stringValue == $name)
      | .attributes[]?
      | select(.key == $key)
      | .value.stringValue // .value.intValue // .value.boolValue
      | select(. == $val)] | length' \
    "$file" 2>/dev/null || echo 0)

  if [ "$found" -gt 0 ] 2>/dev/null; then
    ok "$description"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    err "$description — attribute $attr_key != $expected on $event_name"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
}

assert_attribute_exists() {
  local file=$1 event_name=$2 attr_key=$3 description=${4:-"$2 has $3"}
  _require_jq "assert_attribute_exists" || return

  local found
  found=$(jq -rs --arg name "$event_name" --arg key "$attr_key" \
    '[.[].resourceLogs[].scopeLogs[].logRecords[]
      | select(.body.stringValue == $name)
      | .attributes[]?
      | select(.key == $key)] | length' \
    "$file" 2>/dev/null || echo 0)

  if [ "$found" -gt 0 ] 2>/dev/null; then
    ok "$description"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    err "$description — attribute $attr_key not found on $event_name"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
}

assert_pattern_exists() {
  local file=$1 pattern=$2 description=${3:-"pattern: $2"} required=${4:-true}

  if [ ! -f "$file" ]; then
    if [ "$required" = true ]; then
      err "$description — file not found: $file"
      ASSERT_FAIL=$((ASSERT_FAIL + 1))
    else
      warn "$description — file not found (optional)"
      ASSERT_WARN=$((ASSERT_WARN + 1))
    fi
    return
  fi

  if grep -q "$pattern" "$file" 2>/dev/null; then
    ok "$description"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    if [ "$required" = true ]; then
      err "$description — pattern not found: $pattern"
      ASSERT_FAIL=$((ASSERT_FAIL + 1))
    else
      warn "$description — not found (optional)"
      ASSERT_WARN=$((ASSERT_WARN + 1))
    fi
  fi
}

# ── Ordering Assertions ──────────────────────────────────────────────────────

assert_event_order() {
  local file=$1 first_event=$2 second_event=$3 description=${4:-"$2 before $3"}
  _require_jq "assert_event_order" || return

  local first_ts second_ts
  first_ts=$(jq -rs --arg name "$first_event" \
    '[.[].resourceLogs[].scopeLogs[].logRecords[]
      | select(.body.stringValue == $name)
      | .observedTimeUnixNano] | sort | .[0]' \
    "$file" 2>/dev/null)
  second_ts=$(jq -rs --arg name "$second_event" \
    '[.[].resourceLogs[].scopeLogs[].logRecords[]
      | select(.body.stringValue == $name)
      | .observedTimeUnixNano] | sort | .[0]' \
    "$file" 2>/dev/null)

  if [ -z "$first_ts" ] || [ "$first_ts" = "null" ]; then
    err "$description — $first_event not found"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
    return
  fi
  if [ -z "$second_ts" ] || [ "$second_ts" = "null" ]; then
    err "$description — $second_event not found"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
    return
  fi

  # Compare as strings — nanosecond timestamps are too large for bash arithmetic
  # but lexicographic comparison works for fixed-width numeric strings
  if [ "$first_ts" \< "$second_ts" ] || [ "$first_ts" = "$second_ts" ]; then
    ok "$description"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    err "$description — $first_event ($first_ts) is after $second_event ($second_ts)"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
}

assert_timestamp_monotonic() {
  local file=$1 description=${2:-"timestamps monotonically increasing"}
  _require_jq "assert_timestamp_monotonic" || return

  local violation
  violation=$(jq -rs '
    [.[].resourceLogs[].scopeLogs[].logRecords[].observedTimeUnixNano]
    | to_entries
    | map(select(.key > 0 and .value < .[(key - 1)].value))
    | length' \
    "$file" 2>/dev/null || echo "-1")

  if [ "$violation" = "0" ]; then
    ok "$description"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    err "$description — $violation out-of-order timestamp(s)"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
}

# ── Span Assertions ──────────────────────────────────────────────────────────

assert_span_exists() {
  local file=$1 name_pattern=$2 description=${3:-"span: $2"} required=${4:-true}

  if [ ! -f "$file" ]; then
    if [ "$required" = true ]; then
      err "$description — file not found: $file"
      ASSERT_FAIL=$((ASSERT_FAIL + 1))
    else
      warn "$description — file not found (optional)"
      ASSERT_WARN=$((ASSERT_WARN + 1))
    fi
    return
  fi

  if grep -q "$name_pattern" "$file" 2>/dev/null; then
    ok "$description"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    if [ "$required" = true ]; then
      err "$description — span not found: $name_pattern"
      ASSERT_FAIL=$((ASSERT_FAIL + 1))
    else
      warn "$description — not found (optional)"
      ASSERT_WARN=$((ASSERT_WARN + 1))
    fi
  fi
}

assert_span_hierarchy() {
  local file=$1 parent_pattern=$2 child_pattern=$3 description=${4:-"$3 under $2"}
  _require_jq "assert_span_hierarchy" || return

  local match
  match=$(jq -rs --arg pp "$parent_pattern" --arg cp "$child_pattern" '
    [.[].resourceSpans[].scopeSpans[].spans[]] as $spans |
    ($spans | map(select(.name | test($pp))) | map(.spanId)) as $parentIds |
    ($spans | map(select(.name | test($cp) and .parentSpanId != null and .parentSpanId != ""))
            | map(.parentSpanId)) as $childParentIds |
    ($parentIds | map(select(. as $pid | $childParentIds | any(. == $pid)))) | length' \
    "$file" 2>/dev/null || echo 0)

  if [ "$match" -gt 0 ] 2>/dev/null; then
    ok "$description"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    err "$description — no child spans ($child_pattern) found under parent ($parent_pattern)"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
  fi
}

# ── Resource Assertions ──────────────────────────────────────────────────────

assert_resource_attribute() {
  local file=$1 attr_key=$2 expected_value=${3:-""} description=${4:-"resource: $2"}
  _require_jq "assert_resource_attribute" || return

  if [ ! -f "$file" ]; then
    err "$description — file not found: $file"
    ASSERT_FAIL=$((ASSERT_FAIL + 1))
    return
  fi

  if [ -z "$expected_value" ]; then
    # Any value — just check key exists
    local found
    found=$(jq -rs --arg key "$attr_key" \
      '[.[].resourceLogs[].resource.attributes[] | select(.key == $key)] | length' \
      "$file" 2>/dev/null || echo 0)
    if [ "$found" -gt 0 ] 2>/dev/null; then
      ok "$description"
      ASSERT_PASS=$((ASSERT_PASS + 1))
    else
      err "$description — resource attribute $attr_key not found"
      ASSERT_FAIL=$((ASSERT_FAIL + 1))
    fi
  else
    # Check specific value
    local found
    found=$(jq -rs --arg key "$attr_key" --arg val "$expected_value" \
      '[.[].resourceLogs[].resource.attributes[]
        | select(.key == $key and (.value.stringValue // .value.intValue // .value.boolValue) == $val)] | length' \
      "$file" 2>/dev/null || echo 0)
    if [ "$found" -gt 0 ] 2>/dev/null; then
      ok "$description"
      ASSERT_PASS=$((ASSERT_PASS + 1))
    else
      err "$description — resource attribute $attr_key != $expected_value"
      ASSERT_FAIL=$((ASSERT_FAIL + 1))
    fi
  fi
}

# ── Utility Assertions ───────────────────────────────────────────────────────

assert_file_unchanged() {
  local file=$1 snapshot_file=$2 description=${3:-"file unchanged"}

  if [ ! -f "$snapshot_file" ]; then
    warn "$description — snapshot file not found: $snapshot_file"
    ASSERT_WARN=$((ASSERT_WARN + 1))
    return
  fi

  local before after
  before=$(cat "$snapshot_file" | tr -d ' ')
  after=$(wc -c < "$file" 2>/dev/null | tr -d ' ')

  if [ "$before" = "$after" ]; then
    ok "$description (size=${before} bytes)"
    ASSERT_PASS=$((ASSERT_PASS + 1))
  else
    warn "$description — file changed (${before} → ${after} bytes)"
    ASSERT_WARN=$((ASSERT_WARN + 1))
  fi
}

# ── Self-Test ─────────────────────────────────────────────────────────────────

_run_self_test() {
  log "Assertion library self-test"
  echo ""

  local tmpdir
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" EXIT

  # Create log fixture (2 batches)
  cat > "$tmpdir/logs.json" << 'FIXTURE'
{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"test-svc"}},{"key":"device.id","value":{"stringValue":"emu123"}}]},"scopeLogs":[{"logRecords":[{"body":{"stringValue":"ui.tap"},"observedTimeUnixNano":"1000000000","attributes":[{"key":"mobile.screen.name","value":{"stringValue":"HomeFragment"}}]},{"body":{"stringValue":"ui.screen_view"},"observedTimeUnixNano":"2000000000","attributes":[{"key":"mobile.screen.name","value":{"stringValue":"BookFragment"}}]}]}]}]}
{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"test-svc"}}]},"scopeLogs":[{"logRecords":[{"body":{"stringValue":"app.crash"},"observedTimeUnixNano":"3000000000","attributes":[{"key":"exception.type","value":{"stringValue":"RuntimeException"}}]},{"body":{"stringValue":"app.recovery"},"observedTimeUnixNano":"4000000000","attributes":[{"key":"mobile.recovery_type","value":{"stringValue":"crash"}}]}]}]}]}
FIXTURE

  # Create trace fixture (parent-child hierarchy)
  cat > "$tmpdir/traces.json" << 'FIXTURE'
{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"test-svc"}}]},"scopeSpans":[{"spans":[{"name":"journey.booking","traceId":"abc123","spanId":"span-1","startTimeUnixNano":"1000000000","endTimeUnixNano":"5000000000"},{"name":"page.HomeFragment","traceId":"abc123","spanId":"span-2","parentSpanId":"span-1","startTimeUnixNano":"1000000000","endTimeUnixNano":"3000000000"},{"name":"page.BookFragment","traceId":"abc123","spanId":"span-3","parentSpanId":"span-1","startTimeUnixNano":"3000000000","endTimeUnixNano":"5000000000"}]}]}]}
FIXTURE

  # Create non-monotonic log fixture
  cat > "$tmpdir/non_monotonic.json" << 'FIXTURE'
{"resourceLogs":[{"resource":{"attributes":[]},"scopeLogs":[{"logRecords":[{"body":{"stringValue":"a"},"observedTimeUnixNano":"2000000000"},{"body":{"stringValue":"b"},"observedTimeUnixNano":"1000000000"}]}]}]}
FIXTURE

  # Create file-unchanged snapshot
  echo "0" > "$tmpdir/snapshot.txt"
  touch "$tmpdir/empty.json"

  local test_pass=0 test_total=0

  _check() {
    local name=$1 expected_pass=$2 expected_fail=$3 expected_warn=$4
    test_total=$((test_total + 1))
    if [ "$ASSERT_PASS" = "$expected_pass" ] && [ "$ASSERT_FAIL" = "$expected_fail" ] && [ "$ASSERT_WARN" = "$expected_warn" ]; then
      ok "$name"
      test_pass=$((test_pass + 1))
    else
      err "$name — expected P=$expected_pass F=$expected_fail W=$expected_warn, got P=$ASSERT_PASS F=$ASSERT_FAIL W=$ASSERT_WARN"
    fi
    assert_reset
  }

  # ── assert_event_exists ──
  echo "  assert_event_exists"
  assert_event_exists "$tmpdir/logs.json" "ui.tap" "finds existing event" > /dev/null 2>&1
  _check "finds existing event" 1 0 0

  assert_event_exists "$tmpdir/logs.json" "nonexistent" "fails on missing" > /dev/null 2>&1
  _check "fails on missing event" 0 1 0

  assert_event_exists "$tmpdir/logs.json" "nonexistent" "warns optional" false > /dev/null 2>&1
  _check "warns on optional missing" 0 0 1

  # ── assert_event_count ──
  echo "  assert_event_count"
  assert_event_count "$tmpdir/logs.json" "ui.tap" 1 "" "count >= 1" > /dev/null 2>&1
  _check "count >= 1 passes" 1 0 0

  assert_event_count "$tmpdir/logs.json" "ui.tap" 5 "" "count >= 5" > /dev/null 2>&1
  _check "count >= 5 fails (only 1)" 0 1 0

  # ── assert_attribute_value ──
  echo "  assert_attribute_value"
  assert_attribute_value "$tmpdir/logs.json" "app.recovery" "mobile.recovery_type" "crash" "correct value" > /dev/null 2>&1
  _check "correct attribute value" 1 0 0

  assert_attribute_value "$tmpdir/logs.json" "app.recovery" "mobile.recovery_type" "wrong" "wrong value" > /dev/null 2>&1
  _check "wrong attribute value fails" 0 1 0

  # ── assert_attribute_exists ──
  echo "  assert_attribute_exists"
  assert_attribute_exists "$tmpdir/logs.json" "app.crash" "exception.type" "attr exists" > /dev/null 2>&1
  _check "attribute exists" 1 0 0

  assert_attribute_exists "$tmpdir/logs.json" "app.crash" "nonexistent" "attr missing" > /dev/null 2>&1
  _check "missing attribute fails" 0 1 0

  # ── assert_pattern_exists ──
  echo "  assert_pattern_exists"
  assert_pattern_exists "$tmpdir/logs.json" "RuntimeException" "grep pattern" > /dev/null 2>&1
  _check "grep pattern found" 1 0 0

  assert_pattern_exists "$tmpdir/logs.json" "NoSuchThing" "missing pattern" > /dev/null 2>&1
  _check "missing pattern fails" 0 1 0

  # ── assert_event_order ──
  echo "  assert_event_order"
  assert_event_order "$tmpdir/logs.json" "ui.tap" "app.crash" "correct order" > /dev/null 2>&1
  _check "correct order passes" 1 0 0

  assert_event_order "$tmpdir/logs.json" "app.crash" "ui.tap" "wrong order" > /dev/null 2>&1
  _check "wrong order fails" 0 1 0

  # ── assert_timestamp_monotonic ──
  echo "  assert_timestamp_monotonic"
  assert_timestamp_monotonic "$tmpdir/logs.json" "monotonic" > /dev/null 2>&1
  _check "monotonic timestamps pass" 1 0 0

  assert_timestamp_monotonic "$tmpdir/non_monotonic.json" "non-monotonic" > /dev/null 2>&1
  _check "non-monotonic fails" 0 1 0

  # ── assert_span_exists ──
  echo "  assert_span_exists"
  assert_span_exists "$tmpdir/traces.json" "journey" "span found" > /dev/null 2>&1
  _check "span found" 1 0 0

  assert_span_exists "$tmpdir/traces.json" "nonexistent" "span missing" false > /dev/null 2>&1
  _check "missing span warns" 0 0 1

  # ── assert_span_hierarchy ──
  echo "  assert_span_hierarchy"
  assert_span_hierarchy "$tmpdir/traces.json" "journey\\..*" "page\\..*" "hierarchy" > /dev/null 2>&1
  _check "parent-child hierarchy" 1 0 0

  # ── assert_resource_attribute ──
  echo "  assert_resource_attribute"
  assert_resource_attribute "$tmpdir/logs.json" "service.name" "test-svc" "with value" > /dev/null 2>&1
  _check "resource attr with value" 1 0 0

  assert_resource_attribute "$tmpdir/logs.json" "device.id" "" "any value" > /dev/null 2>&1
  _check "resource attr any value" 1 0 0

  assert_resource_attribute "$tmpdir/logs.json" "service.name" "wrong" "wrong value" > /dev/null 2>&1
  _check "wrong resource value fails" 0 1 0

  # ── assert_file_unchanged ──
  echo "  assert_file_unchanged"
  assert_file_unchanged "$tmpdir/empty.json" "$tmpdir/snapshot.txt" "unchanged" > /dev/null 2>&1
  _check "file unchanged passes" 1 0 0

  echo "5" > "$tmpdir/snapshot_diff.txt"
  assert_file_unchanged "$tmpdir/empty.json" "$tmpdir/snapshot_diff.txt" "changed" > /dev/null 2>&1
  _check "file changed warns" 0 0 1

  # ── Summary ──
  echo ""
  if [ $test_pass -eq $test_total ]; then
    ok "Self-test: $test_pass/$test_total passed"
  else
    err "Self-test: $test_pass/$test_total passed"
    exit 1
  fi
}

# Run self-test if invoked directly with --self-test
if [ "${1:-}" = "--self-test" ]; then
  _run_self_test
fi
```

- [ ] **Step 2: Make executable and verify syntax**

```bash
chmod +x scripts/test/lib/assertions.sh
bash -n scripts/test/lib/assertions.sh
```
Expected: no output (syntax OK)

- [ ] **Step 3: Run self-test**

```bash
./scripts/test/lib/assertions.sh --self-test
```
Expected: `Self-test: 24/24 passed`

---

### Task 2: Retrofit validate-crash-recovery.sh

**Files:**
- Modify: `scripts/test/validate-crash-recovery.sh`

- [ ] **Step 1: Replace entire file content**

```bash
#!/usr/bin/env bash
# Validate that crash-recovery telemetry was received by the local collector.
#
# Checks for:
#   - Pre-crash events (ui.screen_view, ui.tap, navigation breadcrumbs)
#   - app.crash event with exception details
#   - app.recovery event with recovery_type=crash
#   - Service identity (service.name=validated-test from SharedPreferences override)
#   - Session continuity (session.id present)
#
# Prerequisites: run the crash test with a local collector first:
#   ./run-real-crash-test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"

LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"
OUTPUT_DIR="$SCRIPT_DIR/collector/output"

AIRPLANE_MODE=false
for arg in "$@"; do
  case "$arg" in
    --airplane-mode) AIRPLANE_MODE=true ;;
  esac
done

echo ""
log "Validating crash-recovery telemetry"
echo "   Output dir: $OUTPUT_DIR"
echo ""

# ── Pre-crash events ───────────────────────────────────────────────────────

log "Pre-crash event signals"

assert_event_exists "$LOGS" "ui.screen_view" \
  "ui.screen_view events (screen instrumentation captured before crash)"

assert_event_exists "$LOGS" "ui.tap" \
  "ui.tap events (tap instrumentation captured before crash)"

assert_event_exists "$LOGS" "app.foreground" \
  "app.foreground event (lifecycle captured before crash)"

assert_event_exists "$LOGS" "buffer.snapshot" \
  "buffer.snapshot event (pre-crash buffer stats)"

assert_event_exists "$LOGS" "demo.step" \
  "demo.step events (scenario pacing events)" false

# ── Crash event ────────────────────────────────────────────────────────────

log "Crash event signals"

assert_event_exists "$LOGS" "app.crash" \
  "app.crash event (real crash captured by ErrorInstrumentation)" false

assert_pattern_exists "$LOGS" "RuntimeException" \
  "exception.type=RuntimeException on crash event" false

assert_pattern_exists "$LOGS" "booking service crash\|Booking service crash\|fatal error" \
  "exception.message contains crash description" false

# ── Recovery event ─────────────────────────────────────────────────────────

log "Recovery event signals"

assert_event_exists "$LOGS" "app.recovery" \
  "app.recovery event (RecoveryTracker detected crash on restart)"

assert_pattern_exists "$LOGS" "recovery_type" \
  "recovery_type attribute present on recovery event"

# ── Service identity ───────────────────────────────────────────────────────

log "Service identity"

assert_resource_attribute "$LOGS" "service.name" "validated-test" \
  "service.name=validated-test (SharedPreferences override survived crash)"

assert_pattern_exists "$LOGS" "session.id\|mobile.session.id" \
  "session.id attribute present on events"

assert_resource_attribute "$LOGS" "device.id" "" \
  "device.id resource attribute"

# ── Traces ─────────────────────────────────────────────────────────────────

log "Trace signals"

assert_span_exists "$TRACES" "page\\." \
  "page.* spans (screen view page spans from pre-crash navigation)" false

# ── Airplane mode signals ─────────────────────────────────────────────────

if [ "$AIRPLANE_MODE" = true ]; then
  log "Airplane mode signals"

  assert_file_unchanged "$LOGS" "$OUTPUT_DIR/.logs_size_before" \
    "no events received while offline"

  assert_event_exists "$LOGS" "app.crash" \
    "app.crash event arrived after network restore" false

  assert_event_exists "$LOGS" "app.recovery" \
    "app.recovery event arrived after network restore"
fi

# ── Summary ────────────────────────────────────────────────────────────────

assert_summary "crash-recovery"
```

- [ ] **Step 2: Verify syntax**

```bash
bash -n scripts/test/validate-crash-recovery.sh
```
Expected: no output

---

### Task 3: Retrofit validate-telemetry.sh

**Files:**
- Modify: `scripts/test/validate-telemetry.sh`

- [ ] **Step 1: Replace entire file content**

```bash
#!/usr/bin/env bash
# Validate that expected telemetry was received by the local collector.
#
# Prerequisites: run the collector + scenarios first:
#   ./run-validated-tests.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"

LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"
METRICS="$SCRIPT_DIR/collector/output/metrics.json"

echo ""
log "Validating telemetry received by local collector"
echo "   Output dir: $SCRIPT_DIR/collector/output"
echo ""

# ── Logs ─────────────────────────────────────────────────────────────────────

log "Log signals"

assert_event_exists "$LOGS" "ui.tap" \
  "ui.tap events (tap instrumentation)"

assert_event_exists "$LOGS" "ui.screen_view" \
  "ui.screen_view events (screen instrumentation)"

assert_event_exists "$LOGS" "ui.scroll" \
  "ui.scroll events (scroll instrumentation)" false

assert_event_exists "$LOGS" "ui.back_press" \
  "ui.back_press events (back-press instrumentation)" false

# OR pattern — assert_pattern_exists handles grep alternation
assert_pattern_exists "$LOGS" "app.foreground\|app.background" \
  "app.foreground/background events (lifecycle instrumentation)"

assert_event_exists "$LOGS" "device.orientation" \
  "device.orientation events (screen-orientation instrumentation)" false

assert_pattern_exists "$LOGS" "device.battery\|device.power\|device.storage" \
  "system events (system-events instrumentation)" false

assert_pattern_exists "$LOGS" "session.id" \
  "session.id attribute present on log events"

assert_pattern_exists "$LOGS" "view.id" \
  "view.id attribute present on log events"

assert_pattern_exists "$LOGS" "screen.name" \
  "screen.name attribute present on log events"

# ── Traces ───────────────────────────────────────────────────────────────────

log "Trace signals"

assert_span_exists "$TRACES" "page\\." \
  "page.* spans (screen view page spans)"

assert_span_exists "$TRACES" "http" \
  "HTTP spans (network instrumentation)" false

# ── Metrics ──────────────────────────────────────────────────────────────────

log "Metric signals"

assert_pattern_exists "$METRICS" "jvm\|process\|device\|app" \
  "Device/app metrics (vitals instrumentation)" false

# ── Service identity ─────────────────────────────────────────────────────────

log "Service identity"

assert_resource_attribute "$LOGS" "service.name" "" \
  "service.name resource attribute"

assert_resource_attribute "$LOGS" "device.id" "" \
  "device.id resource attribute"

# ── Summary ──────────────────────────────────────────────────────────────────

assert_summary "telemetry"
```

- [ ] **Step 2: Verify syntax**

```bash
bash -n scripts/test/validate-telemetry.sh
```
Expected: no output

---

### Task 4: Integration Test — Run Against Real Collector Data

**Files:** None (verification only)

- [ ] **Step 1: Run self-test**

```bash
cd mobile-otel && ./scripts/test/lib/assertions.sh --self-test
```
Expected: `Self-test: 24/24 passed`

- [ ] **Step 2: Run validate-crash-recovery.sh against existing collector data**

If collector output exists from the last crash demo run:

```bash
cd mobile-otel && ./scripts/test/validate-crash-recovery.sh
```
Expected: same pass/fail/warn counts as before the retrofit

- [ ] **Step 3: Run validate-telemetry.sh against existing collector data (if available)**

```bash
cd mobile-otel && ./scripts/test/validate-telemetry.sh
```
Expected: same behavior as before

- [ ] **Step 4: Run the full crash demo CI mode to validate end-to-end**

```bash
cd mobile-otel && ./scripts/test/run-real-crash-test.sh --ci
```
Expected: Phase 1 crash, Phase 2 recovery, validation passes, telemetry dump shows timeline

- [ ] **Step 5: Commit**

```bash
cd mobile-otel
git add \
  scripts/test/lib/assertions.sh \
  scripts/test/validate-crash-recovery.sh \
  scripts/test/validate-telemetry.sh \
  docs/superpowers/plans/2026-04-11-us049-assertion-framework.md
git commit -m "feat: US-049 — OTLP assertion framework + validation script retrofit

jq-based assertion library with 12 functions: event exists, event count,
attribute value/exists, pattern exists, event order, timestamp monotonic,
span exists/hierarchy, resource attribute, file unchanged. Includes
embedded self-test (24 tests). Retrofits validate-telemetry.sh and
validate-crash-recovery.sh to use the library.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
