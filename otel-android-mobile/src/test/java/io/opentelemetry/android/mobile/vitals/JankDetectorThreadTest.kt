/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.vitals

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNull

/**
 * SR-022: JankDetector must not touch Choreographer at construction.
 *
 * `Choreographer.getInstance()` requires a thread with a Looper. If the
 * SDK constructs a JankDetector from a background thread (e.g. a worker
 * pool, a customization hook running off-main), the construct-time call
 * to `Choreographer.getInstance()` will throw IllegalStateException.
 *
 * Lazy init pushes that requirement to `startMonitoring()`, which the
 * SDK already guarantees runs on main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JankDetectorThreadTest {

    @Test
    fun `JankDetector createForTest does not touch Choreographer off-main`() {
        val executor = Executors.newSingleThreadExecutor()
        val thrown = AtomicReference<Throwable?>(null)
        executor.submit {
            try {
                // Construction itself must not crash even when the calling
                // thread has no Looper. Production initialize() runs on
                // main; this test exercises only the construct-time path
                // via a test seam — startMonitoring() stays main-thread.
                JankDetector.createForTest(VitalsConfig.default())
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertNull(thrown.get(),
            "Expected no exception from background construction, got ${thrown.get()}")
    }
}
