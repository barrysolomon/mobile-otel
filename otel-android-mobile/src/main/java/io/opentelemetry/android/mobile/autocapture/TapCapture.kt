/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.widget.TextView
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Captures tap and long-press events from the Window's touch stream.
 *
 * ## Trace hierarchy
 *
 * Page spans are created by [AutoCaptureManager.startPageSpan] on every fragment resume and are
 * always sampled by [DynamicSampler] based on name prefix (`page.*`) — no attribute needed.
 * This makes the page span the root of all interactions on a screen:
 *
 *   page.BookFragment  ← always-sampled root (AutoCaptureManager)
 *   ├── ui.tap          ← zero-duration child span (this class, emit path A)
 *   ├── ui.tap
 *   ├── booking.submit  ← manually created in BookFragment, parented to pageContext
 *   │   └── POST /posts ← OkHttp child span
 *   └── ui.swipe        ← zero-duration child span (ScrollCapture, same pattern)
 *
 * ## Emit path A vs B
 *
 * At touch time (ACTION_UP on main thread), the current OTel context is captured. The emit()
 * function then inspects the captured context:
 *
 *   A. Valid + sampled parent span present → emit as zero-duration child span.
 *      The tap appears in the trace waterfall under the page (or booking) span.
 *
 *   B. No valid/sampled parent → emit as log record with context attached.
 *      Used when auto-capture is disabled or the page span was somehow not active.
 *
 * The page span being forced to always sample (see AutoCaptureManager) means path A is taken
 * for all normal page interactions. Without that, the DynamicSampler at the default 0.65
 * baseline would drop ~35% of page spans, causing all taps on those screens to take path B
 * and appear as flat, unconnected log entries in Dash0.
 *
 * Context is captured on the main thread and carried through the coalesce queue so the
 * scheduler thread emits with the correct parent.
 */
class TapCapture(
    private val logger: Logger,
    private val tracer: Tracer,
    private val sessionTracker: SessionTracker,
    private val options: AutoCaptureOptions
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OTel-AutoTap").apply { isDaemon = true }
    }

    private var pending: PendingTap? = null
    private var pendingFuture: ScheduledFuture<*>? = null

    // Track down position for swipe detection — only accessed on the main thread.
    private var downX: Float = 0f
    private var downY: Float = 0f

    fun handleTouchEvent(window: Window, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
            }
            MotionEvent.ACTION_CANCEL -> {
                downX = 0f
                downY = 0f
            }
            MotionEvent.ACTION_UP -> handleActionUp(window, event)
        }
    }

    private fun handleActionUp(window: Window, event: MotionEvent) {
        if (!options.captureTaps && !options.captureLongPress && !options.captureSwipe) return

        // Capture OTel context on the main thread — carries any active parent span.
        val capturedContext = Context.current()

        val rootView = window.decorView ?: return
        val rawX = event.rawX.toInt()
        val rawY = event.rawY.toInt()

        // Check if this is a swipe before doing the more expensive hit-test.
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (options.captureSwipe && distance >= options.swipeMinDistancePx) {
            val direction = when {
                kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> if (dy > 0) "down" else "up"
                else -> if (dx > 0) "right" else "left"
            }
            val screenName = sessionTracker.getCurrentScreenName()
            val attrs = Attributes.builder()
                .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                .put(AttributeKey.stringKey("ui.swipe.direction"), direction)
                .apply { if (screenName != null) put(AttributeKey.stringKey("screen.name"), screenName) }
                .build()
            emit("ui.swipe", attrs, 1, capturedContext)
            downX = 0f
            downY = 0f
            return
        }
        downX = 0f
        downY = 0f

        val hitResult = ViewHitTester.hitTest(rootView, rawX, rawY, options.maxHitTestDepth)
        val target = hitResult.view
        val screenName = sessionTracker.getCurrentScreenName()

        val attributes = buildAttributes(rootView, target, hitResult.confidence, rawX, rawY, screenName)
        val isLongPress = (event.eventTime - event.downTime) >= ViewConfiguration.getLongPressTimeout()

        val eventName = if (isLongPress && options.captureLongPress) "ui.long_press" else "ui.tap"
        if (eventName == "ui.tap" && !options.captureTaps) return
        if (eventName == "ui.tap") {
            queueTap(eventName, attributes, capturedContext)
        } else {
            emit(eventName, attributes, 1, capturedContext)
        }
    }

    fun shutdown() {
        pendingFuture?.cancel(false)
        scheduler.shutdownNow()
    }

    private fun buildAttributes(
        rootView: View,
        target: View?,
        confidence: String,
        rawX: Int,
        rawY: Int,
        screenName: String?
    ): Attributes {
        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
            .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
            .put(AttributeKey.stringKey("interaction.source"), "auto.window")
            .put(AttributeKey.stringKey("element.confidence"), confidence)

        if (screenName != null) {
            attributes.put(AttributeKey.stringKey("screen.name"), screenName)
        }

        val bucket = CoordinateBucketer.bucket(
            x = rawX.toFloat(),
            y = rawY.toFloat(),
            width = rootView.width,
            height = rootView.height,
            gridSize = options.bucketGridSize
        )
        if (bucket != null) {
            attributes
                .put(AttributeKey.longKey("ui.tap.bucket_row"), bucket.row.toLong())
                .put(AttributeKey.longKey("ui.tap.bucket_col"), bucket.col.toLong())
                .put(AttributeKey.longKey("ui.tap.grid_size"), bucket.gridSize.toLong())
        }

        if (target != null) {
            val className = target.javaClass.name
            val resourceId = getResourceName(target)

            if (isAllowed(target, resourceId, className)) {
                attributes
                    .put(AttributeKey.stringKey("ui.element.class"), className)
                    .put(AttributeKey.booleanKey("ui.element.enabled"), target.isEnabled)
                    .put(AttributeKey.booleanKey("ui.element.clickable"), target.isClickable)

                if (resourceId != null) {
                    attributes.put(AttributeKey.stringKey("ui.element.resource_id"), resourceId)
                }

                val contentDescHash = PrivacyUtils.maybeHash(target.contentDescription, options)
                if (contentDescHash != null) {
                    attributes.put(AttributeKey.stringKey("ui.element.content_desc_hash"), contentDescHash)
                }

                if (target is TextView) {
                    val textHash = PrivacyUtils.maybeHash(target.text, options)
                    if (textHash != null) {
                        attributes.put(AttributeKey.stringKey("ui.element.text_hash"), textHash)
                    }
                }
            }
        }

        return attributes.build()
    }

    private fun getResourceName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        return try {
            view.resources.getResourceName(id)
        } catch (_: Exception) {
            null
        }
    }

    private fun isAllowed(view: View, resourceId: String?, className: String): Boolean {
        if (options.denylistedViewClasses.contains(className)) return false
        if (resourceId != null && options.denylistedResourceIds.contains(resourceId)) return false

        if (options.allowlistedViewClasses.isNotEmpty() && !options.allowlistedViewClasses.contains(className)) {
            return false
        }
        if (options.allowlistedResourceIds.isNotEmpty() && (resourceId == null || !options.allowlistedResourceIds.contains(resourceId))) {
            return false
        }
        return true
    }

    private fun queueTap(eventName: String, attributes: Attributes, capturedContext: Context) {
        val now = SystemClock.elapsedRealtime()
        val previous = pending

        if (previous != null && previous.eventName == eventName && previous.attributes == attributes &&
            now - previous.lastTapAtMs <= options.tapCoalesceWindowMs
        ) {
            previous.count += 1
            previous.lastTapAtMs = now
            scheduleEmit()
            return
        }

        flushPending()
        pending = PendingTap(eventName, attributes, capturedContext, now, 1)
        scheduleEmit()
    }

    private fun scheduleEmit() {
        pendingFuture?.cancel(false)
        pendingFuture = scheduler.schedule({
            flushIfStale()
        }, options.tapCoalesceWindowMs, TimeUnit.MILLISECONDS)
    }

    private fun flushIfStale() {
        val current = pending ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - current.lastTapAtMs >= options.tapCoalesceWindowMs) {
            flushPending()
        }
    }

    private fun flushPending() {
        val current = pending ?: return
        emit(current.eventName, current.attributes, current.count, current.capturedContext)
        pending = null
    }

    /**
     * Emits a tap as a child span when a parent span is active, or as a log when idle.
     *
     * Child span approach: zero-duration span parented to the active transaction span.
     * This makes taps appear in the trace waterfall under the booking/directions span.
     *
     * Log approach (no active parent): preserves existing behaviour while attaching the
     * OTel context so the log is correlated to any trace in flight.
     */
    private fun emit(eventName: String, attributes: Attributes, count: Int, capturedContext: Context) {
        val allAttrs = if (count > 1) {
            Attributes.builder()
                .putAll(attributes)
                .put(AttributeKey.longKey("ui.tap.count"), count.toLong())
                .put(AttributeKey.longKey("ui.tap.window_ms"), options.tapCoalesceWindowMs)
                .build()
        } else {
            attributes
        }

        val parentSpan = Span.fromContext(capturedContext)
        if (parentSpan.spanContext.isValid && parentSpan.spanContext.isSampled) {
            // Active parent span — emit as child span for trace waterfall visibility
            val childSpan = tracer.spanBuilder(eventName)
                .setParent(capturedContext)
                .setAllAttributes(allAttrs)
                .startSpan()
            childSpan.end()
        } else {
            // No active parent — emit as log, but attach context for trace correlation
            logger.logRecordBuilder()
                .setBody(eventName)
                .setSeverity(Severity.INFO)
                .setAllAttributes(allAttrs)
                .setContext(capturedContext)
                .emit()
        }
    }

    private data class PendingTap(
        val eventName: String,
        val attributes: Attributes,
        val capturedContext: Context,
        var lastTapAtMs: Long,
        var count: Int
    )
}
