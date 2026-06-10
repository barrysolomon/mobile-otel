/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.sampling

import io.opentelemetry.android.mobile.policy.RemoteGate
import io.opentelemetry.android.mobile.policy.SdkConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.samplers.SamplingDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the remote kill-switch / global-sampling integration in
 * [DynamicSampler.shouldSample]:
 *
 * - `enabled = false` is a hard DROP for ALL spans, including page/startup.
 * - the global `sample_rate` folds in as a CAP (min with the local rate), so it
 *   can only reduce volume, never raise it.
 * - the default open gate leaves prior behaviour untouched.
 */
class DynamicSamplerGateTest {

    private val noop = Attributes.empty()
    private val noLinks = emptyList<LinkData>()
    private val noParent = Context.root()

    private fun sample(sampler: DynamicSampler, traceId: String, name: String = "test-span") =
        sampler.shouldSample(noParent, traceId.padEnd(32, '0').take(32), name,
            SpanKind.INTERNAL, noop, noLinks)

    // ── enabled = false ⇒ hard DROP for everything ───────────────────────

    @Test
    fun `disabled gate drops normal spans`() {
        val gate = RemoteGate().apply { apply(SdkConfig(enabled = false)) }
        val sampler = DynamicSampler(baselineSamplingRate = 1.0, remoteGate = gate)
        // Even at baseline rate 1.0, a disabled SDK must drop.
        repeat(20) { i ->
            val r = sample(sampler, "00000000000000010000000000000000".replaceRange(0, 1, "%x".format(i % 16)))
            assertEquals(SamplingDecision.DROP, r.decision)
        }
    }

    @Test
    fun `disabled gate drops page spans (hard drop, no force-sample)`() {
        val gate = RemoteGate().apply { apply(SdkConfig(enabled = false)) }
        val sampler = DynamicSampler(baselineSamplingRate = 1.0, highPrioritySamplingRate = 1.0, remoteGate = gate)
        listOf("page.Home", "page.Cart", "app.startup").forEach { name ->
            val r = sample(sampler, "ffffffffffffffff0000000000000000", name)
            assertEquals("$name must hard-drop when SDK disabled", SamplingDecision.DROP, r.decision)
        }
    }

    @Test
    fun `disabled drop carries sdk_disabled attribute`() {
        val gate = RemoteGate().apply { apply(SdkConfig(enabled = false)) }
        val sampler = DynamicSampler(baselineSamplingRate = 1.0, remoteGate = gate)
        val r = sample(sampler, "0000000000000001ffffffffffffffff")
        assertTrue(r.attributes.get(AttributeKey.booleanKey("sampling.sdk_disabled")) == true)
    }

    // ── global rate folds in as a CAP ────────────────────────────────────

    @Test
    fun `global rate 0 drops all spans including page spans`() {
        val gate = RemoteGate().apply { apply(SdkConfig(enabled = true, sampleRate = 0.0)) }
        val sampler = DynamicSampler(baselineSamplingRate = 1.0, highPrioritySamplingRate = 1.0, remoteGate = gate)
        // Page spans are normally force-sampled, but a global cap of 0 must drop them.
        assertEquals(SamplingDecision.DROP, sample(sampler, "0000000000000001ff00000000000000", "page.Home").decision)
        assertEquals(SamplingDecision.DROP, sample(sampler, "0000000000000001ff00000000000000", "tap").decision)
    }

    @Test
    fun `global rate 1_0 leaves local decision untouched`() {
        val gate = RemoteGate() // default open: enabled, rate 1.0
        val sampler = DynamicSampler(baselineSamplingRate = 1.0, highPrioritySamplingRate = 1.0, remoteGate = gate)
        // Low-bit trace id at rate 1.0 → SAMPLE.
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
            sample(sampler, "00000000000000010000000000000000").decision)
        // Page span force-sampled.
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
            sample(sampler, "ffffffffffffffff0000000000000000", "page.Home").decision)
    }

    @Test
    fun `global rate caps a high local rate`() {
        // Local high-priority rate is 1.0; global cap is 0.5. A trace id whose
        // LOWER 64 bits are all-ones (ratio ~1.0 — the sampler keys on the
        // trailing 8 bytes) must DROP because the effective rate is
        // min(1.0, 0.5) = 0.5.
        val gate = RemoteGate().apply { apply(SdkConfig(enabled = true, sampleRate = 0.5)) }
        val sampler = DynamicSampler(baselineSamplingRate = 1.0, highPrioritySamplingRate = 1.0, remoteGate = gate)
        val r = sample(sampler, "0000000000000000ffffffffffffffff", "page.Home")
        assertEquals(SamplingDecision.DROP, r.decision)
        // The sampling.rate attribute reflects the capped rate.
        assertEquals(0.5, r.attributes.get(AttributeKey.doubleKey("sampling.rate"))!!, 0.0001)
    }

    @Test
    fun `global cap never raises a low local rate`() {
        // Local baseline 0.1, global 1.0 → min = 0.1, so a trace id whose lower
        // 64 bits are all-ones (ratio ~1.0) still drops.
        val gate = RemoteGate() // open, rate 1.0
        val sampler = DynamicSampler(baselineSamplingRate = 0.1, remoteGate = gate)
        val r = sample(sampler, "0000000000000000ffffffffffffffff")
        assertEquals(SamplingDecision.DROP, r.decision)
        assertEquals(0.1, r.attributes.get(AttributeKey.doubleKey("sampling.rate"))!!, 0.0001)
    }
}
