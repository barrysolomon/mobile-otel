/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

extension ErrorCoalescer {
    /// Creates a test `ReadableLogRecord` with the given parameters. Lives in
    /// the SDK module so test files don't need to import Foundation directly
    /// (see `BufferedEventTestSupport.swift` for the same pattern).
    static func makeTestRecord(
        body: String = "error",
        severity: Severity = .error,
        exceptionType: String? = nil,
        exceptionMessage: String? = nil,
        eventName: String? = nil,
        attributes extra: [String: String] = [:]
    ) -> ReadableLogRecord {
        var attrs: [String: AttributeValue] = [:]
        if let t = exceptionType { attrs["exception.type"] = .string(t) }
        if let m = exceptionMessage { attrs["exception.message"] = .string(m) }
        if let e = eventName { attrs["event.name"] = .string(e) }
        for (k, v) in extra { attrs[k] = .string(v) }
        return ReadableLogRecord(
            resource: Resource(),
            instrumentationScopeInfo: InstrumentationScopeInfo(name: "test"),
            timestamp: Date(),
            severity: severity,
            body: .string(body),
            attributes: attrs
        )
    }
}
