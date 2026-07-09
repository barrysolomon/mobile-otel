# CLAUDE.md — otel-ios-mobile

Guidance for Claude Code (claude.ai/code) when working in this iOS SDK subtree.

## What this is

Swift Package Manager-based iOS SDK that wraps the upstream OpenTelemetry Swift SDK (`opentelemetry-swift` 2.x) and adds mobile-specific features the upstream lacks: dual-tier buffering, policy DSL evaluator, OTLP/HTTP export, 6 instrumentation modules, iOS resource attributes, crash recovery.

Parallel to `otel-android-mobile/` — both repos share the same policy DSL v2 schema and produce comparable telemetry in Dash0.

## Build commands

```bash
# Run everything from the REPO ROOT (mobile-otel/) — Package.swift moved there in PR #54 (d879fb17).
cd /Users/barrysolomon/Projects/Dash0/mobile-observability/mobile-otel

# Host tests (macOS target, fast — ~1-2s):
./scripts/ci/run-tests.sh --ios

# iOS simulator tests (requires Xcode + iOS 26 runtime installed — first run ~5 min, subsequent ~20s):
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test \
    -scheme OTelMobile-Package \
    -destination "platform=iOS Simulator,name=iPhone 17"

# Full cross-platform run:
./scripts/ci/run-tests.sh --all   # Android + Go + iOS + RN
```

Note: `swift test` works from either directory (SwiftPM walks up to find `Package.swift`), but `xcodebuild` run from `otel-ios-mobile/` fails with "does not contain an Xcode project, workspace or package".

## Key toolchain gotchas (from experience)

1. **Don't `import Foundation` in test files.** Swift Testing's `_Testing_Foundation` overlay framework ships without its `Modules/` directory in Command Line Tools. Tests that need `Foundation` types must go through SDK-side helpers (see `BufferedEventTestSupport.swift` / `DiskLogBufferTestSupport.swift` for the pattern).

2. **opentelemetry-swift is split.** `opentelemetry-swift-core` has the API/SDK; `opentelemetry-swift` has the exporters. Both are in `Package.swift` as separate dependencies. Instrumentation modules depend on `OpenTelemetryApi` (from core); SDK target depends on both.

3. **iOS 15 API compat.** Package targets `.iOS(.v15)` so we don't use `ContinuousClock`, `Duration`, `Task.sleep(for:)`. Use `Date` / `Task.sleep(nanoseconds:)` instead.

4. **Auto-install must be deferred.** Instrumentation `install()` calls during `App.init` / `@StateObject` closure race with SwiftUI's scene setup, leaving apps stuck on the launch screen. `OTelMobile.start(config:)` wraps installs in `DispatchQueue.main.async`. Don't remove that deferral.

5. **SwiftUI vs UIKit swizzle.** UIViewController swizzle in `ScreenInstrumentation` breaks SwiftUI's `UIHostingController` lifecycle. Default install is SwiftUI-bridge-only (via `.trackScreen("Name")` ViewModifier). UIKit swizzle is opt-in via `enableUIKitSwizzle: true` — use only in pure-UIKit apps.

6. **Signal handlers must be async-signal-safe.** POSIX allows only a short list of calls (`write`, `signal`, `raise`, etc.). Our `signalHandler` in `ErrorsInstrumentation` uses a pre-opened file descriptor and `write(2)` — no Foundation, no allocations, no strings. See `docs/SDK_SAFETY.md`.

## Architecture

```
App
 ↓ OTelMobile.start(config:)
OTelMobile (facade)
 ├── LoggerProvider
 │    ├── MobileLogRecordProcessor (RAM + optional disk buffer) → OTLP
 │    └── BatchLogRecordProcessor → OTLP/HTTP exporter
 ├── TracerProvider → BatchSpanProcessor → OTLP/HTTP exporter
 ├── MeterProvider → PeriodicMetricReader → OTLP/HTTP exporter
 ├── Resource (service + telemetry.sdk + os + device attributes)
 ├── SessionManager (UUID + inactivity timeout + UserDefaults persist)
 └── Auto-installed instrumentation:
      ├── NetworkInstrumentation (URLProtocol auto-capture)
      ├── LifecycleInstrumentation (NotificationCenter)
      ├── ErrorsInstrumentation (NSException + signals + crash-marker recovery)
      ├── ScreenInstrumentation (SwiftUI ViewModifier bridge)
      ├── FreezeInstrumentation (main-thread watchdog)
      └── VitalsInstrumentation (app.start, ui.jank, memory)
```

Disk buffer (opt-in via `OTelMobile.start(config:diskBuffer:)`) spills RAM-evicted events to sqlite3 (actor-wrapped, WAL mode). Recovery drains pending events on next launch.

## Testing

- Swift Testing (`@Test` / `#expect`), not XCTest. CLT ships one, not the other.
- ~560 tests on the host lane / 575 on the simulator lane, across 77 suites, as of `main` (2026-07-09). A handful of tests are simulator-only.
- Test support files live in the main SDK module (e.g. `BufferedEventTestSupport.swift`) and are called from test files via `@testable import`. This is the workaround for the `_Testing_Foundation` CLT gap.

## Safety invariants (enforced in CI)

See `docs/SDK_SAFETY.md`. CI runs grep checks in `.github/workflows/ios-tests.yml`:
- Zero `fatalError` / `preconditionFailure` / `try!` in SDK source
- Zero `Thread.sleep` in SDK source (demo code OK)
- Any `as!` must have a documented fallback guard

## Branch

`main` is canonical for iOS — all v1.0.0 work merged there (PR #65, 2026-07-09). The old `iPhone` branch is dead/stale (~170 commits behind, last commit 2026-05-29); do not base work on it.

## Docs that matter here

- `docs/IOS_SDK_GUIDE.md` — integration guide
- `docs/IOS_CONFIGURATION.md` — every `MobileConfig` field
- `docs/HOW_TO_DEMO_IOS.md` — simulator demo runbook
- `docs/IOS_ANDROID_PARITY.md` — feature-by-feature matrix
- `docs/SDK_SAFETY.md` — defensive posture + tracked risks
- `docs/IOS_CRASH_REPORTING.md` — PLCrashReporter integration guide
- `examples/upstream-demo-app-ios/README.md` — Astronomy Shop — the canonical iOS demo (auto-demo loop, 14-span checkout, ShopTelemetry helper)
- `examples/demo-app-ios-starter/README.md` — legacy minimal starter app; not in CI, not in validate scripts. Kept for reference but not the active demo path.
