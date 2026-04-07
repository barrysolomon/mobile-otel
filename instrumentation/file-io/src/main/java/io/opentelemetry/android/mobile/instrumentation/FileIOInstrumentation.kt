// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Provides helpers for tracing file I/O operations as OTel spans.
 *
 * Unlike other instrumentation modules that auto-capture, File I/O requires
 * explicit wrapping because Java's file APIs don't have interceptor hooks.
 *
 * Usage:
 * ```kotlin
 * val fileIO = FileIOInstrumentation()
 * // After install...
 *
 * // Wrap a read operation
 * val data = fileIO.traceRead(file) { file.readBytes() }
 *
 * // Wrap a write operation
 * fileIO.traceWrite(file) { file.writeText("content") }
 *
 * // Wrap stream operations
 * val input = fileIO.tracedInputStream(FileInputStream(file))
 * val output = fileIO.tracedOutputStream(FileOutputStream(file))
 * ```
 */
@Incubating
class FileIOInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.file-io"

    private var tracer: Tracer? = null

    override fun install(application: Application, context: InstrumentationContext) {
        this.tracer = context.openTelemetry.getTracer(instrumentationName)
    }

    override fun uninstall() {
        tracer = null
    }

    /**
     * Traces a file read operation.
     *
     * @param file The file being read
     * @param block The read operation to trace
     * @return The result of [block]
     */
    fun <T> traceRead(file: File, block: () -> T): T {
        val t = tracer ?: return block()
        val span = t.spanBuilder("file.read")
            .setAllAttributes(fileAttributes(file, "read"))
            .startSpan()
        return try {
            val result = span.makeCurrent().use { block() }
            if (file.exists()) {
                span.setAttribute("file.size", file.length())
            }
            result
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.message ?: "file read error")
            throw e
        } finally {
            span.end()
        }
    }

    /**
     * Traces a file write operation.
     *
     * @param file The file being written
     * @param block The write operation to trace
     */
    fun <T> traceWrite(file: File, block: () -> T): T {
        val t = tracer ?: return block()
        val sizeBefore = if (file.exists()) file.length() else 0L
        val span = t.spanBuilder("file.write")
            .setAllAttributes(fileAttributes(file, "write"))
            .startSpan()
        return try {
            val result = span.makeCurrent().use { block() }
            val sizeAfter = if (file.exists()) file.length() else 0L
            span.setAttribute("file.size", sizeAfter)
            span.setAttribute("file.bytes_written", sizeAfter - sizeBefore)
            result
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.message ?: "file write error")
            throw e
        } finally {
            span.end()
        }
    }

    /**
     * Wraps an InputStream to trace total bytes read when closed.
     */
    fun tracedInputStream(stream: InputStream, name: String = "file.read.stream"): InputStream {
        val t = tracer ?: return stream
        return TracedInputStream(stream, t, name)
    }

    /**
     * Wraps an OutputStream to trace total bytes written when closed.
     */
    fun tracedOutputStream(stream: OutputStream, name: String = "file.write.stream"): OutputStream {
        val t = tracer ?: return stream
        return TracedOutputStream(stream, t, name)
    }

    private fun fileAttributes(file: File, operation: String): Attributes {
        return Attributes.of(
            AttributeKey.stringKey("file.name"), file.name,
            AttributeKey.stringKey("file.path"), file.parent ?: "",
            AttributeKey.stringKey("file.operation"), operation
        )
    }
}

/**
 * InputStream wrapper that creates a span covering the full read lifecycle.
 */
internal class TracedInputStream(
    private val delegate: InputStream,
    tracer: Tracer,
    spanName: String
) : InputStream() {

    private val span: Span = tracer.spanBuilder(spanName).startSpan()
    private var bytesRead: Long = 0

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }

    override fun close() {
        try {
            delegate.close()
            span.setAttribute("file.bytes_read", bytesRead)
        } finally {
            span.end()
        }
    }

    override fun available(): Int = delegate.available()
}

/**
 * OutputStream wrapper that creates a span covering the full write lifecycle.
 */
internal class TracedOutputStream(
    private val delegate: OutputStream,
    tracer: Tracer,
    spanName: String
) : OutputStream() {

    private val span: Span = tracer.spanBuilder(spanName).startSpan()
    private var bytesWritten: Long = 0

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        bytesWritten += len
    }

    override fun flush() = delegate.flush()

    override fun close() {
        try {
            delegate.flush()
            delegate.close()
            span.setAttribute("file.bytes_written", bytesWritten)
        } finally {
            span.end()
        }
    }
}
