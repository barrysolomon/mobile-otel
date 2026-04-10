/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.mobile.config.ExportMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for the MobileOtel.initialize(context) { } DSL overload.
 *
 * Verifies the full initialization path: DSL config → MobileConfig →
 * MobileLoggerProvider → OTelMobileBuilder → OpenTelemetryMobile.
 *
 * Run with:
 *   cd examples/demo-app && ./gradlew :otel-android-mobile:connectedDebugAndroidTest \
 *     --tests "*.DslIntegrationTest"
 */
@RunWith(AndroidJUnit4::class)
class DslIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MobileLoggerProvider.resetForTesting()
        MobileOtel.shutdown()
    }

    @After
    fun tearDown() {
        MobileOtel.shutdown()
        MobileLoggerProvider.resetForTesting()
    }

    @Test
    fun dslInitializeReturnsOpenTelemetryMobile() {
        val mobile = MobileOtel.initialize(context) {
            service {
                name = "dsl-integration-test"
                version = "1.0.0"
            }
            export {
                endpoint = "http://localhost:4317"
                mode = ExportMode.CONDITIONAL
            }
        }

        assertNotNull("initialize should return OpenTelemetryMobile", mobile)
        assertNotNull("openTelemetry should be set", mobile.openTelemetry)
        assertTrue("sessionId should not be empty", mobile.sessionId.isNotEmpty())
    }

    @Test
    fun dslInitializeStoresOnMobileOtel() {
        val mobile = MobileOtel.initialize(context) {
            service {
                name = "dsl-storage-test"
                version = "1.0.0"
            }
            export {
                endpoint = "http://localhost:4317"
            }
        }

        assertSame(
            "openTelemetryMobile should be stored on MobileOtel",
            mobile,
            MobileOtel.openTelemetryMobile
        )
    }

    @Test
    fun dslInitializeWithCustomizersDoesNotCrash() {
        val mobile = MobileOtel.initialize(context) {
            service {
                name = "customizer-dsl-test"
                version = "1.0.0"
            }
            export {
                endpoint = "http://localhost:4317"
            }
            exportCustomizers {
                log { exporter -> exporter }  // identity customizer
                span { exporter -> exporter }
            }
        }

        assertNotNull(mobile)
    }

    @Test
    fun dslTracerLoggerMeterAreUsable() {
        val mobile = MobileOtel.initialize(context) {
            service {
                name = "accessor-test"
                version = "1.0.0"
            }
            export {
                endpoint = "http://localhost:4317"
            }
        }

        assertNotNull("getTracer should return non-null", mobile.getTracer("test-scope"))
        assertNotNull("getLogger should return non-null", mobile.getLogger("test-scope"))
        assertNotNull("getMeter should return non-null", mobile.getMeter("test-scope"))
    }

    @Test
    fun shutdownCleansUpOpenTelemetryMobile() {
        MobileOtel.initialize(context) {
            service {
                name = "shutdown-test"
                version = "1.0.0"
            }
            export {
                endpoint = "http://localhost:4317"
            }
        }

        assertNotNull(MobileOtel.openTelemetryMobile)
        MobileOtel.shutdown()
        assertNull("openTelemetryMobile should be null after shutdown", MobileOtel.openTelemetryMobile)
    }
}
