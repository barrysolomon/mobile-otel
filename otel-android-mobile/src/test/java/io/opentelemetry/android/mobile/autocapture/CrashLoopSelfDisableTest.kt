/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.config.MobileConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring test for crash-loop self-disable: [MobileOtel.initialize] — the
 * single initialization floor both `OTelMobile.start` and any legacy caller
 * go through — must consult [CrashLoopDetector] BEFORE any SDK setup and
 * abort with [CrashLoopDisabledException] on a DISABLED verdict.
 * `OTelMobile.start`'s existing catch-all then leaves the SDK in its
 * documented degraded/no-op state (provider null, host unaffected).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrashLoopSelfDisableTest {

    private lateinit var application: Application
    private val prefs
        get() = application.getSharedPreferences("otel_recovery", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
    }

    @Test
    fun `initialize aborts before any setup when crash loop threshold is reached`() {
        prefs.edit().putBoolean("crash_marker", true).commit()
        val config = MobileConfig(
            serviceName = "crash-loop-test",
            serviceVersion = "0.0.0",
            collectorEndpoint = "https://unused.invalid",
            crashLoopThreshold = 1
        )

        assertThrows(CrashLoopDisabledException::class.java) {
            MobileOtel.initialize(application, config)
        }
        // The launch was still counted, so diagnostics can see it.
        assertEquals(1, CrashLoopDetector.consecutiveCrashCount(application))
    }
}
