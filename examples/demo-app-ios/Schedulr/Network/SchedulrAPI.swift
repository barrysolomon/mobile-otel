import Foundation

/// Thin URLSession wrapper for the demo-backend REST API. Endpoints
/// match the Android demo-app's BookingApi:
/// - `GET /api/doctors` — provider list
/// - `GET /api/slots?doctor_id=&date=` — available time slots
/// - `GET /api/appointments` — list bookings
/// - `POST /api/appointments` — create a booking
///
/// Uses the SDK-installed `OTelURLProtocol` automatically — every
/// `URLSession.shared` call becomes a child span under the active page
/// span, exactly like Android's `OTelNetworkInterceptor` does for
/// OkHttp. No explicit instrumentation needed at the call site.
@MainActor
final class SchedulrAPI: ObservableObject {
    let baseURL: String

    /// When true, the next `fetchAppointments()` hits a non-existent path
    /// so the demo-backend returns 404. Drives the
    /// `OTelURLProtocol → http.error log → http_match policy → flushWindow`
    /// pipeline through a real URLSession round-trip — the only way to
    /// exercise the SDK's http.error emission path end-to-end.
    /// Set by `DebugToolbar`'s HTTP500 button. Auto-reset after use.
    /// Mirrors Android's `AppointmentRepository.forceNextFetchError`.
    @Published var forceNextFetchError: Bool = false

    init(baseURL: String) {
        self.baseURL = baseURL
    }

    func fetchProviders() async throws -> [Provider] {
        try await get("/api/doctors", as: [Provider].self)
    }

    func fetchSlots(providerId: String, date: String) async throws -> [TimeSlot] {
        let path = "/api/slots?doctor_id=\(providerId)&date=\(date)"
        return try await get(path, as: [TimeSlot].self)
    }

    func fetchAppointments() async throws -> [Appointment] {
        if forceNextFetchError {
            forceNextFetchError = false
            // Hit a real backend path that returns HTTP 503 — backend has a
            // `/api/force-500/*` debug route. Real URLSession round-trip →
            // OTelURLProtocol observes 5xx (above the default
            // errorStatusThreshold of 500) → emits http.error log with
            // event.name attribute, which the DSL http_match matcher uses.
            //
            // Why not /api/force-error/* (which would 404): iOS's default
            // errorStatusThreshold is 500, so a 404 wouldn't trigger the
            // log emission path. Android's threshold is 400 — different
            // defaults across platforms. Using 503 hits both.
            let path = "/api/force-500/run-\(Int(Date().timeIntervalSince1970))"
            return try await get(path, as: [Appointment].self)
        }
        return try await get("/api/appointments", as: [Appointment].self)
    }

    func bookAppointment(_ appointment: Appointment) async throws -> Appointment {
        try await post("/api/appointments", body: appointment, as: Appointment.self)
    }

    // MARK: - Internals

    private func get<T: Decodable>(_ path: String, as: T.Type) async throws -> T {
        let url = try makeURL(path)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        let (data, response) = try await URLSession.shared.data(for: request)
        try Self.validate(response)
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func post<Body: Encodable, T: Decodable>(
        _ path: String,
        body: Body,
        as: T.Type
    ) async throws -> T {
        let url = try makeURL(path)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await URLSession.shared.data(for: request)
        try Self.validate(response)
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func makeURL(_ path: String) throws -> URL {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw URLError(.badURL)
        }
        return url
    }

    private static func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        guard (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse, userInfo: [
                NSLocalizedDescriptionKey: "HTTP \(http.statusCode) from \(http.url?.absoluteString ?? "unknown")"
            ])
        }
    }
}
