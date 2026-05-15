# Schedulr — iOS Demo App

iOS twin of [`examples/demo-app/`](../demo-app/) (Android Schedulr).
Same booking flow, same backend (`examples/demo-backend/` on
`http://localhost:3001`), same telemetry contract. A Dash0 dashboard
built against the Android version works unchanged against the iOS one
once you point it at `service.name=otel-ios-schedulr`.

## What's in here

```
demo-app-ios/
├── project.yml                 # xcodegen project definition
├── Schedulr/
│   ├── SchedulrApp.swift       # @main entry, boots OTelMobile + DiskLogBuffer
│   ├── Models/Appointment.swift
│   ├── Network/SchedulrAPI.swift
│   ├── Telemetry/SchedulrConfig.swift
│   ├── Telemetry/SchedulrTelemetry.swift
│   ├── Views/RootView.swift
│   ├── Views/CalendarView.swift
│   ├── Views/BookingView.swift
│   ├── Views/AppointmentsView.swift
│   └── Views/BufferDebugView.swift  # dual-tier buffer + export status inspector
└── SchedulrUITests/
    └── SchedulrJourneyUITest.swift   # XCUI booking-flow loop
```

## First run

1. Install [`xcodegen`](https://github.com/yonaskolb/XcodeGen):
   `brew install xcodegen`
2. Copy the config template:
   ```bash
   cp Schedulr/otel-config.json.template Schedulr/otel-config.json
   # Edit otel-config.json — fill in endpoint + authToken + dataset
   # from Dash0 → Organization Settings → OpenTelemetry Setup.
   ```
3. Start the demo backend (separate terminal):
   ```bash
   cd ../demo-backend && npm install && npm run dev
   ```
4. Generate the Xcode project + open:
   ```bash
   cd examples/demo-app-ios
   xcodegen generate
   open Schedulr.xcodeproj
   ```
5. Run the `Schedulr` scheme on an iPhone simulator (iOS 16+).

## Adopting the SDK in your own app

Schedulr is intentionally minimal so the integration patterns below
translate directly to a real production app. Everything in this section
is what *you* would write in your own codebase — not demo plumbing.

### Minimal start

The SDK ships with sensible defaults. The smallest possible bring-up is
a single call from your `@main` `App`'s init:

```swift
import OTelMobileSDK

@main
struct MyApp: App {
    init() {
        let config = MobileConfig(
            serviceName: "my-ios-app",
            endpoint: "https://YOUR-DASH0-INGEST-HOST.dash0.com",
            authToken: "auth_XXXX",
            exportMode: .continuous,
            extraHeaders: ["Dash0-Dataset": "my-dataset"]
        )
        _ = try? OTelMobile.start(config: config)
    }
    var body: some Scene { WindowGroup { ContentView() } }
}
```

That single call gives you OTLP/HTTP export, session management, the
auto-installed instrumentation modules (network, lifecycle, errors,
screen, freeze, vitals), and a RAM ring buffer (5000 events). No further
wiring needed for the basics.

### Enabling the dual-tier (RAM + disk) buffer

The disk buffer is **opt-in** — pass a `DiskLogBuffer` to the start call
and the SDK spills RAM-evicted events to a sqlite file so they survive
process death, network outages, and app suspend/resume cycles.

```swift
import OTelMobileSDK

@MainActor
final class Telemetry: ObservableObject {
    @Published private(set) var mobile: OTelMobile?
    @Published private(set) var diskBuffer: DiskLogBuffer?

    init() {
        Task { await bootstrap() }
    }

    private func bootstrap() async {
        do {
            // Defaults: <App Support>/io.dash0.mobile/buffer.db, 50 MB cap,
            // 24 h TTL. Override any of these via the init parameters.
            let buffer = try await DiskLogBuffer()
            let m = try OTelMobile.start(
                config: myConfig,
                diskBuffer: buffer
            )
            self.diskBuffer = buffer
            self.mobile = m
        } catch {
            // Don't crash the host app — disk-buffer creation can fail
            // in tightly-sandboxed environments. SDK still runs RAM-only
            // in that case.
            NSLog("Telemetry init failed: \(error)")
        }
    }
}
```

Why async: `DiskLogBuffer.init` is `async throws` because it opens the
sqlite handle and prepares statements. Wrap your bootstrap in a `Task`
from your `App.init` so the SDK comes up shortly after launch (~10–50ms
typical). Existing call sites should null-check the SDK instance until
the bootstrap completes — see [`SchedulrApp.swift`](Schedulr/SchedulrApp.swift)
for the pattern Schedulr uses.

What you get from the disk buffer:
- Events evicted from the RAM ring (when it overflows or is mirrored for
  crash safety) persist to disk and drain on next launch.
- Network outages don't lose data — `RetryableExporter` backs off, the
  buffer fills, recovery drains when connectivity returns.
- App crashes don't lose data — see the next section.

### Crash recovery (zero extra code)

The auto-installed `ErrorsInstrumentation` wires both
`NSSetUncaughtExceptionHandler` and POSIX signal handlers (`SIGILL`,
`SIGSEGV`, `SIGBUS`, `SIGABRT`, `SIGFPE`, `SIGPIPE`) on `OTelMobile.start`.
You don't need to do anything else — when your app crashes, the SDK:

1. **At crash time:** writes a small async-signal-safe crash marker file
   to disk. (Does not call into the exporter — only POSIX-safe calls are
   allowed inside a signal handler.)
2. **On next launch:** sees the marker, emits an `app.crash` log carrying
   the **previous** `session.id` plus `exception.type` /
   `exception.message`, then calls `recoverFromDisk()` to drain any
   pre-crash events still in the sqlite buffer through the OTLP exporter.
   Original timestamps and `session.id`s are preserved, so the events
   slot back into the pre-crash timeline in Dash0 rather than appearing
   as "now."
3. **Policy hook:** the `app.crash` log also matches the SDK's default
   `crash-recovery` policy (`flushWindowMinutes: 5`), triggering a
   selective flush of the last 5 minutes of buffered events.

To verify in Dash0: filter on `service.name=<your service>` and
`event.name=app.crash`. Click into the log's `session.id` to see the
full pre-crash session reconstructed, including any disk-recovered
events.

> **Note on Xcode's debugger:** When running from Xcode with the debugger
> attached, Xcode intercepts the trap before the SDK's signal handler can
> run, so the crash marker never gets written. For an honest end-to-end
> crash test, launch the app from the simulator's home screen (or run on
> a real device) so the signal handler sees the trap first.

### Observing export status

The SDK publishes every export transition (success, retry, failure, auth
error) through a process-wide `ExportStatusManager`. Register a listener
to surface this in your own metrics, logs, or in-app debug UI:

```swift
import OTelMobileCore

final class ExportLogger: ExportStatusListener {
    func onExportStatus(_ status: ExportStatus) {
        switch status {
        case .success(let n):
            // wire to your own metric
            print("exported \(n) events")
        case .failed(let reason, let n, let attempt):
            print("export failed (#\(attempt), \(n) lost): \(reason)")
        case .authError(let reason, _):
            // alert user — token is bad, no amount of retrying helps
            print("auth error: \(reason)")
        case .retrying(let attempt, let max, let delayMs):
            print("retry \(attempt)/\(max) in \(delayMs)ms")
        }
    }
}

ExportStatusManager.shared.addListener(ExportLogger())
```

Listener callbacks fire on the exporter's thread — dispatch to the main
queue before touching SwiftUI state. See
[`BufferDebugView.swift`](Schedulr/Views/BufferDebugView.swift)'s
`ExportStatusBridge` for an `ObservableObject` wrapper that bridges into
SwiftUI cleanly.

### Manual flushes

For most apps the SDK's continuous export and policy-driven flushes are
enough. Two situations where you might want to flush explicitly from
your own code:

```swift
// On user-initiated logout / account switch — drain anything still
// buffered before tearing down the session.
let result = mobile.forceFlush()
// result is .success or .failure(reason:)

// Right after a high-value event you don't want to lose — e.g. a
// transaction confirmation — selective flush of the last N minutes.
Task {
    let result = await mobile.flushWindow(minutes: 5)
}
```

The SDK also auto-flushes on `UIApplication.willResignActiveNotification`
and `UIApplication.willTerminateNotification`, so you usually don't need
to wire those manually.

## Demo: Buffer debug tab

The fourth tab ("Buffer") is a Schedulr-specific debug screen — useful
for live demos, but **not something you'd ship in your own app**. It
reads off the same public APIs documented above (`DiskLogBuffer.rowCount()`,
`ExportStatusManager.addListener`, `OTelMobile.forceFlush`) and renders
them in a SwiftUI list.

The screen shows live row count and disk-bytes usage, the latest
`ExportStatus` transition, and three action buttons:

- **Force flush** — calls `mobile.forceFlush()`.
- **Flush last 5 min** — calls `mobile.flushWindow(minutes: 5)`.
- **Prune by TTL** — calls `diskBuffer.pruneByTTL()` (deletes rows older
  than the configured 24 h retention).

### Network-outage demo

1. Switch the simulator / device to airplane mode.
2. Click around the booking flow to generate events.
3. Watch the **Disk Buffer → Rows** count climb on the Buffer tab —
   RAM-evicted events spill to disk because export keeps failing.
4. Turn airplane mode off, hit **Force flush**. Rows drain,
   `ExportStatus` flips from `retrying` → `success`, the same events
   land in Dash0 under their original timestamps.

### Crash recovery demo

The Buffer tab also has a **"Crash app now (fatalError)"** button at the
bottom. It calls `fatalError` directly, which the Swift runtime maps to
`SIGILL`/`SIGTRAP` — caught by `ErrorsInstrumentation`'s POSIX signal
handler.

1. Click around (booking flow, calendar) to generate events.
2. Open the Buffer tab — note disk row count + last export status.
3. Hit **Crash app now**. The app dies.
4. **Re-launch from the simulator home screen** (not Xcode ▶ — the
   debugger swallows the trap and the crash marker doesn't get written).
5. Within ~5–10 s of launch, check Dash0: the pre-crash session's events
   plus a new `app.crash` log (carrying the *pre-crash* `session.id`)
   show up, all sharing the original trace IDs.

## Gotchas

- **Selected date persists across launches.** The Book screen reads
  `@AppStorage("schedulr.selectedDate")`, which is set on the Calendar
  tab and survives app restarts. If you ran the demo on a previous day
  the stored date will be stale, and the Time Slot picker will sit on
  "Pick a provider above to see slots for &lt;old-date&gt;." because the
  demo backend only seeds slots for today + the immediate future. Fix:
  open the Calendar tab and pick today's date — or erase the simulator
  (`xcrun simctl erase booted`) to reset `UserDefaults` back to the
  `Date()` default.

## Telemetry contract

Identical-twin parity with Android. Notable spans / logs:

| Signal | Span name / log body | Attributes |
|---|---|---|
| Booking flow | `booking.submit` (span) | `provider`, `appointment.type`, `time_slot`, `transaction.id`, `transaction.type=booking`, `transaction.outcome=PASS\|FAIL`, `booking.notes_provided`, `booking.date_days_ahead`, `demo_run_id`, `appointment.id` (on success) |
| Booking success | `appointment.booked` (log, INFO) | `event.name=appointment.booked`, `transaction.id`, `provider`, `appointment.type`, `time_slot`, `demo_run_id` |
| HTTP calls | `HTTP GET` / `HTTP POST` (span) | `http.request.method`, `url.full` (PII-scrubbed), `http.response.status_code`, `server.address` |
| Lifecycle | `app.startup`, `app.start.cold`, `app.start.warm` (spans) | `mobile.app.start.type`, `mobile.app.start.duration_ms`, `mobile.app.start.process_start_time` |

The `demo_run_id` is a UUID minted at app launch — same idea as
Android's `demo_run_id`. Lets you slice a single demo session out of
multi-launch traffic in Dash0.

## Validation

Run the cross-platform validator from the repo root:

```bash
./scripts/test/validate-ios-demo-app.sh
```

This boots the simulator, builds + installs Schedulr, runs the
`SchedulrJourneyUITest` against the live backend, then queries Dash0
to confirm the expected `booking.submit` spans and `appointment.booked`
logs landed.

## Skipped vs Android (intentional)

Android's `examples/demo-app/` has eight other activities (Settings,
Config, Login, MainActivity demo scenarios, About, Help, Logs,
RingBuffer). They're not part of the canonical booking flow — the
ANR / crash / OOM scenarios in MainActivity are already covered for
iOS by `scripts/test/validate-ios-us063-crash-flush.sh` and friends;
Settings / Config / Login are demo decoration not tied to the
telemetry contract. Skipping them keeps Schedulr focused.
