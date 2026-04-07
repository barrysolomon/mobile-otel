// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.api.trace.SpanKind

/**
 * Captures tap, long-press, and swipe events from [WindowEventHub].
 *
 * Uses [GestureDetector] so that long-press is detected mid-hold (not at ACTION_UP),
 * which correctly handles cases where the system cancels the touch sequence (context menus,
 * scroll containers, etc.).
 *
 * Swipe is detected at ACTION_UP using raw coordinate delta against [TapConfig.swipeMinDistancePx].
 * ACTION_CANCEL resets state without emitting, so scroll-driven touches are silently discarded.
 *
 * **Thread note:** [onTouchEvent] is always called on the Android main thread.
 */
@Incubating
class TapInstrumentation(
    private val config: TapConfig = TapConfig()
) : MobileInstrumentation, WindowEventListener {

    override val instrumentationName = "io.opentelemetry.android.mobile.tap"

    private var hub: WindowEventHub? = null
    private var ctx: InstrumentationContext? = null
    private var logger: Logger? = null
    private var gestureDetector: GestureDetector? = null

    // Down coordinates for swipe distance calculation — main thread only.
    private var downX: Float = 0f
    private var downY: Float = 0f
    // Set to true when GestureDetector fires onLongPress so ACTION_UP doesn't also emit a tap.
    private var longPressEmitted: Boolean = false
    // Window reference captured at ACTION_DOWN for hit-testing at ACTION_UP.
    private var currentWindow: Window? = null

    /**
     * Installs the tap instrumentation by registering this instance as a [WindowEventListener].
     *
     * @param application The host application, used as the [android.content.Context] for [GestureDetector].
     * @param context Instrumentation context carrying the OTel logger, tracer, and session provider.
     */
    override fun install(application: Application, context: InstrumentationContext) {
        ctx = context
        hub = context.windowEventHub
        logger = context.logger(instrumentationName)
        gestureDetector = GestureDetector(
            application as Context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (!config.captureLongPress) return
                    longPressEmitted = true
                    val ctx2 = ctx ?: return
                    val sp = ctx2.sessionProvider
                    val target = currentWindow?.decorView?.let { hitTest(it, e.rawX.toInt(), e.rawY.toInt()) }
                    val attrs = Attributes.builder()
                        .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                        .put(MobileSemconv.VIEW_ID, sp.getViewId())
                        .apply {
                            sp.getCurrentScreenName()?.let { put(MobileSemconv.SCREEN_NAME, it) }
                            applyElementAttrs(this, target)
                        }
                        .build()
                    emitUiTelemetry(MobileSemconv.UI_LONG_PRESS, attrs, ctx2)
                }
            }
        )
        context.windowEventHub.addListener(this)
    }

    /**
     * Uninstalls the tap instrumentation and releases all held references.
     */
    override fun uninstall() {
        hub?.removeListener(this)
        hub = null
        ctx = null
        logger = null
        gestureDetector = null
        longPressEmitted = false
        currentWindow = null
    }

    override fun onTouchEvent(event: MotionEvent, window: Window) {
        gestureDetector?.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                currentWindow = window
                longPressEmitted = false
            }
            MotionEvent.ACTION_CANCEL -> {
                downX = 0f
                downY = 0f
                longPressEmitted = false
                currentWindow = null
            }
            MotionEvent.ACTION_UP -> handleActionUp(event, window)
        }
    }

    private fun handleActionUp(event: MotionEvent, window: Window) {
        val context = ctx
        if (context == null) {
            downX = 0f; downY = 0f; longPressEmitted = false; currentWindow = null
            return
        }
        val sessionProvider = context.sessionProvider

        val dx = event.rawX - downX
        val dy = event.rawY - downY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        downX = 0f; downY = 0f; currentWindow = null

        if (config.captureSwipe && distance >= config.swipeMinDistancePx) {
            val direction = when {
                kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> if (dy > 0) "down" else "up"
                else -> if (dx > 0) "right" else "left"
            }
            val attrs = Attributes.builder()
                .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
                .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
                .put(MobileSemconv.SWIPE_DIRECTION, direction)
                .apply { sessionProvider.getCurrentScreenName()?.let { put(MobileSemconv.SCREEN_NAME, it) } }
                .build()
            emitUiTelemetry(MobileSemconv.UI_SWIPE, attrs, context)
            longPressEmitted = false
            return
        }

        // Long-press was already emitted by GestureDetector — don't also emit a tap.
        if (longPressEmitted) {
            longPressEmitted = false
            return
        }

        if (!config.captureTaps) return

        val target = window.decorView?.let { hitTest(it, event.rawX.toInt(), event.rawY.toInt()) }
        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .apply {
                sessionProvider.getCurrentScreenName()?.let { put(MobileSemconv.SCREEN_NAME, it) }
                applyElementAttrs(this, target)
            }
            .build()
        emitUiTelemetry(MobileSemconv.UI_TAP, attrs, context)
    }

    /**
     * Walk the view tree to find the deepest visible, enabled View that contains (x, y).
     * Returns null if no match within [TapConfig.maxHitTestDepth] levels.
     */
    private fun hitTest(root: View, x: Int, y: Int, depth: Int = 0): View? {
        if (depth > config.maxHitTestDepth) return null
        if (!root.isShown) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        if (x < loc[0] || x > loc[0] + root.width || y < loc[1] || y > loc[1] + root.height) return null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = hitTest(root.getChildAt(i), x, y, depth + 1)
                if (child != null) return child
            }
        }
        return root
    }

    private fun applyElementAttrs(builder: AttributesBuilder, target: View?) {
        target ?: return
        val resourceId: String? = if (target.id != View.NO_ID) {
            try { target.resources.getResourceEntryName(target.id) } catch (_: Exception) { null }
        } else null
        resourceId?.let { builder.put(MobileSemconv.UI_ELEMENT_ID, it) }
        builder.put(MobileSemconv.UI_ELEMENT_CLASS, target.javaClass.simpleName)
        builder.put(MobileSemconv.UI_ELEMENT_ENABLED, target.isEnabled)
        builder.put(MobileSemconv.UI_ELEMENT_CLICKABLE, target.isClickable)
        // Capture visible text label — safe: only non-empty, non-sensitive button/label text
        val label: String? = when {
            target is TextView && target.text.isNotEmpty() -> target.text.toString()
            target.contentDescription?.isNotEmpty() == true -> target.contentDescription.toString()
            else -> null
        }
        label?.let { builder.put(MobileSemconv.UI_ELEMENT_LABEL, it) }
    }

    private fun emitUiTelemetry(
        name: String,
        attrs: io.opentelemetry.api.common.Attributes,
        context: InstrumentationContext
    ) {
        val log = logger ?: return
        when (context.uiTelemetryMode) {
            UiTelemetryMode.EVENTS -> log.logRecordBuilder()
                .setBody(name).setSeverity(Severity.INFO).setAllAttributes(attrs).emit()
            UiTelemetryMode.SPANS  -> context.tracer(instrumentationName)
                .spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan().apply { setAllAttributes(attrs); end() }
            UiTelemetryMode.BOTH   -> {
                log.logRecordBuilder()
                    .setBody(name).setSeverity(Severity.INFO).setAllAttributes(attrs).emit()
                context.tracer(instrumentationName)
                    .spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan().apply { setAllAttributes(attrs); end() }
            }
        }

        // Add breadcrumb for user input events
        val screenName = context.sessionProvider.getCurrentScreenName() ?: "unknown"
        val elementId = attrs.get(MobileSemconv.UI_ELEMENT_ID)
        context.addBreadcrumb(
            JourneyBreadcrumb.userInput(
                screen = screenName,
                action = name,
                elementId = elementId
            )
        )
    }
}
