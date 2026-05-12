import SwiftUI

/// List of booked appointments. Mirrors Android's
/// `AppointmentsFragment` — pull-to-refresh fires `GET /api/appointments`,
/// auto-traced by NetworkInstrumentation as a child span under the
/// active page span.
struct AppointmentsView: View {
    @EnvironmentObject private var api: SchedulrAPI
    @State private var appointments: [Appointment] = []
    @State private var isLoading = false
    @State private var lastError: String?

    var body: some View {
        NavigationStack {
            Group {
                if appointments.isEmpty && !isLoading {
                    if #available(iOS 17.0, *) {
                        ContentUnavailableView(
                            "No appointments yet",
                            systemImage: "calendar.badge.exclamationmark",
                            description: Text("Use the Book tab to create your first appointment.")
                        )
                    } else {
                        VStack(spacing: 12) {
                            Image(systemName: "calendar.badge.exclamationmark")
                                .font(.largeTitle)
                                .foregroundColor(.secondary)
                            Text("No appointments yet")
                                .font(.headline)
                            Text("Use the Book tab to create your first appointment.")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                } else {
                    List {
                        ForEach(appointments) { appt in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(appt.title).font(.headline)
                                Text("\(appt.timeSlot) · \(appt.type.displayName)")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                if !appt.notes.isEmpty {
                                    Text(appt.notes)
                                        .font(.footnote)
                                        .foregroundColor(.secondary)
                                }
                            }
                            .padding(.vertical, 4)
                            .accessibilityIdentifier("appointment.row")
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .overlay {
                if isLoading && appointments.isEmpty {
                    ProgressView("Loading appointments...")
                }
            }
            .navigationTitle("Appointments")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await reload() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityIdentifier("appointments.reload")
                }
            }
            .task { await reload() }
            .refreshable { await reload() }
        }
    }

    private func reload() async {
        isLoading = true
        defer { isLoading = false }
        do {
            appointments = try await api.fetchAppointments()
            lastError = nil
        } catch {
            lastError = "Couldn't load appointments: \(error.localizedDescription)"
        }
    }
}
