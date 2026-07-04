/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.opentelemetry.android.mobile.config.MobileConfig
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test: `OTelMobile.stop()` must be callable from ANY thread.
 *
 * Found by StartupBudgetTest's tearDown: stop() →
 * LifecycleInstrumentation.uninstall → LifecycleRegistry.removeObserver
 * threw `IllegalStateException: Method removeObserver must be called on the
 * main thread` when invoked off-main. Host apps legitimately call stop()
 * from shutdown paths on worker threads (and RN consumers call the SDK from
 * the JS bridge thread); an SDK teardown crashing the host violates the
 * prime directive in OTelMobile.start's own comments.
 */
@RunWith(AndroidJUnit4::class)
class StopThreadSafetyTest {

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            OTelMobile.stop(timeoutSeconds = 5)
        }
        MobileLoggerProvider.resetForTesting()
    }

    @Test
    fun stopFromBackgroundThreadDoesNotThrow() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            OTelMobile.start(
                application,
                MobileConfig(
                    serviceName = "stop-thread-safety-test",
                    serviceVersion = "1.0.0",
                    collectorEndpoint = "http://localhost:4317",
                    remoteConfigEnabled = false,
                ),
            )
        }

        var thrown: Throwable? = null
        val worker = Thread {
            try {
                OTelMobile.stop(timeoutSeconds = 5)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        worker.start()
        worker.join(30_000)

        assertTrue("worker thread must finish (stop() hung?)", !worker.isAlive)
        assertNull("stop() must not throw off the main thread (got: $thrown)", thrown)
    }
}
