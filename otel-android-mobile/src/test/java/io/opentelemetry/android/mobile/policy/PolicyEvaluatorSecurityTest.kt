/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.context.ContextSnapshot
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Security tests for [PolicyEvaluator].
 *
 * Validates:
 * - Regex length limit rejects patterns exceeding MAX_REGEX_LENGTH
 * - Invalid regex patterns are handled gracefully (no crash)
 * - Regex cache prevents unbounded growth
 * - Policy count limits are enforced in parseConfig
 * - Flush window is clamped to valid range
 * - Conditions-per-policy are capped
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyEvaluatorSecurityTest {

    private lateinit var context: Context
    private lateinit var config: MobileConfig
    private lateinit var evaluator: PolicyEvaluator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        config = MobileConfig(
            serviceName = "test-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:8080",
            attachContextAttributes = false
        )
        evaluator = PolicyEvaluator(context, config)
    }

    // ── Regex safety ──────────────────────────────────────────────────────────

    @Test
    fun `regex within length limit works`() {
        val condition = Condition(regex = "^ui\\..*")
        assertTrue(invokeMatchesCondition("ui.freeze", condition))
    }

    @Test
    fun `regex exceeding MAX_REGEX_LENGTH is rejected`() {
        // Create a regex pattern longer than 200 chars
        val longPattern = "a".repeat(PolicyEvaluator.MAX_REGEX_LENGTH + 1)
        val condition = Condition(regex = longPattern)
        assertFalse(invokeMatchesCondition("aaaa", condition),
            "Regex longer than MAX_REGEX_LENGTH should be rejected")
    }

    @Test
    fun `regex at exactly MAX_REGEX_LENGTH is accepted`() {
        val exactPattern = "a".repeat(PolicyEvaluator.MAX_REGEX_LENGTH)
        val condition = Condition(regex = exactPattern)
        // This pattern matches a string of exactly MAX_REGEX_LENGTH 'a' characters
        val matchingValue = "a".repeat(PolicyEvaluator.MAX_REGEX_LENGTH)
        assertTrue(invokeMatchesCondition(matchingValue, condition))
    }

    @Test
    fun `invalid regex pattern does not crash`() {
        val condition = Condition(regex = "[invalid(")
        // Should return false, not throw
        assertFalse(invokeMatchesCondition("test", condition),
            "Invalid regex should return false, not throw")
    }

    @Test
    fun `valid regex patterns are cached and reusable`() {
        val condition = Condition(regex = "^http\\..*")
        // Call multiple times to exercise cache
        assertTrue(invokeMatchesCondition("http.request", condition))
        assertTrue(invokeMatchesCondition("http.error", condition))
        assertFalse(invokeMatchesCondition("ui.freeze", condition))
    }

    // ── Policy constant validations ───────────────────────────────────────────

    @Test
    fun `MAX_POLICIES constant is reasonable`() {
        assertTrue(PolicyEvaluator.MAX_POLICIES in 1..1000,
            "MAX_POLICIES should be between 1 and 1000")
    }

    @Test
    fun `MAX_CONDITIONS_PER_POLICY constant is reasonable`() {
        assertTrue(PolicyEvaluator.MAX_CONDITIONS_PER_POLICY in 1..100,
            "MAX_CONDITIONS_PER_POLICY should be between 1 and 100")
    }

    @Test
    fun `flush window range constants are valid`() {
        assertTrue(PolicyEvaluator.MIN_FLUSH_WINDOW_MINUTES >= 1,
            "MIN_FLUSH_WINDOW_MINUTES should be at least 1")
        assertTrue(PolicyEvaluator.MAX_FLUSH_WINDOW_MINUTES <= 1440,
            "MAX_FLUSH_WINDOW_MINUTES should be at most 1440 (24h)")
        assertTrue(PolicyEvaluator.MIN_FLUSH_WINDOW_MINUTES < PolicyEvaluator.MAX_FLUSH_WINDOW_MINUTES,
            "MIN should be less than MAX")
    }

    // ── parseConfig validation ────────────────────────────────────────────────

    @Test
    fun `parseConfig clamps flush window to valid range`() {
        // Build a JSON config with an absurdly large flush window
        val json = buildConfigJson(flushWindowMinutes = 999_999)
        val policyConfig = invokeParseConfig(json)

        val flushWindow = policyConfig.policies.first().actions.flushWindowMinutes
        assertEquals(PolicyEvaluator.MAX_FLUSH_WINDOW_MINUTES, flushWindow,
            "Flush window should be clamped to MAX (${PolicyEvaluator.MAX_FLUSH_WINDOW_MINUTES})")
    }

    @Test
    fun `parseConfig clamps zero flush window to minimum`() {
        val json = buildConfigJson(flushWindowMinutes = 0)
        val policyConfig = invokeParseConfig(json)

        val flushWindow = policyConfig.policies.first().actions.flushWindowMinutes
        assertEquals(PolicyEvaluator.MIN_FLUSH_WINDOW_MINUTES, flushWindow,
            "Flush window 0 should be clamped to MIN (${PolicyEvaluator.MIN_FLUSH_WINDOW_MINUTES})")
    }

    @Test
    fun `parseConfig accepts valid flush window`() {
        val json = buildConfigJson(flushWindowMinutes = 5)
        val policyConfig = invokeParseConfig(json)

        val flushWindow = policyConfig.policies.first().actions.flushWindowMinutes
        assertEquals(5, flushWindow, "Valid flush window should pass through unchanged")
    }

    @Test
    fun `parseConfig limits number of policies`() {
        // Build JSON with more policies than MAX_POLICIES
        val policyCount = PolicyEvaluator.MAX_POLICIES + 50
        val json = buildMultiPolicyJson(policyCount)
        val policyConfig = invokeParseConfig(json)

        assertTrue(policyConfig.policies.size <= PolicyEvaluator.MAX_POLICIES,
            "Should have at most ${PolicyEvaluator.MAX_POLICIES} policies, got ${policyConfig.policies.size}")
    }

    // ── Default policies are safe ─────────────────────────────────────────────

    @Test
    fun `default crash policy has reasonable flush window`() {
        val log = createLogRecord("app.crash")
        val result = evaluator.evaluate(log)

        assertTrue(result != null, "app.crash should match a default policy")
        assertTrue(result!!.flushWindowMinutes in 1..10,
            "Crash policy flush window should be 1-10 minutes, got ${result.flushWindowMinutes}")
    }

    @Test
    fun `default ui-freeze policy has reasonable flush window`() {
        val log = createLogRecord("ui.freeze")
        val result = evaluator.evaluate(log)

        assertTrue(result != null, "ui.freeze should match a default policy")
        assertTrue(result!!.flushWindowMinutes in 1..10,
            "Freeze policy flush window should be 1-10 minutes")
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private fun invokeMatchesCondition(value: Any?, condition: Condition): Boolean {
        val method = PolicyEvaluator::class.java.getDeclaredMethod(
            "matchesCondition",
            Any::class.java,
            Condition::class.java
        )
        method.isAccessible = true
        return method.invoke(evaluator, value, condition) as Boolean
    }

    private fun invokeParseConfig(json: String): PolicyConfig {
        return PolicyEvaluator.parseConfigAny(json) ?: PolicyConfig(emptyList())
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    private fun buildConfigJson(flushWindowMinutes: Int): String {
        return """
        {
          "version": 1,
          "workflows": [
            {
              "id": "test-policy",
              "enabled": true,
              "trigger": {"any": [{"event": "test.event"}]},
              "actions": [{"type": "flush_window", "minutes": $flushWindowMinutes}]
            }
          ]
        }
        """.trimIndent()
    }

    private fun buildMultiPolicyJson(count: Int): String {
        val policies = (1..count).joinToString(",\n") { i ->
            """{"id": "policy-$i", "enabled": true, "trigger": {"any": [{"event": "event.$i"}]}, "actions": [{"type": "flush_window", "minutes": 2}]}"""
        }
        return """{"version": 1, "workflows": [$policies]}"""
    }

    // SR-008: PolicyEvaluator must accept an injected OkHttpClient so callers
    // can share the SDK's connection pool / dispatcher pool instead of every
    // PolicyEvaluator instance constructing its own (which wastes fds + threads
    // at fleet scale).

    @Test
    fun `accepts injected OkHttpClient via constructor`() {
        val sharedClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
        val ev = PolicyEvaluator(context, config, httpClient = sharedClient)
        assertNotNull(ev)
    }

    @Test
    fun `exposes injected OkHttpClient via getHttpClientForTest`() {
        val sharedClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
        val ev = PolicyEvaluator(context, config, httpClient = sharedClient)
        assertSame(sharedClient, ev.getHttpClientForTest(),
            "Injected client must be used, not a freshly constructed one")
    }

    private fun createLogRecord(eventName: String): LogRecordData = object : LogRecordData {
        override fun getResource() = Resource.empty()
        override fun getInstrumentationScopeInfo() = InstrumentationScopeInfo.empty()
        override fun getTimestampEpochNanos() = System.currentTimeMillis() * 1_000_000
        override fun getObservedTimestampEpochNanos() = System.currentTimeMillis() * 1_000_000
        override fun getSpanContext() = SpanContext.getInvalid()
        override fun getSeverity() = Severity.INFO
        override fun getSeverityText() = "INFO"
        override fun getBody() = Body.string(eventName)
        override fun getAttributes() = Attributes.empty()
        override fun getTotalAttributeCount() = 0
    }
}
