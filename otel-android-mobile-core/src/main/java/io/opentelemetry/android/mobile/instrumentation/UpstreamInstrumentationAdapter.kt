// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.sdk.common.Clock

class UpstreamInstrumentationAdapter(
    private val upstream: AndroidInstrumentation
) : MobileInstrumentation {

    override val instrumentationName: String = upstream.name

    override fun install(application: Application, context: InstrumentationContext) {
        // Since opentelemetry-android 1.3.0, AndroidInstrumentation.install takes
        // (Context, OpenTelemetryRum). Wrap our own state in the shim; an
        // Application is a Context, so it satisfies the first parameter directly.
        val rum = MobileOpenTelemetryRum(
            openTelemetry = context.openTelemetry,
            sessionProvider = context.sessionProvider,
            clock = context.clock ?: Clock.getDefault()
        )
        upstream.install(application, rum)
    }
}
