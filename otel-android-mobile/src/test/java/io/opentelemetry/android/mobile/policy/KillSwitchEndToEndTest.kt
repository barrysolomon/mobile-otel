/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.android.mobile.buffering.DiskLogBuffer
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.sampling.DynamicSampler
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.trace.samplers.SamplingDecision
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kill-switch end-to-end proof (TEST_HARDENING_PLAN P0 #3).
 *
 * The README promises: remote config can disable the SDK fleet-wide via the
 * `sdk.enabled` block. This test proves the WHOLE chain in one place, with no
 * step stubbed out:
 *
 *   1. **Poll** — a real [PolicyEvaluator] fetches config over HTTP from a
 *      loopback MockWebServer (the same `fetchConfig` path production runs).
 *   2. **Parse + apply** — the `sdk` block lands in the shared [RemoteGate].
 *   3. **Choke points stop exporting** — with the gate disabled:
 *      - logs: [MobileLogRecordProcessor.onEmit] drops every record; a
 *        `forceFlush` joins with NOTHING reaching the exporter;
 *      - spans: [DynamicSampler.shouldSample] hard-DROPs.
 *
 * The re-enable half is just as important: it proves the pipeline was
 * functional and the *gate* was what stopped exports — i.e. this test can
 * actually fail. A disabled-only test would stay green if the whole export
 * path were broken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KillSwitchEndToEndTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var exporter: MockLogRecordExporter

    private val disabledConfigJson =
        """{"version":2,"sdk":{"enabled":false},"workflows":[]}"""
    private val enabledConfigJson =
        """{"version":2,"sdk":{"enabled":true,"sample_rate":1.0},"workflows":[]}"""

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        exporter = MockLogRecordExporter()
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.close()
        DiskLogBuffer.resetForTesting()
    }

    private fun makeConfig(): MobileConfig = MobileConfig(
        serviceName = "killswitch-e2e",
        serviceVersion = "1.0.0",
        // Loopback is exempt from HTTPS enforcement, so the poller really polls.
        collectorEndpoint = server.url("/").toString().removeSuffix("/"),
        configPollIntervalSeconds = 3600, // one initial fetch within the test window
        ramBufferSize = 100,
        diskBufferMb = 10,
        diskBufferTtlHours = 1,
    )

    private fun enqueue(body: String) {
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    private fun wrap(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    private fun buildProcessor(config: MobileConfig, gate: RemoteGate): MobileLogRecordProcessor =
        MobileLogRecordProcessor.builder(context)
            .setExporter(exporter)
            .setConfig(config)
            .setMeter(OpenTelemetry.noop().meterProvider.get("test"))
            .setRemoteGate(gate)
            .build()

    /** Poll until [predicate] holds or the timeout elapses. */
    private fun awaitUntil(timeoutMs: Long = 5000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    private fun sampleDecision(sampler: DynamicSampler, name: String): SamplingDecision =
        sampler.shouldSample(
            OtelContext.root(),
            "0".repeat(32),
            name,
            SpanKind.INTERNAL,
            Attributes.empty(),
            emptyList(),
        ).decision

    @Test
    fun `remote sdk_enabled=false stops both choke points, re-enable restores them`() {
        val gate = RemoteGate()
        val config = makeConfig()

        // ── Phase 1: poll a disabling config ────────────────────────────
        enqueue(disabledConfigJson)
        val evaluator = PolicyEvaluator(context, config, remoteGate = gate)
        try {
            assertTrue(
                awaitUntil { !gate.snapshot().enabled },
                "polled sdk.enabled=false must reach the RemoteGate (poll → parse → apply)",
            )

            // ── Phase 2: log choke point drops everything ───────────────
            val processor = buildProcessor(config, gate)
            try {
                repeat(5) { i ->
                    processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("blocked.$i")))
                }
                assertTrue(
                    processor.forceFlush().join(10, TimeUnit.SECONDS).isSuccess,
                    "forceFlush must settle",
                )
                assertEquals(
                    0,
                    exporter.exportedLogs.size,
                    "remotely-disabled SDK must export NOTHING (got ${exporter.exportedLogs.size} records)",
                )

                // ── Phase 3: span choke point hard-drops ────────────────
                // Even always-sampled high-priority span names drop when disabled.
                val sampler = DynamicSampler(1.0, 1.0, gate)
                assertEquals(SamplingDecision.DROP, sampleDecision(sampler, "page.HomeScreen"))
                assertEquals(SamplingDecision.DROP, sampleDecision(sampler, "app.startup"))

                // ── Phase 4: a fresh poll re-enables both choke points ──
                // Proves the pipeline was healthy and the GATE was the cause —
                // without this half, a broken export path would green Phase 2.
                enqueue(enabledConfigJson)
                val evaluator2 = PolicyEvaluator(context, config, remoteGate = gate)
                try {
                    assertTrue(
                        awaitUntil { gate.snapshot().enabled },
                        "polled sdk.enabled=true must re-open the RemoteGate",
                    )

                    repeat(5) { i ->
                        processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("allowed.$i")))
                    }
                    assertTrue(
                        processor.forceFlush().join(10, TimeUnit.SECONDS).isSuccess,
                        "forceFlush must settle",
                    )
                    assertEquals(
                        5,
                        exporter.exportedLogs.size,
                        "re-enabled SDK must export the 5 allowed records (and still none of the blocked ones)",
                    )
                    assertTrue(
                        exporter.exportedLogs.none { it.bodyValue?.asString()?.startsWith("blocked.") == true },
                        "records emitted while disabled must never surface later",
                    )

                    assertEquals(
                        SamplingDecision.RECORD_AND_SAMPLE,
                        sampleDecision(DynamicSampler(1.0, 1.0, gate), "page.HomeScreen"),
                    )
                } finally {
                    evaluator2.shutdown()
                }
            } finally {
                processor.shutdown()
            }
        } finally {
            evaluator.shutdown()
        }
    }

    @Test
    fun `disabled gate drops events at emit time, not at flush time`() {
        // Regression guard for the nastier failure mode: events buffered while
        // disabled must not sit in RAM/disk and leak out after a re-enable.
        val gate = RemoteGate()
        gate.apply(SdkConfig(enabled = false, sampleRate = 1.0))

        val processor = buildProcessor(makeConfig(), gate)
        try {
            repeat(3) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("buffered.$i")))
            }
            // Re-enable AFTER emission: anything that was merely "parked" would flush now.
            gate.apply(SdkConfig(enabled = true, sampleRate = 1.0))
            assertTrue(processor.forceFlush().join(10, TimeUnit.SECONDS).isSuccess)
            assertEquals(
                0,
                exporter.exportedLogs.size,
                "events emitted while disabled must be DROPPED, not parked for later export",
            )
            assertFalse(exporter.exportedLogs.any { it.bodyValue?.asString()?.startsWith("buffered.") == true })
        } finally {
            processor.shutdown()
        }
    }
}
