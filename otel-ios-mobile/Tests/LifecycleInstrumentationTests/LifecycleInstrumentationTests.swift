import Foundation
import Testing
@testable import LifecycleInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk

#if canImport(UIKit) && (os(iOS) || os(tvOS))
import UIKit

/// Captures emitted log records so tests can assert on shape.
/// Conforms to the OTel Logger protocol; relies on protocol extension
/// defaults for everything except setBody / setAttributes / emit, which
/// the tests need to observe.
final class RecordingLogger: Logger, @unchecked Sendable {
    private let lock = NSLock()
    private var _records: [(body: String, attributes: [String: AttributeValue])] = []
    var records: [(body: String, attributes: [String: AttributeValue])] {
        lock.lock(); defer { lock.unlock() }
        return _records
    }

    func eventBuilder(name: String) -> EventBuilder { NoopEventBuilder() }

    func logRecordBuilder() -> LogRecordBuilder {
        return RecordingLogRecordBuilder(sink: self)
    }

    fileprivate func record(body: String, attributes: [String: AttributeValue]) {
        lock.lock(); defer { lock.unlock() }
        _records.append((body, attributes))
    }
}

private final class NoopEventBuilder: EventBuilder {}

private final class RecordingLogRecordBuilder: LogRecordBuilder {
    private weak var sink: RecordingLogger?
    private var body: String = ""
    private var attributes: [String: AttributeValue] = [:]

    init(sink: RecordingLogger) { self.sink = sink }

    func setBody(_ body: AttributeValue) -> Self {
        if case .string(let s) = body { self.body = s }
        return self
    }
    func setAttributes(_ attributes: [String: AttributeValue]) -> Self {
        self.attributes = attributes
        return self
    }
    func emit() {
        sink?.record(body: body, attributes: attributes)
    }
}

@Suite("LifecycleInstrumentation late-init")
struct LifecycleInstrumentationLateInitTests {

    @Test("install when app already active emits late foreground")
    func installWhenAppAlreadyActiveEmitsLateForeground() async throws {
        let logger = RecordingLogger()
        let inst = LifecycleInstrumentation(applicationStateProvider: { .active })
        inst.install(tracer: nil, logger: logger)

        // Allow the DispatchQueue.main.async synthesis path to run.
        try await Task.sleep(nanoseconds: 50_000_000) // 50ms

        let foregrounds = logger.records.filter { $0.body == "app.foreground" }
        #expect(foregrounds.count == 1, "Expected 1 app.foreground, got \(foregrounds.count)")
        #expect(foregrounds.first?.attributes["app.foreground.type"] == .string("instrumentation_late"))

        inst.uninstall()
    }

    @Test("late install does not double-emit when natural didBecomeActive arrives")
    func lateInstallDoesNotDoubleEmitOnNaturalDidBecomeActive() async throws {
        let logger = RecordingLogger()
        let inst = LifecycleInstrumentation(applicationStateProvider: { .active })
        inst.install(tracer: nil, logger: logger)
        try await Task.sleep(nanoseconds: 50_000_000)

        // Synthesis already emitted. Now post a natural didBecomeActive.
        await MainActor.run {
            NotificationCenter.default.post(
                name: UIApplication.didBecomeActiveNotification, object: nil
            )
        }
        try await Task.sleep(nanoseconds: 50_000_000)

        let foregrounds = logger.records.filter { $0.body == "app.foreground" }
        #expect(foregrounds.count == 1, "foregroundActive dedup should suppress the natural didBecomeActive after late-init; got \(foregrounds.count)")

        // Now do a real bg → fg cycle and confirm one more foreground (natural).
        await MainActor.run {
            NotificationCenter.default.post(
                name: UIApplication.didEnterBackgroundNotification, object: nil
            )
            NotificationCenter.default.post(
                name: UIApplication.didBecomeActiveNotification, object: nil
            )
        }
        try await Task.sleep(nanoseconds: 50_000_000)

        let foregrounds2 = logger.records.filter { $0.body == "app.foreground" }
        #expect(foregrounds2.count == 2, "After bg→fg cycle: expected 2 total foregrounds, got \(foregrounds2.count)")
        #expect(foregrounds2.last?.attributes["app.foreground.type"] == .string("natural"))

        inst.uninstall()
    }

    @Test("install when app inactive does not emit foreground", arguments: [UIApplication.State.inactive, UIApplication.State.background])
    func installWhenAppInactiveDoesNotEmitForeground(state: UIApplication.State) async throws {
        let logger = RecordingLogger()
        let inst = LifecycleInstrumentation(applicationStateProvider: { state })
        inst.install(tracer: nil, logger: logger)
        try await Task.sleep(nanoseconds: 50_000_000)

        let foregrounds = logger.records.filter { $0.body == "app.foreground" }
        #expect(foregrounds.count == 0, "Expected no app.foreground when applicationState=\(state); got \(foregrounds.count)")

        // app.launch should still fire regardless.
        let launches = logger.records.filter { $0.body == "app.launch" }
        #expect(launches.count == 1, "app.launch should fire unconditionally")

        inst.uninstall()
    }
}
#endif
