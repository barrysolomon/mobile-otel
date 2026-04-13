// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import android.app.Application
import android.util.Log
import com.amplifyframework.core.Amplify
import com.amplifyframework.hub.HubChannel
import com.amplifyframework.hub.HubEvent
import com.amplifyframework.hub.SubscriptionToken
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext
import io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongUpDownCounter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicReference

@Incubating
class AmplifyDataStoreInstrumentation(
    private val config: AmplifyDataStoreConfig = AmplifyDataStoreConfig()
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.amplifydatastore"

    private var ctx: InstrumentationContext? = null
    private var tracer: Tracer? = null
    private var logger: Logger? = null

    // Metrics
    private var outboxDepth: LongUpDownCounter? = null
    private var syncSuccessCount: LongCounter? = null
    private var syncFailureCount: LongCounter? = null
    private var syncLatency: DoubleHistogram? = null

    // Thread-safe sync span tracking
    private val activeSyncSpan = AtomicReference<Span?>(null)
    @Volatile private var syncStartTimeMs: Long = 0L

    // Hub subscription lifecycle
    private var hubSubscriptionToken: SubscriptionToken? = null
    private var syncTimeoutFuture: ScheduledFuture<*>? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OTel-DataStoreSyncTimeout").apply { isDaemon = true }
    }

    /** Visible for testing -- true after successful install(). */
    internal val isInstalled: Boolean get() = hubSubscriptionToken != null

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return

        // Guard: Amplify must be on the classpath
        try {
            Class.forName("com.amplifyframework.hub.HubChannel")
        } catch (_: ClassNotFoundException) {
            Log.i(TAG, "Amplify not found on classpath -- skipping DataStore instrumentation")
            return
        }

        ctx = context
        tracer = context.tracer(instrumentationName)
        logger = context.logger(instrumentationName)

        val meter = context.meter(instrumentationName)
        outboxDepth = meter.upDownCounterBuilder("datastore.outbox.depth")
            .setDescription("Number of mutations queued in the outbox")
            .build()
        syncSuccessCount = meter.counterBuilder("datastore.sync.success.count")
            .setDescription("Number of successful model syncs")
            .build()
        syncFailureCount = meter.counterBuilder("datastore.sync.failure.count")
            .setDescription("Number of sync failures")
            .build()
        syncLatency = meter.histogramBuilder("datastore.sync.latency")
            .setDescription("Duration of sync cycles in milliseconds")
            .setUnit("ms")
            .build()

        // Subscribe to DataStore Hub events -- capture token for uninstall()
        hubSubscriptionToken = Amplify.Hub.subscribe(HubChannel.DATASTORE) { event ->
            try {
                handleHubEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "Error handling Hub event: ${event.name}", e)
            }
        }
    }

    override fun uninstall() {
        hubSubscriptionToken?.let { Amplify.Hub.unsubscribe(it) }
        hubSubscriptionToken = null
        activeSyncSpan.getAndSet(null)?.end()
        syncTimeoutFuture?.cancel(false)
        syncTimeoutFuture = null
        executor.shutdownNow()
        ctx = null
        tracer = null
        logger = null
    }

    private fun handleHubEvent(event: HubEvent<*>) {
        // Event handling added in Tasks 5-8
    }

    companion object {
        private const val TAG = "AmplifyDataStoreInst"
    }
}
