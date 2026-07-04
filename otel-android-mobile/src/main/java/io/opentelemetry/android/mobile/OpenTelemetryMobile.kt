// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile

import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.MobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.CompletableResultCode

class OpenTelemetryMobile internal constructor(
    val openTelemetry: OpenTelemetry,
    private val handle: OTelMobileHandle,
    private val sessionProvider: MobileSessionProvider,
    private val loggerProvider: MobileLoggerProvider
) {
    val sessionId: String get() = sessionProvider.getSessionId()

    fun getTracer(scope: String): Tracer = openTelemetry.getTracer(scope)
    fun getLogger(scope: String): Logger = openTelemetry.logsBridge.get(scope)
    fun getMeter(scope: String): Meter = openTelemetry.getMeter(scope)

    fun forceFlush(timeoutSeconds: Long = 30): CompletableResultCode {
        return loggerProvider.forceFlush(timeoutSeconds)
    }

    fun flushWindow(minutes: Int): CompletableResultCode {
        return loggerProvider.getMobileProcessor().flushWindow(minutes)
    }

    fun shutdown(timeoutSeconds: Long = 30) {
        handle.stop(timeoutSeconds)
    }
}
