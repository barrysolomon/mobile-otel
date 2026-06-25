// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.content.Context
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.instrumentation.AndroidInstrumentation

@Incubating
interface MobileInstrumentation : AndroidInstrumentation {
    val instrumentationName: String
    val instrumentationVersion: String get() = "1.0.0"

    override val name: String get() = instrumentationName

    fun install(application: Application, context: InstrumentationContext)

    // Upstream's AndroidInstrumentation.install/uninstall signature changed in
    // opentelemetry-android 1.3.0 from a single InstallationContext to
    // (Context, OpenTelemetryRum). We bridge it onto our richer mobile
    // signature here so the concrete modules never see the upstream churn.
    override fun install(context: Context, openTelemetryRum: OpenTelemetryRum) {
        val app = context.applicationContext as? Application
            ?: throw IllegalStateException(
                "MobileInstrumentation requires an Application context, got ${context.javaClass.name}"
            )
        install(app, InstrumentationContext.fromOpenTelemetryRum(app, openTelemetryRum))
    }

    override fun uninstall(context: Context, openTelemetryRum: OpenTelemetryRum) {
        uninstall()
    }

    fun uninstall() {}
}
