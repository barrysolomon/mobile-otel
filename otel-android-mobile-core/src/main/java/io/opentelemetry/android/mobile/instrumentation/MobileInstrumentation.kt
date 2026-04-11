// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext

@Incubating
interface MobileInstrumentation : AndroidInstrumentation {
    val instrumentationName: String
    val instrumentationVersion: String get() = "1.0.0"

    override val name: String get() = instrumentationName

    fun install(application: Application, context: InstrumentationContext)

    override fun install(ctx: InstallationContext) {
        val app = ctx.application
            ?: throw IllegalStateException(
                "MobileInstrumentation requires Application context, got ${ctx.context.javaClass.name}"
            )
        install(app, InstrumentationContext.fromInstallationContext(ctx))
    }

    override fun uninstall(ctx: InstallationContext) {
        uninstall()
    }

    fun uninstall() {}
}
