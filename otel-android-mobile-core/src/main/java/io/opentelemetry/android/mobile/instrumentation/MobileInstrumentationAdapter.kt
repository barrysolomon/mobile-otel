// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext

/**
 * Wraps a [MobileInstrumentation] as an upstream [AndroidInstrumentation],
 * allowing native mobile modules to be registered with upstream discovery
 * mechanisms that expect [AndroidInstrumentation].
 */
class MobileInstrumentationAdapter(
    private val mobile: MobileInstrumentation,
    private val windowEventHub: WindowEventHub,
    private val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
) : AndroidInstrumentation {

    override fun install(ctx: InstallationContext) {
        val sessionProvider = UpstreamSessionProviderAdapter(ctx.sessionManager)
        val app = ctx.application
        val mobileCtx = InstrumentationContext(
            openTelemetry = ctx.openTelemetry,
            sessionProvider = sessionProvider,
            windowEventHub = windowEventHub,
            application = app,
            uiTelemetryMode = uiTelemetryMode
        )
        mobile.install(app, mobileCtx)
    }
}
