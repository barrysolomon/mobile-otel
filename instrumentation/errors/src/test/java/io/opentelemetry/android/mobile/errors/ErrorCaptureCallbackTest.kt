// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.errors

import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * UJ-004: verifies that `onErrorCaptured` fires after a successful error
 * capture so the host SDK can chain visual capture (screenshot + wireframe)
 * onto every recorded error.
 *
 * Contract:
 * - Fires only when the error was actually recorded (not when filtered or
 *   rate-limited or dedup-skipped).
 * - Receives the source string (`"uncaught"`, `"coroutine"`, `"manual"`,
 *   `"rxjava"`) so the consumer can encode it into the capture trigger
 *   attribute (e.g., `"error_uncaught"`).
 */
class ErrorCaptureCallbackTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @After
    fun tearDown() {
        ErrorInstrumentation.reset()
    }

    private fun init(
        config: ErrorConfig = ErrorConfig(),
        onErrorCaptured: ((source: String) -> Unit)? = null
    ): ErrorInstrumentation {
        ErrorInstrumentation.reset()
        val logger = otelRule.openTelemetry.logsBridge.get("test-errors")
        return ErrorInstrumentation.initialize(
            config = config,
            logger = logger,
            onErrorCaptured = onErrorCaptured
        )
    }

    @Test
    fun `callback fires on a captured error with the source`() {
        val captured = AtomicReference<String?>(null)
        val inst = init(onErrorCaptured = { source -> captured.set(source) })

        inst.captureException(RuntimeException("boom"), source = "manual")

        assertEquals("manual", captured.get())
    }

    @Test
    fun `callback receives the right source for each error pathway`() {
        val captured = mutableListOf<String>()
        val inst = init(onErrorCaptured = { source -> captured.add(source) })

        inst.captureException(RuntimeException("a"), source = "manual")
        inst.captureException(IllegalStateException("b"), source = "coroutine")
        inst.captureException(IllegalArgumentException("c"), source = "rxjava")

        assertEquals(listOf("manual", "coroutine", "rxjava"), captured)
    }

    @Test
    fun `callback does not fire when error is filtered`() {
        val captured = AtomicReference<String?>(null)
        val inst = init(onErrorCaptured = { source -> captured.set(source) })

        // SocketTimeoutException is in the default filter list — should be silently dropped.
        inst.captureException(java.net.SocketTimeoutException("timeout"), source = "uncaught")

        assertNull(captured.get())
    }
}
