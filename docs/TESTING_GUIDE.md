# Testing Guide

Quick reference for all test commands.

## Scripts (run from repo root)

| Script | What | Emulator? | Dash0? |
|--------|------|-----------|--------|
| `./scripts/test/run-unit-tests.sh` | All unit tests (Android + Go) | No | No |
| `./scripts/test/run-unit-tests.sh --android` | Android unit tests only | No | No |
| `./scripts/test/run-unit-tests.sh --go` | Go processor tests only | No | No |
| `./scripts/test/run-integration-tests.sh` | SDK integration tests on emulator | Yes | No |
| `./scripts/test/run-dash0-tests.sh` | All 18 Dash0 scenarios | Yes | Yes |
| `./scripts/test/run-dash0-tests.sh --journeys` | UserJourney suite only | Yes | Yes |
| `./scripts/test/run-validated-tests.sh` | Scenarios + local collector + validation | Yes | No (local) |
| `./scripts/demo/run-demo-full.sh` | Full demo (emulators + build + Dash0 scenarios) | Starts 2 | Yes |
| `./scripts/e2e/run-e2e.sh` | Zero-to-demo Android e2e + **Dash0 receipt gate** | Starts 2 | Yes |
| `./scripts/e2e/run-platform-e2e.sh ios-native rn-android rn-ios` | Drive the other 3 platforms' demos (launch → crash → recovery), receipt-gated | Sim/emu | Yes |

---

## Validated Tests (local collector, no Dash0 needed)

The gold standard: runs scenarios against a local OTel Collector (Docker), then validates that all expected signals were received. **No Dash0 account required.**

```bash
./scripts/test/run-validated-tests.sh              # emulator must be running
./scripts/test/run-validated-tests.sh --start-emu  # starts emulator for you
./scripts/test/run-validated-tests.sh --skip-scenarios  # just validate (data already collected)
```

What it does:
1. Starts a local OTel Collector in Docker (ports 4317/4318)
2. Configures the demo app to export to the local collector
3. Runs UserJourney scenario tests on the emulator
4. Waits for collector to flush to JSON files
5. Validates received telemetry (checks for expected signals)
6. Stops the collector
7. Restores original Dash0 config

Signals validated:
- `ui.tap`, `ui.screen_view` (required)
- `app.foreground`/`app.background` (required)
- `session.id`, `view.id`, `screen.name` attributes (required)
- `page.*` trace spans (required)
- `service.name`, `device.id` resource attributes (required)
- `ui.scroll`, `ui.back_press`, `device.orientation` (optional — may not trigger in all scenarios)

**Requires Docker.** To validate manually:
```bash
# Just the validation part (if collector output already exists):
./scripts/test/validate-telemetry.sh
```

---

All manual Gradle commands run from `examples/demo-app/`.

---

## Unit Tests (no emulator needed)

### Run everything (~2 min)
```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:testDebugUnitTest \
  :otel-android-mobile:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest \
  :instrumentation-screen:testDebugUnitTest \
  :instrumentation-scroll:testDebugUnitTest \
  :instrumentation-lifecycle:testDebugUnitTest \
  :instrumentation-errors:testDebugUnitTest \
  :instrumentation-screenshot:testDebugUnitTest \
  :instrumentation-wireframe:testDebugUnitTest \
  :instrumentation-compose-click:testDebugUnitTest \
  :instrumentation-screen-orientation:testDebugUnitTest
```

Or use the root script:
```bash
./scripts/ci/run-tests.sh --android-only
```

### Run a single module
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest
./gradlew :otel-android-mobile:testDebugUnitTest
./gradlew :instrumentation-tap:testDebugUnitTest
```

### Run a single test class
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.SupersedesConflictTest"
./gradlew :otel-android-mobile:testDebugUnitTest --tests "*.MobileOtelDslTest"
```

### Go processor tests
```bash
cd collector-processor/mobilepolicyprocessor
go test -v -race ./...
```

---

## Integration Tests (emulator required, no Dash0 needed)

These run on-device but don't send data to Dash0. They test SDK internals (buffer flow, singleton init, exporter wiring).

### Start an emulator
```bash
nohup emulator -avd Medium_Phone_API_36.1 -no-snapshot-save > /tmp/emu.log 2>&1 &
adb wait-for-device
# Wait for boot (~2-4 min):
until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
```

### Run SDK integration tests (~1 min)
```bash
cd examples/demo-app
./gradlew :otel-android-mobile:connectedDebugAndroidTest
```

Tests included:
- `BufferIntegrationTest` — RAM buffer → disk overflow → flushWindow → exporter (9 tests)
- `ExporterCustomizerIntegrationTest` — customizers applied during init, chain order (2 tests)
- `DslIntegrationTest` — DSL overload returns OpenTelemetryMobile, stores on MobileOtel, shutdown cleans up (5 tests)

---

## Dash0 Scenario Tests (emulator + Dash0 credentials required)

These run real user flows on the demo app and **send telemetry to Dash0**. Use these to generate demo data.

### Prerequisites
1. Emulator running
2. Demo backend running: `cd examples/demo-backend && npm run dev &`
3. Dash0 credentials in `examples/demo-app/android/src/debug/assets/otel-config.json`

### Run all scenarios (~8 min, sends data to Dash0)
```bash
cd examples/demo-app
./gradlew :android:connectedDebugAndroidTest
```

This runs 18 tests across 4 suites:
- **UserJourneyScenarios** (5 tests) — multi-screen booking flow, error recovery, navigation
- **FaultScenarios** (4 tests) — jank, ANR, memory pressure, crash recovery
- **ConditionalFlushScenarios** (2 tests) — silent buffer → crash triggers flush
- **EmulatorStressScenarios** (7 tests) — battery drain, thermal, network degradation

### Run a single suite
```bash
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.scenarios.UserJourneyScenarios
```

### Run a single test
```bash
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.scenarios.UserJourneyScenarios#happyPathBooking
```

---

## Full Demo (the big one — sends data to Dash0)

Starts 2 emulators, backend, runs all tests, installs and launches the demo app. **This is what you run before a demo.**

```bash
./scripts/demo/run-demo-full.sh              # windowed (for live demos)
./scripts/demo/run-demo-full.sh --headless   # headless (for CI)
./scripts/demo/run-demo-full.sh --skip-emu   # emulators already running
```

What it does:
1. Starts Pixel_7 + Pixel_3a emulators
2. Starts demo backend (port 3001)
3. Runs all unit tests
4. Builds + installs demo APK on both emulators
5. Enables incubating modules (screenshot, wireframe)
6. Launches the app
7. Runs all 18 Espresso scenarios (sends data to Dash0)
8. Runs SDK integration tests
9. Prints Dash0 dashboard summary

**After it finishes, check Dash0** (dataset: `otel-mobile`):
- `ui.tap`, `ui.screen_view`, `ui.scroll` events
- Journey → page → ui.tap span hierarchy
- Stress signals: `device.health`, `thermal.status`
- Conditional flush: burst of events after crash trigger

---

## Comparison Demo (Phase 5 — upstream vs Dash0)

Builds the upstream astronomy shop demo with both SDKs:

```bash
cd examples/demo-app
./gradlew :upstream-demo-app:installUpstreamDebug :upstream-demo-app:installDash0Debug
```

Both APKs install side-by-side. Run the same flow in each, compare telemetry in Dash0.

---

## CI safety net — what runs where

Per the hardening principle in [TEST_HARDENING_PLAN.md](TEST_HARDENING_PLAN.md)
("a check that CI never runs is a check that doesn't exist"):

| Layer | Workflow | When | What |
|---|---|---|---|
| Unit + lint | `ci.yml` (`android`) | every push | full Robolectric suite (1237 tests), lint |
| R8 consumer gate | `ci.yml` (`android-minified`) | every push | demo app builds minified; entry points survive identity-mapped |
| **AAR size budget** | `ci.yml` (`aar-size`) | every push | umbrella AAR ≤ 700 KB; raising the budget is a same-PR reviewed decision (`scripts/ci/check-aar-size.sh`) |
| iOS compile gate | `ios-ci.yml` | every push (iOS paths) | `swift build` host-side (the full suite hangs off-simulator) |
| **Android instrumented** | `device-tests.yml` | nightly + `v*` tags + manual | full `connectedDebugAndroidTest` on an API-34 emulator — includes the launch gates below |
| **iOS full suite** | `device-tests.yml` | nightly + `v*` tags + manual | 530 tests via `xcodebuild test -scheme OTelMobile-Package` on a simulator |
| Publish gate | `publish.yml` (`verify-ci-green`) | `v*` tags | refuses to publish a tag whose commit lacks green CI |

Trigger the device suite on demand: `gh workflow run device-tests.yml`

### Launch gates (run in every instrumented pass)

| Gate | Proves |
|---|---|
| `StartupBudgetTest` | `OTelMobile.start()` blocks the main thread < 50 ms (HS-001; documented 3× allowance on software-rendered emulators) |
| `StopThreadSafetyTest` | `stop()` is callable from any thread |
| `KillSwitchEndToEndTest` (unit) | remote `sdk.enabled=false` stops both export choke points via the real config-poll path |
| `CrashHandlerChainingTest` (unit) | Crashlytics-style handlers coexist; SDK persists before delegating; exactly one `app.crash` per crash |
| `DiskBufferUpgradePathTest` (unit) | a v1-schema buffer survives the Room migration chain with every event readable |
| `DiskBufferCorruptionRecoveryTest` (unit) | garbage/truncated/foreign-schema buffer files recreate instead of crashing the host |

## Quick Reference

| What | Command | Emulator? | Dash0? | Time |
|------|---------|-----------|--------|------|
| Unit tests (all) | `./scripts/ci/run-tests.sh --android-only` | No | No | ~2 min |
| Go tests | `go test -v -race ./...` | No | No | ~5 sec |
| SDK integration | `./gradlew :otel-android-mobile:connectedDebugAndroidTest` | Yes | No | ~1 min |
| Dash0 scenarios | `./gradlew :android:connectedDebugAndroidTest` | Yes | Yes | ~8 min |
| Full demo | `./scripts/demo/run-demo-full.sh` | Starts 2 | Yes | ~12 min |
| All-platform receipt gate | `./scripts/e2e/run-platform-e2e.sh` | Sim/emu | Yes | ~15 min |
| Device suites in CI | `gh workflow run device-tests.yml` | Hosted | No | ~30 min |
| Comparison demo | Build both flavors, install, manual | Yes | Optional | ~5 min |
