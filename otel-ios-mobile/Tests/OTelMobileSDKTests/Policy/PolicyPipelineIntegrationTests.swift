import Testing
@testable import OTelMobileSDK

/// End-to-end test of the policy pipeline:
///   onEmit -> buffer append -> evaluator.evaluate -> flushWindow -> exporter
///
/// Proves the whole DSL v2 decision path actually fires, not just that each
/// piece works in isolation.
@Suite("PolicyPipelineIntegration")
struct PolicyPipelineIntegrationTests {
    /// When a policy matches, the matching event PLUS any subsequent events in
    /// the same flush window land in the exporter.
    @Test("matching event triggers selective flush")
    func matchingEventTriggersFlush() async {
        let captured = CapturingExporter()
        let evaluator = PolicyEvaluator(policies: [
            Policy(
                id: "crash-handler",
                enabled: true,
                match: Match(
                    logicalOperator: "and",
                    attributes: ["event.name": Condition(equals: "app.crash")]
                ),
                actions: Actions(flushWindowMinutes: 5)
            )
        ])
        let processor = MobileLogRecordProcessor(
            buffer: RAMEventBuffer(capacity: 100),
            exporter: captured,
            sessionProvider: StaticSessionProvider(),
            policyEvaluator: evaluator
        )

        await processor.emitForTesting(body: "app.crash", severity: .fatal)
        // Allow flushWindow's async work to complete.
        try? await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 200)

        let count = await captured.count
        #expect(count >= 1, "expected ≥1 event exported via policy-triggered flush, got \(count)")
    }

    /// When no policy matches, the buffer holds the event but no flush fires.
    @Test("non-matching event does not trigger flush")
    func nonMatchingEventSkipsFlush() async {
        let captured = CapturingExporter()
        let evaluator = PolicyEvaluator(policies: [
            Policy(
                id: "crash-handler",
                enabled: true,
                match: Match(
                    logicalOperator: "and",
                    attributes: ["event.name": Condition(equals: "app.crash")]
                ),
                actions: Actions(flushWindowMinutes: 5)
            )
        ])
        let processor = MobileLogRecordProcessor(
            buffer: RAMEventBuffer(capacity: 100),
            exporter: captured,
            sessionProvider: StaticSessionProvider(),
            policyEvaluator: evaluator
        )

        // Emit something benign — matches no policy.
        await processor.emitForTesting(body: "user.tap", severity: .info)
        try? await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 200)

        let count = await captured.count
        #expect(count == 0, "expected no policy-driven flush, got \(count)")
    }

    /// When no evaluator is configured, onEmit still works (backward compat).
    /// No flush fires; events remain in the buffer until forceFlush.
    @Test("no evaluator = legacy behaviour, no auto-flush")
    func noEvaluatorNoFlush() async {
        let captured = CapturingExporter()
        let processor = MobileLogRecordProcessor(
            buffer: RAMEventBuffer(capacity: 100),
            exporter: captured,
            sessionProvider: StaticSessionProvider(),
            policyEvaluator: nil
        )

        await processor.emitForTesting(body: "app.crash", severity: .fatal)
        try? await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 200)

        let count = await captured.count
        #expect(count == 0, "no evaluator should mean no automatic flush")
    }

    /// Policy swapping (what ConfigPoller does): updatePolicies on the
    /// evaluator changes what subsequent emits trigger.
    @Test("updatePolicies changes flush behaviour on next emit")
    func updatePoliciesTakesEffectOnNextEmit() async {
        let captured = CapturingExporter()
        let evaluator = PolicyEvaluator()  // starts empty
        let processor = MobileLogRecordProcessor(
            buffer: RAMEventBuffer(capacity: 100),
            exporter: captured,
            sessionProvider: StaticSessionProvider(),
            policyEvaluator: evaluator
        )

        // Empty policy set: crash doesn't flush.
        await processor.emitForTesting(body: "app.crash", severity: .fatal)
        try? await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 200)
        let beforeCount = await captured.count
        #expect(beforeCount == 0)

        // Push a policy mid-stream (ConfigPoller does this when new config
        // lands from the gateway).
        await evaluator.updatePolicies([
            Policy(
                id: "crash-handler",
                enabled: true,
                match: Match(
                    logicalOperator: "and",
                    attributes: ["event.name": Condition(equals: "app.crash")]
                ),
                actions: Actions(flushWindowMinutes: 5)
            )
        ])

        // Now the same event should trigger a flush.
        await processor.emitForTesting(body: "app.crash", severity: .fatal)
        try? await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 200)
        let afterCount = await captured.count
        #expect(afterCount >= 1, "updatePolicies should enable flush on next emit")
    }

    /// Disabled policy is ignored even when its matchers would fire.
    @Test("disabled policy does not trigger flush")
    func disabledPolicyIsSkipped() async {
        let captured = CapturingExporter()
        let evaluator = PolicyEvaluator(policies: [
            Policy(
                id: "crash-handler",
                enabled: false,  // <-- disabled
                match: Match(
                    logicalOperator: "and",
                    attributes: ["event.name": Condition(equals: "app.crash")]
                ),
                actions: Actions(flushWindowMinutes: 5)
            )
        ])
        let processor = MobileLogRecordProcessor(
            buffer: RAMEventBuffer(capacity: 100),
            exporter: captured,
            sessionProvider: StaticSessionProvider(),
            policyEvaluator: evaluator
        )

        await processor.emitForTesting(body: "app.crash", severity: .fatal)
        try? await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 200)

        let count = await captured.count
        #expect(count == 0, "disabled policy should not trigger flush")
    }
}
