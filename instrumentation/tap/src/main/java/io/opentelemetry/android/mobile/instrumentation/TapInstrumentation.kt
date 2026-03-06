// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

/**
 * Captures tap, long-press, and swipe events from [WindowEventHub].
 *
 * **Thread note:** [onTouchEvent] is always called on the Android main thread.
 * [uninstall] should also be called on the main thread (consistent with how
 * [InstrumentationRegistry] is used from lifecycle callbacks).
 */
class TapInstrumentation(
    private val config: TapConfig = TapConfig()
) : MobileInstrumentation, WindowEventListener {

    override val instrumentationName = "io.opentelemetry.android.mobile.tap"

    private var hub: WindowEventHub? = null
    private var ctx: InstrumentationContext? = null
    // Cached at install time — logger registry lookup is cheap but not free.
    private var logger: Logger? = null

    // Down coordinates — only accessed on main thread via onTouchEvent.
    private var downX: Float = 0f
    private var downY: Float = 0f

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

    override fun onTouchEvent(event: MotionEvent, window: Window) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
            }
            MotionEvent.ACTION_CANCEL -> {
                downX = 0f
                downY = 0f
            }
            MotionEvent.ACTION_UP -> handleActionUp(event)
        }
    }

    private fun handleActionUp(event: MotionEvent) {
        val context = ctx
        val log = logger
        if (context == null || log == null) {
            // Uninstalled between ACTION_DOWN and ACTION_UP; reset stale coords.
            downX = 0f
            downY = 0f
            return
        }
        val sessionProvider = context.sessionProvider

        val dx = event.rawX - downX
        val dy = event.rawY - downY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)

        if (config.captureSwipe && distance >= config.swipeMinDistancePx) {
            val direction = when {
                kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> if (dy > 0) "down" else "up"
                else -> if (dx > 0) "right" else "left"
            }
            val attrs = Attributes.builder()
                .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
                .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
                .put(MobileSemconv.SWIPE_DIRECTION, direction)
                .apply {
                    sessionProvider.getCurrentScreenName()?.let { put(MobileSemconv.SCREEN_NAME, it) }
                }
                .build()
            log.logRecordBuilder()
                .setBody(MobileSemconv.UI_SWIPE)
                .setSeverity(Severity.INFO)
                .setAllAttributes(attrs)
                .emit()
            downX = 0f
            downY = 0f
            return
        }
        downX = 0f
        downY = 0f

        val isLongPress = (event.eventTime - event.downTime) >= ViewConfiguration.getLongPressTimeout()
        // When captureLongPress=false, long-press gestures fall through as taps.
        val eventName = if (isLongPress && config.captureLongPress) MobileSemconv.UI_LONG_PRESS else MobileSemconv.UI_TAP
        if (eventName == MobileSemconv.UI_TAP && !config.captureTaps) return

        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .apply {
                sessionProvider.getCurrentScreenName()?.let { put(MobileSemconv.SCREEN_NAME, it) }
            }
            .build()

        log.logRecordBuilder()
            .setBody(eventName)
            .setSeverity(Severity.INFO)
            .setAllAttributes(attrs)
            .emit()
    }
}
