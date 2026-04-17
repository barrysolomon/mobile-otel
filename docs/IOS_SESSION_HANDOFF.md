# iOS Session Handoff — 2026-04-17

Snapshot of the `iPhone` branch at end of the 2026-04-17 session. Read this when resuming iOS work so the next session has full context without re-discovering state.

## Where we left off

- **Branch:** `iPhone`, 26 commits ahead of `main`, pushed to `origin/iPhone`.
- **Tests:** 107 passing on macOS via `./run-tests.sh`; iOS simulator build verified on iPhone 17 simulator (iOS 26.4) in an earlier run.
- **Last action attempted:** running `validate-ios-end-to-end.sh` to send a full batch of telemetry to Dash0 and verify arrival. **Interrupted by a path bug** (fixed in-session — next run will use the real path resolution).
- **Next action:** rerun `scripts/test/validate-ios-end-to-end.sh` and query Dash0 (filter `os.name=iOS`, `service.name=otel-ios-demo-starter`) to confirm the full stack works end-to-end.

## Command to resume

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel"
git checkout iPhone   # if not already there
./validate-ios-end-to-end.sh   # symlink path now works after the realpath fix
```

Or the dual-platform demo for side-by-side Android+iOS validation:

```bash
./run-dual-platform-demo.sh   # boots both simulators, auto-emits for ~indefinite
```

## Architectural map (1-minute read)

```
App
 │
 ▼ OTelMobile.start(config:)   // one call wires everything
OTelMobile (facade)
 │
 ├── LoggerProvider
 │    ├── MobileLogRecordProcessor
 │    │    ├── RAMEventBuffer (actor, capped on events + bytes + per-event)
 │    │    ├── DiskLogBuffer (sqlite3 actor, WAL, startup recovery) [optional]
 │    │    ├── PolicyEvaluator (actor, LRU regex cache)
 │    │    └── OTel LogRecordExporter (OTLP/HTTP; gRPC via factory)
 │    └── BatchLogRecordProcessor → same OTLP exporter (real-time export)
 │
 ├── TracerProvider → BatchSpanProcessor → OTLP
 ├── MeterProvider → PeriodicMetricReader → OTLP
 │
 ├── Resource (os.name=iOS, device.manufacturer=Apple, ...)
 ├── SessionManager (UUID + 15min inactivity + UserDefaults persist)
 │
 ├── Auto-installed instrumentation (flagged by AutoCaptureOptions, deferred main-tick):
 │    ├── NetworkInstrumentation (URLProtocol)
 │    ├── LifecycleInstrumentation (NotificationCenter)
 │    ├── ErrorsInstrumentation (NSException chain-through + signal handlers)
 │    ├── ScreenInstrumentation (SwiftUI ViewModifier default, UIKit swizzle opt-in)
 │    ├── FreezeInstrumentation (DispatchSourceTimer main-thread watchdog)
 │    └── VitalsInstrumentation (app.start, ui.jank, memory_warning)
 │
 └── When enablePolicyPolling=true:
      └── ConfigPoller → evaluator.updatePolicies → wired to processor
```

Full policy loop now closes: `gateway → ConfigPoller → PolicyEvaluator → onEmit → conditional flushWindow → OTLP → Dash0`.

## Commits this session (21 on iPhone)

`b066415` → `70b49c6` → `fedcb51` → `36b3d7f` → `516151b` → `4ca5e2d` → `c4a3db0` → `c53ba77` → `50c9ef6` → `9f330e7` → `2a7207e` → `c9f156e` → `212bc3a` → `ea7f448` → `8739420` → `9ad8797` → `ce52dc5` → `f1a187b` → `61ed9e4` → `45dfb73` → `6a7b645`

(See `git log iPhone --oneline ^main` for full messages.)

## Important files for next-session

| File | Role |
|---|---|
| `otel-ios-mobile/CLAUDE.md` | Agent-facing SDK guide |
| `docs/IOS_ANDROID_PARITY.md` | What's done + deferred |
| `docs/SDK_SAFETY.md` | Defensive posture + remaining risks |
| `docs/IOS_CRASH_REPORTING.md` | PLCrashReporter integration |
| `otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift` | Entry point, auto-install logic |
| `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift` | Buffer + policy evaluation + export |
| `scripts/test/validate-ios-end-to-end.sh` | Single-command E2E validation |
| `scripts/demo/run-dual-platform-demo.sh` | Concurrent Android+iOS demo |
| `.github/workflows/ios-tests.yml` | CI incl. static safety audit |

## Deferred / known issues for future sessions

1. **Screenshot/Wireframe SwiftUI ViewModifiers** — needs privacy-design first (text redaction, attribute size caps, consent model).
2. **28 Android `validate-us0XX-*.sh` scenario scripts** — only 1 iOS equivalent today. Low priority unless a customer asks.
3. **Bundle.main path in demo app config loader** — works with the current demo flow; if we ever ship the SDK for distribution through SPM as a standalone dependency (vs in-repo), double-check resource lookups.
4. **OTel-Swift 2.x is still young** — monitor upstream for gauge API changes (we use the current builder pattern but this has churned once already per the 2026-04-16 iOS spec amendment history).

## Branch discipline

Per `project_iphone_branch.md` memory: **do NOT merge iPhone → main** without explicit approval. iOS port stays isolated until validated in production / on real devices. `main` remains Android-only and ships-ready.
