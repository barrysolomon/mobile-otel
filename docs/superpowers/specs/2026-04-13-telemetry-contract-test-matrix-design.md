# Telemetry Contract Test Matrix — Design Spec

> **Date:** 2026-04-13
> **Status:** Approved
> **Purpose:** Exhaustive combinatorial testing of export modes, policy triggers, config flags, error filtering, and signal contracts

---

## Problem

The SDK has unit tests for individual behaviors and E2E tests that check "did something arrive." No test verifies:
- The same user journey produces the same telemetry across all three export modes
- Every config flag toggle produces the expected behavioral change
- Error filtering, deduplication, and rate limiting work correctly in combination
- Buffered events preserve timestamps, ordering, and span hierarchy
- Real crash recovery, airplane mode, and backend trace propagation work end-to-end

## Goal

A two-layer test harness:
- **Layer 1 (unit):** ~90 parameterized tests covering the combinatorial matrix. Kotlin + Robolectric + MockLogRecordExporter. Runs in ~30s, no emulator.
- **Layer 2 (E2E):** 8 device scenarios covering behaviors that require real hardware. Espresso + local collector + JSON validation. Runs in ~4 min on emulator.

Both layers share a common assertions library (`TelemetryContract`) and a canonical "golden journey" that anchors mode equivalence testing.

---

## Golden Journey

A fixed 8-step user flow that exercises every instrumentation module. Produces a deterministic, countable set of signals.

| Step | User Action | Log Events | Spans |
|------|------------|------------|-------|
| 1 | App launch | `app.start`, `app.foreground` | — |
| 2 | View calendar | `ui.screen_view` (CalendarFragment) | `page.CalendarFragment` |
| 3 | Tap a date | `ui.tap` | — |
| 4 | Navigate to booking | `ui.screen_view` (BookFragment) | `page.BookFragment` |
| 5 | Fill form | `ui.text_input` | — |
| 6 | Submit booking | — | HTTP span (child of page) |
| 7 | Trigger 503 error | `http.error` | — |
| 8 | Navigate back | `ui.back_press`, `ui.screen_view` (CalendarFragment) | `page.CalendarFragment` |

**Expected totals:** 8 log events + 4 spans (minimum). HYBRID mode also produces `device.heartbeat` and `prediction.cycle` via immediate export.

### Two Implementations

- **Unit tests:** `GoldenJourneyEmitter` — programmatically emits the exact event sequence via the SDK's logger/tracer APIs. No HTTP stack, no UI interaction. Produces events with the same bodies, severities, and attributes the real journey would.
- **E2E tests:** Real Espresso interactions on the demo app. Produces events via actual instrumentation modules.

Both implementations produce the same event contract. The emitter is the source of truth for what the journey should produce.

---

## Core Assertion: Mode Equivalence

The central claim this harness proves:

> **After all flushing completes, CONTINUOUS, CONDITIONAL, and HYBRID produce the same set of events (same bodies, same attributes, same span hierarchy). Only the timing of arrival differs.**

### How Timing Is Normalized

- **Unit tests:** Call `forceFlush()` after the golden journey in every mode. This exports everything regardless of mode. Then compare event sets.
- **E2E tests:** For CONTINUOUS, wait for the periodic flush interval (10s). For CONDITIONAL, trigger a flush (the 503 in step 7 fires the http-error policy). For HYBRID, heartbeats arrive immediately, bulk events arrive on the 503 trigger. Wait 15s after journey completes, then validate.

### What's Compared

```
modeA_logs.map { it.body }.sorted() == modeB_logs.map { it.body }.sorted()
modeA_spans.map { it.name }.sorted() == modeB_spans.map { it.name }.sorted()
```

HYBRID mode's extra `device.heartbeat` and `prediction.cycle` events are excluded from the comparison (they're HYBRID-only by design).

---

## Layer 1: Unit Test Matrix

### File Structure

```
otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/matrix/
├── GoldenJourneyEmitter.kt              — programmatic event sequence
├── TelemetryContract.kt                 — shared assertion functions
├── ExportModeEquivalenceTest.kt         — 3 modes × golden journey = same events
├── PolicyTriggerMatrixTest.kt           — 5 triggers × 3 modes
├── ErrorFilterMatrixTest.kt             — 6 exception types × 3 filter configs
├── ConfigFlagMatrixTest.kt              — 8 flags toggled independently
├── SignalContractTest.kt                — universal attribute presence
├── DeduplicationMatrixTest.kt           — same exception across dedup windows
├── RateLimitMatrixTest.kt               — burst exceeding limit
├── TimestampContractTest.kt             — ensureTimestamp on all emitters
└── HybridTimingTest.kt                  — heartbeats exported before trigger flush
```

### GoldenJourneyEmitter

```kotlin
class GoldenJourneyEmitter(
    private val logger: Logger,
    private val tracer: Tracer,
    private val sessionId: String = "test-session-001"
) {
    /** Emits the full golden journey event sequence. Returns expected event bodies. */
    fun emit(): GoldenJourneyExpectation {
        // Step 1: App launch
        emitLog("app.start", Severity.INFO, mapOf("app.start.type" to "cold"))
        emitLog("app.foreground", Severity.INFO)
        
        // Step 2: Calendar screen
        emitLog("ui.screen_view", Severity.INFO, mapOf("mobile.screen.name" to "CalendarFragment"))
        val calendarSpan = tracer.spanBuilder("page.CalendarFragment").startSpan()
        
        // Step 3: Tap
        emitLog("ui.tap", Severity.INFO, mapOf("mobile.screen.name" to "CalendarFragment"))
        
        // Step 4: Navigate to booking
        calendarSpan.end()
        emitLog("ui.screen_view", Severity.INFO, mapOf("mobile.screen.name" to "BookFragment"))
        val bookSpan = tracer.spanBuilder("page.BookFragment").startSpan()
        
        // Step 5: Fill form
        emitLog("ui.text_input", Severity.INFO, mapOf("mobile.screen.name" to "BookFragment"))
        
        // Step 6: Submit booking (HTTP span)
        val httpSpan = tracer.spanBuilder("HTTP POST /api/appointments")
            .setParent(io.opentelemetry.context.Context.current().with(bookSpan))
            .startSpan()
        httpSpan.end()
        
        // Step 7: Trigger 503
        emitLog("http.error", Severity.ERROR, mapOf(
            "http.status_code" to "503",
            "http.url" to "/api/appointments"
        ))
        
        // Step 8: Navigate back
        bookSpan.end()
        emitLog("ui.back_press", Severity.INFO)
        emitLog("ui.screen_view", Severity.INFO, mapOf("mobile.screen.name" to "CalendarFragment"))
        val backSpan = tracer.spanBuilder("page.CalendarFragment").startSpan()
        backSpan.end()
        
        return GoldenJourneyExpectation(
            expectedLogBodies = setOf(
                "app.start", "app.foreground",
                "ui.screen_view", "ui.tap", "ui.screen_view",
                "ui.text_input", "http.error",
                "ui.back_press", "ui.screen_view"
            ),
            expectedSpanNames = setOf(
                "page.CalendarFragment", "page.BookFragment",
                "HTTP POST /api/appointments", "page.CalendarFragment"
            ),
            expectedLogCount = 9,
            expectedSpanCount = 4
        )
    }
    
    private fun emitLog(body: String, severity: Severity, attrs: Map<String, String> = emptyMap()) {
        val builder = logger.logRecordBuilder()
            .setBody(body)
            .setSeverity(severity)
        val attrBuilder = io.opentelemetry.api.common.Attributes.builder()
            .put("mobile.session.id", sessionId)
        attrs.forEach { (k, v) -> attrBuilder.put(io.opentelemetry.api.common.AttributeKey.stringKey(k), v) }
        builder.setAllAttributes(attrBuilder.build()).emit()
    }
}

data class GoldenJourneyExpectation(
    val expectedLogBodies: Set<String>,
    val expectedSpanNames: Set<String>,
    val expectedLogCount: Int,
    val expectedSpanCount: Int
)
```

### TelemetryContract (shared assertions)

```kotlin
object TelemetryContract {
    /** Assert that all expected event bodies are present. */
    fun assertEventSet(events: List<LogRecordData>, expected: Set<String>) {
        val actual = events.map { it.bodyValue?.asString() }.filterNotNull().toSet()
        val missing = expected - actual
        assertTrue("Missing events: $missing", missing.isEmpty())
    }
    
    /** Assert every event has session.id and timestamp > 0. */
    fun assertUniversalAttributes(events: List<LogRecordData>) {
        for (event in events) {
            val sessionId = event.attributes[AttributeKey.stringKey("mobile.session.id")]
            assertNotNull("Event '${event.bodyValue?.asString()}' missing session.id", sessionId)
            assertTrue(
                "Event '${event.bodyValue?.asString()}' has zero timestamp",
                event.timestampEpochNanos > 0
            )
        }
    }
    
    /** Assert span parent-child relationships. */
    fun assertSpanParentage(spans: List<SpanData>, parentName: String, childNames: List<String>) {
        val parent = spans.first { it.name == parentName }
        for (childName in childNames) {
            val child = spans.first { it.name == childName }
            assertEquals(
                "Span '$childName' should be child of '$parentName'",
                parent.spanContext.spanId, child.parentSpanContext.spanId
            )
        }
    }
    
    /** Assert same events across all three modes (excluding HYBRID-only signals). */
    fun assertModeEquivalence(
        continuousLogs: List<LogRecordData>,
        conditionalLogs: List<LogRecordData>,
        hybridLogs: List<LogRecordData>,
        continuousSpans: List<SpanData>,
        conditionalSpans: List<SpanData>,
        hybridSpans: List<SpanData>
    ) {
        val hybridOnlyBodies = setOf("device.heartbeat", "prediction.cycle", "prediction.high_risk_alert")
        
        fun logBodies(events: List<LogRecordData>) =
            events.map { it.bodyValue?.asString() ?: "" }
                .filter { it !in hybridOnlyBodies }
                .sorted()
        
        fun spanNames(spans: List<SpanData>) =
            spans.map { it.name }.sorted()
        
        assertEquals("CONTINUOUS vs CONDITIONAL logs differ", logBodies(continuousLogs), logBodies(conditionalLogs))
        assertEquals("CONTINUOUS vs HYBRID logs differ", logBodies(continuousLogs), logBodies(hybridLogs))
        assertEquals("CONTINUOUS vs CONDITIONAL spans differ", spanNames(continuousSpans), spanNames(conditionalSpans))
        assertEquals("CONTINUOUS vs HYBRID spans differ", spanNames(continuousSpans), spanNames(hybridSpans))
    }
    
    /** Assert no duplicate events (same body + same timestamp = duplicate). */
    fun assertNoDuplicates(events: List<LogRecordData>) {
        val fingerprints = events.map { "${it.bodyValue?.asString()}|${it.timestampEpochNanos}" }
        assertEquals("Duplicate events found", fingerprints.size, fingerprints.toSet().size)
    }
    
    /** Assert event A appears before event B by timestamp. */
    fun assertOrdering(events: List<LogRecordData>, beforeBody: String, afterBody: String) {
        val beforeTs = events.first { it.bodyValue?.asString() == beforeBody }.timestampEpochNanos
        val afterTs = events.first { it.bodyValue?.asString() == afterBody }.timestampEpochNanos
        assertTrue("'$beforeBody' should precede '$afterBody'", beforeTs <= afterTs)
    }
}
```

### Test Details

#### ExportModeEquivalenceTest (3 tests)

Parameterized over `ExportMode.CONTINUOUS`, `CONDITIONAL`, `HYBRID`. Each test:
1. Configures `MobileLogRecordProcessor` with the mode
2. Runs `GoldenJourneyEmitter.emit()`
3. Calls `processor.forceFlush()`
4. Collects exported events from `MockLogRecordExporter`

After all three runs: `TelemetryContract.assertModeEquivalence(...)`.

#### PolicyTriggerMatrixTest (15 tests)

3 modes × 5 triggers. For each combination:

| Trigger | Event emitted | Expected behavior (CONDITIONAL) | Expected behavior (CONTINUOUS) | Expected behavior (HYBRID) |
|---------|--------------|--------------------------------|-------------------------------|---------------------------|
| `ui.freeze` (duration > 2s) | `ui.freeze` with `duration_ms=3000` | flush_window(2min) fires | No policy flush, periodic exports | flush_window(2min) fires |
| `app.crash` | `app.crash` with RuntimeException | flush_window(5min) fires | No policy flush | flush_window(5min) fires |
| `http.error` (503) | `http.error` with `http.status_code=503` | flush_window(2min) fires | No policy flush | flush_window(2min) fires |
| `http.error` (404) | `http.error` with `http.status_code=404` | NO flush (excluded) | No policy flush | NO flush (excluded) |
| `app.foreground` | `app.foreground` | flush_window(5min) fires | No policy flush | flush_window(5min) fires |

Assertions: In CONDITIONAL/HYBRID, verify `MockLogRecordExporter` received events after the trigger. In CONTINUOUS, verify events arrive via periodic flush only (no immediate flush on trigger).

For the 404 case: verify `MockLogRecordExporter` received ZERO events in CONDITIONAL mode (no flush triggered). The events stay in the buffer.

#### ErrorFilterMatrixTest (18 tests)

6 exception types × 3 filter configs:

| Exception | Default filter | Empty filter | Custom filter (`ArithmeticException` only) |
|-----------|---------------|-------------|------------------------------------------|
| `SocketTimeoutException` | Filtered (0 events) | Captured (1 event) | Captured (1 event) |
| `ConnectException` | Filtered (0 events) | Captured (1 event) | Captured (1 event) |
| `UnknownHostException` | Filtered (0 events) | Captured (1 event) | Captured (1 event) |
| `RuntimeException` | Captured (1 event) | Captured (1 event) | Captured (1 event) |
| `NullPointerException` | Captured (1 event) | Captured (1 event) | Captured (1 event) |
| `ArithmeticException` | Captured (1 event) | Captured (1 event) | Filtered (0 events) |

#### ConfigFlagMatrixTest (8 tests)

Each test toggles ONE flag from its default, runs the golden journey, and asserts the specific behavioral change:

| Flag | Default | Toggled | Assertion |
|------|---------|---------|-----------|
| `captureUncaughtExceptions` | true | false | Uncaught RuntimeException produces 0 events |
| `captureCoroutineExceptions` | true | false | Coroutine exception produces 0 events |
| `filterExceptions` | network list | empty | SocketTimeoutException produces 1 event |
| `deduplicateWindowMs` | 5 min | 0 | Same exception twice → 2 events (no dedup) |
| `rateLimit` | 10/min | 2/min | 5 exceptions → only 2 captured |
| `flushOnError` | true | false | Error captured but no immediate flush |
| `attachBreadcrumbs` | true | false | Error event has no `mobile.user.journey` attribute |
| `attachVitals` | true | false | Error event has no `device.battery` attribute |

#### SignalContractTest (9 tests)

One test per golden journey event body. Each asserts:
- `timestampEpochNanos > 0` (ensureTimestamp working)
- `observedTimestampEpochNanos > 0`
- `mobile.session.id` attribute present and non-empty
- `severity` is correct (INFO for ui events, ERROR for errors)
- `body` matches expected string

#### DeduplicationMatrixTest (4 tests)

| Scenario | Exceptions | Window | Expected |
|----------|-----------|--------|----------|
| Same exception twice within window | 2× `RuntimeException("crash")` | 5 min | 1 event |
| Same exception after window expires | 2× `RuntimeException("crash")`, 6 min apart | 5 min | 2 events |
| Different exceptions within window | `RuntimeException` + `NullPointerException` | 5 min | 2 events |
| Same message, different stack frame | 2× `RuntimeException("crash")` from different call sites | 5 min | 2 events (different fingerprint) |

#### RateLimitMatrixTest (3 tests)

| Scenario | Exceptions | Limit | Expected |
|----------|-----------|-------|----------|
| Under limit | 5 × RuntimeException | 10/min | 5 events |
| At limit | 10 × RuntimeException | 10/min | 10 events |
| Over limit | 15 × RuntimeException | 10/min | 10 events (5 dropped) |

#### TimestampContractTest (2 tests)

| Scenario | Assertion |
|----------|-----------|
| Event with explicit setTimestamp() | `timestampEpochNanos == explicit value` |
| Event without setTimestamp() | `timestampEpochNanos == observedTimestampEpochNanos` (ensureTimestamp copied it) |

#### HybridTimingTest (1 test)

HYBRID mode only:
1. Emit 3 `device.heartbeat` events
2. Emit `http.error` (triggers policy flush)
3. Assert: heartbeat export batch arrived BEFORE the policy-triggered flush batch
4. Check `MockLogRecordExporter.exportedBatches` ordering

---

## Layer 2: E2E Device Tests

### Prerequisites

- Emulator running
- Demo backend running on port 3001
- Local collector running (Docker, port 14317)
- App + test APK installed
- App pointed at local collector via SharedPreferences

### Test Structure

```
examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/e2e/
├── E2ETestBase.kt                        — shared setup (collector reset, app config, wait helpers)
├── GoldenJourneyModeEquivalenceTest.kt   — 3 modes × same journey → same collector output
├── CrashRecoveryE2ETest.kt              — crash → restart → pre-crash events in collector
├── AirplaneModeE2ETest.kt              — offline → buffer → reconnect → flush
├── AirplaneModeCrashE2ETest.kt         — offline + crash → reconnect → flush
├── TimestampPreservationE2ETest.kt     — buffered events keep original timestamps
├── OtlpSerializationE2ETest.kt         — collector JSON has correct resource/scope/body
├── SpanHierarchyE2ETest.kt             — journey → page → ui.tap parent-child
└── BackendTracePropagationE2ETest.kt   — mobile spanId in backend span parentId
```

### E2ETestBase

```kotlin
abstract class E2ETestBase {
    companion object {
        const val COLLECTOR_OUTPUT = "/path/to/collector/output"
        const val PACKAGE = "io.opentelemetry.android.demo"
    }
    
    // Reset collector output before each test
    fun resetCollector() { /* rm logs.json traces.json metrics.json, restart collector */ }
    
    // Write SharedPreferences for export target + mode
    fun configureApp(mode: String, endpoint: String = "http://10.0.2.2:14317") { /* ... */ }
    
    // Wait for collector to have at least N log events
    fun waitForLogs(minCount: Int, timeoutSeconds: Int = 30) { /* poll logs.json */ }
    
    // Read collector JSON output
    fun readLogs(): List<JsonObject> { /* parse logs.json */ }
    fun readTraces(): List<JsonObject> { /* parse traces.json */ }
}
```

### E2E Test Details

#### 1. GoldenJourneyModeEquivalenceTest

Runs the golden journey 3 times (once per mode). Between runs: reset collector, reconfigure app mode, restart app.

```
For mode in [CONTINUOUS, CONDITIONAL, HYBRID]:
  1. resetCollector()
  2. configureApp(mode)
  3. Run Espresso golden journey (8 steps)
  4. Wait for flush (CONTINUOUS: 15s, CONDITIONAL: triggered by 503, HYBRID: triggered by 503)
  5. Collect logs + traces from collector JSON
  
Assert: log bodies (excluding heartbeat/prediction) are identical across all 3 runs.
Assert: span names are identical across all 3 runs.
```

#### 2. CrashRecoveryE2ETest

1. Configure CONDITIONAL mode
2. Run golden journey steps 1-6 (pre-crash events buffered)
3. Trigger real crash (`RealCrashPhase1Test`)
4. Wait for process death
5. Restart app (`RealCrashPhase2Test`)
6. Wait for recovery flush
7. Assert: collector has `app.crash`, `app.recovery`, AND all pre-crash events
8. Assert: pre-crash event timestamps are from BEFORE the crash, not shifted to recovery time

#### 3. AirplaneModeE2ETest

1. Enable airplane mode
2. Launch app, run golden journey steps 1-5 (events buffer to disk, exports fail)
3. Disable airplane mode
4. Force restart app (triggers RecoveryTracker flush)
5. Wait 20s
6. Assert: collector has all buffered events
7. Assert: event timestamps are from the offline period, not from the flush time

#### 4. AirplaneModeCrashE2ETest

Combines airplane mode + crash:
1. Enable airplane mode
2. Launch app, generate events
3. Trigger real crash (offline)
4. Disable airplane mode
5. Restart app (recovery + flush)
6. Assert: pre-crash events + crash event + recovery event all in collector
7. Assert: timestamps preserved from offline period

#### 5. TimestampPreservationE2ETest

1. Record wall-clock time T1
2. Run golden journey
3. Record wall-clock time T2
4. Wait for flush
5. Assert: all event `timeUnixNano` values are between T1 and T2
6. Assert: all event `timeUnixNano` values are non-null (ensureTimestamp working)
7. Assert: `observedTimeUnixNano` values are also between T1 and T2 (not shifted)

#### 6. OtlpSerializationE2ETest

Validates the raw JSON structure in collector output:
1. Run golden journey
2. Wait for flush
3. Parse `logs.json`, assert each record has:
   - `resourceLogs[].resource.attributes` contains `service.name`, `device.id`, `os.name`
   - `scopeLogs[].scope.name` is non-empty
   - `logRecords[].body.stringValue` is non-empty
   - `logRecords[].timeUnixNano` is non-null and non-zero
   - `logRecords[].severityNumber` is set
4. Parse `traces.json`, assert each record has:
   - `resourceSpans[].resource.attributes` contains `service.name`
   - `scopeSpans[].spans[].name` is non-empty
   - `scopeSpans[].spans[].traceId` and `spanId` are non-empty

#### 7. SpanHierarchyE2ETest

1. Start a journey span
2. Run golden journey
3. End journey span
4. Wait for flush
5. Parse `traces.json`
6. Assert: `page.BookFragment` span has `parentSpanId` == journey span's `spanId`
7. Assert: HTTP span has `parentSpanId` == `page.BookFragment` span's `spanId`

#### 8. BackendTracePropagationE2ETest

Requires demo backend running with OTel tracing.
1. Run golden journey step 6 (submit booking → HTTP POST to backend)
2. Wait for both mobile and backend telemetry to arrive at collector
3. Parse `traces.json`
4. Find the mobile HTTP span (name starts with `HTTP`)
5. Find the backend Express span (service.name = `otel-mobile-backend`)
6. Assert: backend span's `parentSpanId` == mobile HTTP span's `spanId`
7. Assert: both spans share the same `traceId`

---

## Test Count Summary

| Test file | Tests |
|-----------|-------|
| ExportModeEquivalenceTest | 3 |
| PolicyTriggerMatrixTest | 15 |
| ErrorFilterMatrixTest | 18 |
| ConfigFlagMatrixTest | 8 |
| SignalContractTest | 9 |
| DeduplicationMatrixTest | 4 |
| RateLimitMatrixTest | 3 |
| TimestampContractTest | 2 |
| HybridTimingTest | 1 |
| **Unit total** | **63** |
| GoldenJourneyModeEquivalenceTest | 1 (runs 3 modes internally) |
| CrashRecoveryE2ETest | 1 |
| AirplaneModeE2ETest | 1 |
| AirplaneModeCrashE2ETest | 1 |
| TimestampPreservationE2ETest | 1 |
| OtlpSerializationE2ETest | 1 |
| SpanHierarchyE2ETest | 1 |
| BackendTracePropagationE2ETest | 1 |
| **E2E total** | **8** |
| **Grand total** | **71** |

---

## Running the Tests

### Unit tests (CI, every push)

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:testDebugUnitTest --tests "*.matrix.*"
```

### E2E tests (pre-demo, pre-release)

```bash
# Requires: emulator running, backend running, collector running
cd examples/demo-app
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.e2e
```

### Demo Control Center integration

Add to the menu under INSPECT RESULTS:

```
t)  Run contract test matrix (unit tests, ~30s)
T)  Run E2E contract tests (requires emulator + collector, ~4min)
```

---

## Out of Scope

- iOS equivalence testing (iOS SDK doesn't exist yet)
- Performance benchmarking (how fast events export, not what exports)
- Amplify DataStore signal validation (module not wired to demo app yet)
- Backend scheduling span validation (backend instrumentation not yet deployed)
- Dash0 API-based contract validation (Prometheus API can't query event bodies)
