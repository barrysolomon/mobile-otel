import Foundation
import OpenTelemetryApi

/// Helpers that emit the Schedulr telemetry contract — span names,
/// log bodies, and attribute keys — identical to the Android demo-app.
/// Identical-twin parity: a Dash0 dashboard built against the Android
/// app's `booking.submit` span keyed on `provider` / `appointment.type`
/// / `time_slot` works unchanged against the iOS app.
///
/// Span names mirrored from `BookFragment.bookAppointment()`:
/// - `booking.submit` — root span around the entire booking flow
/// - `HTTP POST` / `HTTP GET` — auto-emitted by NetworkInstrumentation
///
/// Log bodies mirrored:
/// - `appointment.booked` — emitted on successful POST /api/appointments
/// - `screen.enter` / `screen.exit` — fragment lifecycle equivalents
/// - `user.interaction` — bottom-tab nav, button taps
enum SchedulrTelemetry {
    /// Stable per-app-launch identifier. Same role as Android's
    /// `demo_run_id` — lets a customer slice a single demo session out
    /// of multi-launch traffic in Dash0.
    static let demoRunId: String = UUID().uuidString

    /// Build the standard attribute set every span/log carries. Caller
    /// adds the event-specific attrs on top.
    static func baseAttributes() -> [String: AttributeValue] {
        [
            "demo_run_id": .string(demoRunId),
        ]
    }
}
