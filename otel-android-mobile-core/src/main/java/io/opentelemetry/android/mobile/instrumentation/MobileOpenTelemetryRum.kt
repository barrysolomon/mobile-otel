// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.session.SessionProvider
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.Clock

/**
 * Adapts the Mobile SDK's own state into the upstream [OpenTelemetryRum]
 * handle that [io.opentelemetry.android.instrumentation.AndroidInstrumentation.install]
 * requires since opentelemetry-android 1.3.0.
 *
 * The Mobile SDK builds and owns its [OpenTelemetry] instance directly rather
 * than routing through upstream's RUM builder, so when we forward an upstream
 * [io.opentelemetry.android.instrumentation.AndroidInstrumentation] through
 * [UpstreamInstrumentationAdapter] we wrap our existing pieces in this thin
 * shim. The field mapping is 1:1 with the old `InstallationContext`
 * (openTelemetry / sessionProvider / clock).
 *
 * [emitEvent] is bridged onto the OpenTelemetry logs bridge so upstream
 * modules that emit events still flow through our pipeline; [shutdown] is a
 * no-op because the Mobile SDK owns the lifecycle of the underlying instance
 * (see [OTelMobileHandle.stop]).
 */
internal class MobileOpenTelemetryRum(
    override val openTelemetry: OpenTelemetry,
    override val sessionProvider: SessionProvider,
    override val clock: Clock
) : OpenTelemetryRum {

    override fun emitEvent(eventName: String, body: String, attributes: Attributes) {
        val builder = openTelemetry.logsBridge.get("io.opentelemetry.android.mobile.upstream")
            .logRecordBuilder()
            .setEventName(eventName)
            .setAllAttributes(attributes)
        if (body.isNotEmpty()) builder.setBody(body)
        builder.emit()
    }

    /** No-op: the Mobile SDK owns the lifecycle of the underlying instance. */
    override fun shutdown() {}
}
