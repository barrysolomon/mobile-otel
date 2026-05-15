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
///
/// Wires the dual-tier buffer (RAM + sqlite-backed `DiskLogBuffer`) so
/// events evicted from the RAM ring survive process death and drain on
/// the next launch — mirrors Android's default. The buffer instance is
/// retained on the bootstrap so the in-app `BufferDebugView` can read
/// `rowCount()` / `totalSizeBytes()` for the live stats display.
@MainActor
final class SchedulrBootstrap: ObservableObject {
    /// `nil` until the async bootstrap finishes, or if the SDK couldn't
    /// initialise (missing config / disk-buffer open failure). The app
    /// still renders — just emits nothing. Surfaced for the debug UI to
    /// show "no telemetry" instead of pretending success.
    @Published private(set) var mobile: OTelMobile?

    /// Disk-backed spill buffer. Held so the Buffer debug tab can query
    /// `rowCount()` / `totalSizeBytes()` for the live stats display. Nil
    /// when disk-buffer creation failed (e.g. sandbox write denied) — the
    /// SDK still runs RAM-only in that case.
    @Published private(set) var diskBuffer: DiskLogBuffer?

    init() {
        Task { [weak self] in
            await self?.bootstrap()
        }
    }

    private func bootstrap() async {
        let config = SchedulrConfig.load()
        do {
            // sqlite-backed spill at <App Support>/io.dash0.mobile/buffer.db.
            // 50 MB cap + 24 h TTL — same defaults as Android's DiskLogBuffer.
            let buffer = try await DiskLogBuffer()
            let m = try OTelMobile.start(
                config: config.toMobileConfig(),
                diskBuffer: buffer
            )
            self.diskBuffer = buffer
            self.mobile = m
        } catch {
            // Print to stderr so the simulator console catches it. Don't
            // crash — a missing token / disk-buffer failure shouldn't
            // break the demo UI.
            FileHandle.standardError.write(
                "Schedulr: OTelMobile.start failed — \(error)\n".data(using: .utf8) ?? Data()
            )
        }
    }
}
