/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.sampling

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.samplers.SamplingDecision
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that [SamplerFactory.createSampler] wires [SamplingConfig] correctly to the
 * underlying OTel samplers, and that numeric rates and bool flags take effect when
 * a sampling decision is made.
 *
 * These are end-to-end configuration → decision tests, not unit tests of individual
 * sampler classes. Each test changes exactly one config value and verifies the
 * observable effect on sampling outcomes.
 */
class SamplerFactoryConfigTest {

    private val noAttrs = Attributes.empty()
    private val noLinks = emptyList<io.opentelemetry.sdk.trace.data.LinkData>()
    private val rootCtx = Context.root()

    private fun decide(config: SamplingConfig, traceId: String = "a".repeat(32), name: String = "test-span") =
        SamplerFactory.createSampler(config)
            .shouldSample(rootCtx, traceId, name, SpanKind.INTERNAL, noAttrs, noLinks)
            .decision

    // ── ALWAYS_ON / ALWAYS_OFF ────────────────────────────────────────────────

    @Test
    fun `ALWAYS_ON strategy samples every span`() {
        val config = SamplingConfig.alwaysOn()
        repeat(20) { i ->
            assertEquals(SamplingDecision.RECORD_AND_SAMPLE, decide(config, "trace${"0".repeat(27)}$i"))
        }
    }

    @Test
    fun `ALWAYS_OFF strategy drops every span`() {
        val config = SamplingConfig.alwaysOff()
        repeat(20) { i ->
            assertEquals(SamplingDecision.DROP, decide(config, "trace${"0".repeat(27)}$i"))
        }
    }

    // ── TRACE_ID_RATIO ────────────────────────────────────────────────────────

    @Test
    fun `TRACE_ID_RATIO rate 1_0 samples all spans`() {
        val config = SamplingConfig.production(rate = 1.0)
        repeat(20) { i ->
            assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
                decide(config, "${i.toString(16).padStart(32, '0')}"))
        }
    }

    @Test
    fun `TRACE_ID_RATIO rate 0_0 drops all spans`() {
        val config = SamplingConfig.production(rate = 0.0)
        repeat(20) { i ->
            assertEquals(SamplingDecision.DROP,
                decide(config, "${i.toString(16).padStart(32, '0')}"))
        }
    }

    @Test
    fun `TRACE_ID_RATIO rate 0_5 produces deterministic decisions per trace ID`() {
        val config = SamplingConfig.production(rate = 0.5)
        val id = "deadbeef12345678abcdef0011223344"
        val first = decide(config, id)
        val second = decide(config, id)
        assertEquals("Same trace ID must always yield same decision", first, second)
    }

    // ── DYNAMIC ───────────────────────────────────────────────────────────────

    @Test
    fun `DYNAMIC samplingRate 0_0 drops all normal spans`() {
        val config = SamplingConfig.dynamic(normalRate = 0.0, highPriorityRate = 1.0)
        repeat(20) { i ->
            assertEquals(SamplingDecision.DROP,
                decide(config, "0000000000000000${i.toString(16).padStart(16, '0')}"))
        }
    }

    @Test
    fun `DYNAMIC samplingRate 1_0 samples all normal spans`() {
        val config = SamplingConfig.dynamic(normalRate = 1.0, highPriorityRate = 1.0)
        repeat(20) { i ->
            assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
                decide(config, "${i.toString(16).padStart(32, '0')}"))
        }
    }

    @Test
    fun `DYNAMIC highPriorityRate 1_0 samples page spans regardless of baseline rate`() {
        val config = SamplingConfig.dynamic(normalRate = 0.0, highPriorityRate = 1.0)
        val sampler = SamplerFactory.createSampler(config)

        listOf("page.HomeScreen", "page.LoginScreen", "app.startup").forEach { spanName ->
            val decision = sampler.shouldSample(rootCtx,
                "0".repeat(32), spanName, SpanKind.INTERNAL, noAttrs, noLinks).decision
            assertEquals("$spanName must always sample regardless of baseline rate",
                SamplingDecision.RECORD_AND_SAMPLE, decision)
        }
    }

    @Test
    fun `DYNAMIC highPriorityRate 0_0 drops page spans`() {
        val config = SamplingConfig.dynamic(normalRate = 0.0, highPriorityRate = 0.0)
        val decision = SamplerFactory.createSampler(config)
            .shouldSample(rootCtx, "0".repeat(32), "page.HomeScreen",
                SpanKind.INTERNAL, noAttrs, noLinks).decision
        assertEquals("page span must be dropped when highPriorityRate=0.0",
            SamplingDecision.DROP, decision)
    }

    @Test
    fun `DYNAMIC samplingRate change is reflected in subsequent decisions`() {
        val config = SamplingConfig.dynamic(normalRate = 0.0)
        val sampler = SamplerFactory.createSampler(config) as DynamicSampler

        // Initially 0% — all should drop
        repeat(10) { i ->
            assertEquals(SamplingDecision.DROP,
                sampler.shouldSample(rootCtx, i.toString(16).padStart(32, '0'),
                    "test", SpanKind.INTERNAL, noAttrs, noLinks).decision)
        }

        // Change to 100%
        sampler.setSamplingRate(1.0)
        repeat(10) { i ->
            assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
                sampler.shouldSample(rootCtx, i.toString(16).padStart(32, '0'),
                    "test", SpanKind.INTERNAL, noAttrs, noLinks).decision)
        }
    }

    @Test
    fun `DYNAMIC resetToBaseline reverts to original rate`() {
        val config = SamplingConfig.dynamic(normalRate = 0.1)
        val sampler = SamplerFactory.createSampler(config) as DynamicSampler

        sampler.setSamplingRate(1.0)
        assertEquals(1.0, sampler.getCurrentSamplingRate(), 0.001)

        sampler.resetToBaseline()
        assertEquals(0.1, sampler.getCurrentSamplingRate(), 0.001)
    }

    // ── PARENT_BASED ──────────────────────────────────────────────────────────

    @Test
    fun `PARENT_BASED with root rate 1_0 samples root spans`() {
        val config = SamplingConfig.parentBased(rootRate = 1.0)
        // For root spans (no parent), the root sampler is used
        repeat(10) { i ->
            assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
                decide(config, i.toString(16).padStart(32, '0')))
        }
    }

    @Test
    fun `PARENT_BASED with root rate 0_0 drops root spans`() {
        val config = SamplingConfig.parentBased(rootRate = 0.0)
        repeat(10) { i ->
            assertEquals(SamplingDecision.DROP,
                decide(config, i.toString(16).padStart(32, '0')))
        }
    }

    // ── SamplingConfig validation guards ─────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `samplingRate above 1_0 is rejected`() {
        SamplingConfig(samplingRate = 1.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `samplingRate below 0_0 is rejected`() {
        SamplingConfig(samplingRate = -0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `highPrioritySamplingRate above 1_0 is rejected`() {
        SamplingConfig(highPrioritySamplingRate = 1.1)
    }

    @Test
    fun `boundary values 0_0 and 1_0 are accepted`() {
        SamplingConfig(samplingRate = 0.0)
        SamplingConfig(samplingRate = 1.0)
        SamplingConfig(highPrioritySamplingRate = 0.0)
        SamplingConfig(highPrioritySamplingRate = 1.0)
    }
}
