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
│   ├── SchedulrApp.swift       # @main entry, boots OTelMobile
│   ├── Models/Appointment.swift
│   ├── Network/SchedulrAPI.swift
│   ├── Telemetry/SchedulrConfig.swift
│   ├── Telemetry/SchedulrTelemetry.swift
│   ├── Views/RootView.swift
│   ├── Views/CalendarView.swift
│   ├── Views/BookingView.swift
│   └── Views/AppointmentsView.swift
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
