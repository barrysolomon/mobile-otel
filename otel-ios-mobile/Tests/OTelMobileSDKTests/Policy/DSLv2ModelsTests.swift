import Testing
@testable import OTelMobileSDK

@Suite("DSLv2Models")
struct DSLv2ModelsTests {

    // MARK: - Crash (no fields)

    @Test("decodeCrashMatcherFlat")
    func decodeCrashMatcherFlat() throws {
        let matcher = try DSLMatcher.decode(fromJsonString: #"{"type": "crash"}"#)
        guard case .crash = matcher else {
            Issue.record("Expected .crash, got \(matcher)")
            return
        }
    }

    @Test("decodeCrashMatcherNested")
    func decodeCrashMatcherNested() throws {
        let matcher = try DSLMatcher.decode(fromJsonString: #"{"type": "crash", "config": {}}"#)
        guard case .crash = matcher else {
            Issue.record("Expected .crash, got \(matcher)")
            return
        }
    }

    // MARK: - ui_freeze (single Int field)

    @Test("decodeUiFreezeFlat")
    func decodeUiFreezeFlat() throws {
        let matcher = try DSLMatcher.decode(fromJsonString: #"{"type": "ui_freeze", "duration_ms": 3000}"#)
        guard case .uiFreeze(let durationMs) = matcher else {
            Issue.record("Expected .uiFreeze, got \(matcher)")
            return
        }
        #expect(durationMs == 3000)
    }

    @Test("decodeUiFreezeNested")
    func decodeUiFreezeNested() throws {
        let matcher = try DSLMatcher.decode(
            fromJsonString: #"{"type": "ui_freeze", "config": {"duration_ms": 3000}}"#
        )
        guard case .uiFreeze(let durationMs) = matcher else {
            Issue.record("Expected .uiFreeze, got \(matcher)")
            return
        }
        #expect(durationMs == 3000)
    }

    // MARK: - http_match (3 optional fields)

    @Test("decodeHttpMatchWithAllFields")
    func decodeHttpMatchWithAllFields() throws {
        let matcher = try DSLMatcher.decode(
            fromJsonString: #"{"type": "http_match", "status_min": 500, "route_contains": "/api", "method": "POST"}"#
        )
        guard case .httpMatch(let statusMin, let routeContains, let method) = matcher else {
            Issue.record("Expected .httpMatch, got \(matcher)")
            return
        }
        #expect(statusMin == 500)
        #expect(routeContains == "/api")
        #expect(method == "POST")
    }

    @Test("decodeHttpMatchNested")
    func decodeHttpMatchNested() throws {
        let matcher = try DSLMatcher.decode(
            fromJsonString: #"{"type": "http_match", "config": {"status_min": 500, "route_contains": "/api", "method": "POST"}}"#
        )
        guard case .httpMatch(let statusMin, let routeContains, let method) = matcher else {
            Issue.record("Expected .httpMatch, got \(matcher)")
            return
        }
        #expect(statusMin == 500)
        #expect(routeContains == "/api")
        #expect(method == "POST")
    }

    @Test("decodeHttpMatchOptionalMissing")
    func decodeHttpMatchOptionalMissing() throws {
        let matcher = try DSLMatcher.decode(fromJsonString: #"{"type": "http_match"}"#)
        guard case .httpMatch(let statusMin, let routeContains, let method) = matcher else {
            Issue.record("Expected .httpMatch, got \(matcher)")
            return
        }
        #expect(statusMin == nil)
        #expect(routeContains == nil)
        #expect(method == nil)
    }

    // MARK: - compound

    @Test("decodeCompoundMatcher")
    func decodeCompoundMatcher() throws {
        let json = #"""
        {
            "type": "compound",
            "combine": "all",
            "children": [
                {"type": "crash"},
                {"type": "ui_freeze", "duration_ms": 2000}
            ]
        }
        """#
        let matcher = try DSLMatcher.decode(fromJsonString: json)
        guard case .compound(let combine, let children) = matcher else {
            Issue.record("Expected .compound, got \(matcher)")
            return
        }
        #expect(combine == .all)
        #expect(children.count == 2)
        guard case .crash = children[0] else {
            Issue.record("Expected first child to be .crash")
            return
        }
        guard case .uiFreeze(let durationMs) = children[1] else {
            Issue.record("Expected second child to be .uiFreeze")
            return
        }
        #expect(durationMs == 2000)
    }

    // MARK: - flush_buffer action (both styles)

    @Test("decodeFlushBufferAction")
    func decodeFlushBufferAction() throws {
        let action = try DSLAction.decode(
            fromJsonString: #"{"type": "flush_buffer", "minutes": 5, "scope": "session"}"#
        )
        guard case .flushBuffer(let minutes, let scope) = action else {
            Issue.record("Expected .flushBuffer, got \(action)")
            return
        }
        #expect(minutes == 5)
        #expect(scope == .session)
    }

    @Test("decodeFlushBufferActionNested")
    func decodeFlushBufferActionNested() throws {
        let action = try DSLAction.decode(
            fromJsonString: #"{"type": "flush_buffer", "config": {"minutes": 5, "scope": "session"}}"#
        )
        guard case .flushBuffer(let minutes, let scope) = action else {
            Issue.record("Expected .flushBuffer, got \(action)")
            return
        }
        #expect(minutes == 5)
        #expect(scope == .session)
    }

    // MARK: - Full workflow

    @Test("decodeFullWorkflow")
    func decodeFullWorkflow() throws {
        let json = #"""
        {
            "id": "crash-handler",
            "name": "Crash Handler",
            "enabled": true,
            "priority": 1,
            "initial_state": "watching",
            "states": [{
                "id": "watching",
                "matchers": [{"type": "crash"}],
                "on_match": {
                    "actions": [{"type": "flush_buffer", "minutes": 5, "scope": "session"}],
                    "transition_to": "done"
                }
            }]
        }
        """#
        let workflow = try DSLWorkflow.decode(fromJsonString: json)
        #expect(workflow.id == "crash-handler")
        #expect(workflow.name == "Crash Handler")
        #expect(workflow.enabled == true)
        #expect(workflow.priority == 1)
        #expect(workflow.initialState == "watching")
        #expect(workflow.states.count == 1)
        #expect(workflow.states[0].id == "watching")
        #expect(workflow.states[0].matchers.count == 1)
        #expect(workflow.states[0].onMatch?.transitionTo == "done")
        #expect(workflow.states[0].onMatch?.actions.count == 1)
    }

    // MARK: - Full config

    @Test("decodeFullConfig")
    func decodeFullConfig() throws {
        let json = #"""
        {
            "version": 2,
            "buffer_config": {"ram_events": 3000, "disk_mb": 25, "retention_hours": 12, "strategy": "overwrite_oldest"},
            "workflows": []
        }
        """#
        let config = try DSLConfigV2.decode(fromJsonString: json)
        #expect(config.version == 2)
        #expect(config.bufferConfig.ramEvents == 3000)
        #expect(config.bufferConfig.diskMb == 25)
        #expect(config.bufferConfig.retentionHours == 12)
        #expect(config.bufferConfig.strategy == "overwrite_oldest")
        #expect(config.workflows.isEmpty)
    }

    // MARK: - Fleet / backend (parsed, no fields)

    @Test("decodeFleetMatcherDoesNotFail")
    func decodeFleetMatcherDoesNotFail() throws {
        let matcher = try DSLMatcher.decode(fromJsonString: #"{"type": "fleet_threshold"}"#)
        guard case .fleetThreshold = matcher else {
            Issue.record("Expected .fleetThreshold, got \(matcher)")
            return
        }
    }

    @Test("decodeBackendMatcherDoesNotFail")
    func decodeBackendMatcherDoesNotFail() throws {
        let matcher = try DSLMatcher.decode(fromJsonString: #"{"type": "backend_health"}"#)
        guard case .backendHealth = matcher else {
            Issue.record("Expected .backendHealth, got \(matcher)")
            return
        }
    }

    @Test("decodeUnknownMatcherThrows")
    func decodeUnknownMatcherThrows() {
        #expect(throws: (any Error).self) {
            _ = try DSLMatcher.decode(fromJsonString: #"{"type": "totally_unknown_type_xyz"}"#)
        }
    }
}
