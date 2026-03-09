// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.view.KeyEvent
import android.view.Window
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

class BackPressInstrumentation(
    /** Also attach back-press events as span events on [Span.current()]. Default true. */
    private val addSpanEvents: Boolean = true
) : MobileInstrumentation, WindowEventListener {

    override val instrumentationName = "io.opentelemetry.android.mobile.back_press"

    private var hub: WindowEventHub? = null
    private var ctx: InstrumentationContext? = null
    private var logger: Logger? = null

    override fun install(application: Application, context: InstrumentationContext) {
        ctx = context
        hub = context.windowEventHub
        logger = context.logger(instrumentationName)
        context.windowEventHub.addListener(this)
    }

    override fun uninstall() {
        hub?.removeListener(this)
        hub = null
        ctx = null
        logger = null
    }

    override fun onKeyEvent(event: KeyEvent, window: Window) {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) return
        val log = logger ?: return
        val context = ctx ?: return
        val sessionProvider = context.sessionProvider

        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .apply {
                sessionProvider.getCurrentScreenName()?.let { put(MobileSemconv.SCREEN_NAME, it) }
            }
            .build()

        log.logRecordBuilder()
            .setBody(MobileSemconv.UI_BACK_PRESS)
            .setSeverity(Severity.INFO)
            .setAllAttributes(attrs)
            .emit()
        if (addSpanEvents) ctx?.tracer(instrumentationName)?.spanBuilder(MobileSemconv.UI_BACK_PRESS)
            ?.startSpan()?.apply { setAllAttributes(attrs); end() }
    }
}
