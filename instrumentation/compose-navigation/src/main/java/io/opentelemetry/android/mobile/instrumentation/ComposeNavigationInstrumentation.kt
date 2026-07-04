// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.util.Log
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Scope

@Incubating
@Supersedes("compose.navigation")
class ComposeNavigationInstrumentation(
    private val config: ComposeNavigationConfig = ComposeNavigationConfig(),
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.compose.navigation"

    private var logger: Logger? = null
    private var tracer: Tracer? = null
    private var sessionProvider: MobileSessionProvider? = null
    private var pageSpan: Span? = null
    private var pageScope: Scope? = null

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return
        try {
            this.logger = context.logger(instrumentationName)
            this.tracer = context.tracer(instrumentationName)
            this.sessionProvider = context.sessionProvider
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install compose navigation instrumentation", e)
        }
    }

    override fun uninstall() {
        endPageSpan()
        logger = null
        tracer = null
        sessionProvider = null
    }

    fun onDestinationChanged(screenName: String) {
        val sp = sessionProvider ?: return
        sp.onScreenView(screenName)

        if (config.emitScreenViewLogs) {
            val attrs = Attributes.builder()
                .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                .put(MobileSemconv.VIEW_ID, sp.getViewId())
                .put(MobileSemconv.SCREEN_NAME, screenName)
                .apply {
                    sp.getPreviousScreenName()?.let { put(MobileSemconv.PREVIOUS_SCREEN, it) }
                    val timeOnScreen = sp.getTimeOnScreenMs()
                    if (timeOnScreen > 0) put(MobileSemconv.TIME_ON_SCREEN_MS, timeOnScreen)
                }
                .build()

            logger?.logRecordBuilder()
                ?.setBody(MobileSemconv.UI_SCREEN_VIEW)
                ?.setSeverity(Severity.INFO)
                ?.setAllAttributes(attrs)
                ?.emit()
        }

        if (config.emitPageSpans) {
            startPageSpan(screenName)
        }
    }

    private fun startPageSpan(screenName: String) {
        endPageSpan()
        val sp = sessionProvider ?: return
        pageSpan = tracer?.spanBuilder("page.$screenName")
            ?.setSpanKind(SpanKind.INTERNAL)
            ?.setAttribute(MobileSemconv.SESSION_ID.key, sp.getSessionId())
            ?.setAttribute(MobileSemconv.VIEW_ID.key, sp.getViewId())
            ?.setAttribute(MobileSemconv.SCREEN_NAME.key, screenName)
            ?.startSpan()
        pageScope = pageSpan?.makeCurrent()
    }

    private fun endPageSpan() {
        pageScope?.close()
        pageScope = null
        pageSpan?.takeIf { it.isRecording }?.end()
        pageSpan = null
    }

    companion object {
        private const val TAG = "ComposeNavInstr"
    }
}
