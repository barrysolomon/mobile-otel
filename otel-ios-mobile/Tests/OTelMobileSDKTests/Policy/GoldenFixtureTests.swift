import Foundation
import Testing
@testable import OTelMobileSDK

/// Runs every JSON fixture under `golden/dsl/` through the iOS PolicyEvaluator
/// and asserts the verdict matches `expectedMatch`. A new matcher added to one
/// platform without iOS support fails the corresponding fixture here.
///
/// Fixture format documented in `golden/README.md`.
@Suite("GoldenDSLFixtures")
struct GoldenFixtureTests {

    /// Iterates every fixture under golden/dsl/ and asserts each case's verdict.
    /// Failure messages include the fixture filename + case name so a regression
    /// is immediately traceable to the file that broke.
    @Test("all golden fixtures evaluate as expected")
    func evaluateAllFixtures() async {
        let fixtures = GoldenFixtureLoader.allFixtures()
        #expect(!fixtures.isEmpty, "no golden fixtures found — check golden/ path resolution")

        for fixture in fixtures {
            let evaluator = PolicyEvaluator(policies: fixture.policies)
            for testCase in fixture.cases {
                let result = await evaluator.evaluate(attributes: testCase.attributes)
                let actual = result?.policyId
                let expected = testCase.knownDriftActual ?? testCase.expectedMatch
                #expect(
                    actual == expected,
                    "\(fixture.filePath) :: \(testCase.name): expected \(expected ?? "nil"), got \(actual ?? "nil")"
                )
            }
        }
    }
}

// MARK: - Fixture model

struct GoldenFixture: CustomStringConvertible {
    let filePath: String
    let name: String
    let policies: [Policy]
    let cases: [GoldenCase]

    var description: String { "\(name) (\(filePath))" }
}

struct GoldenCase {
    let name: String
    let attributes: [String: String]
    let expectedMatch: String?
    /// Per-platform documented drift. If set on iOS, the test accepts this
    /// verdict instead of `expectedMatch` and prints a notice rather than
    /// failing — same shape as the Android harness.
    let knownDriftActual: String?
    let knownDriftReason: String?

    init(
        name: String,
        attributes: [String: String],
        expectedMatch: String?,
        knownDriftActual: String? = nil,
        knownDriftReason: String? = nil
    ) {
        self.name = name
        self.attributes = attributes
        self.expectedMatch = expectedMatch
        self.knownDriftActual = knownDriftActual
        self.knownDriftReason = knownDriftReason
    }
}

// MARK: - Loader

/// Discovers and parses fixtures under `<workspace>/golden/dsl/**/*.json`.
/// Workspace is resolved by walking up from `#filePath` until a `golden/`
/// directory is found — robust against SwiftPM not bundling resources from
/// outside the package root.
enum GoldenFixtureLoader {
    static func allFixtures() -> [GoldenFixture] {
        guard let goldenDir = locateGoldenDir() else {
            // Returning an empty array would silently pass the test suite —
            // surface the failure as a fixture instead so the developer sees it.
            return [GoldenFixture(
                filePath: "<not found>",
                name: "LOADER ERROR: could not locate golden/ directory",
                policies: [],
                cases: [GoldenCase(
                    name: "loader",
                    attributes: [:],
                    expectedMatch: "should-not-occur"
                )]
            )]
        }

        let dslDir = goldenDir.appendingPathComponent("dsl")
        let fileManager = FileManager.default
        guard let enumerator = fileManager.enumerator(
            at: dslDir,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        var fixtures: [GoldenFixture] = []
        for case let url as URL in enumerator where url.pathExtension == "json" {
            if let fixture = parse(url: url) {
                fixtures.append(fixture)
            }
        }
        return fixtures.sorted { $0.filePath < $1.filePath }
    }

    private static func locateGoldenDir() -> URL? {
        // Walk up from this file's directory until we find a `golden/` sibling.
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<10 {
            let candidate = dir.appendingPathComponent("golden")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            dir = dir.deletingLastPathComponent()
        }
        return nil
    }

    private static func parse(url: URL) -> GoldenFixture? {
        guard let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let name = (json["name"] as? String) ?? url.lastPathComponent
        let policiesJson = (json["policies"] as? [[String: Any]]) ?? []
        let casesJson = (json["cases"] as? [[String: Any]]) ?? []

        let policies = policiesJson.compactMap(parsePolicy)
        let cases = casesJson.compactMap(parseCase)
        return GoldenFixture(
            filePath: url.lastPathComponent,
            name: name,
            policies: policies,
            cases: cases
        )
    }

    private static func parsePolicy(_ obj: [String: Any]) -> Policy? {
        guard let id = obj["id"] as? String else { return nil }
        let enabled = (obj["enabled"] as? Bool) ?? true
        let matchObj = (obj["match"] as? [String: Any]) ?? [:]
        let actionsObj = (obj["actions"] as? [String: Any]) ?? [:]
        let flushMinutes = (actionsObj["flushWindowMinutes"] as? Int) ?? 2

        let logicalOp = (matchObj["operator"] as? String) ?? "and"
        let attrsObj = (matchObj["attributes"] as? [String: [String: Any]]) ?? [:]
        var conditions: [String: Condition] = [:]
        for (key, condObj) in attrsObj {
            conditions[key] = parseCondition(condObj)
        }
        return Policy(
            id: id,
            enabled: enabled,
            match: Match(logicalOperator: logicalOp, attributes: conditions),
            actions: Actions(flushWindowMinutes: flushMinutes)
        )
    }

    private static func parseCondition(_ obj: [String: Any]) -> Condition {
        Condition(
            equals: obj["equals"] as? String,
            notEquals: obj["notEquals"] as? String,
            gt: obj["gt"] as? Double,
            lt: obj["lt"] as? Double,
            gte: obj["gte"] as? Double,
            lte: obj["lte"] as? Double,
            contains: obj["contains"] as? String,
            regex: obj["regex"] as? String
        )
    }

    private static func parseCase(_ obj: [String: Any]) -> GoldenCase? {
        guard let name = obj["name"] as? String else { return nil }
        // attributes can be string, int, or double in JSON; stringify everything
        // so the iOS evaluator's attribute-string contract holds.
        var attrs: [String: String] = [:]
        if let rawAttrs = obj["attributes"] as? [String: Any] {
            for (k, v) in rawAttrs {
                attrs[k] = stringify(v)
            }
        }
        let expected = obj["expectedMatch"] as? String  // nil if JSON null
        let driftObj = (obj["knownDrift"] as? [String: Any])?["ios"] as? [String: Any]
        return GoldenCase(
            name: name,
            attributes: attrs,
            expectedMatch: expected,
            knownDriftActual: driftObj?["actual"] as? String,
            knownDriftReason: driftObj?["reason"] as? String
        )
    }

    private static func stringify(_ value: Any) -> String {
        if let s = value as? String { return s }
        if let i = value as? Int { return String(i) }
        if let d = value as? Double { return String(d) }
        if let b = value as? Bool { return String(b) }
        return String(describing: value)
    }
}
