# Parity test inventory

Cross-cutting behaviors and the end-to-end tests that lock them down on each platform. Unit tests aren't enough — Robolectric collapses Android's threading model (memory: `feedback_robolectric_main_thread`), the iOS simulator behaves differently from real iOS in subtle ways (memory: `feedback_ios_nwpathmonitor_test_seam`), and React Native depends on the native modules underneath. Each behavior needs a real-device or real-simulator test to call itself locked down.

This document is an honest map of "what's covered E2E vs. what isn't." Gaps here are the next pieces of work.

## Behavior coverage

| Behavior | Android E2E | iOS E2E | RN E2E |
|---|---|---|---|
| HYBRID flushes on `http.error` policy match | `ConditionalFlushScenarios.httpErrorFlush` (connectedAndroidTest, Pixel_7) | `SchedulrUITests.testHttpErrorThroughOTelURLProtocol` (iPhone 17 simulator) | not yet — relies on native modules |
| CONTINUOUS periodic flush drains buffer | `UserJourneyScenarios` covers it implicitly (multi-screen flow exports throughout) | implicit via Schedulr launch + Dash0 query | not yet |
| Crash → flush on recovery launch (HYBRID + CONDITIONAL) | `ConditionalFlushScenarios.crashRecovery` (real RuntimeException + APK reinstall) | `SchedulrUITests` crash button + recovery (manual) | not yet |
| Network LOST → AVAILABLE triggers flush | `OfflineResilienceScenarios` airplane-toggle | iOS NetworkRestoredFlushTests is unit-only; **no real-simulator E2E** | not yet |
| Offline buffering survives process death (disk buffer) | `OfflineResilienceScenarios.diskBufferSurvivesKill` | iOS `OfflineReconnectionTests` is unit-only; **no real-simulator E2E** | not yet |
| ANR / ui.freeze emits + matches policy | `FaultScenarios.anr` | not yet | not yet |
| Predictive export policy fires on network-loss signal | covered by demo-side instrumented test | not yet | not yet |
| Disk buffer eviction under sustained load | `EmulatorStressScenarios.thermalAndBattery` + unit `TtlEvictionStressTests` (both platforms) | `TtlEvictionStressTests` unit only | not yet |
| RN bridge: native config propagation | `validate-rn-end-to-end.sh --mode=jest` (mock-mode) | mode=jest | not yet on real simulator/emulator |
| Sampler config: dynamic priorities for `page.*` / `app.startup` | implicit in UserJourneyScenarios | not yet | not yet |

## Notable gaps

1. **iOS network-restored flush has no E2E.** `NetworkRestoredFlushTests.swift` is unit-level and uses the static `_offlineOverride` test seam. There is no iPhone-simulator test that toggles airplane mode and asserts an OTLP POST follows. Android has `OfflineResilienceScenarios` doing exactly this.

2. **iOS offline disk-buffer survival has no E2E.** The unit `OfflineReconnectionTests` exercise the disk-buffer drain path, but no test runs the SDK through a real simulator process kill + relaunch + Dash0 confirmation.

3. **RN has no real-device E2E for any of these behaviors.** The current `validate-rn-end-to-end.sh --mode=jest` runs the JS bridge tests in mock mode; the parity guarantee for RN today is "if the native modules pass, the bridge passes." When the bridge marshalling itself drifts, only a real-device test would catch it.

4. **No Go-side fixture runner for policy matchers.** Track 2 of the architecture-hardening epic added Android + iOS golden fixture tests. Go is deferred because its processor evaluates per-OTLP-record-stream rather than against a JSON policy + attribute map shape; a Go runner needs a different test harness.

## What to do about the gaps

The architecture-hardening epic does NOT close these — closing them is a multi-week parity epic that follows. The point of this document is to make the gaps visible and acknowledged rather than assumed-covered.

When prioritising the parity-coverage epic, weight by:
- **Customer-facing pain.** A customer hits the iOS network-restored gap if they have a flaky network; this is high-traffic.
- **Drift risk.** RN is downstream of both native SDKs; drift in either native module breaks RN silently.
- **Cost to add.** The iOS E2E gaps are cheap (XCUITest already wired); RN E2E needs Detox or similar (expensive).

Recommended next epic order: iOS NetworkRestored E2E → iOS offline disk-buffer E2E → RN airplane-toggle E2E.
