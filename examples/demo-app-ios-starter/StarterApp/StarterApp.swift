import SwiftUI
import OpenTelemetryApi
import OTelMobileSDK
import OTelMobileCore

@main
struct StarterApp: App {
    @StateObject private var model = DemoModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
        }
    }
}

@MainActor
final class DemoModel: ObservableObject {
    @Published var eventsEmitted: Int = 0
    @Published var status: String = "Starting..."
    @Published var datasetName: String = "(not loaded)"
    @Published var mobile: OTelMobile?

    init() {
        Task { @MainActor in
            let result = OTelMobileBootstrap.start()
            self.mobile = result.mobile
            self.datasetName = result.config?.dataset ?? "(not loaded)"

            guard self.mobile != nil else {
                self.status = result.status
                return
            }

            self.status = "SDK started — auto-emitting 5 events..."
            await autoEmit()
        }
    }

    @MainActor
    func autoEmit() async {
        guard let mobile = mobile else { return }
        let events: [(String, Severity)] = [
            ("app.start", .info),
            ("user.tap", .info),
            ("user.scroll", .info),
            ("api.call", .info),
            ("session.end", .info),
        ]
        for (body, sev) in events {
            mobile.emit(body: body, severity: sev)
            eventsEmitted += 1
            try? await Task.sleep(nanoseconds: 100_000_000) // 100ms spread
        }
        status = "Flushing \(eventsEmitted) events to Dash0..."
        // Give the buffer a moment to settle, then flush
        try? await Task.sleep(nanoseconds: 500_000_000)
        let result = mobile.forceFlush()
        status = "Flushed (\(result)) — check Dash0 for \(eventsEmitted) events"
    }

    @MainActor
    func emitManual() {
        guard let mobile = mobile else { return }
        mobile.emit(body: "user.button_tap", severity: .info)
        eventsEmitted += 1
    }

    @MainActor
    func flushManual() {
        _ = mobile?.forceFlush()
    }
}
