import Testing
@testable import OTelMobileSDK

/// Parity tests for the iOS `PolicyEvaluator` actor. Mirrors the unit coverage
/// in Android's `PolicyEvaluatorTest.kt` (the portion dealing with pure
/// matcher evaluation — fetch/poll and geo/device are not yet iOS-ported).
@Suite("PolicyEvaluator")
struct PolicyEvaluatorTests {

    // MARK: - Helpers

    /// Builds a single-condition policy for the happy-path tests.
    private static func policy(
        id: String,
        enabled: Bool = true,
        attr: String,
        condition: Condition,
        logical: String = "and",
        flushMinutes: Int = 2
    ) -> Policy {
        Policy(
            id: id,
            enabled: enabled,
            match: Match(logicalOperator: logical, attributes: [attr: condition]),
            actions: Actions(flushWindowMinutes: flushMinutes)
        )
    }

    // MARK: - 1. No policies

    @Test("evaluate returns nil when no policies are loaded")
    func noPoliciesReturnsNil() async {
        let evaluator = PolicyEvaluator()
        let result = await evaluator.evaluate(attributes: ["event.name": "app.crash"])
        #expect(result == nil)
    }

    // MARK: - 2. Disabled policy skipped

    @Test("disabled policies are skipped")
    func disabledPolicySkipped() async {
        let p = Self.policy(
            id: "disabled",
            enabled: false,
            attr: "event.name",
            condition: Condition(equals: "app.crash")
        )
        let evaluator = PolicyEvaluator(policies: [p])
        let result = await evaluator.evaluate(attributes: ["event.name": "app.crash"])
        #expect(result == nil)
    }

    // MARK: - 3. equals match

    @Test("equals matches exact attribute value")
    func equalsMatch() async {
        let p = Self.policy(
            id: "eq",
            attr: "event.name",
            condition: Condition(equals: "app.crash"),
            flushMinutes: 5
        )
        let evaluator = PolicyEvaluator(policies: [p])
        let result = await evaluator.evaluate(attributes: ["event.name": "app.crash"])
        #expect(result?.policyId == "eq")
        #expect(result?.flushWindowMinutes == 5)
    }

    // MARK: - 4. equals mismatch

    @Test("equals returns nil on mismatch")
    func equalsMismatch() async {
        let p = Self.policy(
            id: "eq",
            attr: "event.name",
            condition: Condition(equals: "app.crash")
        )
        let evaluator = PolicyEvaluator(policies: [p])
        let result = await evaluator.evaluate(attributes: ["event.name": "ui.tap"])
        #expect(result == nil)
    }

    // MARK: - 5. notEquals match

    @Test("notEquals matches when values differ")
    func notEqualsMatch() async {
        let p = Self.policy(
            id: "ne",
            attr: "event.name",
            condition: Condition(notEquals: "ui.tap")
        )
        let evaluator = PolicyEvaluator(policies: [p])
        let result = await evaluator.evaluate(attributes: ["event.name": "app.crash"])
        #expect(result?.policyId == "ne")

        let miss = await evaluator.evaluate(attributes: ["event.name": "ui.tap"])
        #expect(miss == nil)
    }

    // MARK: - 6. Numeric comparisons: gt/gte/lt/lte

    @Test("gt compares strictly greater")
    func greaterThan() async {
        let p = Self.policy(id: "gt", attr: "duration_ms", condition: Condition(gt: 2000.0))
        let evaluator = PolicyEvaluator(policies: [p])
        #expect(await evaluator.evaluate(attributes: ["duration_ms": "2001"])?.policyId == "gt")
        #expect(await evaluator.evaluate(attributes: ["duration_ms": "2000"]) == nil)
        #expect(await evaluator.evaluate(attributes: ["duration_ms": "1999"]) == nil)
    }

    @Test("gte is inclusive at boundary")
    func greaterOrEqual() async {
        let p = Self.policy(id: "gte", attr: "http.status_code", condition: Condition(gte: 500.0))
        let evaluator = PolicyEvaluator(policies: [p])
        #expect(await evaluator.evaluate(attributes: ["http.status_code": "500"])?.policyId == "gte")
        #expect(await evaluator.evaluate(attributes: ["http.status_code": "499"]) == nil)
    }

    @Test("lt compares strictly less")
    func lessThan() async {
        let p = Self.policy(id: "lt", attr: "score", condition: Condition(lt: 0.5))
        let evaluator = PolicyEvaluator(policies: [p])
        #expect(await evaluator.evaluate(attributes: ["score": "0.4"])?.policyId == "lt")
        #expect(await evaluator.evaluate(attributes: ["score": "0.5"]) == nil)
    }

    @Test("lte is inclusive at boundary")
    func lessOrEqual() async {
        let p = Self.policy(id: "lte", attr: "battery", condition: Condition(lte: 20.0))
        let evaluator = PolicyEvaluator(policies: [p])
        #expect(await evaluator.evaluate(attributes: ["battery": "20"])?.policyId == "lte")
        #expect(await evaluator.evaluate(attributes: ["battery": "21"]) == nil)
    }

    @Test("numeric parse failure returns no-match, never crashes")
    func numericParseFailureSafe() async {
        let p = Self.policy(id: "gt", attr: "duration_ms", condition: Condition(gt: 100.0))
        let evaluator = PolicyEvaluator(policies: [p])
        #expect(await evaluator.evaluate(attributes: ["duration_ms": "not-a-number"]) == nil)
        #expect(await evaluator.evaluate(attributes: ["duration_ms": ""]) == nil)
    }

    // MARK: - 7. contains

    @Test("contains matches a substring")
    func containsMatch() async {
        let p = Self.policy(id: "c", attr: "http.route", condition: Condition(contains: "/api/"))
        let evaluator = PolicyEvaluator(policies: [p])
        #expect(await evaluator.evaluate(attributes: ["http.route": "/api/users"])?.policyId == "c")
        #expect(await evaluator.evaluate(attributes: ["http.route": "/static/x.js"]) == nil)
    }

    // MARK: - 8. regex + cache hit

    @Test("regex matches and cache is reused on repeated evaluation")
    func regexMatchAndCacheHit() async {
        let p = Self.policy(id: "r", attr: "exception.message", condition: Condition(regex: ".*heap.*"))
        let evaluator = PolicyEvaluator(policies: [p])

        // First call: compiles and caches.
        let first = await evaluator.evaluate(attributes: ["exception.message": "out of heap space"])
        #expect(first?.policyId == "r")

        // Second call: should hit the cache. We can't inspect the cache directly,
        // but we can assert the result is still correct after many invocations
        // (flushing out any regression where compilation throws on the hot path).
        for _ in 0..<50 {
            let hit = await evaluator.evaluate(attributes: ["exception.message": "heap corruption"])
            #expect(hit?.policyId == "r")
        }

        // Non-match case:
        #expect(await evaluator.evaluate(attributes: ["exception.message": "null pointer"]) == nil)
    }

    // MARK: - 9. AND combination — both match

    @Test("AND: returns policy when every attribute condition matches")
    func andAllMatch() async {
        let policy = Policy(
            id: "and-both",
            enabled: true,
            match: Match(
                logicalOperator: "and",
                attributes: [
                    "event.name": Condition(equals: "http.error"),
                    "http.status_code": Condition(gte: 500.0)
                ]
            ),
            actions: Actions(flushWindowMinutes: 3)
        )
        let evaluator = PolicyEvaluator(policies: [policy])
        let result = await evaluator.evaluate(attributes: [
            "event.name": "http.error",
            "http.status_code": "502"
        ])
        #expect(result?.policyId == "and-both")
        #expect(result?.flushWindowMinutes == 3)
    }

    // MARK: - 10. AND combination — one misses

    @Test("AND: returns nil when any attribute condition misses")
    func andOneMiss() async {
        let policy = Policy(
            id: "and-miss",
            enabled: true,
            match: Match(
                logicalOperator: "and",
                attributes: [
                    "event.name": Condition(equals: "http.error"),
                    "http.status_code": Condition(gte: 500.0)
                ]
            ),
            actions: Actions(flushWindowMinutes: 3)
        )
        let evaluator = PolicyEvaluator(policies: [policy])
        let result = await evaluator.evaluate(attributes: [
            "event.name": "http.error",
            "http.status_code": "404"
        ])
        #expect(result == nil)
    }

    // MARK: - 11. OR combination

    @Test("OR: returns policy when at least one condition matches")
    func orOneMatch() async {
        let policy = Policy(
            id: "or-one",
            enabled: true,
            match: Match(
                logicalOperator: "or",
                attributes: [
                    "event.name": Condition(equals: "app.crash"),
                    "severity": Condition(equals: "ERROR")
                ]
            ),
            actions: Actions(flushWindowMinutes: 5)
        )
        let evaluator = PolicyEvaluator(policies: [policy])
        let result = await evaluator.evaluate(attributes: [
            "event.name": "ui.tap",
            "severity": "ERROR"
        ])
        #expect(result?.policyId == "or-one")

        let none = await evaluator.evaluate(attributes: [
            "event.name": "ui.tap",
            "severity": "INFO"
        ])
        #expect(none == nil)
    }

    // MARK: - 12. Multiple policies — first match wins

    @Test("multiple policies: first enabled match wins")
    func firstMatchWins() async {
        let a = Self.policy(
            id: "a",
            attr: "event.name",
            condition: Condition(equals: "app.crash"),
            flushMinutes: 5
        )
        let b = Self.policy(
            id: "b",
            attr: "event.name",
            condition: Condition(equals: "app.crash"),
            flushMinutes: 10
        )
        let evaluator = PolicyEvaluator(policies: [a, b])
        let result = await evaluator.evaluate(attributes: ["event.name": "app.crash"])
        #expect(result?.policyId == "a")
        #expect(result?.flushWindowMinutes == 5)
    }

    @Test("multiple policies: skips disabled, falls through to next enabled")
    func skipsDisabledToNextEnabled() async {
        let disabledFirst = Self.policy(
            id: "a",
            enabled: false,
            attr: "event.name",
            condition: Condition(equals: "app.crash"),
            flushMinutes: 5
        )
        let enabledSecond = Self.policy(
            id: "b",
            attr: "event.name",
            condition: Condition(equals: "app.crash"),
            flushMinutes: 10
        )
        let evaluator = PolicyEvaluator(policies: [disabledFirst, enabledSecond])
        let result = await evaluator.evaluate(attributes: ["event.name": "app.crash"])
        #expect(result?.policyId == "b")
        #expect(result?.flushWindowMinutes == 10)
    }

    // MARK: - 13. Missing attribute in event

    @Test("missing attribute fails the condition")
    func missingAttribute() async {
        let policy = Policy(
            id: "needs-status",
            enabled: true,
            match: Match(
                logicalOperator: "and",
                attributes: [
                    "event.name": Condition(equals: "http.error"),
                    "http.status_code": Condition(gte: 500.0)
                ]
            ),
            actions: Actions(flushWindowMinutes: 2)
        )
        let evaluator = PolicyEvaluator(policies: [policy])
        // Only event.name present — status_code condition cannot be satisfied.
        let result = await evaluator.evaluate(attributes: ["event.name": "http.error"])
        #expect(result == nil)
    }

    // MARK: - 14. Invalid regex doesn't crash

    @Test("invalid regex pattern returns nil, does not crash")
    func invalidRegexSafe() async {
        // Unbalanced paren — NSRegularExpression will throw on compile.
        let p = Self.policy(
            id: "bad-regex",
            attr: "message",
            condition: Condition(regex: "(unterminated")
        )
        let evaluator = PolicyEvaluator(policies: [p])
        #expect(await evaluator.evaluate(attributes: ["message": "anything"]) == nil)
        // Repeated evals must not crash either — cache stores the broken state.
        for _ in 0..<10 {
            #expect(await evaluator.evaluate(attributes: ["message": "anything"]) == nil)
        }
    }

    // MARK: - 15. updatePolicies swaps atomically

    @Test("updatePolicies swaps config atomically")
    func updatePoliciesSwaps() async {
        let initial = Self.policy(
            id: "v1",
            attr: "event.name",
            condition: Condition(equals: "app.crash"),
            flushMinutes: 5
        )
        let evaluator = PolicyEvaluator(policies: [initial])
        #expect(await evaluator.currentPolicyCount() == 1)
        #expect(await evaluator.evaluate(attributes: ["event.name": "app.crash"])?.policyId == "v1")

        let replacement = Self.policy(
            id: "v2",
            attr: "event.name",
            condition: Condition(equals: "ui.freeze"),
            flushMinutes: 2
        )
        await evaluator.updatePolicies([replacement])
        #expect(await evaluator.currentPolicyCount() == 1)

        // Old policy no longer matches.
        #expect(await evaluator.evaluate(attributes: ["event.name": "app.crash"]) == nil)
        // New policy does.
        let hit = await evaluator.evaluate(attributes: ["event.name": "ui.freeze"])
        #expect(hit?.policyId == "v2")
        #expect(hit?.flushWindowMinutes == 2)
    }

    // MARK: - 16. Empty Match rejected (Android parity)

    @Test("policy with empty attributes is never a match")
    func emptyAttributesNeverMatches() async {
        let empty = Policy(
            id: "empty",
            enabled: true,
            match: Match(logicalOperator: "and", attributes: [:]),
            actions: Actions(flushWindowMinutes: 2)
        )
        let evaluator = PolicyEvaluator(policies: [empty])
        let result = await evaluator.evaluate(attributes: ["event.name": "app.crash"])
        #expect(result == nil)
    }

    // MARK: - 17. contextSnapshot accepted but ignored (future-proof)

    @Test("contextSnapshot argument is accepted and does not affect result today")
    func contextSnapshotIgnoredForNow() async {
        let p = Self.policy(
            id: "eq",
            attr: "event.name",
            condition: Condition(equals: "app.crash")
        )
        let evaluator = PolicyEvaluator(policies: [p])
        let snap = ContextSnapshot(
            countryCode: "US",
            networkType: "wifi",
            batteryState: "charging"
        )
        let result = await evaluator.evaluate(
            attributes: ["event.name": "app.crash"],
            contextSnapshot: snap
        )
        #expect(result?.policyId == "eq")
    }
}
