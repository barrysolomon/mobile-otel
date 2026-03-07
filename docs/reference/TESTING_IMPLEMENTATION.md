# Testing Implementation

Current state of the test suite across all components of the mobile observability system.

---

## Android Unit Tests — 486 tests passing

All tests run via Robolectric (JVM, no emulator required):

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:test
```

### Test files

| File | Tests | What it covers |
|---|---|---|
| `MobileLoggerProviderTest.kt` | 13 | Init, singleton, device ID persistence, force flush, shutdown |
| `MobileConfigTest.kt` | 18 | Validation, builder pattern, defaults, input validation |
| `MobileLogRecordProcessorTest.kt` | ~60 | onEmit, RAM buffer overflow, policy evaluation integration, time-window flush, force flush, thread safety |
| `DiskLogBufferTest.kt` | ~50 | Event persistence, TTL cleanup, size enforcement, Room database ops, time-window queries |
| `PolicyEvaluatorTest.kt` | ~70 | All condition operators (equals/gt/lt/gte/lte/contains/regex), logical operators (and/or), geo/device extension |
| `PolicyEvaluatorGeoDeviceTest.kt` | ~30 | Geo/device policy DSL — timezone, OS version, device model matching |
| `PiiScrubberTest.kt` | ~25 | Email, phone, URL scrubbing from stack traces |
| `SessionTrackerTest.kt` | ~20 | Session ID lifecycle, view ID rotation, screen name tracking |
| `DynamicSamplerTest.kt` | ~20 | Baseline rate, `page.*` always-sample, scheduled revert, thread safety |
| `ExportModeTest.kt` | ~60 | CONDITIONAL/CONTINUOUS/HYBRID mode behavior, policy trigger gates, non-matching event handling |
| `UserJourneyExportModeTest.kt` | ~40 | 18 scenario tests + 2 cross-mode comparisons across 4 journey families |

### Test infrastructure

**`MockLogRecordExporter`** — in-memory capture, `shouldFail` toggle, `simulatedDelayMs`, per-batch tracking via `exportBatches`, `waitForLogs()` async helper.

**`TestUtils`** — factory methods for all common event types:
- `createTestLogRecord(body, attrs, timestamp, severity)`
- `createUIFreezeLog(durationMs)`, `createCrashLog(type)`, `createHttpErrorLog(statusCode, route)`
- `createNavigationEvent(screen, previousScreen)`, `createBreadcrumb(action, screen)`
- `createTransactionEvent(index, type)`, `createApiRequestEvent(index, endpoint, statusCode)`
- `emitAll(processor, records)` — batch emit helper

**`TestLogRecordData`** — lightweight `LogRecordData` implementation for test construction without the full OTel SDK overhead.

### Export mode validation (UserJourneyExportModeTest)

Tests that verify the same user journey emits **different telemetry** under each export mode:

| Scenario | CONDITIONAL | CONTINUOUS | HYBRID |
|---|---|---|---|
| Happy-path booking (nav + transactions) | Held in buffer | Auto-exported on timer | Timer exports |
| Network error recovery (pre-error requests → http.error) | Pre-error held → http.error flushes all | Timer exports without waiting for error | Periodic + error-triggered extra flush |
| Crash recovery (24 events → app.crash) | All 24 held → crash flushes | Auto-exports without crash | Periodic + crash triggers extra flush |
| HTTP error flush (15 requests → http.error) | All 15 held → http.error flushes | Auto-exports | Both paths |

---

## Go Unit Tests — 90+ tests

```bash
cd collector-processor/mobilepolicyprocessor
go test -v -race ./...
```

| File | Tests | Coverage |
|---|---|---|
| `processor_test.go` | 60+ | Policy evaluation, log annotation, ConsumeLogs pipeline, resource attrs, sample action, multi-policy |
| `config_test.go` | 30+ | Config validation, condition operators, logical operators, action types, complex scenarios |
| `factory_test.go` | 7 | Factory creation, default config, processor creation, capabilities, invalid config, start/shutdown |

---

## Espresso Instrumented Tests — 17 tests (4 suites)

Run on connected emulators/devices. Generate live telemetry to Dash0.

```bash
# All suites, both emulators
./run-dash0-scenarios.sh --all

# One suite
./run-dash0-scenarios.sh --stress --verbose

# One test on one device
./run-dash0-scenarios.sh --stress --test batteryDrain --device emulator-5554
```

### Suite: UserJourneyScenarios

Happy-path and common-error user flows through the Schedulr demo app:

| Test | Flow | Dash0 signals |
|---|---|---|
| `happyPathBooking` | Nav → search → book appointment | `appointment.booked`, booking span with form/device attrs, `POST /api/appointments` child span |
| `browseWithoutBooking` | Nav 4 screens, no booking | Screen view events, page spans, tap/swipe auto-capture |
| `searchAndNetworkError` | Search → swipe refresh → trigger HTTP 500 | `http.error` → `http-error-detector` policy flush, pre-error requests in flush window |
| `getDirections` | Book → directions → location | Location permission pre-granted, deep link breadcrumbs |

### Suite: FaultScenarios

Isolated fault injections demonstrating SDK signal generation:

| Test | Fault | Dash0 signals |
|---|---|---|
| `jankDetection` | DebugToolbar → Trigger Jank | `mobile.ui.jank` with `jank.severity`, `jank.frame_time_ms`, `jank.dropped_frames` |
| `memoryPressure` | DebugToolbar → Trigger Memory | `device.memory.low`, health metrics, possible pre-emptive flush |
| `anrDetection` | DebugToolbar → Trigger ANR (6s block) | `anr.detected`, `anr.recovered`, breadcrumb trail |
| `crashAndRecovery` | Simulated app.crash + recovery | `app.crash` → `crash-recovery` policy flush of 5-min window, `app.crash_recovery` |

### Suite: ConditionalFlushScenarios

Demonstrates buffer accumulation + policy-triggered selective flush:

| Test | Setup | Trigger | Expected |
|---|---|---|---|
| `quietBufferThenCrashFlush` | 20 transactions + 4 nav events (24 total, silent) | `app.crash` event | All 24 flushed, `buffer.snapshot` events show pre/post |
| `httpErrorFlush` | 15 `api.request` events buffered | HTTP 500 via toolbar + explicit `http.error` | All 15 flushed, `http-error-detector` policy fires |

### Suite: EmulatorStressScenarios

Injects real device stress via `uiAutomation.executeShellCommand()` — no root, no external adb:

| Test | Shell commands | SDK response |
|---|---|---|
| `batteryDrain` | `dumpsys battery set level X` (100→5 in steps) | `device.battery_level` gauge, crash_risk prediction at ≤15%, pre-emptive flush |
| `thermalThrottle` | `cmd thermalservice override-status X` (API 29+) + `dumpsys battery set temp` | Thermal gauge, network_loss_risk prediction at SEVERE |
| `memoryPressure` | `am send-trim-memory RUNNING_LOW/CRITICAL/COMPLETE` | onTrimMemory callbacks, health metrics, possible pre-emptive flush |
| `networkDegradation` | `svc wifi disable` + `svc data disable` | Connectivity change logs, HTTP span errors |
| `rapidBatteryDrain` | 100→1% in 5% steps at 500ms intervals | Measures prediction detection latency |
| `combinedStress` | Low battery + SEVERE thermal + RUNNING_CRITICAL memory simultaneously | Elevated crash_risk, early aggressive flush |
| `extremeLowBattery` | Instant drop to 5% | Measures time from condition to pre-emptive flush |

Each stress test:
1. Calls `restoreEmulatorState()` in `@Before`/`@After` (separate from base class `setUp`/`tearDown`)
2. Emits `buffer.snapshot` events at key milestones to make flush visible in Dash0
3. Tags all telemetry with `demo.run_id` for filtering

---

## Test Runners

### `./run-tests.sh` — unit tests only

```bash
./run-tests.sh               # Android unit tests + Go unit tests
./run-tests.sh --android-only
./run-tests.sh --go-only
./run-tests.sh --integration  # Adds connectedDebugAndroidTest (requires emulator)
```

### `./run-dash0-scenarios.sh` — Dash0 telemetry scenarios

```bash
./run-dash0-scenarios.sh --all                          # All 4 suites, all connected devices
./run-dash0-scenarios.sh --journeys --faults            # Specific suites
./run-dash0-scenarios.sh --stress --test batteryDrain   # Single test
./run-dash0-scenarios.sh --all --run-id "sprint42"      # Custom Dash0 run ID
./run-dash0-scenarios.sh --all --repeat 3               # Soak/load run
./run-dash0-scenarios.sh --dry-run                      # Build only
./run-dash0-scenarios.sh --list-devices                 # Show connected devices
./run-dash0-scenarios.sh --all --report                 # Open HTML report after run
```

All scenario runs print a Dash0 filter at the end: `demo.run_id = "<run_id>"`.

---

## Default Export Policies

Three policies active by default in `PolicyEvaluator`. Tests validate that these fire correctly:

| Policy ID | Trigger | Action |
|---|---|---|
| `ui-freeze-detector` | log body = `"ui.freeze"` | `flushWindow(2 minutes)` |
| `crash-recovery` | log body = `"app.crash"` | `flushWindow(5 minutes)` |
| `http-error-detector` | log body = `"http.error"` | `flushWindow(5 minutes)` |

---

## Test Design Patterns

### Processor injection for unit tests

```kotlin
val mockExporter = MockLogRecordExporter()
val processor = MobileLogRecordProcessor.builder(context)
    .setExporter(mockExporter)
    .build()
processor.onEmit(Context.root(), asReadWriteLogRecord(TestUtils.createCrashLog()))
assertThat(mockExporter.exportedLogs).hasSize(1)
```

### Export mode validation pattern

```kotlin
// CONDITIONAL: events with non-matching body stay buffered
val processor = buildProcessor(ExportMode.CONDITIONAL, exporter)
TestUtils.emitAll(processor, TestUtils.createTestLogRecords("api.request", 10))
assertThat(exporter.exportedLogs).isEmpty()  // nothing yet

// Trigger — crash recovery policy fires
processor.onEmit(Context.root(), asReadWriteLogRecord(TestUtils.createCrashLog()))
assertThat(exporter.exportedLogs.size).isGreaterThan(10)  // crash + pre-crash events
```

### Emulator stress pattern

```kotlin
// Inject condition
uiAutomation.executeShellCommand("dumpsys battery set level 5").close()
// Emit marker so flush is visible in Dash0
MobileOtel.sendEvent("stress.extreme_low_battery", mapOf("battery.level" to 5), Severity.ERROR)
// Allow health monitor to detect + flush
Thread.sleep(10_000)
emitBufferStats("post_flush")
// Restore
uiAutomation.executeShellCommand("dumpsys battery reset").close()
```

---

## CI/CD

GitHub Actions (`.github/workflows/test.yml`):

| Job | Trigger | What runs |
|---|---|---|
| `android-unit-tests` | Every push/PR | `./gradlew :otel-android-mobile:test` + Codecov upload |
| `go-tests` | Every push/PR | `go test -race ./...` + Codecov upload |
| `lint` | Every push/PR | Android lint + `go vet` + `golangci-lint` |
| `build` | Every push/PR | Full build verification including demo app |
| `android-integration-tests` | `main` branch only | Emulator + `connectedDebugAndroidTest` |
