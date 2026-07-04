/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * PR-014: Multi-policy evaluation precedence tests.
 *
 * Locks down the contract: when multiple policies match the same event,
 * the first matching policy in list order wins (early return). This is the
 * defined precedence — list position is priority.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyEvaluatorPrecedenceTest {

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

    private fun setPolicies(policies: List<Policy>) {
        val field = PolicyEvaluator::class.java.getDeclaredField("policyConfig")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ref = field.get(evaluator) as AtomicReference<PolicyConfig?>
        ref.set(PolicyConfig(policies))
    }

    private fun createLogRecord(
        eventName: String,
        attributes: Attributes = Attributes.empty()
    ): LogRecordData {
        return object : LogRecordData {
            override fun getResource() = Resource.empty()
            override fun getInstrumentationScopeInfo() = InstrumentationScopeInfo.empty()
            override fun getTimestampEpochNanos() = System.currentTimeMillis() * 1_000_000
            override fun getObservedTimestampEpochNanos() = System.currentTimeMillis() * 1_000_000
            override fun getSpanContext() = SpanContext.getInvalid()
            override fun getSeverity() = Severity.INFO
            override fun getSeverityText() = "INFO"
            override fun getBody() = Body.string(eventName)
            override fun getAttributes() = attributes
            override fun getTotalAttributeCount() = attributes.size()
        }
    }

    @Test
    fun `first matching policy wins when multiple policies match`() {
        setPolicies(listOf(
            Policy("policy-A", true, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(2)),
            Policy("policy-B", true, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(10)),
        ))

        val result = evaluator.evaluate(createLogRecord("error.crash"))

        assertNotNull(result)
        assertEquals("policy-A", result.policyId, "First policy in list order must win")
        assertEquals(2, result.flushWindowMinutes)
    }

    @Test
    fun `second policy wins when first is disabled`() {
        setPolicies(listOf(
            Policy("policy-A", false, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(2)),
            Policy("policy-B", true, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(10)),
        ))

        val result = evaluator.evaluate(createLogRecord("error.crash"))

        assertNotNull(result)
        assertEquals("policy-B", result.policyId, "Disabled policy should be skipped")
        assertEquals(10, result.flushWindowMinutes)
    }

    @Test
    fun `no match when all policies are disabled`() {
        setPolicies(listOf(
            Policy("policy-A", false, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(2)),
            Policy("policy-B", false, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(10)),
        ))

        val result = evaluator.evaluate(createLogRecord("error.crash"))
        assertNull(result, "No match when all policies disabled")
    }

    @Test
    fun `broader policy matched first preempts narrower policy`() {
        setPolicies(listOf(
            Policy("broad", true, Match("and", mapOf("event.name" to Condition(contains = "error"))), Actions(5)),
            Policy("narrow", true, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(15)),
        ))

        val result = evaluator.evaluate(createLogRecord("error.crash"))

        assertNotNull(result)
        assertEquals("broad", result.policyId, "Broader policy listed first preempts narrower one")
    }

    @Test
    fun `narrower policy matched first takes priority over broader`() {
        setPolicies(listOf(
            Policy("narrow", true, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(15)),
            Policy("broad", true, Match("and", mapOf("event.name" to Condition(contains = "error"))), Actions(5)),
        ))

        val result = evaluator.evaluate(createLogRecord("error.crash"))

        assertNotNull(result)
        assertEquals("narrow", result.policyId, "List order determines priority, not specificity")
    }

    @Test
    fun `non-matching first policy skipped, second policy matches`() {
        setPolicies(listOf(
            Policy("freeze-only", true, Match("and", mapOf("event.name" to Condition(equals = "ui.freeze"))), Actions(2)),
            Policy("crash-handler", true, Match("and", mapOf("event.name" to Condition(equals = "error.crash"))), Actions(5)),
        ))

        val result = evaluator.evaluate(createLogRecord("error.crash"))

        assertNotNull(result)
        assertEquals("crash-handler", result.policyId)
    }

    @Test
    fun `empty policy list yields no match`() {
        setPolicies(emptyList())

        val result = evaluator.evaluate(createLogRecord("error.crash"))
        assertNull(result)
    }

    @Test
    fun `each policy has distinct flush window — correct one returned`() {
        setPolicies(listOf(
            Policy("p1", true, Match("and", mapOf("event.name" to Condition(equals = "a"))), Actions(1)),
            Policy("p2", true, Match("and", mapOf("event.name" to Condition(equals = "b"))), Actions(3)),
            Policy("p3", true, Match("and", mapOf("event.name" to Condition(equals = "c"))), Actions(7)),
        ))

        assertEquals(1, evaluator.evaluate(createLogRecord("a"))!!.flushWindowMinutes)
        assertEquals(3, evaluator.evaluate(createLogRecord("b"))!!.flushWindowMinutes)
        assertEquals(7, evaluator.evaluate(createLogRecord("c"))!!.flushWindowMinutes)
    }
}
