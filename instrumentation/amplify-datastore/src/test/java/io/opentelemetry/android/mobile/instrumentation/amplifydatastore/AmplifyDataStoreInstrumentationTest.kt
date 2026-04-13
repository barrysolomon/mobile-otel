// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.amplifyframework.hub.HubEvent
import io.opentelemetry.android.mobile.instrumentation.DefaultMobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext
import io.opentelemetry.android.mobile.instrumentation.MobileSemconv
import io.opentelemetry.android.mobile.instrumentation.WindowEventHub
import io.opentelemetry.api.logs.Severity
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

    // ── Helper: fire a Hub event into the instrumentation ──────────────────

    private fun fireHubEvent(inst: AmplifyDataStoreInstrumentation, eventName: String, data: Any? = null) {
        val event = HubEvent.create(eventName, data ?: "")
        val method = AmplifyDataStoreInstrumentation::class.java.getDeclaredMethod(
            "handleHubEvent", HubEvent::class.java
        )
        method.isAccessible = true
        method.invoke(inst, event)
    }

    // ── Sync span lifecycle ────────────────────────────────────────────────

    @Test fun `syncStarted creates span`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")
        // End the span so it shows up in otelRule.spans
        fireHubEvent(inst, "syncQueriesReady")
        val spans = otelRule.spans
        assertTrue(spans.any { it.name == MobileSemconv.DATASTORE_SYNC }, "syncStarted should create a datastore.sync span")
    }

    @Test fun `syncQueriesReady ends span`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")
        val syncSpan = otelRule.spans.first { it.name == MobileSemconv.DATASTORE_SYNC }
        assertTrue(syncSpan.hasEnded(), "Span should be ended after syncQueriesReady")
    }

    @Test fun `syncQueriesReady without syncStarted is safe`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncQueriesReady")
        assertTrue(otelRule.spans.isEmpty())
    }

    @Test fun `multiple syncs create separate spans`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")
        val syncSpans = otelRule.spans.filter { it.name == MobileSemconv.DATASTORE_SYNC }
        assertTrue(syncSpans.size == 2, "Should have 2 separate sync spans")
    }

    @Test fun `captureSyncSpans=false suppresses spans`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(captureSyncSpans = false))
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")
        assertTrue(otelRule.spans.isEmpty())
    }

    @Test fun `sync span has session id`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")
        val syncSpan = otelRule.spans.first { it.name == MobileSemconv.DATASTORE_SYNC }
        val sessionId = syncSpan.attributes[MobileSemconv.SESSION_ID]
        assertTrue(sessionId != null && sessionId.isNotEmpty())
    }

    // ── Outbox mutations ───────────────────────────────────────────────────

    @Test fun `outboxMutationEnqueued emits log`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        val logs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_OUTBOX_ENQUEUED }
        assertTrue(logs.size == 1)
        assertTrue(logs[0].severity == Severity.INFO)
    }

    @Test fun `outboxMutationProcessed emits log`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationProcessed")
        val logs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_OUTBOX_PROCESSED }
        assertTrue(logs.size == 1)
    }

    @Test fun `enqueue and process cycle counts correctly`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationProcessed")
        fireHubEvent(inst, "outboxMutationProcessed")
        val enqueued = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_OUTBOX_ENQUEUED }
        val processed = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_OUTBOX_PROCESSED }
        assertTrue(enqueued.size == 3)
        assertTrue(processed.size == 2)
    }

    @Test fun `captureOutboxEvents=false suppresses outbox`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(captureOutboxEvents = false))
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationProcessed")
        val outboxLogs = otelRule.logRecords.filter { it.bodyValue?.asString()?.startsWith("datastore.outbox") == true }
        assertTrue(outboxLogs.isEmpty())
    }

    @Test fun `outbox events include session id`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        val log = otelRule.logRecords.first { it.bodyValue?.asString() == MobileSemconv.DATASTORE_OUTBOX_ENQUEUED }
        val sessionId = log.attributes[MobileSemconv.SESSION_ID]
        assertTrue(sessionId != null && sessionId.isNotEmpty())
    }

    // ── Model synced ───────────────────────────────────────────────────────

    @Test fun `modelSynced emits log`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "modelSynced")
        val logs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_MODEL_SYNCED }
        assertTrue(logs.size == 1 && logs[0].severity == Severity.INFO)
    }

    @Test fun `captureSyncMetrics=false suppresses modelSynced`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(captureSyncMetrics = false))
        fireHubEvent(inst, "modelSynced")
        assertTrue(otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_MODEL_SYNCED }.isEmpty())
    }

    // ── Network status changed ─────────────────────────────────────────────

    @Test fun `networkStatusChanged emits log`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "networkStatusChanged")
        val logs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_NETWORK_CHANGED }
        assertTrue(logs.size == 1 && logs[0].severity == Severity.INFO)
    }

    @Test fun `attachNetworkState=false suppresses networkStatusChanged`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(attachNetworkState = false))
        fireHubEvent(inst, "networkStatusChanged")
        assertTrue(otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_NETWORK_CHANGED }.isEmpty())
    }

    // ── Subscription established ───────────────────────────────────────────

    @Test fun `subscriptionEstablished emits log`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "subscriptionEstablished")
        val logs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_SUBSCRIPTION_EST }
        assertTrue(logs.size == 1 && logs[0].severity == Severity.INFO)
    }

    // ── Cross-cutting: attributes on all signals ───────────────────────────

    @Test fun `network type attached to all signals`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "modelSynced")
        fireHubEvent(inst, "subscriptionEstablished")
        for (log in otelRule.logRecords) {
            val networkType = log.attributes[MobileSemconv.NETWORK_TYPE]
            assertTrue(networkType != null, "Log '${log.bodyValue?.asString()}' should have network.type")
        }
    }

    @Test fun `session id attached to all signals`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "modelSynced")
        fireHubEvent(inst, "subscriptionEstablished")
        for (log in otelRule.logRecords) {
            val sessionId = log.attributes[MobileSemconv.SESSION_ID]
            assertTrue(sessionId != null && sessionId.isNotEmpty(), "Log '${log.bodyValue?.asString()}' should have session.id")
        }
    }

    // ── Sync timeout ───────────────────────────────────────────────────────

    @Test
    fun `sync timeout emits failure after configured delay`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(syncTimeoutMs = 100))
        fireHubEvent(inst, "syncStarted")
        Thread.sleep(200)
        val failLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_SYNC_FAILED }
        assertTrue(failLogs.size == 1, "Timeout should emit exactly one sync.failed")
        assertTrue(failLogs[0].severity == Severity.ERROR)
        val errorType = failLogs[0].attributes[MobileSemconv.ERROR_TYPE]
        assertTrue(errorType == "timeout", "Error type should be 'timeout'")
    }

    @Test
    fun `syncQueriesReady cancels timeout - no failure emitted`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(syncTimeoutMs = 200))
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")
        Thread.sleep(300)
        val failLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_SYNC_FAILED }
        assertTrue(failLogs.isEmpty(), "No failure after successful sync completion")
    }

    @Test
    fun `timeout and completion race is safe`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(syncTimeoutMs = 50))
        fireHubEvent(inst, "syncStarted")
        Thread.sleep(40)
        fireHubEvent(inst, "syncQueriesReady")
        Thread.sleep(100)
        val spans = otelRule.spans.filter { it.name == MobileSemconv.DATASTORE_SYNC }
        assertTrue(spans.size == 1, "Exactly one sync span")
        assertTrue(spans[0].hasEnded(), "Span should be ended")
    }

    @Test
    fun `syncTimeoutMs configures timeout duration`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(syncTimeoutMs = 500))
        fireHubEvent(inst, "syncStarted")
        Thread.sleep(200)
        val failLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.DATASTORE_SYNC_FAILED }
        assertTrue(failLogs.isEmpty(), "Should not timeout yet at 200ms with 500ms timeout")
        fireHubEvent(inst, "syncQueriesReady")
    }

    // ── Uninstall cleanup ──────────────────────────────────────────────────

    @Test
    fun `uninstall ends active span`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")
        inst.uninstall()
        activeInst = null
        val spans = otelRule.spans.filter { it.name == MobileSemconv.DATASTORE_SYNC }
        assertTrue(spans.isNotEmpty() && spans[0].hasEnded(), "Uninstall should end active span")
    }
}
