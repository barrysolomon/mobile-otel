/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.opentelemetry.android.mobile.config.MobileConfig
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup budget gate — enforces TEST_PLAN **HS-001**: SDK init must take
 * **< 50 ms on the main thread**.
 *
 * `OTelMobile.start()` is documented as "call from `Application.onCreate`",
 * which runs on the main thread of every host app: every millisecond spent
 * there is a millisecond added to the host's cold start. This test measures
 * the REAL synchronous cost of `start()` on the instrumentation target's
 * main thread — the same code path, looper, and class-loading cost a host
 * app pays.
 *
 * Two assertions, both required:
 *  1. init actually SUCCEEDED (`getLoggerProvider()` resolves). `start()`
 *     deliberately degrades to a silent no-op on internal failure — a no-op
 *     is fast, and a fast-but-broken init must not pass a performance gate.
 *  2. the main-thread time is under budget.
 *
 * Runs as part of `:otel-android-mobile:connectedDebugAndroidTest`, which
 * `scripts/e2e/run-e2e.sh` executes on every E2E run.
 */
@RunWith(AndroidJUnit4::class)
class StartupBudgetTest {

    @After
    fun tearDown() {
        // stop() removes lifecycle observers, which androidx requires to happen
        // on the main thread (LifecycleRegistry.enforceMainThreadIfNeeded).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            OTelMobile.stop(timeoutSeconds = 5)
        }
        MobileLoggerProvider.resetForTesting()
    }

    @Test
    fun hs001_sdkInitUnder50msOnMainThread() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // Loopback endpoint, no remote-config polling: HS-001 budgets the
        // SDK's own init work, not network round-trips (which must already
        // be off the main thread — if one sneaks INTO init, this gate is
        // exactly what should catch it).
        val config = MobileConfig(
            serviceName = "startup-budget-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            remoteConfigEnabled = false,
        )

        var elapsedNanos = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val t0 = SystemClock.elapsedRealtimeNanos()
            OTelMobile.start(application, config)
            elapsedNanos = SystemClock.elapsedRealtimeNanos() - t0
        }
        val elapsedMs = elapsedNanos / 1_000_000.0

        // Guard against the silent-degrade path: a failed init is a no-op and
        // would sail under any time budget.
        assertNotNull(
            "SDK init must actually succeed for the budget measurement to mean anything",
            OTelMobile.getLoggerProvider(),
        )

        assertTrue(
            "HS-001 VIOLATION: OTelMobile.start() blocked the main thread for " +
                "%.1f ms (budget: $BUDGET_MS ms). Init work grew — move it off ".format(elapsedMs) +
                "the main thread or trim it; do not raise the budget without a spec change.",
            elapsedMs < BUDGET_MS,
        )
    }

    companion object {
        /** TEST_PLAN HS-001: "Time from init() to ready — < 50ms on main thread". */
        private const val BUDGET_MS = 50.0
    }
}
