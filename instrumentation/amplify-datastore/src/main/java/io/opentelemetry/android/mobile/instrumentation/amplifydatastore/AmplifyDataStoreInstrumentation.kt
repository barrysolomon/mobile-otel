// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import android.app.Application
import android.util.Log
import com.amplifyframework.core.Amplify
import com.amplifyframework.hub.HubChannel
import com.amplifyframework.hub.HubEvent
import com.amplifyframework.hub.SubscriptionToken
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext
import io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation
import io.opentelemetry.android.mobile.instrumentation.MobileSemconv
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongUpDownCounter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
        val context = ctx ?: return
        val networkAttrs = if (config.attachNetworkState) getNetworkAttributes(context.application) else emptyMap()
        val sessionId = context.sessionProvider.getSessionId()
        val screenName = context.sessionProvider.getCurrentScreenName() ?: "unknown"

        when (event.name) {
            "syncStarted" -> handleSyncStarted(networkAttrs, sessionId, screenName)
            "syncQueriesReady" -> handleSyncQueriesReady(networkAttrs, sessionId)
            "outboxMutationEnqueued" -> handleOutboxEnqueued(networkAttrs, sessionId, screenName)
            "outboxMutationProcessed" -> handleOutboxProcessed(event, networkAttrs, sessionId, screenName)
            "modelSynced" -> handleModelSynced(networkAttrs, sessionId)
            "networkStatusChanged" -> handleNetworkStatusChanged(networkAttrs, sessionId, screenName)
            "subscriptionEstablished" -> handleSubscriptionEstablished(networkAttrs, sessionId)
        }
    }

    // ── Sync span lifecycle ────────────────────────────────────────────────

    private fun handleSyncStarted(networkAttrs: Map<String, String>, sessionId: String, screenName: String) {
        if (!config.captureSyncSpans) return
        val t = tracer ?: return
        val context = ctx ?: return

        val span = t.spanBuilder(MobileSemconv.DATASTORE_SYNC)
            .setAttribute(MobileSemconv.SESSION_ID, sessionId)
            .setAttribute(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
            .setAttribute(MobileSemconv.SYNC_DIRECTION, "download")
            .startSpan()
        activeSyncSpan.set(span)
        syncStartTimeMs = android.os.SystemClock.elapsedRealtime()

        // Schedule timeout -- cancelled if sync completes normally
        syncTimeoutFuture?.cancel(false)
        syncTimeoutFuture = executor.schedule({
            activeSyncSpan.getAndSet(null)?.let { staleSpan ->
                staleSpan.setStatus(StatusCode.ERROR, "Sync timeout")
                staleSpan.end()
                val log = logger ?: return@let
                log.logRecordBuilder()
                    .setBody(MobileSemconv.DATASTORE_SYNC_FAILED)
                    .setSeverity(Severity.ERROR)
                    .setAllAttributes(
                        Attributes.builder()
                            .put(MobileSemconv.SESSION_ID, sessionId)
                            .put(MobileSemconv.ERROR_TYPE, "timeout")
                            .put(MobileSemconv.ERROR_MESSAGE, "Sync did not complete within ${config.syncTimeoutMs}ms")
                            .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                            .build()
                    )
                    .emit()
                syncFailureCount?.add(1)
                val currentScreen = ctx?.sessionProvider?.getCurrentScreenName() ?: "unknown"
                context.addBreadcrumb(
                    JourneyBreadcrumb.error(screen = currentScreen, errorType = "sync.failed", message = "Sync timeout after ${config.syncTimeoutMs}ms", attributes = networkAttrs)
                )
            }
        }, config.syncTimeoutMs, TimeUnit.MILLISECONDS)

        context.addBreadcrumb(
            JourneyBreadcrumb.custom(screen = screenName, action = "sync.started", attributes = networkAttrs)
        )
    }

    private fun handleSyncQueriesReady(networkAttrs: Map<String, String>, sessionId: String) {
        if (!config.captureSyncSpans) return
        syncTimeoutFuture?.cancel(false)
        syncTimeoutFuture = null
        activeSyncSpan.getAndSet(null)?.end()
        if (config.captureSyncMetrics && syncStartTimeMs > 0) {
            val duration = android.os.SystemClock.elapsedRealtime() - syncStartTimeMs
            syncLatency?.record(duration.toDouble())
            syncStartTimeMs = 0L
        }
    }

    // ── Outbox mutations ───────────────────────────────────────────────────

    private fun handleOutboxEnqueued(networkAttrs: Map<String, String>, sessionId: String, screenName: String) {
        if (!config.captureOutboxEvents) return
        val log = logger ?: return
        val context = ctx ?: return

        log.logRecordBuilder()
            .setBody(MobileSemconv.DATASTORE_OUTBOX_ENQUEUED)
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sessionId)
                    .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                    .build()
            )
            .emit()
        outboxDepth?.add(1)
        context.addBreadcrumb(
            JourneyBreadcrumb.custom(screen = screenName, action = "outbox.enqueued", attributes = networkAttrs)
        )
    }

    private fun handleOutboxProcessed(event: HubEvent<*>, networkAttrs: Map<String, String>, sessionId: String, screenName: String) {
        if (!config.captureOutboxEvents) return
        val log = logger ?: return
        val context = ctx ?: return

        val success = extractMutationSuccess(event)

        if (success) {
            log.logRecordBuilder()
                .setBody(MobileSemconv.DATASTORE_OUTBOX_PROCESSED)
                .setSeverity(Severity.INFO)
                .setAllAttributes(
                    Attributes.builder()
                        .put(MobileSemconv.SESSION_ID, sessionId)
                        .put(MobileSemconv.MUTATION_SUCCESS, true)
                        .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                        .build()
                )
                .emit()
        } else if (config.captureConflicts) {
            log.logRecordBuilder()
                .setBody(MobileSemconv.DATASTORE_OUTBOX_CONFLICT)
                .setSeverity(Severity.WARN)
                .setAllAttributes(
                    Attributes.builder()
                        .put(MobileSemconv.SESSION_ID, sessionId)
                        .put(MobileSemconv.MUTATION_SUCCESS, false)
                        .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                        .build()
                )
                .emit()
            context.addBreadcrumb(
                JourneyBreadcrumb.error(screen = screenName, errorType = "outbox.conflict", message = "Mutation conflict", attributes = networkAttrs)
            )
        }
        outboxDepth?.add(-1)
    }

    private fun extractMutationSuccess(event: HubEvent<*>): Boolean {
        val data = event.data ?: return true
        return data !is Throwable
    }

    // ── Remaining event handlers ───────────────────────────────────────────

    private fun handleModelSynced(networkAttrs: Map<String, String>, sessionId: String) {
        if (!config.captureSyncMetrics) return
        val log = logger ?: return

        log.logRecordBuilder()
            .setBody(MobileSemconv.DATASTORE_MODEL_SYNCED)
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sessionId)
                    .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                    .build()
            )
            .emit()
        syncSuccessCount?.add(1)
    }

    private fun handleNetworkStatusChanged(networkAttrs: Map<String, String>, sessionId: String, screenName: String) {
        if (!config.attachNetworkState) return
        val log = logger ?: return
        val context = ctx ?: return

        log.logRecordBuilder()
            .setBody(MobileSemconv.DATASTORE_NETWORK_CHANGED)
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sessionId)
                    .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                    .build()
            )
            .emit()
        context.addBreadcrumb(
            JourneyBreadcrumb.custom(screen = screenName, action = "network.changed", attributes = networkAttrs)
        )
    }

    private fun handleSubscriptionEstablished(networkAttrs: Map<String, String>, sessionId: String) {
        val log = logger ?: return

        log.logRecordBuilder()
            .setBody(MobileSemconv.DATASTORE_SUBSCRIPTION_EST)
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sessionId)
                    .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                    .build()
            )
            .emit()
    }

    // ── Network attributes helper ──────────────────────────────────────────

    @android.annotation.SuppressLint("MissingPermission")
    private fun getNetworkAttributes(application: Application): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        try {
            val cm = application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return attrs
            val network = cm.activeNetwork
            if (network == null) {
                attrs["network.type"] = "none"
                return attrs
            }
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null) {
                attrs["network.type"] = "unknown"
                return attrs
            }
            attrs["network.type"] = when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (_: SecurityException) {
            attrs["network.type"] = "unknown"
        }
        return attrs
    }

    companion object {
        private const val TAG = "AmplifyDataStoreInst"
    }
}
