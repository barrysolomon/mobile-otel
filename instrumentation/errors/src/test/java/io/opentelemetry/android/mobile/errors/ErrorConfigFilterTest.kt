// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.errors

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ErrorConfig exception filtering — verifies that network I/O
 * exceptions are filtered by default and don't produce app.crash events.
 */
class ErrorConfigFilterTest {

    @Test
    fun `default config filters SocketTimeoutException`() {
        val config = ErrorConfig()
        assertTrue(config.shouldFilterException(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun `default config filters ConnectException`() {
        val config = ErrorConfig()
        assertTrue(config.shouldFilterException(java.net.ConnectException("Connection refused")))
    }

    @Test
    fun `default config filters UnknownHostException`() {
        val config = ErrorConfig()
        assertTrue(config.shouldFilterException(java.net.UnknownHostException("host.invalid")))
    }

    @Test
    fun `default config filters SocketException`() {
        val config = ErrorConfig()
        assertTrue(config.shouldFilterException(java.net.SocketException("Connection reset")))
    }

    @Test
    fun `default config filters SSLException`() {
        val config = ErrorConfig()
        assertTrue(config.shouldFilterException(javax.net.ssl.SSLException("handshake failed")))
    }

    @Test
    fun `default config filters InterruptedIOException`() {
        val config = ErrorConfig()
        assertTrue(config.shouldFilterException(java.io.InterruptedIOException("interrupted")))
    }

    @Test
    fun `default config does NOT filter RuntimeException`() {
        val config = ErrorConfig()
        assertFalse(config.shouldFilterException(RuntimeException("app crash")))
    }

    @Test
    fun `default config does NOT filter NullPointerException`() {
        val config = ErrorConfig()
        assertFalse(config.shouldFilterException(NullPointerException("npe")))
    }

    @Test
    fun `default config does NOT filter IllegalStateException`() {
        val config = ErrorConfig()
        assertFalse(config.shouldFilterException(IllegalStateException("bad state")))
    }

    @Test
    fun `default config does NOT filter OutOfMemoryError`() {
        val config = ErrorConfig()
        assertFalse(config.shouldFilterException(OutOfMemoryError("oom")))
    }

    @Test
    fun `empty filterExceptions allows all exceptions`() {
        val config = ErrorConfig(filterExceptions = emptyList())
        assertFalse(config.shouldFilterException(java.net.SocketTimeoutException("timeout")))
        assertFalse(config.shouldFilterException(RuntimeException("crash")))
    }

    @Test
    fun `custom filterExceptions overrides defaults`() {
        val config = ErrorConfig(filterExceptions = listOf("java.lang.ArithmeticException"))
        assertTrue(config.shouldFilterException(ArithmeticException("div by zero")))
        // Network exceptions NOT filtered when custom list replaces defaults
        assertFalse(config.shouldFilterException(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun `production config includes CancellationException filters`() {
        val config = ErrorConfig.production()
        assertTrue(config.shouldFilterException(
            java.util.concurrent.CancellationException("cancelled")))
    }

    @Test
    fun `disabled config filters everything`() {
        val config = ErrorConfig(enabled = false)
        assertTrue(config.shouldFilterException(RuntimeException("should be filtered")))
    }
}
