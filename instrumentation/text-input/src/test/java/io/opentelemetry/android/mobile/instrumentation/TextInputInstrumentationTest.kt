// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextInputInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeCtx(config: TextInputConfig = TextInputConfig()) =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), mockk<Application>(relaxed = true))

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.text_input", TextInputInstrumentation().instrumentationName)
    }

    @Test fun `registers as WindowEventListener on install`() {
        val app = mockk<Application>(relaxed = true)
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        TextInputInstrumentation().install(app, ctx)

        verify { hub.addListener(any()) }
    }

    @Test fun `uninstall removes WindowEventListener`() {
        val app = mockk<Application>(relaxed = true)
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = TextInputInstrumentation()
        inst.install(app, ctx)
        inst.uninstall()

        verify { hub.removeListener(any()) }
    }

    @Test fun `emitTextInput emits log record with correct body`() {
        val inst = TextInputInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())
        inst.emitTextInput(resourceId = "email_field", enabled = true, charCount = 5)

        assertTrue(otelRule.logRecords.any { it.bodyValue?.asString() == "ui.text_input" })
    }

    @Test fun `char_count and is_set emitted by default`() {
        val inst = TextInputInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())
        inst.emitTextInput(resourceId = "name_field", enabled = true, charCount = 8)

        val record = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.text_input" }
        assertEquals(8L, record.attributes[MobileSemconv.TEXT_CHAR_COUNT])
        assertEquals(true, record.attributes[MobileSemconv.TEXT_IS_SET])
    }

    @Test fun `is_set is false when charCount is 0`() {
        val inst = TextInputInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())
        inst.emitTextInput(resourceId = "name_field", enabled = true, charCount = 0)

        val record = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.text_input" }
        assertEquals(0L, record.attributes[MobileSemconv.TEXT_CHAR_COUNT])
        assertFalse(record.attributes[MobileSemconv.TEXT_IS_SET]!!)
    }

    @Test fun `text content not emitted by default`() {
        val inst = TextInputInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx())
        inst.emitTextInput(resourceId = "name_field", enabled = true, charCount = 5, text = "hello")

        val record = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.text_input" }
        assertNull(record.attributes[MobileSemconv.TEXT_CONTENT])
    }

    @Test fun `text content emitted when opted in and field is allowlisted`() {
        val config = TextInputConfig(captureTextContent = true, textContentAllowlist = setOf("search_field"))
        val inst = TextInputInstrumentation(config)
        inst.install(mockk(relaxed = true), makeCtx(config))
        inst.emitTextInput(resourceId = "search_field", enabled = true, charCount = 5, text = "shoes")

        val record = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.text_input" }
        assertEquals("shoes", record.attributes[MobileSemconv.TEXT_CONTENT])
    }

    @Test fun `text content not emitted for non-allowlisted field even when opted in`() {
        val config = TextInputConfig(captureTextContent = true, textContentAllowlist = setOf("search_field"))
        val inst = TextInputInstrumentation(config)
        inst.install(mockk(relaxed = true), makeCtx(config))
        inst.emitTextInput(resourceId = "password_field", enabled = true, charCount = 8, text = "secret")

        val record = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.text_input" }
        assertNull(record.attributes[MobileSemconv.TEXT_CONTENT])
    }

    @Test fun `char_count not emitted when captureCharCount is false`() {
        val config = TextInputConfig(captureCharCount = false, captureIsSet = false)
        val inst = TextInputInstrumentation(config)
        inst.install(mockk(relaxed = true), makeCtx(config))
        inst.emitTextInput(resourceId = "field", enabled = true, charCount = 3)

        val record = otelRule.logRecords.first { it.bodyValue?.asString() == "ui.text_input" }
        assertNull(record.attributes[MobileSemconv.TEXT_CHAR_COUNT])
        assertNull(record.attributes[MobileSemconv.TEXT_IS_SET])
    }
}
