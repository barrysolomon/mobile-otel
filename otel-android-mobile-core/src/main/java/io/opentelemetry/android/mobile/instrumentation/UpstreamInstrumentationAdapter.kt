// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.android.session.SessionManager
import io.opentelemetry.android.session.SessionObserver

/**
 * Wraps an upstream [AndroidInstrumentation] as a [MobileInstrumentation],
 * allowing upstream modules discovered via ServiceLoader to be installed
 * alongside native mobile modules through [InstrumentationRegistry].
 *
 * Because the upstream [AndroidInstrumentation] interface does not expose a
 * `name` property, callers must supply the [instrumentationName] explicitly.
 */
class UpstreamInstrumentationAdapter(
    private val upstream: AndroidInstrumentation,
    override val instrumentationName: String
) : MobileInstrumentation {

    override fun install(application: Application, context: InstrumentationContext) {
        val sessionManager = SessionProviderAsSessionManager(context.sessionProvider)
        val installCtx = InstallationContext(
            application,
            context.openTelemetry,
            sessionManager
        )
        upstream.install(installCtx)
    }
}

/**
 * Minimal [SessionManager] implementation that delegates to a [MobileSessionProvider].
 *
 * Upstream [InstallationContext] requires a [SessionManager], but our SDK uses
 * [MobileSessionProvider]. This adapter bridges the gap. Observer registration
 * is a no-op since session lifecycle is managed by [MobileSessionProvider] directly.
 */
internal class SessionProviderAsSessionManager(
    private val provider: MobileSessionProvider
) : SessionManager {
    override fun getSessionId(): String = provider.getSessionId()
    override fun addObserver(observer: SessionObserver) {
        // Session lifecycle is managed by MobileSessionProvider; observers not forwarded.
    }
}
