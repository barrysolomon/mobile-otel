// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.sdk.common.Clock

class UpstreamInstrumentationAdapter(
    private val upstream: AndroidInstrumentation
) : MobileInstrumentation {

    override val instrumentationName: String = upstream.name

    override fun install(application: Application, context: InstrumentationContext) {
        upstream.install(InstallationContext(
            application,
            context.openTelemetry,
            context.sessionProvider,
            context.clock ?: Clock.getDefault()
        ))
    }
}
