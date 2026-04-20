import SwiftUI

/// Three-tab root mirroring the Android `SchedulingActivity` bottom-
/// nav. Skipped the Directions and Profile tabs from Android — they're
/// purely UI-decorative there (no backend calls, no telemetry beyond
/// `screen.enter`); not needed for the canonical booking-flow demo.
struct RootView: View {
    @EnvironmentObject private var bootstrap: SchedulrBootstrap
    @StateObject private var api: SchedulrAPI

    init() {
        let backend = SchedulrConfig.load().backendUrl
        _api = StateObject(wrappedValue: SchedulrAPI(baseURL: backend))
    }

    var body: some View {
        TabView {
            CalendarView()
                .tabItem { Label("Calendar", systemImage: "calendar") }
                .accessibilityIdentifier("tab.calendar")
            BookingView()
                .environmentObject(api)
                .tabItem { Label("Book", systemImage: "plus.circle") }
                .accessibilityIdentifier("tab.book")
            AppointmentsView()
                .environmentObject(api)
                .tabItem { Label("Appointments", systemImage: "list.bullet") }
                .accessibilityIdentifier("tab.appointments")
        }
    }
}
