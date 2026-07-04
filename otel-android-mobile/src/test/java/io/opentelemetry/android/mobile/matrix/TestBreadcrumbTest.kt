// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Validates that test configuration metadata can be attached to events
 * as attributes, enabling traceability in Dash0 when E2E tests run.
 */
class TestBreadcrumbTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `events can carry test config breadcrumbs`() {
        val logger = otelRule.openTelemetry.logsBridge.get("breadcrumb-test")
        logger.logRecordBuilder()
            .setBody("ui.tap")
            .setSeverity(Severity.INFO)
            .setAllAttributes(Attributes.builder()
                .put(AttributeKey.stringKey("test.scenario"), "golden_journey")
                .put(AttributeKey.stringKey("test.export_mode"), "HYBRID")
                .put(AttributeKey.stringKey("test.filter_config"), "default")
                .put(AttributeKey.stringKey("test.run_id"), "matrix-2026-04-13-001")
                .build())
            .emit()

        val event = otelRule.logRecords.first()
        assertEquals("golden_journey", event.attributes[AttributeKey.stringKey("test.scenario")])
        assertEquals("HYBRID", event.attributes[AttributeKey.stringKey("test.export_mode")])
        assertEquals("default", event.attributes[AttributeKey.stringKey("test.filter_config")])
        assertEquals("matrix-2026-04-13-001", event.attributes[AttributeKey.stringKey("test.run_id")])
    }

    @Test fun `GoldenJourneyEmitter events carry session id as breadcrumb`() {
        val logger = otelRule.openTelemetry.logsBridge.get("breadcrumb-test")
        val tracer = otelRule.openTelemetry.getTracer("breadcrumb-test")
        GoldenJourneyEmitter(logger, tracer, sessionId = "test-run-xyz").emit()

        for (event in otelRule.logRecords) {
            assertEquals(
                "test-run-xyz",
                event.attributes[AttributeKey.stringKey("mobile.session.id")],
                "Event '${event.bodyValue?.asString()}' should carry test session ID"
            )
        }
    }
}
