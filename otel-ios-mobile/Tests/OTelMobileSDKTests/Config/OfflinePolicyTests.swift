import Foundation
import Testing
@testable import OTelMobileSDK
import OTelMobileCore

/// Serialized because every test mutates the static `MobileLogRecordProcessor._offlineOverride`
/// test seam. Swift Testing runs `@Test` cases in parallel by default — without
/// `.serialized`, two tests can clobber each other's override, and the surviving
/// state leaks into whichever test reads it next. See `feedback_ios_nwpathmonitor_test_seam`
/// for why the static-override pattern exists in the first place.
@Suite("OfflinePolicy", .serialized)
struct OfflinePolicyTests {

    @Test("bufferAll has no min severity and does not drop all")
    func bufferAllProperties() {
        #expect(OfflinePolicy.bufferAll.minBufferSeverity == nil)
        #expect(OfflinePolicy.bufferAll.dropsAll == false)
    }

    @Test("errorOnly min severity is .error")
    func errorOnlyMinSeverity() {
        #expect(OfflinePolicy.errorOnly.minBufferSeverity == .error)
        #expect(OfflinePolicy.errorOnly.dropsAll == false)
    }

    @Test("warnAndAbove min severity is .warn")
    func warnAndAboveMinSeverity() {
        #expect(OfflinePolicy.warnAndAbove.minBufferSeverity == .warn)
        #expect(OfflinePolicy.warnAndAbove.dropsAll == false)
    }

    @Test("dropAll drops everything")
    func dropAllProperties() {
        #expect(OfflinePolicy.dropAll.dropsAll == true)
    }

    @Test("MobileConfig integrates offline policy")
    func mobileConfigIntegration() {
        let config = MobileConfig(
            serviceName: "test",
            endpoint: "http://localhost",
            offlinePolicy: .errorOnly
        )
        #expect(config.offlinePolicy == .errorOnly)
    }

    @Test("MobileConfig defaults to bufferAll")
    func mobileConfigDefault() {
        let config = MobileConfig(serviceName: "test", endpoint: "http://localhost")
        #expect(config.offlinePolicy == .bufferAll)
    }

    @Test("ERROR_ONLY drops INFO when offline")
    func errorOnlyDropsInfoWhenOffline() async {
        MobileLogRecordProcessor._offlineOverride = true
        defer { MobileLogRecordProcessor._offlineOverride = nil }

        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = PolicyTestExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: PolicyStubSession(),
            offlinePolicy: .errorOnly
        )
        await processor.emitForTesting(body: "info-event", severity: .info)
        let events = buffer.peek()
        #expect(events.isEmpty, "INFO should be dropped when offline + errorOnly")
    }

    @Test("ERROR_ONLY buffers ERROR when offline")
    func errorOnlyBuffersErrorWhenOffline() async {
        MobileLogRecordProcessor._offlineOverride = true
        defer { MobileLogRecordProcessor._offlineOverride = nil }

        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = PolicyTestExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: PolicyStubSession(),
            offlinePolicy: .errorOnly
        )
        await processor.emitForTesting(body: "error-event", severity: .error)
        let events = buffer.peek()
        #expect(events.count == 1, "ERROR should be buffered when offline + errorOnly")
    }

    @Test("ERROR_ONLY buffers all when online")
    func errorOnlyBuffersAllWhenOnline() async {
        MobileLogRecordProcessor._offlineOverride = false
        defer { MobileLogRecordProcessor._offlineOverride = nil }

        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = PolicyTestExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: PolicyStubSession(),
            offlinePolicy: .errorOnly
        )
        await processor.emitForTesting(body: "info-online", severity: .info)
        let events = buffer.peek()
        #expect(events.count == 1, "all events should buffer when online regardless of policy")
    }

    @Test("DROP_ALL drops everything when offline")
    func dropAllDropsWhenOffline() async {
        MobileLogRecordProcessor._offlineOverride = true
        defer { MobileLogRecordProcessor._offlineOverride = nil }

        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = PolicyTestExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: PolicyStubSession(),
            offlinePolicy: .dropAll
        )
        await processor.emitForTesting(body: "error-drop", severity: .error)
        let events = buffer.peek()
        #expect(events.isEmpty, "DROP_ALL should drop even ERROR when offline")
    }

    @Test("WARN_AND_ABOVE filters correctly when offline")
    func warnAndAboveFiltering() async {
        MobileLogRecordProcessor._offlineOverride = true
        defer { MobileLogRecordProcessor._offlineOverride = nil }

        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = PolicyTestExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: PolicyStubSession(),
            offlinePolicy: .warnAndAbove
        )
        await processor.emitForTesting(body: "info-drop", severity: .info)
        await processor.emitForTesting(body: "warn-keep", severity: .warn)
        await processor.emitForTesting(body: "error-keep", severity: .error)
        let events = buffer.peek()
        #expect(events.count == 2, "WARN_AND_ABOVE should keep WARN + ERROR, drop INFO")
    }
}

// MARK: - Test helpers

fileprivate final class PolicyStubSession: SessionProvider, @unchecked Sendable {
    var sessionId: String { "offline-policy-session" }
    func rotateSession() -> String { "offline-policy-session" }
}

fileprivate final class PolicyTestExporter: BufferedEventExporter, @unchecked Sendable {
    private let lock = NSLock()
    private var _received: [BufferedEvent] = []
    var received: [BufferedEvent] {
        get async { lock.lock(); defer { lock.unlock() }; return _received }
    }
    func export(_ events: [BufferedEvent]) -> BufferExportResult {
        lock.lock(); defer { lock.unlock() }
        _received.append(contentsOf: events)
        return .success
    }
}
