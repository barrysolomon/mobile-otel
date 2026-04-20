import SwiftUI

/// Date-picker landing screen. Mirrors Android's `CalendarFragment`.
/// Selection is stored in `@AppStorage` so the BookingView can read
/// it without a shared parent state — matches Android's
/// `SharedPreferences`-backed selection.
struct CalendarView: View {
    @AppStorage("schedulr.selectedDate") private var selectedDateMs: Double = Date().timeIntervalSince1970 * 1000

    private var selectedDate: Date {
        get { Date(timeIntervalSince1970: selectedDateMs / 1000) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                DatePicker(
                    "Appointment Date",
                    selection: Binding(
                        get: { selectedDate },
                        set: { selectedDateMs = $0.timeIntervalSince1970 * 1000 }
                    ),
                    in: Date()...,
                    displayedComponents: [.date]
                )
                .datePickerStyle(.graphical)
                .padding()
                .accessibilityIdentifier("calendar.datePicker")

                Text("Switch to the Book tab to schedule an appointment for the selected date.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                Spacer()
            }
            .navigationTitle("Calendar")
        }
    }
}
