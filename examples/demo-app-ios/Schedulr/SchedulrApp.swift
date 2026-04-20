import SwiftUI
import OTelMobileSDK
import OTelMobileCore

/// Schedulr — iOS twin of the Android `examples/demo-app/`. Same booking
/// flow (calendar → provider → time slot → submit) hitting the same
/// `examples/demo-backend/` REST API on port 3001. Telemetry contract
/// matches Android's `BookFragment.bookAppointment` line-for-line so a
/// Dash0 dashboard filtering on `service.name=otel-ios-schedulr` and
/// one on `service.name=otel-android-schedulr` see identically-shaped
/// span and log records.
@main
struct SchedulrApp: App {
    @StateObject private var bootstrap = SchedulrBootstrap()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(bootstrap)
        }
    }
}

/// Boots the iOS SDK with the same MobileConfig knobs the Android app
/// uses. Reads `Schedulr/otel-config.json` (gitignored) for the Dash0
/// endpoint + auth token + dataset; falls back to a no-export local
/// config so the app still launches without credentials.
@MainActor
final class SchedulrBootstrap: ObservableObject {
    /// `nil` when the SDK couldn't initialise (missing config). The app
    /// still renders — just emits nothing. Surfaced for the debug UI to
    /// show "no telemetry" instead of pretending success.
    @Published private(set) var mobile: OTelMobile?

    init() {
        let config = SchedulrConfig.load()
        do {
            mobile = try OTelMobile.start(config: config.toMobileConfig())
        } catch {
            mobile = nil
            // Print to stderr so the simulator console catches it. Don't
            // crash — a missing token shouldn't break the demo UI for
            // dry-runs.
            FileHandle.standardError.write(
                "Schedulr: OTelMobile.start failed — \(error)\n".data(using: .utf8) ?? Data()
            )
        }
    }
}
