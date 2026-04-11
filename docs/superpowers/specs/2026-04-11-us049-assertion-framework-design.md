# US-049: Validation Assertion Framework — Design Specification

**Date:** 2026-04-11
**Status:** Draft
**Scope:** Structured jq-based assertion library for OTLP collector JSON output, replacing grep-based `check_signal` with typed assertion functions. Includes self-test and retrofit of existing validation scripts.
**Parent Epic:** `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (Phase 9, US-049)

---

## 1. Problem

The existing validation scripts (`validate-telemetry.sh`, `validate-crash-recovery.sh`) use `grep` pattern matching against raw OTLP JSON. This works for "does this string appear in the file" but cannot:

- Verify event ordering (A happens before B by timestamp)
- Check attribute values on specific events (not just "attribute key exists somewhere")
- Validate span parent-child hierarchies
- Assert timestamp monotonicity
- Count events of a specific type

Phase 9 (US-050 through US-077) requires all of these capabilities. US-049 builds the reusable assertion library that all subsequent validation scenarios depend on.

## 2. Approach

Single bash file (`assertions.sh`) with jq-based assertion functions, a self-test mode, and counter management. Retrofit existing validation scripts to use it, eliminating the duplicated `check_signal` pattern.

All jq queries use `jq -rs` (raw output + slurp) to handle line-delimited OTLP JSON (one export batch per line, multiple lines per file).

## 3. OTLP JSON Structure Reference

The collector's file exporter produces line-delimited JSON. Each line is one export batch.

### Logs (`logs.json`)

```text
Line N: {"resourceLogs":[{
  "resource": {"attributes": [{"key":"service.name","value":{"stringValue":"..."}}, ...]},
  "scopeLogs": [{
    "logRecords": [{
      "body": {"stringValue": "ui.tap"},
      "observedTimeUnixNano": "1775928218938283000",
      "severityText": "INFO",
      "attributes": [{"key":"mobile.screen.name","value":{"stringValue":"HomeFragment"}}, ...]
    }, ...]
  }]
}]}
```

### Traces (`traces.json`)

```text
Line N: {"resourceSpans":[{
  "resource": {"attributes": [...]},
  "scopeSpans": [{
    "spans": [{
      "name": "page.CalendarFragment",
      "traceId": "3c9325d306dfd094...",
      "spanId": "fcd052971153d14d",
      "parentSpanId": null,
      "startTimeUnixNano": "1775928218938283000",
      "endTimeUnixNano": "1775928218939082542",
      "kind": 1,
      "attributes": [...]
    }, ...]
  }]
}]}
```

Key: `parentSpanId` is **absent** for root spans in the OTLP JSON protobuf mapping (the field is omitted entirely, not set to null). For child spans, the field is present with the parent's `spanId` value.

## 4. Assertion Functions

### Counter Management

```bash
ASSERT_PASS=0; ASSERT_FAIL=0; ASSERT_WARN=0

assert_reset()    # Reset counters to 0
assert_summary()  # Print pass/fail/warn totals, exit 1 if failures

# jq availability check at source time
if ! command -v jq > /dev/null 2>&1; then
  warn "assertions.sh: jq not found (brew install jq)"
  warn "  Only assert_pattern_exists/assert_span_exists work without jq"
  _ASSERTIONS_NO_JQ=true
fi
```

**Dependency:** `jq` is required for all jq-based assertions (`assert_event_exists`, `assert_event_count`, `assert_attribute_value`, `assert_attribute_exists`, `assert_event_order`, `assert_timestamp_monotonic`, `assert_span_hierarchy`, `assert_resource_attribute`). If jq is missing, the library warns at source time but does not exit — `assert_pattern_exists` and `assert_span_exists` (grep-based) still work. jq-based functions check `_ASSERTIONS_NO_JQ` and emit a clear `"jq not available"` failure.

`assert_summary` takes an optional label for the output message:

```bash
assert_summary "crash-recovery"
# Output:
# ══════════════════════════════════════
#   Passed:  10
#   Failed:  0
#   Warned:  3 (optional signals)
# ══════════════════════════════════════
#   ✓ All required crash-recovery signals validated!
```

### Log Assertions

**`assert_event_exists`** — checks that at least one log record has `body.stringValue` matching the event name.

```bash
assert_event_exists <file> <event_name> [description] [required=true]
```

jq query: `[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == $name)] | length > 0`

**`assert_event_count`** — checks the count of matching events falls within a range.

```bash
assert_event_count <file> <event_name> <min> [max] [description]
```

jq query: same select, check `length >= min` and optionally `length <= max`.

**`assert_attribute_value`** — finds events by body, checks a specific attribute has the expected value.

```bash
assert_attribute_value <file> <event_name> <attr_key> <expected_value> [description]
```

jq query: select event by body, then `.attributes[] | select(.key == $attr_key) | .value.stringValue == $expected`.

**`assert_attribute_exists`** — finds events by body, checks a specific attribute key is present (any value).

```bash
assert_attribute_exists <file> <event_name> <attr_key> [description]
```

**`assert_pattern_exists`** — raw grep across the file. Direct replacement for `check_signal`. Handles OR patterns (`\|`), arbitrary text search.

```bash
assert_pattern_exists <file> <pattern> [description] [required=true]
```

Implementation: `grep -q "$pattern" "$file"` — same as current `check_signal`.

### Ordering Assertions

**`assert_event_order`** — verifies the FIRST occurrence of event A has an earlier `observedTimeUnixNano` than the FIRST occurrence of event B.

```bash
assert_event_order <file> <first_event> <second_event> [description]
```

jq query: extract first timestamp of each event name, compare.

**`assert_timestamp_monotonic`** — verifies all log records across all batches have non-decreasing `observedTimeUnixNano`.

```bash
assert_timestamp_monotonic <file> [description]
```

jq query: extract all timestamps sorted by index, check each is >= previous.

### Span Assertions

**`assert_span_exists`** — checks at least one span name matches the pattern.

```bash
assert_span_exists <file> <name_pattern> [description] [required=true]
```

Implementation: `grep -q "$name_pattern" "$file"` — spans are in the JSON text, grep works for simple name matching. For precision, use jq: `[.[].resourceSpans[].scopeSpans[].spans[] | select(.name | test($pattern))] | length > 0`.

**`assert_span_hierarchy`** — verifies that at least one child span (matching child pattern) has a `parentSpanId` that matches the `spanId` of a parent span (matching parent pattern).

```bash
assert_span_hierarchy <file> <parent_pattern> <child_pattern> [description]
```

jq query: collect parent `spanId`s by name pattern, collect child `parentSpanId`s by name pattern (only spans where `parentSpanId` is present and non-null — root spans omit the field entirely per OTLP protobuf-JSON mapping), check intersection is non-empty.

### Resource Assertions

**`assert_resource_attribute`** — checks that a resource attribute with the given key (and optionally value) exists on any exported batch.

```bash
assert_resource_attribute <file> <attr_key> [expected_value] [description]
```

jq query: `.[].resourceLogs[].resource.attributes[] | select(.key == $attr_key)` then optionally check `.value.stringValue == $expected`.

**Empty value semantics:** If `expected_value` is omitted or empty string `""`, the assertion only checks that the key exists (any value). Implementation uses `[ -z "$expected_value" ]` to distinguish.

**Logs only:** This function operates on `resourceLogs` paths. For trace resource attributes, use `assert_pattern_exists` as a grep fallback. Future US items can add `assert_trace_resource_attribute` if needed.

### Utility Assertions

**`assert_file_unchanged`** — compares current file size against a previously saved snapshot. Used for airplane mode validation (no events received while offline).

```bash
assert_file_unchanged <file> <snapshot_file> [description]
```

Reads byte count from `<snapshot_file>`, compares to current `wc -c` of `<file>`. Warns (not fails) if snapshot file doesn't exist.

## 5. Self-Test

When run directly (`./assertions.sh --self-test`), creates multi-line OTLP JSON fixtures in a temp dir and exercises every assertion function.

### Test Fixture

Two-line log fixture (simulates 2 export batches):

```json
{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"test-svc"}},{"key":"device.id","value":{"stringValue":"emu123"}}]},"scopeLogs":[{"logRecords":[{"body":{"stringValue":"ui.tap"},"observedTimeUnixNano":"1000000000","attributes":[{"key":"mobile.screen.name","value":{"stringValue":"HomeFragment"}}]},{"body":{"stringValue":"ui.screen_view"},"observedTimeUnixNano":"2000000000","attributes":[{"key":"mobile.screen.name","value":{"stringValue":"BookFragment"}}]}]}]}]}
{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"test-svc"}}]},"scopeLogs":[{"logRecords":[{"body":{"stringValue":"app.crash"},"observedTimeUnixNano":"3000000000","attributes":[{"key":"exception.type","value":{"stringValue":"RuntimeException"}}]},{"body":{"stringValue":"app.recovery"},"observedTimeUnixNano":"4000000000","attributes":[{"key":"mobile.recovery_type","value":{"stringValue":"crash"}}]}]}]}]}
```

One-line trace fixture with parent-child hierarchy:

```json
{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"test-svc"}}]},"scopeSpans":[{"spans":[{"name":"journey.booking","traceId":"abc123","spanId":"span-1","startTimeUnixNano":"1000000000","endTimeUnixNano":"5000000000"},{"name":"page.HomeFragment","traceId":"abc123","spanId":"span-2","parentSpanId":"span-1","startTimeUnixNano":"1000000000","endTimeUnixNano":"3000000000"},{"name":"page.BookFragment","traceId":"abc123","spanId":"span-3","parentSpanId":"span-1","startTimeUnixNano":"3000000000","endTimeUnixNano":"5000000000"}]}]}]}
```

Note: Root span (`journey.booking`) omits `parentSpanId` entirely — matches real OTLP protobuf-JSON serialization. Child spans include it.

### Test Cases

| Function | Pass case | Fail case |
| --- | --- | --- |
| `assert_event_exists` | `"ui.tap"` found | `"nonexistent"` not found |
| `assert_event_exists` (optional) | missing event → warn not fail | |
| `assert_event_count` | `"ui.tap" min=1` | `"ui.tap" min=5` (only 1) |
| `assert_attribute_value` | `"app.recovery" "mobile.recovery_type" "crash"` | wrong value |
| `assert_attribute_exists` | `"app.crash" "exception.type"` | missing attr |
| `assert_pattern_exists` | `"RuntimeException"` found | `"NoSuchEvent"` not found |
| `assert_event_order` | `"ui.tap"` before `"app.crash"` | `"app.crash"` before `"ui.tap"` |
| `assert_timestamp_monotonic` | fixture timestamps are monotonic | (create non-monotonic fixture for fail) |
| `assert_span_exists` | `"journey\\..*"` matches | `"nonexistent"` doesn't |
| `assert_span_hierarchy` | `"journey\\..*"` parent of `"page\\..*"` | reversed hierarchy |
| `assert_resource_attribute` | `"service.name" "test-svc"` | wrong value |
| `assert_file_unchanged` | same size → pass | different size → warn |

Self-test output format:

```text
▸ Assertion library self-test

  assert_event_exists
  ✓ finds existing event
  ✓ fails on missing event (expected)
  ✓ warns on optional missing event (expected)

  ...

  Self-test: 18/18 passed
```

The self-test temporarily redirects assertion output and checks that pass/fail/warn counters increment correctly. After each test group, counters are reset with `assert_reset()`.

## 6. Retrofit: validate-crash-recovery.sh

Replace inline `check_signal` + counters + summary with assertion library calls.

**Bootstrap note:** Both validation scripts are invoked as subprocesses (not sourced), so they don't inherit `SCRIPT_DIR` from the parent. They must set `SCRIPT_DIR` themselves before sourcing `assertions.sh`.

### Before (current)

```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
log()  { ... }; ok()   { ... }; err()  { ... }; warn() { ... }
PASS=0; FAIL=0; WARN=0
check_signal() { ... grep ... }
check_signal "$OUTPUT_DIR/logs.json" "ui.screen_view" "screen events"
# ... 16 check_signal calls + 1 inline counter increment
#     (the .logs_size_before file check at lines 135-142 is NOT a check_signal
#      — it reads a file and increments PASS directly. Maps to assert_file_unchanged.)
echo "Passed: $PASS  Failed: $FAIL"
```

### After

```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"

log "Pre-crash event signals"
assert_event_exists "$LOGS" "ui.screen_view" "ui.screen_view events"
assert_event_exists "$LOGS" "ui.tap" "ui.tap events"
assert_event_exists "$LOGS" "app.foreground" "app.foreground event"
assert_event_exists "$LOGS" "buffer.snapshot" "buffer.snapshot event"
assert_event_exists "$LOGS" "demo.step" "demo.step events" false

log "Crash event signals"
assert_event_exists "$LOGS" "app.crash" "app.crash event" false
assert_pattern_exists "$LOGS" "RuntimeException" "RuntimeException" false
assert_pattern_exists "$LOGS" "booking service crash\|Booking service crash\|fatal error" "crash description" false

log "Recovery event signals"
assert_event_exists "$LOGS" "app.recovery" "app.recovery event"
assert_pattern_exists "$LOGS" "recovery_type" "recovery_type attribute"

log "Service identity"
assert_resource_attribute "$LOGS" "service.name" "validated-test" "service.name=validated-test"
assert_pattern_exists "$LOGS" "session.id\|mobile.session.id" "session.id attribute"
assert_resource_attribute "$LOGS" "device.id" "" "device.id attribute"

log "Trace signals"
assert_span_exists "$TRACES" "page\\." "page spans" false

if [ "$AIRPLANE_MODE" = true ]; then
  log "Airplane mode signals"
  assert_file_unchanged "$LOGS" "$OUTPUT_DIR/.logs_size_before" "no events while offline"
  assert_event_exists "$LOGS" "app.crash" "app.crash after network restore" false
  assert_event_exists "$LOGS" "app.recovery" "app.recovery after network restore"
fi

assert_summary "crash-recovery"
```

## 7. Retrofit: validate-telemetry.sh

Same treatment — replace `check_signal` with assertion functions.

### Retrofitted script

```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/assertions.sh"
LOGS="$SCRIPT_DIR/collector/output/logs.json"
TRACES="$SCRIPT_DIR/collector/output/traces.json"
METRICS="$SCRIPT_DIR/collector/output/metrics.json"

log "Log signals"
assert_event_exists "$LOGS" "ui.tap" "ui.tap events"
assert_event_exists "$LOGS" "ui.screen_view" "ui.screen_view events"
assert_event_exists "$LOGS" "ui.scroll" "ui.scroll events" false
assert_event_exists "$LOGS" "ui.back_press" "ui.back_press events" false
# OR pattern — assert_pattern_exists handles grep alternation; assert_event_exists only takes a single name
assert_pattern_exists "$LOGS" "app.foreground\|app.background" "lifecycle events"
assert_event_exists "$LOGS" "device.orientation" "orientation events" false
assert_pattern_exists "$LOGS" "device.battery\|device.power\|device.storage" "system events" false
assert_pattern_exists "$LOGS" "session.id" "session.id attribute"
assert_pattern_exists "$LOGS" "view.id" "view.id attribute"
assert_pattern_exists "$LOGS" "screen.name" "screen.name attribute"

log "Trace signals"
assert_span_exists "$TRACES" "page\\." "page spans"
assert_span_exists "$TRACES" "http" "HTTP spans" false

log "Metric signals"
assert_pattern_exists "$METRICS" "jvm\|process\|device\|app" "device/app metrics" false

log "Service identity"
assert_resource_attribute "$LOGS" "service.name" "" "service.name attribute"
assert_resource_attribute "$LOGS" "device.id" "" "device.id attribute"

assert_summary "telemetry"
```

## 8. Files

### New files

| File | Purpose |
| --- | --- |
| `scripts/test/lib/assertions.sh` | Assertion library + self-test (~200 lines) |

### Modified files

| File | Change |
| --- | --- |
| `scripts/test/validate-crash-recovery.sh` | Rewrite to use assertions.sh |
| `scripts/test/validate-telemetry.sh` | Rewrite to use assertions.sh |

### No other changes

The demo control center (`run-real-crash-test.sh`) calls `validate-crash-recovery.sh` via the `validate()` function in `crash-test-phases.sh`. Since the script's interface (exit code, `--airplane-mode` flag) stays the same, no callers need updating.

## 9. What's NOT in Scope

- Metric-specific assertions (US-058+ will need these — add then)
- HTML/visual test reports (overkill for bash scripts)
- New validation scenarios (US-050 through US-077 — separate items)
- Changes to the collector config or OTLP export format
