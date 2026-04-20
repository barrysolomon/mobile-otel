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
        try await get("/api/appointments", as: [Appointment].self)
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
