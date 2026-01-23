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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TapCapture(
    private val logger: Logger,
    private val sessionTracker: SessionTracker,
    private val options: AutoCaptureOptions
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OTel-AutoTap").apply { isDaemon = true }
    }

    private var pending: PendingTap? = null
    private var pendingFuture: ScheduledFuture<*>? = null

    fun handleTouchEvent(window: Window, event: MotionEvent) {
        if (!options.captureTaps && !options.captureLongPress) return
        if (event.actionMasked != MotionEvent.ACTION_UP) return

        val rootView = window.decorView ?: return
        val rawX = event.rawX.toInt()
        val rawY = event.rawY.toInt()
        val hitResult = ViewHitTester.hitTest(rootView, rawX, rawY, options.maxHitTestDepth)
        val target = hitResult.view
        val screenName = sessionTracker.getCurrentScreenName()

        val attributes = buildAttributes(rootView, target, hitResult.confidence, rawX, rawY, screenName)
        val isLongPress = (event.eventTime - event.downTime) >= ViewConfiguration.getLongPressTimeout()

        val eventName = if (isLongPress && options.captureLongPress) "ui.long_press" else "ui.tap"
        if (eventName == "ui.tap" && !options.captureTaps) return
        if (eventName == "ui.tap") {
            queueTap(eventName, attributes)
        } else {
            emit(eventName, attributes, 1)
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

    private fun queueTap(eventName: String, attributes: Attributes) {
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
        pending = PendingTap(eventName, attributes, now, 1)
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
        emit(current.eventName, current.attributes, current.count)
        pending = null
    }

    private fun emit(eventName: String, attributes: Attributes, count: Int) {
        val builder = Attributes.builder().putAll(attributes)
        if (count > 1) {
            builder.put(AttributeKey.longKey("ui.tap.count"), count.toLong())
            builder.put(AttributeKey.longKey("ui.tap.window_ms"), options.tapCoalesceWindowMs)
        }

        logger.logRecordBuilder()
            .setBody(eventName)
            .setSeverity(Severity.INFO)
            .setAllAttributes(builder.build())
            .emit()
    }

    private data class PendingTap(
        val eventName: String,
        val attributes: Attributes,
        var lastTapAtMs: Long,
        var count: Int
    )
}
