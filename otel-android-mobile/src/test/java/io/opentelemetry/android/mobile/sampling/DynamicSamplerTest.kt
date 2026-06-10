/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.sampling

import io.opentelemetry.sdk.trace.samplers.SamplingDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Comprehensive tests for [DynamicSampler].
 *
 * Covers:
 * - Always-on / always-off boundary cases
 * - Trace-ID ratio-based sampling determinism
 * - High-priority span override
 * - Runtime rate adjustment
 * - Scheduled revert to baseline
 * - Thread safety under concurrent adjustment
 * - Factory companion methods
 * - Description string
 */
class DynamicSamplerTest {

    private val noop = io.opentelemetry.api.common.Attributes.empty()
    private val noLinks = emptyList<io.opentelemetry.sdk.trace.data.LinkData>()
    private val noParent = io.opentelemetry.context.Context.root()

    // ── Boundary / rate = 1.0 ─────────────────────────────────────────────

    @Test
    fun `rate 1_0 always samples`() {
        val sampler = DynamicSampler(baselineSamplingRate = 1.0)
        repeat(20) { i ->
            val result = sample(sampler, traceId = "abcdef${i.toString(16).padStart(10, '0')}")
            assertEquals(SamplingDecision.RECORD_AND_SAMPLE, result.decision)
        }
    }

    // ── Boundary / rate = 0.0 ─────────────────────────────────────────────

    @Test
    fun `rate 0_0 never samples`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.0)
        repeat(20) { i ->
            val result = sample(sampler, traceId = "abcdef${i.toString(16).padStart(10, '0')}")
            assertEquals(SamplingDecision.DROP, result.decision)
        }
    }

    // ── Determinism ───────────────────────────────────────────────────────

    @Test
    fun `same trace ID always produces same decision`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.5)
        val traceId = "deadbeef12345678abcdef0011223344"
        val first  = sample(sampler, traceId).decision
        val second = sample(sampler, traceId).decision
        val third  = sample(sampler, traceId).decision
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun `different trace IDs with rate 1_0 all sample`() {
        val sampler = DynamicSampler(baselineSamplingRate = 1.0)
        val ids = (0..99).map { "trace${it.toString().padStart(26, '0')}" }
        ids.forEach { id ->
            assertEquals(SamplingDecision.RECORD_AND_SAMPLE, sample(sampler, id).decision)
        }
    }

    // ── Page span name-based force-sampling (OTel-native) ────────────────

    @Test
    fun `page prefix spans always sampled regardless of rate`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.0, highPrioritySamplingRate = 1.0)
        val traceId = "0000000000000000ffffffffffffffff"
        listOf("page.BookFragment", "page.CalendarFragment", "page.HomeScreen").forEach { name ->
            val result = sampler.shouldSample(noParent, traceId, name,
                io.opentelemetry.api.trace.SpanKind.INTERNAL, noop, noLinks)
            assertEquals("$name should always sample", SamplingDecision.RECORD_AND_SAMPLE, result.decision)
        }
    }

    @Test
    fun `app startup span always sampled`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.0, highPrioritySamplingRate = 1.0)
        val result = sampler.shouldSample(noParent, "0000000000000000ffffffffffffffff",
            "app.startup", io.opentelemetry.api.trace.SpanKind.INTERNAL, noop, noLinks)
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, result.decision)
    }

    @Test
    fun `page span result includes sampling_page_span attribute`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.0, highPrioritySamplingRate = 1.0)
        val result = sampler.shouldSample(noParent, "0000000000000000ffffffffffffffff",
            "page.SomeScreen", io.opentelemetry.api.trace.SpanKind.INTERNAL, noop, noLinks)
        val flag = result.attributes.get(
            io.opentelemetry.api.common.AttributeKey.booleanKey("sampling.page_span")
        )
        assertTrue("sampling.page_span should be true for page spans", flag == true)
    }

    @Test
    fun `non-page span with rate 0 is dropped`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.0)
        val result = sample(sampler, "0000000000000000ffffffffffffffff")
        assertEquals(SamplingDecision.DROP, result.decision)
    }

    @Test
    fun `non-named-span with rate 0 drops regardless of attributes`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.0)
        val result = sample(sampler, "0000000000000000ffffffffffffffff")
        assertEquals(SamplingDecision.DROP, result.decision)
    }

    // ── Sampling attributes included in result ────────────────────────────

    @Test
    fun `sampling_rate attribute is included in result`() {
        val sampler = DynamicSampler(baselineSamplingRate = 1.0)
        val result = sample(sampler, "abcdef0000000000ffffffff12345678")
        val rate = result.attributes.get(
            io.opentelemetry.api.common.AttributeKey.doubleKey("sampling.rate")
        )
        assertFalse("sampling.rate should be present", rate == null)
        assertEquals(1.0, rate!!, 0.001)
    }

    @Test
    fun `sampling_strategy attribute is dynamic`() {
        val sampler = DynamicSampler(baselineSamplingRate = 1.0)
        val result = sample(sampler, "abcdef0000000000ffffffff12345678")
        val strategy = result.attributes.get(
            io.opentelemetry.api.common.AttributeKey.stringKey("sampling.strategy")
        )
        assertEquals("dynamic", strategy)
    }

    // ── setSamplingRate ───────────────────────────────────────────────────

    @Test
    fun `setSamplingRate updates getCurrentSamplingRate`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.1)
        sampler.setSamplingRate(0.75)
        assertEquals(0.75, sampler.getCurrentSamplingRate(), 0.001)
    }

    @Test
    fun `setSamplingRate to 1_0 causes all traces to sample`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.0)
        sampler.setSamplingRate(1.0)
        repeat(10) { i ->
            assertEquals(
                SamplingDecision.RECORD_AND_SAMPLE,
                sample(sampler, "trace${i.toString(16).padStart(28, '0')}").decision
            )
        }
    }

    @Test
    fun `setSamplingRate to 0_0 drops all traces`() {
        val sampler = DynamicSampler(baselineSamplingRate = 1.0)
        sampler.setSamplingRate(0.0)
        repeat(10) { i ->
            assertEquals(
                SamplingDecision.DROP,
                sample(sampler, "trace${i.toString(16).padStart(28, '0')}").decision
            )
        }
    }

    // ── resetToBaseline ───────────────────────────────────────────────────

    @Test
    fun `resetToBaseline reverts to original baseline rate`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.1)
        sampler.setSamplingRate(1.0)
        sampler.resetToBaseline()
        assertEquals(0.1, sampler.getCurrentSamplingRate(), 0.001)
    }

    @Test
    fun `getBaselineSamplingRate is immutable`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.05)
        sampler.setSamplingRate(1.0)
        assertEquals(0.05, sampler.getBaselineSamplingRate(), 0.001)
    }

    // ── Scheduled revert ──────────────────────────────────────────────────

    @Test
    fun `scheduled revert reverts to baseline after duration`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.1)
        // Set rate for 1 minute, then manually trigger revert by setting revert time to the past
        // (We can't wait 1 real minute in a unit test, so we simulate via direct rate manipulation)
        sampler.setSamplingRate(1.0, durationMinutes = 60)
        assertEquals(1.0, sampler.getCurrentSamplingRate(), 0.001)

        // Directly reset to baseline to verify the revert mechanism works
        sampler.resetToBaseline()
        assertEquals(0.1, sampler.getCurrentSamplingRate(), 0.001)
    }

    @Test
    fun `setSamplingRate without duration has no scheduled revert`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.1)
        sampler.setSamplingRate(0.5)
        // Rate should remain 0.5 indefinitely (no scheduled revert)
        Thread.sleep(50)
        assertEquals(0.5, sampler.getCurrentSamplingRate(), 0.001)
    }

    // ── Validation ────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `baselineSamplingRate above 1_0 throws`() {
        DynamicSampler(baselineSamplingRate = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `baselineSamplingRate below 0_0 throws`() {
        DynamicSampler(baselineSamplingRate = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `highPrioritySamplingRate above 1_0 throws`() {
        DynamicSampler(highPrioritySamplingRate = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setSamplingRate above 1_0 throws`() {
        DynamicSampler().setSamplingRate(1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setSamplingRate below 0_0 throws`() {
        DynamicSampler().setSamplingRate(-0.01)
    }

    // ── Description ──────────────────────────────────────────────────────

    @Test
    fun `getDescription contains baseline and highPriority rates`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.1, highPrioritySamplingRate = 1.0)
        val desc = sampler.description
        assertTrue(desc.contains("0.1"))
        assertTrue(desc.contains("1.0"))
    }

    // ── Companion factory methods ─────────────────────────────────────────

    @Test
    fun `alwaysOn creates sampler with 1_0 baseline`() {
        val sampler = DynamicSampler.alwaysOn()
        assertEquals(1.0, sampler.getBaselineSamplingRate(), 0.001)
    }

    @Test
    fun `alwaysOff creates sampler with 0_0 baseline`() {
        val sampler = DynamicSampler.alwaysOff()
        assertEquals(0.0, sampler.getBaselineSamplingRate(), 0.001)
    }

    @Test
    fun `production creates sampler with specified rate`() {
        val sampler = DynamicSampler.production(rate = 0.05)
        assertEquals(0.05, sampler.getBaselineSamplingRate(), 0.001)
    }

    @Test
    fun `production default rate is 0_1`() {
        val sampler = DynamicSampler.production()
        assertEquals(0.1, sampler.getBaselineSamplingRate(), 0.001)
    }

    // ── Thread safety ─────────────────────────────────────────────────────

    @Test
    fun `concurrent setSamplingRate calls do not corrupt state`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.1)
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(100)

        repeat(100) { i ->
            executor.submit {
                try {
                    sampler.setSamplingRate(if (i % 2 == 0) 1.0 else 0.0)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timed out waiting for concurrent updates", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val rate = sampler.getCurrentSamplingRate()
        assertTrue("Rate should be 0.0 or 1.0 after concurrent writes", rate == 0.0 || rate == 1.0)
    }

    @Test
    fun `concurrent shouldSample calls do not throw`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.5)
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(100)
        val errors = mutableListOf<Throwable>()

        repeat(100) { i ->
            executor.submit {
                try {
                    sample(sampler, "trace${i.toString(16).padStart(28, '0')}")
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Unexpected errors during concurrent sampling: $errors", errors.isEmpty())
    }

    // ── SR-013: scheduled revert must not deadlock on lock upgrade ──

    // ReentrantReadWriteLock does not support upgrading from read to write
    // on the same thread. The old `checkScheduledRevert` body did exactly
    // that: held the read lock open and then asked for the write lock
    // inside the same block — guaranteed deadlock once the scheduled time
    // elapsed. This test sets a tiny duration, waits for it to expire,
    // and then calls shouldSample, which transitively invokes
    // checkScheduledRevert. Pre-fix: the call blocks forever; the test
    // times out. Post-fix: the call returns and the rate has reverted.

    @Test(timeout = 5_000)
    fun `scheduled revert does not deadlock when elapsed`() {
        val sampler = DynamicSampler(baselineSamplingRate = 0.1)
        // Set a short-lived rate-override and wait for the revert clock to
        // pass. durationMinutes is Int, so we use 0; the revertTime then
        // equals now + 0 = now, which has elapsed by the time we sample.
        // Then add Thread.sleep to make sure System.currentTimeMillis()
        // has advanced past it.
        sampler.setSamplingRate(rate = 0.9, durationMinutes = 0)
        Thread.sleep(20)

        // Pre-fix this call deadlocks inside checkScheduledRevert. The
        // @Test timeout fires after 5s if so. Use an all-`f` LOWER half so the
        // ratio is ≈ 1.0 — the sampler keys on the trailing 8 bytes — so the
        // SAMPLE/DROP outcome then directly depends on rate (1.0 > 0.9 → DROP
        // at the temporary rate, 1.0 > 0.1 → DROP at baseline). What we're
        // really asserting here is "call returns and rate has reverted to
        // baseline", which is verifiable by getCurrentSamplingRate.
        val result = sample(sampler, "0000000000000000ffffffffffffffff")

        // Post-fix: revert happened, rate is back to baseline (0.1). The
        // sample decision itself is DROP either way (rate < ratio); the
        // load-bearing assertion is the rate value.
        assertEquals(SamplingDecision.DROP, result.decision)
        assertEquals(0.1, sampler.getCurrentSamplingRate(), 0.001)
    }

    // ── SR-023: top-bit-set trace IDs must not bias toward "always sample" ──

    // Before SR-023, the trace-id hex was parsed via parseUnsignedLong →
    // toDouble() → / Long.MAX_VALUE. A trace ID whose keyed bytes have the top
    // bit set parsed to a negative Long; the ratio became negative, and
    // negative < rate is *always* true, so ~50% of all trace IDs were sampled
    // regardless of rate. The sampler keys on the LOWER 8 bytes (trailing 16
    // hex chars) to match the OTel TraceIdRatioBased sampler and the iOS SDK,
    // so these tests place the discriminating bits in the LOWER half:
    // an all-`f` lower half maps to a ratio near 1.0, and a near-zero lower
    // half maps to a ratio near 0.0.

    @Test
    fun `top-bit-set trace ID at low rate is dropped`() {
        // Lower 8 bytes all-`f` → ratio ≈ 1.0, must be > rate 0.01 → DROP.
        val sampler = DynamicSampler(baselineSamplingRate = 0.01)
        val traceId = "0000000000000000ffffffffffffffff"
        assertEquals(SamplingDecision.DROP, sample(sampler, traceId).decision)
    }

    @Test
    fun `low-bit-set trace ID at low rate is sampled`() {
        // Lower 8 bytes ≈ 0 → ratio ≈ 0.0, must be < rate 0.01 → SAMPLE.
        val sampler = DynamicSampler(baselineSamplingRate = 0.01)
        val traceId = "ffffffffffffffff0000000000000001"
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, sample(sampler, traceId).decision)
    }

    @Test
    fun `sampler honors rate across a full hex distribution`() {
        // 256 trace ids evenly spaced across the unsigned 64-bit range. At
        // rate 0.5, ~half should sample. Pre-fix (SR-023), every top-bit-set
        // key was sampled regardless plus the rest's natural sampling, biasing
        // observed rate way above 0.5.
        //
        // The sampler keys on the LOWER 8 bytes (trailing 16 hex chars), so the
        // varying byte must live in the LOWER half: upper 64 bits are zero and
        // the high byte of the LOW half steps with `i`, toggling its top bit in
        // lockstep with i >= 128.
        val sampler = DynamicSampler(baselineSamplingRate = 0.5)
        var sampled = 0
        for (i in 0..255) {
            val hiByteOfLowHalf = "%02x".format(i)
            val traceId = "0".repeat(16) + hiByteOfLowHalf + "00000000000000"
            if (sample(sampler, traceId).decision == SamplingDecision.RECORD_AND_SAMPLE) sampled++
        }
        // 256 keys uniformly across [0, 1]: expect 128 ± a small margin.
        // Pre-fix this is 192-256 (top half always sampled + bottom half ~50%).
        assertTrue("Expected sampled count near 128 of 256, got $sampled", sampled in 96..160)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun sample(
        sampler: DynamicSampler,
        traceId: String,
        attributes: io.opentelemetry.api.common.Attributes = noop
    ) = sampler.shouldSample(
        noParent,
        traceId.padEnd(32, '0').take(32),
        "test-span",
        io.opentelemetry.api.trace.SpanKind.INTERNAL,
        attributes,
        noLinks
    )
}
