/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.android.mobile.MobileLoggerProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Instrumentation must never crash the host app (roadmap N14).
 *
 * Thread construction can fail at runtime — most commonly
 * `OutOfMemoryError: unable to create new native thread` on a
 * thread-starved device. [TapCapture] and [FreezeDetector] build a
 * single-thread scheduler at construction time; if that throws, SDK
 * init would take the whole app down. These tests pin the contract:
 * construction survives a failing thread factory and the components
 * degrade gracefully (taps emit synchronously without coalescing;
 * freeze detection becomes a no-op).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThreadConstructionGuardTest {

    private val throwingFactory: () -> java.util.concurrent.ScheduledExecutorService = {
        throw OutOfMemoryError("unable to create new native thread")
    }

    private fun relaxedLogger(): Logger {
        val builder = mockk<LogRecordBuilder>(relaxed = true)
        return mockk<Logger>(relaxed = true) {
            every { logRecordBuilder() } returns builder
        }
    }

    // ── TapCapture ──────────────────────────────────────────────────────────

    @Test
    fun `TapCapture construction survives thread creation failure`() {
        TapCapture(
            logger = relaxedLogger(),
            tracer = mockk<Tracer>(relaxed = true),
            sessionTracker = SessionTracker(AutoCaptureOptions()),
            options = AutoCaptureOptions(),
            schedulerFactory = throwingFactory
        )
        // Reaching this line without an OutOfMemoryError IS the assertion.
    }

    @Test
    fun `TapCapture still emits taps synchronously when scheduler is unavailable`() {
        val logger = relaxedLogger()
        val capture = TapCapture(
            logger = logger,
            tracer = mockk<Tracer>(relaxed = true),
            sessionTracker = SessionTracker(AutoCaptureOptions()),
            options = AutoCaptureOptions(),
            schedulerFactory = throwingFactory
        )

        val window = Robolectric.buildActivity(Activity::class.java).setup().get().window
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, 10f, 10f, 0)
        capture.handleTouchEvent(window, down)
        capture.handleTouchEvent(window, up)
        down.recycle()
        up.recycle()

        // No active parent span in the test → the tap takes the log-record
        // path. With no scheduler the coalesce queue must flush immediately
        // instead of silently dropping the tap.
        verify(atLeast = 1) { logger.logRecordBuilder() }
    }

    @Test
    fun `TapCapture shutdown is safe when scheduler construction failed`() {
        val capture = TapCapture(
            logger = relaxedLogger(),
            tracer = mockk<Tracer>(relaxed = true),
            sessionTracker = SessionTracker(AutoCaptureOptions()),
            options = AutoCaptureOptions(),
            schedulerFactory = throwingFactory
        )
        capture.shutdown()
    }

    // ── FreezeDetector ──────────────────────────────────────────────────────

    @Test
    fun `FreezeDetector construction survives thread creation failure`() {
        FreezeDetector(
            logger = relaxedLogger(),
            provider = mockk<MobileLoggerProvider>(relaxed = true),
            sessionTracker = SessionTracker(AutoCaptureOptions()),
            options = AutoCaptureOptions(),
            schedulerFactory = throwingFactory
        )
    }

    @Test
    fun `FreezeDetector start and stop are no-ops when scheduler is unavailable`() {
        val detector = FreezeDetector(
            logger = relaxedLogger(),
            provider = mockk<MobileLoggerProvider>(relaxed = true),
            sessionTracker = SessionTracker(AutoCaptureOptions()),
            options = AutoCaptureOptions(),
            schedulerFactory = throwingFactory
        )
        detector.start()
        detector.stop()
    }
}
