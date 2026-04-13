// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.instrumentation.DefaultMobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext
import io.opentelemetry.android.mobile.instrumentation.WindowEventHub
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AmplifyDataStoreInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private var activeInst: AmplifyDataStoreInstrumentation? = null

    private fun realApp(): Application = ApplicationProvider.getApplicationContext()

    private fun makeCtx(app: Application = realApp()) =
        InstrumentationContext(
            otelRule.openTelemetry,
            DefaultMobileSessionProvider(),
            WindowEventHub(),
            app
        )

    private fun installAndTrack(
        config: AmplifyDataStoreConfig = AmplifyDataStoreConfig()
    ): AmplifyDataStoreInstrumentation {
        val inst = AmplifyDataStoreInstrumentation(config)
        val app = realApp()
        inst.install(app, makeCtx(app))
        activeInst = inst
        return inst
    }

    @After
    fun tearDown() {
        activeInst?.uninstall()
        activeInst = null
    }

    @Test
    fun `instrumentationName is correct`() {
        val inst = AmplifyDataStoreInstrumentation()
        assertTrue(inst.instrumentationName == "io.opentelemetry.android.mobile.amplifydatastore")
    }

    @Test
    fun `config enabled=false is no-op`() {
        val inst = AmplifyDataStoreInstrumentation(AmplifyDataStoreConfig(enabled = false))
        inst.install(realApp(), makeCtx())
        activeInst = inst
        assertFalse(inst.isInstalled)
    }

    @Test
    fun `install with Amplify on classpath succeeds`() {
        val inst = installAndTrack()
        assertTrue(inst.isInstalled)
    }

    @Test
    fun `uninstall cleans up state`() {
        val inst = installAndTrack()
        assertTrue(inst.isInstalled)
        inst.uninstall()
        assertFalse(inst.isInstalled)
    }
}
