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
 * Tests for PolicyEvaluator's V2 DSL parser (state-machine format).
 * Validates that graphToDSLv2.ts compiler output can be parsed into
 * the internal PolicyConfig model used by the evaluation engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyEvaluatorV2ParseTest {

    @Test
    fun `parseConfigV2 parses crash matcher into policy`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        assertEquals(1, config!!.policies.size)

        val policy = config.policies[0]
        assertEquals("crash-handler", policy.id)
        assertTrue(policy.enabled)
        assertEquals("app.crash", policy.match.attributes["event.name"]?.equals)
        assertEquals(5, policy.actions.flushWindowMinutes)
    }

    @Test
    fun `parseConfigV2 parses ui_freeze with duration threshold`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        val policy = config!!.policies[0]
        assertEquals("ui.freeze", policy.match.attributes["event.name"]?.equals)
        assertEquals(3000.0, policy.match.attributes["duration_ms"]?.gt)
        assertEquals(2, policy.actions.flushWindowMinutes)
    }

    @Test
    fun `parseConfigV2 parses http_match with status_min`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        val policy = config!!.policies[0]
        assertEquals("http.error", policy.match.attributes["event.name"]?.equals)
        assertEquals(500.0, policy.match.attributes["http.status_code"]?.gte)
    }

    @Test
    fun `parseConfigV2 parses exception_pattern with regex`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        val policy = config!!.policies[0]
        assertEquals("app.crash", policy.match.attributes["event.name"]?.equals)
        assertEquals("OutOfMemory", policy.match.attributes["exception.type"]?.contains)
        assertEquals(".*heap.*", policy.match.attributes["exception.message"]?.regex)
    }

    @Test
    fun `parseConfigV2 applies where clause predicates`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        val policy = config!!.policies[0]
        assertEquals("http.request", policy.match.attributes["event.name"]?.equals)
        assertEquals(500.0, policy.match.attributes["http.status_code"]?.gte)
        assertEquals("/api/", policy.match.attributes["http.route"]?.contains)
    }

    @Test
    fun `parseConfigV2 handles multi-state workflow`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        // Two states × 1 matcher each = 2 policies
        assertEquals(2, config!!.policies.size)
        assertEquals("multi-state/watching/0", config.policies[0].id)
        assertEquals("app.crash", config.policies[0].match.attributes["event.name"]?.equals)
        assertEquals(5, config.policies[0].actions.flushWindowMinutes)
        assertEquals("multi-state/recording/0", config.policies[1].id)
        assertEquals("app.anr", config.policies[1].match.attributes["event.name"]?.equals)
        assertEquals(10, config.policies[1].actions.flushWindowMinutes)
    }

    @Test
    fun `parseConfigV2 returns null for version 1`() {
        val json = """{"version": 1, "workflows": []}""".trimIndent()
        val config = PolicyEvaluator.parseConfigV2(json)
        assertNull(config)
    }

    @Test
    fun `parseConfigV2 handles malformed JSON gracefully`() {
        val config = PolicyEvaluator.parseConfigV2("not json at all")
        assertNull(config)
    }

    @Test
    fun `parseConfigV2 handles empty workflows`() {
        val json = """{"version": 2, "buffer_config": {}, "workflows": []}"""
        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        assertEquals(0, config!!.policies.size)
    }

    @Test
    fun `parseConfigV2 skips timeout matchers`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        // timeout should be skipped, crash should parse
        assertEquals(1, config!!.policies.size)
        assertEquals("app.crash", config.policies[0].match.attributes["event.name"]?.equals)
    }

    @Test
    fun `parseConfigV2 parses all device health matcher types`() {
        val types = listOf(
            "low_memory" to "device.low_memory",
            "battery_drain" to "device.battery_drain",
            "thermal_throttle" to "device.thermal_throttle",
            "storage_low" to "device.storage_low",
            "network_loss" to "network.loss",
            "network_restored" to "network.restored",
            "anr" to "app.anr"
        )

        for ((matcherType, expectedEvent) in types) {
            val json = """
            {
              "version": 2, "buffer_config": {},
              "workflows": [{
                "id": "test-$matcherType", "enabled": true, "priority": 1, "initial_state": "default",
                "states": [{
                  "id": "default",
                  "matchers": [{"type": "$matcherType", "config": {}}],
                  "on_match": {"actions": [{"type": "flush_buffer", "config": {"minutes": 2}}]}
                }]
              }]
            }
            """.trimIndent()

            val config = PolicyEvaluator.parseConfigV2(json)
            assertNotNull("Failed to parse matcher type: $matcherType", config)
            assertEquals(
                "Matcher $matcherType should map to event $expectedEvent",
                expectedEvent,
                config!!.policies[0].match.attributes["event.name"]?.equals
            )
        }
    }

    @Test
    fun `parseConfigV2 parses predictive_risk with min_score`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        val policy = config!!.policies[0]
        assertEquals("prediction.high_risk_alert", policy.match.attributes["event.name"]?.equals)
        assertEquals(0.8, policy.match.attributes["risk_score"]?.gte)
    }

    @Test
    fun `parseConfigV2 respects disabled workflows`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        assertEquals(1, config!!.policies.size)
        assertFalse(config.policies[0].enabled)
    }

    @Test
    fun `parseConfigV2 defaults flush window to 2 when no flush_buffer action`() {
        val json = """
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
        """.trimIndent()

        val config = PolicyEvaluator.parseConfigV2(json)
        assertNotNull(config)
        assertEquals(2, config!!.policies[0].actions.flushWindowMinutes)
    }
}
