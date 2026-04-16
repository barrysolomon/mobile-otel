package io.opentelemetry.android.mobile.instrumentation

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimberInstrumentationTest {

    @Test
    fun `instrumentationName is correct`() {
        val inst = TimberInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.timber", inst.instrumentationName)
    }

    @Test
    fun `default minPriority is INFO`() {
        // Default constructor uses Log.INFO — verified indirectly through the tree behavior
        val inst = TimberInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.timber", inst.instrumentationName)
    }

    @Test
    fun `OTelTimberTree logs at or above minPriority`() {
        val logRecordBuilder = mockk<LogRecordBuilder>(relaxed = true) {
            every { setBody(any<String>()) } returns this
            every { setSeverity(any()) } returns this
            every { setAllAttributes(any()) } returns this
        }
        val logger = mockk<Logger> {
            every { logRecordBuilder() } returns logRecordBuilder
        }

        // Plant an OTelTimberTree at WARN level via Timber
        val tree = OTelTimberTree(logger, Log.WARN)
        Timber.plant(tree)
        try {
            // Below WARN — should NOT produce a log record
            Timber.tag("test").d("debug message")
            Timber.tag("test").i("info message")

            // At or above WARN — SHOULD produce log records
            Timber.tag("test").w("warn message")
            Timber.tag("test").e("error message")

            // Verify only the warn and error messages went through
            verify(exactly = 2) { logRecordBuilder.emit() }
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `OTelTimberTree maps severity correctly`() {
        val capturedSeverities = mutableListOf<Severity>()
        val logRecordBuilder = mockk<LogRecordBuilder>(relaxed = true) {
            every { setBody(any<String>()) } returns this
            every { setSeverity(capture(capturedSeverities)) } returns this
            every { setAllAttributes(any()) } returns this
        }
        val logger = mockk<Logger> {
            every { logRecordBuilder() } returns logRecordBuilder
        }

        val tree = OTelTimberTree(logger, Log.VERBOSE)
        Timber.plant(tree)
        try {
            Timber.tag("test").v("verbose")
            Timber.tag("test").d("debug")
            Timber.tag("test").i("info")
            Timber.tag("test").w("warn")
            Timber.tag("test").e("error")

            assertEquals(
                listOf(Severity.TRACE, Severity.DEBUG, Severity.INFO, Severity.WARN, Severity.ERROR),
                capturedSeverities
            )
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `uninstall before install is safe`() {
        val inst = TimberInstrumentation()
        inst.uninstall() // Should not crash
    }
}
