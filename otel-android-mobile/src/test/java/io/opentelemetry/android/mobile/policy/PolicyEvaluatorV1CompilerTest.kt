/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for PolicyEvaluator's V1 compiler output parser.
 * Validates that graphToDSL.ts output can be parsed into PolicyConfig.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyEvaluatorV1CompilerTest {

    @Test
    fun `parseConfigV1Compiler parses crash trigger`() {
        val json = """
        {
          "version": 1,
          "limits": {"diskMb": 50, "ramEvents": 5000, "retentionHours": 24},
          "workflows": [{
            "id": "crash-handler",
            "enabled": true,
            "trigger": {"any": [{"event": "app.crash"}]},
            "actions": [{"type": "flush_window", "minutes": 5, "scope": "session"}]
          }]
        }
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV1Compiler(json)
        assertNotNull(config)
        assertEquals(1, config!!.policies.size)
        assertEquals("crash-handler", config.policies[0].id)
        assertEquals("app.crash", config.policies[0].match.attributes["event.name"]?.equals)
        assertEquals(5, config.policies[0].actions.flushWindowMinutes)
    }

    @Test
    fun `parseConfigV1Compiler parses where clause predicates`() {
        val json = """
        {
          "version": 1,
          "workflows": [{
            "id": "freeze-handler",
            "enabled": true,
            "trigger": {
              "all": [{"event": "ui.freeze", "where": [{"attr": "duration_ms", "op": ">", "value": 2000}]}]
            },
            "actions": [{"type": "flush_window", "minutes": 2}]
          }]
        }
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV1Compiler(json)
        assertNotNull(config)
        val policy = config!!.policies[0]
        assertEquals("ui.freeze", policy.match.attributes["event.name"]?.equals)
        assertEquals(2000.0, policy.match.attributes["duration_ms"]?.gt)
    }

    @Test
    fun `parseConfigV1Compiler parses multiple where clauses`() {
        val json = """
        {
          "version": 1,
          "workflows": [{
            "id": "http-error",
            "enabled": true,
            "trigger": {
              "all": [{
                "event": "http.error",
                "where": [
                  {"attr": "http.status_code", "op": ">=", "value": 500},
                  {"attr": "http.route", "op": "contains", "value": "/api/"}
                ]
              }]
            },
            "actions": [{"type": "flush_window", "minutes": 3}]
          }]
        }
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV1Compiler(json)
        assertNotNull(config)
        val policy = config!!.policies[0]
        assertEquals("http.error", policy.match.attributes["event.name"]?.equals)
        assertEquals(500.0, policy.match.attributes["http.status_code"]?.gte)
        assertEquals("/api/", policy.match.attributes["http.route"]?.contains)
    }

    @Test
    fun `parseConfigV1Compiler parses multiple trigger conditions`() {
        val json = """
        {
          "version": 1,
          "workflows": [{
            "id": "multi-trigger",
            "enabled": true,
            "trigger": {
              "any": [
                {"event": "app.crash"},
                {"event": "app.anr"}
              ]
            },
            "actions": [{"type": "flush_window", "minutes": 5}]
          }]
        }
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV1Compiler(json)
        assertNotNull(config)
        // Each trigger condition becomes its own policy
        assertEquals(2, config!!.policies.size)
        assertEquals("multi-trigger/0", config.policies[0].id)
        assertEquals("app.crash", config.policies[0].match.attributes["event.name"]?.equals)
        assertEquals("multi-trigger/1", config.policies[1].id)
        assertEquals("app.anr", config.policies[1].match.attributes["event.name"]?.equals)
    }

    @Test
    fun `parseConfigV1Compiler handles disabled workflows`() {
        val json = """
        {
          "version": 1,
          "workflows": [{
            "id": "disabled", "enabled": false,
            "trigger": {"any": [{"event": "app.crash"}]},
            "actions": [{"type": "flush_window", "minutes": 5}]
          }]
        }
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV1Compiler(json)
        assertNotNull(config)
        assertFalse(config!!.policies[0].enabled)
    }

    @Test
    fun `parseConfigV1Compiler handles malformed JSON gracefully`() {
        val config = PolicyEvaluator.parseConfigV1Compiler("not json")
        assertNull(config)
    }

    @Test
    fun `parseConfigAny auto-detects v1 compiler format`() {
        val v1Json = """
        {"version": 1, "workflows": [{"id": "test", "enabled": true,
          "trigger": {"any": [{"event": "app.crash"}]},
          "actions": [{"type": "flush_window", "minutes": 5}]}]}
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigAny(v1Json)
        assertNotNull(config)
        assertEquals("app.crash", config!!.policies[0].match.attributes["event.name"]?.equals)
    }

    @Test
    fun `parseConfigAny auto-detects v2 format`() {
        val v2Json = """
        {"version": 2, "buffer_config": {},
          "workflows": [{"id": "test", "enabled": true, "priority": 1,
            "initial_state": "default",
            "states": [{"id": "default",
              "matchers": [{"type": "crash", "config": {}}],
              "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 5}}]}}]}]}
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigAny(v2Json)
        assertNotNull(config)
        assertEquals("app.crash", config!!.policies[0].match.attributes["event.name"]?.equals)
    }

    // Legacy nodes format removed — only v1 compiler and v2 FSM formats supported

    @Test
    fun `parseConfigV1Compiler supports all predicate operators`() {
        val operators = listOf(
            Triple("==", "equals", "test-value"),
            Triple("!=", "notEquals", "test-value"),
            Triple(">", "gt", 100),
            Triple("<", "lt", 100),
            Triple(">=", "gte", 100),
            Triple("<=", "lte", 100),
            Triple("contains", "contains", "substr"),
            Triple("regex", "regex", ".*pattern.*")
        )

        for ((op, _, value) in operators) {
            val valueJson = if (value is String) "\"$value\"" else value.toString()
            val json = """
            {
              "version": 1,
              "workflows": [{
                "id": "op-test",
                "enabled": true,
                "trigger": {"any": [{"event": "test", "where": [{"attr": "field", "op": "$op", "value": $valueJson}]}]},
                "actions": [{"type": "flush_window", "minutes": 2}]
              }]
            }
            """.trimIndent()

            val config = PolicyEvaluator.parseConfigV1Compiler(json)
            assertNotNull("Failed to parse operator: $op", config)
            val cond = config!!.policies[0].match.attributes["field"]
            assertNotNull("Condition missing for operator: $op", cond)
        }
    }
}
