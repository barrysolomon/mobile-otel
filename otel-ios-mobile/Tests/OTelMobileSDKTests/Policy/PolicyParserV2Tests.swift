import Testing
@testable import OTelMobileSDK

/// Behavioral-parity tests with Android's `PolicyEvaluatorV2ParseTest.kt`.
/// Every JSON body below is copied verbatim from the Android test file;
/// assertions are translated from JUnit to Swift Testing.
@Suite("PolicyParserV2")
struct PolicyParserV2Tests {

    // MARK: - Case 1: crash matcher

    @Test("parseConfigV2 parses crash matcher into policy")
    func parseCrashMatcher() {
        let json = """
        {
          "version": 2,
          "buffer_config": {"ram_events": 5000, "disk_mb": 50, "retention_hours": 24, "strategy": "overwrite_oldest"},
          "workflows": [{
            "id": "crash-handler",
            "name": "Crash Handler",
            "enabled": true,
            "priority": 1,
            "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{"type": "crash", "config": {}}],
              "on_match": {
                "actions": [{"type": "flush_buffer", "config": {"minutes": 5, "scope": "session"}}]
              }
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        #expect(config?.policies.count == 1)

        let policy = config!.policies[0]
        #expect(policy.id == "crash-handler")
        #expect(policy.enabled)
        #expect(policy.match.attributes["event.name"]?.equals == "app.crash")
        #expect(policy.actions.flushWindowMinutes == 5)
    }

    // MARK: - Case 2: ui_freeze with duration threshold

    @Test("parseConfigV2 parses ui_freeze with duration threshold")
    func parseUiFreezeWithDuration() {
        let json = """
        {
          "version": 2,
          "buffer_config": {},
          "workflows": [{
            "id": "freeze-handler",
            "enabled": true,
            "priority": 1,
            "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{"type": "ui_freeze", "config": {"duration_ms": 3000}}],
              "on_match": {
                "actions": [{"type": "flush_buffer", "config": {"minutes": 2}}]
              }
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        let policy = config!.policies[0]
        #expect(policy.match.attributes["event.name"]?.equals == "ui.freeze")
        #expect(policy.match.attributes["duration_ms"]?.gt == 3000.0)
        #expect(policy.actions.flushWindowMinutes == 2)
    }

    // MARK: - Case 3: http_match with status_min

    @Test("parseConfigV2 parses http_match with status_min")
    func parseHttpMatchWithStatusMin() {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "http-500", "enabled": true, "priority": 1, "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{"type": "http_match", "config": {"status_min": 500}}],
              "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 3}}]}
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        let policy = config!.policies[0]
        #expect(policy.match.attributes["event.name"]?.equals == "http.error")
        #expect(policy.match.attributes["http.status_code"]?.gte == 500.0)
    }

    // MARK: - Case 4: exception_pattern with regex

    @Test("parseConfigV2 parses exception_pattern with regex")
    func parseExceptionPatternWithRegex() {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "oom-detector", "enabled": true, "priority": 1, "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{"type": "exception_pattern", "config": {"exception_type": "OutOfMemory", "message_pattern": ".*heap.*"}}],
              "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 5}}]}
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        let policy = config!.policies[0]
        #expect(policy.match.attributes["event.name"]?.equals == "app.crash")
        #expect(policy.match.attributes["exception.type"]?.contains == "OutOfMemory")
        #expect(policy.match.attributes["exception.message"]?.regex == ".*heap.*")
    }

    // MARK: - Case 5: where clause predicates

    @Test("parseConfigV2 applies where clause predicates")
    func parseWhereClausePredicates() {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "slow-api", "enabled": true, "priority": 1, "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{
                "type": "event_match",
                "config": {"event_name": "http.request"},
                "where": [
                  {"attr": "http.status_code", "op": ">=", "value": 500},
                  {"attr": "http.route", "op": "contains", "value": "/api/"}
                ]
              }],
              "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 2}}]}
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        let policy = config!.policies[0]
        #expect(policy.match.attributes["event.name"]?.equals == "http.request")
        #expect(policy.match.attributes["http.status_code"]?.gte == 500.0)
        #expect(policy.match.attributes["http.route"]?.contains == "/api/")
    }

    // MARK: - Case 6: multi-state workflow

    @Test("parseConfigV2 handles multi-state workflow")
    func parseMultiStateWorkflow() {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "multi-state", "enabled": true, "priority": 1,
            "initial_state": "watching",
            "states": [
              {
                "id": "watching",
                "matchers": [{"type": "crash", "config": {}}],
                "on_match": {
                  "actions": [{"type": "flush_buffer", "config": {"minutes": 5}}],
                  "transition_to": "recording"
                }
              },
              {
                "id": "recording",
                "matchers": [{"type": "anr", "config": {}}],
                "on_match": {
                  "actions": [{"type": "flush_buffer", "config": {"minutes": 10}}]
                }
              }
            ]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        // Two states × 1 matcher each = 2 policies
        #expect(config!.policies.count == 2)
        #expect(config!.policies[0].id == "multi-state/watching/0")
        #expect(config!.policies[0].match.attributes["event.name"]?.equals == "app.crash")
        #expect(config!.policies[0].actions.flushWindowMinutes == 5)
        #expect(config!.policies[1].id == "multi-state/recording/0")
        #expect(config!.policies[1].match.attributes["event.name"]?.equals == "app.anr")
        #expect(config!.policies[1].actions.flushWindowMinutes == 10)
    }

    // MARK: - Case 7: version 1 returns nil

    @Test("parseConfigV2 returns null for version 1")
    func parseVersion1ReturnsNil() {
        let json = #"{"version": 1, "workflows": []}"#
        let config = PolicyParser.parseV2(json)
        #expect(config == nil)
    }

    // MARK: - Case 8: malformed JSON

    @Test("parseConfigV2 handles malformed JSON gracefully")
    func parseMalformedJson() {
        let config = PolicyParser.parseV2("not json at all")
        #expect(config == nil)
    }

    // MARK: - Case 9: empty workflows

    @Test("parseConfigV2 handles empty workflows")
    func parseEmptyWorkflows() {
        let json = #"{"version": 2, "buffer_config": {}, "workflows": []}"#
        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        #expect(config?.policies.count == 0)
    }

    // MARK: - Case 10: skip timeout matchers

    @Test("parseConfigV2 skips timeout matchers")
    func skipTimeoutMatchers() {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "with-timeout", "enabled": true, "priority": 1, "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [
                {"type": "timeout", "config": {"after_ms": 30000}},
                {"type": "crash", "config": {}}
              ],
              "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 5}}]}
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        // timeout should be skipped, crash should parse
        #expect(config!.policies.count == 1)
        #expect(config!.policies[0].match.attributes["event.name"]?.equals == "app.crash")
    }

    // MARK: - Case 11: all device health matcher types

    @Test(
        "parseConfigV2 parses all device health matcher types",
        arguments: [
            ("low_memory", "device.low_memory"),
            ("battery_drain", "device.battery_drain"),
            ("thermal_throttle", "device.thermal_throttle"),
            ("storage_low", "device.storage_low"),
            ("network_loss", "network.loss"),
            ("anr", "app.anr"),
        ]
    )
    func parseDeviceHealthMatcherTypes(matcherType: String, expectedEvent: String) {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "test-\(matcherType)", "enabled": true, "priority": 1, "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{"type": "\(matcherType)", "config": {}}],
              "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 2}}]}
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil, "Failed to parse matcher type: \(matcherType)")
        #expect(
            config!.policies[0].match.attributes["event.name"]?.equals == expectedEvent,
            "Matcher \(matcherType) should map to event \(expectedEvent)"
        )
    }

    // MARK: - Case 12: predictive_risk with min_score

    @Test("parseConfigV2 parses predictive_risk with min_score")
    func parsePredictiveRiskWithMinScore() {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "risk", "enabled": true, "priority": 1, "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{"type": "predictive_risk", "config": {"risk_type": "crash", "min_score": 0.8}}],
              "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 3}}]}
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        let policy = config!.policies[0]
        #expect(policy.match.attributes["event.name"]?.equals == "prediction.high_risk_alert")
        #expect(policy.match.attributes["risk_score"]?.gte == 0.8)
    }

    // MARK: - Case 13: disabled workflows

    @Test("parseConfigV2 respects disabled workflows")
    func respectDisabledWorkflows() {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "disabled-wf", "enabled": false, "priority": 1, "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{"type": "crash", "config": {}}],
              "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 5}}]}
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        #expect(config!.policies.count == 1)
        #expect(config!.policies[0].enabled == false)
    }

    // MARK: - Case 14: default flush window

    @Test("parseConfigV2 defaults flush window to 2 when no flush_buffer action")
    func defaultFlushWindow() {
        let json = """
        {
          "version": 2, "buffer_config": {},
          "workflows": [{
            "id": "no-flush", "enabled": true, "priority": 1, "initial_state": "default",
            "states": [{
              "id": "default",
              "matchers": [{"type": "crash", "config": {}}],
              "on_match": {"actions": [{"type": "annotate", "config": {"trigger_id": "test", "reason": "test"}}]}
            }]
          }]
        }
        """

        let config = PolicyParser.parseV2(json)
        #expect(config != nil)
        #expect(config!.policies[0].actions.flushWindowMinutes == 2)
    }
}
