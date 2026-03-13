/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Security validation tests for [MobileConfig].
 *
 * Validates:
 * - ramBufferSize upper bound prevents memory exhaustion
 * - diskBufferMb upper bound prevents disk exhaustion
 * - diskBufferTtlHours upper bound prevents unbounded retention
 * - maxExportRetries upper bound prevents infinite retry loops
 * - HTTPS warning for non-TLS endpoints (does not throw, just warns)
 * - Localhost endpoints are exempt from HTTPS warning
 */
class MobileConfigSecurityTest {

    private fun baseConfig(
        collectorEndpoint: String = "https://collector.example.com:4317",
        ramBufferSize: Int = 5000,
        diskBufferMb: Int = 50,
        diskBufferTtlHours: Int = 24,
        maxExportRetries: Int = 3
    ) = MobileConfig(
        serviceName = "test-app",
        serviceVersion = "1.0.0",
        collectorEndpoint = collectorEndpoint,
        ramBufferSize = ramBufferSize,
        diskBufferMb = diskBufferMb,
        diskBufferTtlHours = diskBufferTtlHours,
        maxExportRetries = maxExportRetries
    )

    // ── ramBufferSize bounds ──────────────────────────────────────────────────

    @Test
    fun `ramBufferSize at upper bound is accepted`() {
        baseConfig(ramBufferSize = 100_000) // should not throw
    }

    @Test
    fun `ramBufferSize above upper bound throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(ramBufferSize = 100_001)
        }
    }

    @Test
    fun `ramBufferSize at lower bound is accepted`() {
        baseConfig(ramBufferSize = 1) // should not throw
    }

    @Test
    fun `ramBufferSize zero throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(ramBufferSize = 0)
        }
    }

    @Test
    fun `ramBufferSize negative throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(ramBufferSize = -1)
        }
    }

    // ── diskBufferMb bounds ───────────────────────────────────────────────────

    @Test
    fun `diskBufferMb at upper bound is accepted`() {
        baseConfig(diskBufferMb = 500)
    }

    @Test
    fun `diskBufferMb above upper bound throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(diskBufferMb = 501)
        }
    }

    @Test
    fun `diskBufferMb at lower bound is accepted`() {
        baseConfig(diskBufferMb = 1)
    }

    @Test
    fun `diskBufferMb zero throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(diskBufferMb = 0)
        }
    }

    // ── diskBufferTtlHours bounds ─────────────────────────────────────────────

    @Test
    fun `diskBufferTtlHours at upper bound 168 hours is accepted`() {
        baseConfig(diskBufferTtlHours = 168) // 7 days
    }

    @Test
    fun `diskBufferTtlHours above 168 throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(diskBufferTtlHours = 169)
        }
    }

    @Test
    fun `diskBufferTtlHours at lower bound is accepted`() {
        baseConfig(diskBufferTtlHours = 1)
    }

    @Test
    fun `diskBufferTtlHours zero throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(diskBufferTtlHours = 0)
        }
    }

    // ── maxExportRetries bounds ───────────────────────────────────────────────

    @Test
    fun `maxExportRetries at upper bound 10 is accepted`() {
        baseConfig(maxExportRetries = 10)
    }

    @Test
    fun `maxExportRetries above 10 throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(maxExportRetries = 11)
        }
    }

    @Test
    fun `maxExportRetries zero is accepted`() {
        baseConfig(maxExportRetries = 0)
    }

    @Test
    fun `maxExportRetries negative throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig(maxExportRetries = -1)
        }
    }

    // ── HTTPS endpoint validation ─────────────────────────────────────────────

    @Test
    fun `HTTPS endpoint is accepted without issue`() {
        baseConfig(collectorEndpoint = "https://collector.example.com:4317")
    }

    @Test
    fun `HTTP endpoint does not throw but logs warning`() {
        // Non-HTTPS endpoints are allowed (for backward compatibility) but log a warning.
        // This test verifies the config is created successfully.
        baseConfig(collectorEndpoint = "http://collector.example.com:4317")
    }

    @Test
    fun `localhost HTTP endpoint is accepted without warning`() {
        baseConfig(collectorEndpoint = "http://localhost:4317")
    }

    @Test
    fun `127_0_0_1 HTTP endpoint is accepted without warning`() {
        baseConfig(collectorEndpoint = "http://127.0.0.1:4317")
    }

    @Test
    fun `10_0_2_2 emulator HTTP endpoint is accepted without warning`() {
        // 10.0.2.2 is Android emulator's host loopback address
        baseConfig(collectorEndpoint = "http://10.0.2.2:4317")
    }

    // ── Existing validations still work ───────────────────────────────────────

    @Test
    fun `blank serviceName still throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig().copy(serviceName = "")
        }
    }

    @Test
    fun `blank collectorEndpoint still throws`() {
        assertFailsWith<IllegalArgumentException> {
            baseConfig().copy(collectorEndpoint = "")
        }
    }
}
