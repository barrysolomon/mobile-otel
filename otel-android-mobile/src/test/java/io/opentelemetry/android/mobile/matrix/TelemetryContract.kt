// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.trace.data.SpanData
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

object TelemetryContract {

    fun assertEventSet(events: List<LogRecordData>, expectedBodies: List<String>) {
        val actualCounts = events.groupingBy { it.bodyValue?.asString() ?: "" }.eachCount()
        val expectedCounts = expectedBodies.groupingBy { it }.eachCount()
        for ((body, expectedCount) in expectedCounts) {
            val actualCount = actualCounts[body] ?: 0
            assertTrue(
                actualCount >= expectedCount,
                "Expected at least $expectedCount '$body' events, got $actualCount. All actual: ${actualCounts.keys.sorted()}"
            )
        }
    }

    fun assertUniversalAttributes(events: List<LogRecordData>) {
        for (event in events) {
            val body = event.bodyValue?.asString() ?: "?"
            val sessionId = event.attributes[AttributeKey.stringKey("mobile.session.id")]
            assertNotNull(sessionId, "Event '$body' missing mobile.session.id")
            assertTrue(event.observedTimestampEpochNanos > 0, "Event '$body' has zero observedTimestampEpochNanos")
        }
    }

    fun assertSpanParentage(spans: List<SpanData>, parentName: String, childName: String) {
        val parent = spans.firstOrNull { it.name == parentName }
        assertNotNull(parent, "Parent span '$parentName' not found")
        val child = spans.firstOrNull { it.name == childName }
        assertNotNull(child, "Child span '$childName' not found")
        assertEquals(
            parent.spanContext.spanId, child.parentSpanContext.spanId,
            "Span '$childName' should be child of '$parentName'"
        )
    }

    fun assertModeEquivalence(
        continuousLogs: List<LogRecordData>,
        conditionalLogs: List<LogRecordData>,
        hybridLogs: List<LogRecordData>
    ) {
        fun bodies(events: List<LogRecordData>) =
            events.map { it.bodyValue?.asString() ?: "" }
                .filter { it !in GoldenJourneyEmitter.HYBRID_ONLY_BODIES }
                .sorted()

        assertEquals(bodies(continuousLogs), bodies(conditionalLogs), "CONTINUOUS vs CONDITIONAL log bodies differ")
        assertEquals(bodies(continuousLogs), bodies(hybridLogs), "CONTINUOUS vs HYBRID log bodies differ")
    }

    fun assertNoDuplicates(events: List<LogRecordData>) {
        val fingerprints = events.map { "${it.bodyValue?.asString()}|${it.timestampEpochNanos}" }
        val dupes = fingerprints.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue(dupes.isEmpty(), "Duplicate events found: ${dupes.keys}")
    }

    fun assertOrdering(events: List<LogRecordData>, beforeBody: String, afterBody: String) {
        val beforeEvent = events.firstOrNull { it.bodyValue?.asString() == beforeBody }
        val afterEvent = events.firstOrNull { it.bodyValue?.asString() == afterBody }
        assertNotNull(beforeEvent, "Event '$beforeBody' not found")
        assertNotNull(afterEvent, "Event '$afterBody' not found")
        assertTrue(
            beforeEvent.timestampEpochNanos <= afterEvent.timestampEpochNanos,
            "'$beforeBody' should precede '$afterBody'"
        )
    }

    fun assertEventCount(events: List<LogRecordData>, body: String, expectedCount: Int) {
        val actual = events.count { it.bodyValue?.asString() == body }
        assertEquals(expectedCount, actual, "Expected $expectedCount '$body' events, got $actual")
    }

    fun assertEventAttribute(event: LogRecordData, attrKey: String, expectedValue: String) {
        val actual = event.attributes[AttributeKey.stringKey(attrKey)]
        assertEquals(expectedValue, actual, "Event '${event.bodyValue?.asString()}' attr '$attrKey'")
    }
}
