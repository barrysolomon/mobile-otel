import Foundation

/// Mirrors Android's `Appointment` data class field-for-field. Same
/// JSON shape on the wire so both apps consume the same
/// `examples/demo-backend/` REST API.
struct Appointment: Identifiable, Codable, Equatable {
    let id: String
    let title: String
    let provider: String
    /// Wall-clock millis since epoch. Android stores this as `Long`;
    /// iOS uses `Int64` to keep the JSON shape identical.
    let dateMs: Int64
    let timeSlot: String
    let type: AppointmentType
    let status: AppointmentStatus
    let notes: String

    init(
        id: String = UUID().uuidString,
        title: String,
        provider: String,
        dateMs: Int64,
        timeSlot: String,
        type: AppointmentType,
        status: AppointmentStatus = .confirmed,
        notes: String = ""
    ) {
        self.id = id
        self.title = title
        self.provider = provider
        self.dateMs = dateMs
        self.timeSlot = timeSlot
        self.type = type
        self.status = status
        self.notes = notes
    }
}

enum AppointmentType: String, Codable, CaseIterable, Identifiable {
    case checkup = "CHECKUP"
    case followup = "FOLLOWUP"
    case urgent = "URGENT"
    case consultation = "CONSULTATION"

    var id: String { rawValue }
    var displayName: String {
        switch self {
        case .checkup: return "Checkup"
        case .followup: return "Follow-up"
        case .urgent: return "Urgent"
        case .consultation: return "Consultation"
        }
    }
}

enum AppointmentStatus: String, Codable {
    case confirmed = "CONFIRMED"
    case pending = "PENDING"
    case cancelled = "CANCELLED"
}

/// Provider listing returned by `GET /api/doctors`. Keeping a thin
/// struct rather than a full Doctor mirror — the booking flow only
/// needs the displayable name + id.
struct Provider: Identifiable, Codable, Equatable, Hashable {
    let id: String
    let name: String
}

/// Time-slot listing returned by `GET /api/slots?doctor_id=&date=`.
/// Backend sends `{id, doctor_id, date, time, available}`; we only need
/// `id` + `time` (rendered as the picker label).
struct TimeSlot: Identifiable, Codable, Equatable, Hashable {
    let id: String
    let label: String

    private enum CodingKeys: String, CodingKey {
        case id
        case label = "time"
    }
}
