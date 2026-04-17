# SDK Safety & Defensive Posture

## Principle

> **If the host app ever crashes, the SDK must be blameless.**

Our SDK runs inside our customers' apps. A hang, leak, or crash that can be traced back to our code is worse than never shipping the feature. This document enumerates the defensive measures in place and the remaining risks we're tracking.

Applies to both the Android (`otel-android-mobile/`) and iOS (`otel-ios-mobile/`) SDKs.

## Non-negotiables

1. **Never crash the host app.** Zero `fatalError`, zero `preconditionFailure`, zero force-try (`try!`), and any force-cast (`as!`) has a fallback path.
2. **Signal handlers must be async-signal-safe.** Per POSIX, handlers may only call functions on the async-signal-safe list (`write(2)`, `_exit(2)`, `signal(2)`, `raise(2)`, etc.). No Swift runtime calls, no Foundation, no allocations, no locks, no string interpolation.
3. **Bounded memory.** Every buffer has a maximum event count AND a maximum byte budget. Runaway telemetry must evict, not grow.
4. **Never block the main thread.** UI jank is attributed to us. Background work happens on detached `Task`s, actor-isolated structures, or `DispatchQueue.global`.
5. **Defensive config reads.** Instrumentation must not touch state that could be concurrently mutated without a lock; any cross-thread shared state reads behind `NSLock` or actor isolation.
6. **Fail closed on errors.** Exporter failures retry with backoff but never block emission.

## What's done (iOS)

| Concern | Defense |
|---|---|
| Signal handlers (`SIGABRT`/`SIGSEGV`/`SIGILL`/`SIGFPE`/`SIGBUS`/`SIGPIPE`/`SIGTRAP`) | Handler only calls `write(2)`, `signal()`, `raise()`. File descriptor opened during `install()` on the happy path; no allocations in the handler. 3-byte marker (`S<sig>\n`). |
| Uncaught `NSException` | `NSSetUncaughtExceptionHandler` writes a richer key=value marker. Next launch reads + emits. |
| Host-app force-crash | Zero `fatalError` / `try!` in SDK source. The one `as!` in `OTelURLProtocol` has an `as?` + fallback path. |
| RAM buffer growth | 3 caps: event count, total bytes (10 MB default), per-event size (256 KB default). Oversized events dropped + counted in `droppedOversizeCount`. |
| URLSession recursion | Request tagging via `URLProtocol.property` + inner session uses `.ephemeral` with empty `protocolClasses`. |
| URLProtocol failures | `.unknown` URLError + span end if tracer/config missing. |
| Sensitive headers | Hard floor — `authorization`, `cookie`, `set-cookie`, `proxy-authorization`, `x-api-key` NEVER recorded, regardless of config. |
| Main-thread blocking | Demo `Thread.sleep` replaced with `Task.detached + Task.sleep(nanoseconds:)`. SDK has zero `Thread.sleep`. |
| Auto-install during SwiftUI scene setup | Dispatched through `DispatchQueue.main.async` so scene setup completes first. |
| Lifecycle observers | Weak `self` closures; `uninstall()` removes all. |

## Remaining risks (tracked)

| Risk | Severity | Mitigation plan |
|---|---|---|
| `NSSetUncaughtExceptionHandler` overrides any previously-installed handler | MEDIUM | Chain through (current: assumes we're the only installer — breaks if app uses PLCrashReporter/Sentry/Firebase Crashlytics). |
| `URLSessionConfigurationSwizzle` can't be reliably uninstalled | LOW | Documented. `NetworkInstrumentation.enabled = false` short-circuits interception. |
| `ScreenInstrumentation` UIViewController swizzle races with SwiftUI `UIHostingController` lifecycle | HIGH | Disabled in auto-install. Fix path: SwiftUI `ViewModifier` for SwiftUI screens; UIKit-only swizzle opt-in. |
| No stack traces on signal crashes | MEDIUM | Acceptable for v1 — OS + debugger + PLCrashReporter still see native backtrace via re-raise. Integrate PLCrashReporter as optional dep for OTel-attributed stacks. |
| Auto-install adds ~1 frame to launch (~16ms) | LOW | Deferred already. Move non-UIKit setup to detached Task if profiling shows regression. |
| `DeviceStatsCollector` FileManager volume-info slow on real devices | LOW | Runs on detached Task, 5s cadence. Failure returns nil; no crash. |
| Signal-handler fd closed by OS during crash cleanup | LOW | Worst case: no marker. Accept. |

## What we don't do

- **We don't swallow errors silently.** OTel SDK's internal logger surfaces exporter failures.
- **We don't call `System.exit`/`abort()`/`exit()`** anywhere.
- **We don't use reflection to bypass Swift access modifiers.**
- **We don't do work on the main thread** except O(1) NotificationCenter observer registration.

## Reviewing for safety (pre-commit checklist)

```bash
# 1. No force-unwrap paths in new code
grep -rn "fatalError\|preconditionFailure\|try!\|as!" otel-ios-mobile/Sources/

# 2. No Thread.sleep anywhere in SDK
grep -rn "Thread.sleep" otel-ios-mobile/Sources/

# 3. Signal handler audited against POSIX async-signal-safe list
grep -A 30 "signalHandler(_ sig:" otel-ios-mobile/Sources/ErrorsInstrumentation/

# 4. New buffers have both count AND byte caps
grep -rn "maxTotalBytes\|maxEventBytes" otel-ios-mobile/Sources/

# 5. New UIKit swizzles have opt-in AND a safe disabled-state
grep -rn "method_exchange\|imp_implementationWithBlock" otel-ios-mobile/Sources/
```

## What to watch in production

1. **CPU from BatchProcessor retry loops** when collector endpoint is unreachable — upstream `opentelemetry-swift` handles backoff; verify paused/airplane-mode doesn't spin.
2. **Memory growth on burst** — our buffer has caps; upstream OTel `BatchLogRecordProcessor` has its own `maxQueueSize`.
3. **Crash marker accumulation** — we delete on successful emit; a crash loop could leave stragglers. `Caches/io.dash0.mobile.*` should show ≤1 file.
4. **Swizzle interactions** with other SDKs (Sentry/Firebase/Datadog) that also swizzle `URLSessionConfiguration.protocolClasses`, `UIViewController.viewDidAppear`, `NSSetUncaughtExceptionHandler`. Test compatibility.
