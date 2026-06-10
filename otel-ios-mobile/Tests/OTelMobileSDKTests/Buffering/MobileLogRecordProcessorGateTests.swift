import Testing
@testable import OTelMobileSDK
import OTelMobileCore
import OpenTelemetryApi
import OpenTelemetrySdk

/// Verifies the remote kill-switch gate is consulted on the log choke point
/// (`MobileLogRecordProcessor.onEmit`). Behavioural parity with Android's
/// `MobileLogRecordProcessorGateTest.kt`.
///
/// `emitForTesting` routes through the real `onEmit`, so a record dropped here
/// proves the gate short-circuits before buffering. The bridge-style case
/// emits through the SAME `logger` path the React Native sink uses, proving
/// RN §6 coverage with no RN-side code (see `docs/design/remote-kill-switch.md`).
@Suite("MobileLogRecordProcessorGate")
struct MobileLogRecordProcessorGateTests {

    fileprivate final class GateSessionProvider: SessionProvider, @unchecked Sendable {
        var sessionId: String { "gate-session" }
        func rotateSession() -> String { "gate-session" }
    }

    fileprivate actor GateExporter: BufferedEventExporter {
        private(set) var received: [BufferedEvent] = []
        func export(_ events: [BufferedEvent]) async -> BufferExportResult {
            received.append(contentsOf: events)
            return .success
        }
    }

    private func makeProcessor(gate: RemoteGate) -> (MobileLogRecordProcessor, GateExporter, RAMEventBuffer) {
        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = GateExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: GateSessionProvider(),
            remoteGate: gate
        )
        return (processor, exporter, buffer)
    }

    private func makeProcessor(gate: RemoteGate, coalescer: ErrorCoalescer)
        -> (MobileLogRecordProcessor, RAMEventBuffer) {
        let buffer = RAMEventBuffer(capacity: 100)
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: GateExporter(),
            sessionProvider: GateSessionProvider(),
            remoteGate: gate,
            errorCoalescer: coalescer
        )
        return (processor, buffer)
    }

    @Test("disabled gate drops the record in onEmit (nothing buffered)")
    func disabledDropsInOnEmit() async {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 1.0))
        let (processor, _, buffer) = makeProcessor(gate: gate)
        await processor.emitForTesting(body: "should-be-dropped")
        let peeked = await buffer.peek()
        #expect(peeked.isEmpty, "a remotely-disabled SDK must not buffer the record")
    }

    @Test("enabled gate keeps the record in onEmit")
    func enabledKeepsInOnEmit() async {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 1.0))
        let (processor, _, buffer) = makeProcessor(gate: gate)
        await processor.emitForTesting(body: "should-be-kept")
        let peeked = await buffer.peek()
        #expect(peeked.count == 1)
    }

    @Test("flipping the shared gate at runtime changes onEmit behaviour")
    func runtimeFlip() async {
        let gate = RemoteGate()
        let (processor, _, buffer) = makeProcessor(gate: gate)

        await processor.emitForTesting(body: "kept-1")
        #expect(await buffer.count == 1)

        // Operator disables the fleet mid-flight.
        gate.update(SDKRemoteConfig(enabled: false, sampleRate: 1.0))
        await processor.emitForTesting(body: "dropped")
        #expect(await buffer.count == 1, "no new record while disabled")

        // Operator re-enables.
        gate.update(.default)
        await processor.emitForTesting(body: "kept-2")
        #expect(await buffer.count == 2)
    }

    @Test("rate 0.0 drops every record in onEmit")
    func rateZeroDropsAll() async {
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 0.0))
        let (processor, _, buffer) = makeProcessor(gate: gate)
        for _ in 0..<10 { await processor.emitForTesting(body: "x") }
        let peeked = await buffer.peek()
        #expect(peeked.isEmpty)
    }

    @Test("no gate wired ⇒ records always buffered (backward compatible)")
    func noGateBackwardCompatible() async {
        let buffer = RAMEventBuffer(capacity: 100)
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: GateExporter(),
            sessionProvider: GateSessionProvider()
            // remoteGate omitted → nil
        )
        await processor.emitForTesting(body: "always-kept")
        #expect(await buffer.count == 1)
    }

    // MARK: - Gate runs FIRST: no work (coalescer mutation) when disabled

    @Test("disabled gate does not mutate the error coalescer (gate runs before coalescing)")
    func disabledGateDoesNoCoalescerWork() async {
        // The gate now sits at the TOP of onEmit, before error coalescing, so a
        // disabled SDK must NOT touch coalescer state. Regression guard for the
        // pre-fix ordering where coalescing ran before the gate.
        let coalescer = ErrorCoalescer()
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 1.0))
        let (processor, buffer) = makeProcessor(gate: gate, coalescer: coalescer)

        await processor.emitForTesting(
            body: "boom",
            severity: .error,
            attributes: ["exception.type": .string("NSError"),
                         "exception.message": .string("boom")]
        )
        #expect(await buffer.count == 0, "disabled SDK must not buffer")
        #expect(coalescer.activeGroupCount == 0,
                "disabled SDK must not mutate coalescer state (gate runs first)")
    }

    @Test("enabled gate DOES coalesce errors (control: ordering didn't break coalescing)")
    func enabledGateStillCoalesces() async {
        // Control case proving the move didn't disable coalescing: with the SDK
        // enabled, an error record still creates a coalescer group.
        let coalescer = ErrorCoalescer()
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 1.0))
        let (processor, _) = makeProcessor(gate: gate, coalescer: coalescer)

        await processor.emitForTesting(
            body: "boom",
            severity: .error,
            attributes: ["exception.type": .string("NSError"),
                         "exception.message": .string("boom")]
        )
        #expect(coalescer.activeGroupCount == 1,
                "enabled SDK must still track the error in the coalescer")
    }

    // MARK: - Bridge / RN §6 coverage

    @Test("bridge-style emit through the shared logger is dropped when disabled")
    func bridgeEmitDroppedWhenDisabled() async {
        // RN telemetry rides OTelMobileCallSink → the native logger → this same
        // processor's onEmit. We model that by wiring a real LoggerProvider to
        // the gated processor and emitting via `logger.logRecordBuilder()` — the
        // exact path the RN sink uses — with the gate disabled.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 1.0))
        let buffer = RAMEventBuffer(capacity: 100)
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: GateExporter(),
            sessionProvider: GateSessionProvider(),
            remoteGate: gate
        )
        let provider = LoggerProviderBuilder()
            .with(processors: [processor])
            .build()
        let logger = provider.get(instrumentationScopeName: "io.dash0.mobile.rn-bridge")

        logger.logRecordBuilder()
            .setBody(.string("rn.bridge.event"))
            .setSeverity(.info)
            .emit()
        // Let the (suppressed) detached append task settle.
        try? await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 100)
        #expect(await buffer.count == 0, "bridge-emitted log must be dropped when the SDK is remotely disabled")

        // And kept once re-enabled — proves the same path flows normally.
        gate.update(.default)
        logger.logRecordBuilder()
            .setBody(.string("rn.bridge.event.2"))
            .setSeverity(.info)
            .emit()
        try? await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 100)
        #expect(await buffer.count == 1)
    }
}
