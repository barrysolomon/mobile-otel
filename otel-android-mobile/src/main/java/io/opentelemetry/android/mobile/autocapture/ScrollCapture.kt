/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.WeakHashMap

class ScrollCapture(
    private val logger: Logger,
    private val sessionTracker: SessionTracker,
    private val options: AutoCaptureOptions
) {
    private val tracked = WeakHashMap<RecyclerView, Boolean>()
    private var lastScrollAtMs: Long = 0

    fun attachTo(root: View) {
        if (!options.captureScroll) return
        findRecyclerViews(root).forEach { recyclerView ->
            if (tracked.containsKey(recyclerView)) return@forEach
            recyclerView.addOnScrollListener(scrollListener)
            tracked[recyclerView] = true
        }
    }

    private fun findRecyclerViews(view: View): List<RecyclerView> {
        val result = mutableListOf<RecyclerView>()
        if (view is RecyclerView) {
            result.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                result.addAll(findRecyclerViews(view.getChildAt(i)))
            }
        }
        return result
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastScrollAtMs < options.scrollThrottleMs) return
            if (dx == 0 && dy == 0) return

            lastScrollAtMs = now
            val direction = if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
                if (dy > 0) "down" else "up"
            } else {
                if (dx > 0) "right" else "left"
            }

            val distance = kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy))
            val bucket = when {
                distance < 50 -> "small"
                distance < 200 -> "medium"
                else -> "large"
            }

            val screenName = sessionTracker.getCurrentScreenName()
            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
                .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
                .put(AttributeKey.stringKey("ui.scroll.direction"), direction)
                .put(AttributeKey.stringKey("ui.scroll.distance_bucket"), bucket)
                .apply {
                    if (screenName != null) {
                        put(AttributeKey.stringKey("screen.name"), screenName)
                    }
                }
                .build()

            logger.logRecordBuilder()
                .setBody("ui.scroll")
                .setSeverity(Severity.INFO)
                .setAllAttributes(attributes)
                .emit()
        }
    }
}
