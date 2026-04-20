import SwiftUI
import OpenTelemetryApi

/// Booking form. Mirrors Android's `BookFragment` end-to-end:
/// - Loads providers from `GET /api/doctors` on appear (auto-traced
///   via NetworkInstrumentation).
/// - When provider + date are picked, loads time slots via
///   `GET /api/slots?doctor_id=&date=`.
/// - On submit, opens an explicit `booking.submit` span (Android-
///   parity name) that wraps the `POST /api/appointments` HTTP call.
///   Span carries `provider`, `appointment.type`, `time_slot`,
///   `transaction.id`, `demo_run_id` attributes — same keys Android
///   sets — so a Dash0 dashboard joins the two platforms unchanged.
struct BookingView: View {
    @EnvironmentObject private var bootstrap: SchedulrBootstrap
    @EnvironmentObject private var api: SchedulrAPI
    @AppStorage("schedulr.selectedDate") private var selectedDateMs: Double = Date().timeIntervalSince1970 * 1000

    @State private var providers: [Provider] = []
    @State private var slots: [TimeSlot] = []
    @State private var selectedProvider: Provider?
    @State private var selectedType: AppointmentType = .checkup
    @State private var selectedSlot: TimeSlot?
    @State private var notes: String = ""

    @State private var isLoadingProviders = false
    @State private var isLoadingSlots = false
    @State private var isSubmitting = false
    @State private var lastError: String?
    @State private var lastBookedTitle: String?

    private var selectedDate: Date {
        Date(timeIntervalSince1970: selectedDateMs / 1000)
    }

    private var dateString: String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: selectedDate)
    }

    private var canSubmit: Bool {
        selectedProvider != nil && selectedSlot != nil && !isSubmitting
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Provider") {
                    if isLoadingProviders {
                        ProgressView("Loading providers...")
                    } else {
                        Picker("Provider", selection: $selectedProvider) {
                            Text("Select…").tag(Provider?.none)
                            ForEach(providers) { provider in
                                Text(provider.name).tag(Provider?.some(provider))
                            }
                        }
                        .accessibilityIdentifier("booking.provider")
                    }
                }
                Section("Appointment Type") {
                    Picker("Type", selection: $selectedType) {
                        ForEach(AppointmentType.allCases) { type in
                            Text(type.displayName).tag(type)
                        }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("booking.type")
                }
                Section("Time Slot") {
                    if isLoadingSlots {
                        ProgressView("Loading slots...")
                    } else if slots.isEmpty {
                        Text("Pick a provider above to see slots for \(dateString).")
                            .foregroundColor(.secondary)
                    } else {
                        Picker("Slot", selection: $selectedSlot) {
                            Text("Select…").tag(TimeSlot?.none)
                            ForEach(slots) { slot in
                                Text(slot.label).tag(TimeSlot?.some(slot))
                            }
                        }
                        .accessibilityIdentifier("booking.slot")
                    }
                }
                Section("Notes (optional)") {
                    TextField("e.g. follow-up on lab results", text: $notes)
                        .accessibilityIdentifier("booking.notes")
                }
                Section {
                    Button {
                        Task { await submit() }
                    } label: {
                        if isSubmitting {
                            ProgressView()
                        } else {
                            Text("Book Appointment")
                                .frame(maxWidth: .infinity)
                                .accessibilityIdentifier("booking.submit")
                        }
                    }
                    .disabled(!canSubmit)
                    if let lastError = lastError {
                        Text(lastError)
                            .font(.footnote)
                            .foregroundColor(.red)
                    }
                    if let title = lastBookedTitle {
                        Text("Booked: \(title)")
                            .font(.footnote)
                            .foregroundColor(.green)
                    }
                }
            }
            .navigationTitle("Book")
            .task { await loadProviders() }
            .onChange(of: selectedProvider?.id) { _ in Task { await loadSlots() } }
            .onChange(of: dateString) { _ in Task { await loadSlots() } }
        }
    }

    // MARK: - Actions

    private func loadProviders() async {
        guard providers.isEmpty else { return }
        isLoadingProviders = true
        defer { isLoadingProviders = false }
        do {
            providers = try await api.fetchProviders()
        } catch {
            lastError = "Couldn't load providers: \(error.localizedDescription)"
        }
    }

    private func loadSlots() async {
        guard let providerId = selectedProvider?.id else { slots = []; return }
        isLoadingSlots = true
        defer { isLoadingSlots = false }
        do {
            slots = try await api.fetchSlots(providerId: providerId, date: dateString)
            selectedSlot = nil
        } catch {
            lastError = "Couldn't load slots: \(error.localizedDescription)"
            slots = []
        }
    }

    private func submit() async {
        guard let provider = selectedProvider, let slot = selectedSlot else { return }
        guard let mobile = bootstrap.mobile else {
            lastError = "SDK not configured — copy otel-config.json.template to otel-config.json."
            return
        }
        isSubmitting = true
        defer { isSubmitting = false }
        lastError = nil
        let transactionId = UUID().uuidString
        // booking.submit span — Android-parity name. Wraps the HTTP
        // POST so the network span nests under it in the trace
        // waterfall.
        let tracer = mobile.tracer
        let span = tracer.spanBuilder(spanName: "booking.submit")
            .setSpanKind(spanKind: .internal)
            .startSpan()
        for (key, value) in SchedulrTelemetry.baseAttributes() {
            span.setAttribute(key: key, value: value)
        }
        span.setAttribute(key: "provider", value: provider.name)
        span.setAttribute(key: "appointment.type", value: selectedType.rawValue)
        span.setAttribute(key: "time_slot", value: slot.label)
        span.setAttribute(key: "transaction.id", value: transactionId)
        span.setAttribute(key: "transaction.type", value: "booking")
        span.setAttribute(key: "booking.notes_provided", value: !notes.isEmpty)
        span.setAttribute(
            key: "booking.date_days_ahead",
            value: max(0, Calendar.current.dateComponents([.day], from: Date(), to: selectedDate).day ?? 0)
        )

        let appointment = Appointment(
            title: "\(selectedType.displayName) with \(provider.name)",
            provider: provider.name,
            dateMs: Int64(selectedDateMs),
            timeSlot: slot.label,
            type: selectedType,
            notes: notes
        )
        do {
            let saved = try await api.bookAppointment(appointment)
            span.setAttribute(key: "transaction.outcome", value: "PASS")
            span.setAttribute(key: "appointment.id", value: saved.id)
            span.status = .ok
            span.end()

            // appointment.booked log — Android-parity body.
            mobile.logger?.logRecordBuilder()
                .setBody(AttributeValue.string("appointment.booked"))
                .setSeverity(.info)
                .setAttributes([
                    "event.name": .string("appointment.booked"),
                    "transaction.id": .string(transactionId),
                    "provider": .string(provider.name),
                    "appointment.type": .string(selectedType.rawValue),
                    "time_slot": .string(slot.label),
                    "demo_run_id": .string(SchedulrTelemetry.demoRunId),
                ])
                .emit()
            lastBookedTitle = saved.title
            // Reset the form for the next booking.
            notes = ""
            selectedSlot = nil
        } catch {
            span.setAttribute(key: "transaction.outcome", value: "FAIL")
            span.setAttribute(key: "error.type", value: String(describing: type(of: error)))
            span.setAttribute(key: "error.message", value: error.localizedDescription)
            span.status = .error(description: error.localizedDescription)
            span.end()
            lastError = "Booking failed: \(error.localizedDescription)"
        }
    }
}
