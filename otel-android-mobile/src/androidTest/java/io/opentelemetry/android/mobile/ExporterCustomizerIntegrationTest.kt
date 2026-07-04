/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.mobile.config.ExporterCustomizers
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Integration test verifying that exporter customizers are applied
 * to the OTLP exporter pipeline during SDK initialization.
 *
 * Run with:
 *   cd examples/demo-app && ./gradlew :otel-android-mobile:connectedDebugAndroidTest \
 *     --tests "*.ExporterCustomizerIntegrationTest"
 */
@RunWith(AndroidJUnit4::class)
class ExporterCustomizerIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset singleton for test isolation
        MobileLoggerProvider.resetForTesting()
    }

    @After
    fun tearDown() {
        MobileLoggerProvider.resetForTesting()
    }

    @Test
    fun logCustomizerIsCalledDuringExporterConstruction() {
        var customizerCalled = false

        val config = MobileConfig(
            serviceName = "customizer-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        val customizers = ExporterCustomizers(
            log = listOf { baseExporter ->
                customizerCalled = true
                // Wrap the base exporter — proves the customizer was invoked
                object : LogRecordExporter {
                    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                        return baseExporter.export(logs)
                    }
                    override fun flush(): CompletableResultCode = baseExporter.flush()
                    override fun shutdown(): CompletableResultCode = baseExporter.shutdown()
                }
            }
        )

        MobileLoggerProvider.getInstance(context, config, customizers)

        assertTrue("Log exporter customizer should be called during init", customizerCalled)
    }

    @Test
    fun multipleCustomizersChainInOrder() {
        val callOrder = CopyOnWriteArrayList<String>()

        val config = MobileConfig(
            serviceName = "chain-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        val customizers = ExporterCustomizers(
            log = listOf(
                { exporter -> callOrder.add("first"); exporter },
                { exporter -> callOrder.add("second"); exporter },
                { exporter -> callOrder.add("third"); exporter }
            )
        )

        MobileLoggerProvider.getInstance(context, config, customizers)

        assertEquals(
            "Customizers should be called in registration order",
            listOf("first", "second", "third"),
            callOrder.toList()
        )
    }
}
