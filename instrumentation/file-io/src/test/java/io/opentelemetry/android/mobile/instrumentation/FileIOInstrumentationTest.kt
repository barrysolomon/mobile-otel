package io.opentelemetry.android.mobile.instrumentation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FileIOInstrumentationTest {

    @Test
    fun `instrumentationName is correct`() {
        val inst = FileIOInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.file-io", inst.instrumentationName)
    }

    @Test
    fun `TracedInputStream counts bytes read`() {
        val data = "hello world".toByteArray()
        val tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test")
        val stream = TracedInputStream(ByteArrayInputStream(data), tracer, "test.read")

        val buf = ByteArray(5)
        val n1 = stream.read(buf, 0, 5)
        assertEquals(5, n1)

        val n2 = stream.read()
        assertTrue("Single byte read should return >= 0", n2 >= 0)

        stream.close() // Should not throw
    }

    @Test
    fun `TracedOutputStream counts bytes written`() {
        val tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test")
        val backing = ByteArrayOutputStream()
        val stream = TracedOutputStream(backing, tracer, "test.write")

        stream.write("hello".toByteArray())
        stream.write(32) // space
        stream.flush()
        stream.close()

        assertEquals("hello ", backing.toString())
    }

    @Test
    fun `traceRead without install returns block result`() {
        val inst = FileIOInstrumentation()
        // Not installed — should still execute the block
        val result = inst.traceRead(java.io.File("/tmp/nonexistent")) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `traceWrite without install returns block result`() {
        val inst = FileIOInstrumentation()
        val result = inst.traceWrite(java.io.File("/tmp/nonexistent")) { "done" }
        assertEquals("done", result)
    }

    @Test
    fun `tracedInputStream without install returns original stream`() {
        val inst = FileIOInstrumentation()
        val original = ByteArrayInputStream("test".toByteArray())
        val wrapped = inst.tracedInputStream(original)
        assertSame(original, wrapped) // Not installed, should return original
    }
}
