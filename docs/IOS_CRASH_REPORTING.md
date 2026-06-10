# iOS Crash Reporting Integration

The Dash0 iOS SDK ships a minimal crash-marker mechanism in
[`ErrorsInstrumentation`](../otel-ios-mobile/Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift):

- `NSSetUncaughtExceptionHandler` captures uncaught Objective-C / bridged Swift exceptions (**with** `callStackSymbols` — Cocoa makes these available before tear-down). These frames are written to the marker (up to 50, PII-scrubbed) and surface as `exception.stacktrace`.
- POSIX signal handlers (`SIGABRT`/`SIGSEGV`/`SIGILL`/`SIGFPE`/`SIGBUS`/`SIGPIPE`/`SIGTRAP`) write a fixed 3-byte async-signal-safe marker (`S<sig>`) and re-raise. **No stack trace on this path** — we cannot safely collect one from a signal handler without a dedicated crash reporter.
- Next launch reads the marker and emits an `app.crash` log with the signal number / exception type (`crash.from_marker=true`).

That's enough to **count** crashes and associate them with sessions, and exception-path crashes carry unsymbolicated frames — but it's **not** enough for debugging signal-path native crashes, and **the SDK does not symbolicate anything itself** (no dSYM upload / address→symbol resolution is implemented). For symbolicated native stacks, integrate a dedicated crash reporter alongside our SDK.

For that, integrate **PLCrashReporter** (recommended) alongside our SDK.

## Why PLCrashReporter?

- Apple Store-approved (used by Firebase Crashlytics, HockeyApp, AppCenter, Microsoft Intune, etc.)
- Handles crash-time stack unwinding correctly — uses `mach` exception ports so it captures stacks even when the signal was raised by another thread
- Symbolication tooling
- Actively maintained by Microsoft: https://github.com/microsoft/plcrashreporter

## Integration pattern

The important property: **our SDK is NOT a crash reporter — it's an observability SDK that can emit a crash report**. So we let PLCrashReporter do the hard work of capture, and we re-emit its report as an OTel `app.crash` log record when the next session starts.

### 1. Add PLCrashReporter to your app

SPM:

```swift
// Package.swift (your app)
.package(url: "https://github.com/microsoft/plcrashreporter.git", from: "1.11.2"),
```

CocoaPods:

```ruby
pod 'PLCrashReporter', '~> 1.11'
```

### 2. Install PLCrashReporter BEFORE Dash0's SDK

Order matters. Because [our SDK chains through](../otel-ios-mobile/Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift) the previously-installed `NSUncaughtExceptionHandler`, you install PLCrashReporter first, then `OTelMobile.start(config:)`:

```swift
import CrashReporter
import OTelMobileSDK

// In your App or AppDelegate:
let config = PLCrashReporterConfig.defaultConfiguration()
let crashReporter = PLCrashReporter(configuration: config)
crashReporter?.enable()  // BEFORE OTelMobile.start

let sdkConfig = MobileConfig(...)
let mobile = try OTelMobile.start(config: sdkConfig)
// Our SDK preserves PLCrashReporter's handler via chain-through.
```

### 3. Emit PLCrashReporter's saved report as an OTel log on next launch

At app startup, BEFORE you do anything else, check for a pending PLCrashReporter report and ship it through our SDK:

```swift
func emitPendingPLReportIfAny(mobile: OTelMobile, reporter: PLCrashReporter) {
    guard reporter.hasPendingCrashReport() else { return }
    defer { reporter.purgePendingCrashReport() }

    guard let data = reporter.loadPendingCrashReportData() else { return }
    guard let report = try? PLCrashReport(data: data) else { return }

    // Build a symbolicated text stack (your app's dSYM must be on hand for
    // full symbolication, but even unsymbolicated frames are useful).
    let formatter = PLCrashReportTextFormatter(
        formatter: .iOSReportFormat
    )
    let text = formatter?.stringValue(for: report) ?? ""

    mobile.emit(
        body: "app.crash",
        severity: .fatal,
        attributes: [
            "event.name": .string("app.crash"),
            "crash.source": .string("plcrashreporter"),
            "crash.signal": .string(report.signalInfo?.name ?? "unknown"),
            "crash.code": .string(report.signalInfo?.code ?? "unknown"),
            "crash.address": .string(String(format: "0x%llx", report.signalInfo?.address ?? 0)),
            "exception.stacktrace": .string(text),
            "app.version": .string(report.applicationInfo.applicationMarketingVersion ?? ""),
            "app.build": .string(report.applicationInfo.applicationVersion ?? ""),
        ]
    )
}
```

Call this immediately after `OTelMobile.start`:

```swift
let mobile = try OTelMobile.start(config: sdkConfig)
emitPendingPLReportIfAny(mobile: mobile, reporter: crashReporter!)
```

## Why we didn't bundle PLCrashReporter

1. **Avoid conflicts** — apps already using Firebase Crashlytics / Sentry / Bugsnag don't want a second crash reporter. We'd be picking a fight that every team has to individually resolve.
2. **License clarity** — PLCrashReporter is Apache-2.0 (compatible), but customers often want explicit control over which transitive licenses they pull.
3. **Size** — PLCrashReporter adds ~500 KB to the app bundle. Some customers don't need signal-path stack traces and prefer the smaller footprint.
4. **Chain-through** — our SDK's [`NSSetUncaughtExceptionHandler` chain-through](../otel-ios-mobile/Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift) means you can install ANY crash reporter first and our signal+exception capture will compose cleanly.

## Alternative: Sentry, Bugsnag, Firebase Crashlytics

The same pattern applies — install their SDK first, then ours. Each vendor has its own "pending crash report" API; the translation to OTel is the same shape:

- **Sentry**: hook `Sentry.beforeSendSpan`, or read from their offline queue on startup
- **Firebase Crashlytics**: `Crashlytics.crashlytics().checkForUnsentReports`
- **Bugsnag**: `Bugsnag.lastRunInfo` on startup

Emit via `mobile.emit(body: "app.crash", severity: .fatal, attributes: ...)` with the same attribute shape as the PLCrashReporter example above.

## What the SDK emits on its own

Even without any external crash reporter, our SDK emits useful crash telemetry:

| Event | When | Attributes |
|---|---|---|
| `app.crash` (from NSException marker) | Next launch after uncaught Obj-C exception | `crash.kind=exception`, `crash.name`, `crash.reason`, `crash.timestamp`, `exception.stacktrace` (from `callStackSymbols`) |
| `app.crash` (from signal marker) | Next launch after fatal signal | `crash.kind=signal`, `crash.signal` (e.g. `11` for SIGSEGV), `crash.name` (human-readable) — no stack trace |
| `app.error` (manual) | `ErrorsInstrumentation.shared.recordError(_:)` from Swift code | `error.type`, `error.message`, caller attributes |

Combine these with PLCrashReporter (or similar) for the complete picture.

## Testing crash recovery

In the [Starter demo](../examples/demo-app-ios-starter/README.md) the **Crash Now** button calls `fatalError(...)`, which traps the process via a fatal signal (`SIGTRAP`/`SIGILL` on arm64, `SIGABRT` on some toolchains — all are in the handled set). After you manually relaunch the app, you should see `app.crash` with `crash.kind=signal` and the corresponding `crash.signal` arrive in Dash0 under the `os.name=iOS` slice.

With PLCrashReporter installed, that same crash also ships with a full stack trace.
