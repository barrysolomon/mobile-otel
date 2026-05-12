import Foundation
import OpenTelemetryApi
import OTelMobileSDK
import UIKit

enum SchedulrTelemetry {
    static let demoRunId: String = UUID().uuidString

    static func baseAttributes() -> [String: AttributeValue] {
        [
            "demo_run_id": .string(demoRunId),
        ]
    }

    static func deviceContextAttributes() -> [String: AttributeValue] {
        var attrs: [String: AttributeValue] = [:]
        let device = UIDevice.current
        attrs["device.model"] = .string(device.model)
        attrs["os.version"] = .string(device.systemVersion)
        attrs["device.battery_level"] = .double(Double(device.batteryLevel))
        switch device.batteryState {
        case .charging: attrs["device.battery_state"] = .string("charging")
        case .full:     attrs["device.battery_state"] = .string("full")
        case .unplugged: attrs["device.battery_state"] = .string("unplugged")
        default:        attrs["device.battery_state"] = .string("unknown")
        }
        return attrs
    }

    static func startJourney(
        name: String,
        mobile: OTelMobile
    ) -> Span? {
        guard let tracer = mobile.tracer else { return nil }
        let span = tracer.spanBuilder(spanName: "journey.\(name)")
            .setSpanKind(spanKind: .internal)
            .startSpan()
        span.setAttribute(key: "journey.kind", value: name)
        for (k, v) in baseAttributes() {
            span.setAttribute(key: k, value: v)
        }
        return span
    }
}
