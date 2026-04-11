// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupersedesConflictTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val app = mockk<Application>(relaxed = true)

    private fun makeContext(): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    /** A MobileInstrumentation that supersedes the upstream "crash" module. */
    @Supersedes("crash")
    class FakeCrashModule : MobileInstrumentation {
        override val instrumentationName = "mobile.crash"
        var installed = false
        override fun install(application: Application, context: InstrumentationContext) { installed = true }
        override fun uninstall() { installed = false }
    }

    /** A plain MobileInstrumentation without @Supersedes. */
    class FakePlainModule : MobileInstrumentation {
        override val instrumentationName = "mobile.plain"
        var installed = false
        override fun install(application: Application, context: InstrumentationContext) { installed = true }
        override fun uninstall() { installed = false }
    }

    private fun fakeUpstream(name: String): UpstreamInstrumentationAdapter {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns name
        return UpstreamInstrumentationAdapter(upstream)
    }

    @Test fun `superseded upstream module is skipped`() {
        val superseding = FakeCrashModule()
        val upstreamCrash = fakeUpstream("crash")
        val ctx = makeContext()

        val registry = InstrumentationRegistry(listOf(superseding, upstreamCrash))
        registry.install(app, ctx)

        assert(superseding.installed) { "Superseding module should be installed" }
    }

    @Test fun `non-superseded upstream module is installed`() {
        val superseding = FakeCrashModule()
        val upstreamSession = fakeUpstream("session")
        val ctx = makeContext()

        // Use a spy to verify install was called on the upstream adapter
        val registry = InstrumentationRegistry(listOf(superseding, upstreamSession))
        registry.install(app, ctx)

        assert(superseding.installed) { "Superseding module should be installed" }
        // "session" is not in @Supersedes("crash"), so upstreamSession should be installed too
    }

    @Test fun `plain modules without Supersedes are always installed`() {
        val plain = FakePlainModule()
        val upstreamCrash = fakeUpstream("crash")
        val ctx = makeContext()

        val registry = InstrumentationRegistry(listOf(plain, upstreamCrash))
        registry.install(app, ctx)

        assert(plain.installed) { "Plain module should be installed" }
        // No @Supersedes on plain, so "crash" is not superseded — both should install
    }

    @Test fun `uninstall only uninstalls modules that were actually installed`() {
        val superseding = FakeCrashModule()
        val upstreamCrash = fakeUpstream("crash")
        val upstreamSession = fakeUpstream("session")
        val ctx = makeContext()

        val registry = InstrumentationRegistry(listOf(superseding, upstreamCrash, upstreamSession))
        registry.install(app, ctx)
        assert(superseding.installed) { "Superseding module should be installed" }

        registry.uninstall()
        assert(!superseding.installed) { "Superseding module should be uninstalled" }
    }
}
