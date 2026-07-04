// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import io.opentelemetry.android.mobile.export.ExportStatus

/**
 * A 32dp draggable circle that shows the current export status at a glance.
 *
 * - **Tap** toggles the detail card.
 * - **Long-press + drag** repositions the badge anywhere on screen.
 * - Border color reflects the latest [ExportStatus]: green (success), red (failed/auth),
 *   orange (retrying), grey (unknown).
 */
@SuppressLint("ViewConstructor")
class DebugBadgeView(
    context: Context,
    private val onToggleCard: () -> Unit
) : View(context) {

    private val sizePx = (32 * resources.displayMetrics.density).toInt()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9000000.toInt() // 85% opacity black
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CAF50.toInt() // green default
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CAF50.toInt()
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    private var statusIcon = "?"
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var viewStartX = 0f
    private var viewStartY = 0f
    private var longPressTriggered = false

    private val longPressRunnable = Runnable { longPressTriggered = true }

    init {
        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        elevation = 20f * resources.displayMetrics.density
    }

    fun updateStatus(status: ExportStatus?) {
        val (color, icon) = when (status) {
            is ExportStatus.Success -> 0xFF4CAF50.toInt() to "\u2713"   // checkmark
            is ExportStatus.AuthError -> 0xFFF44336.toInt() to "\u2717" // X
            is ExportStatus.Failed -> 0xFFF44336.toInt() to "!"
            is ExportStatus.Retrying -> 0xFFFF9800.toInt() to "\u21BB"  // clockwise arrow
            null -> 0xFF888888.toInt() to "?"
        }
        borderPaint.color = color
        iconPaint.color = color
        statusIcon = icon
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width / 2f) - borderPaint.strokeWidth
        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)
        // Draw status icon centered
        val textY = cy - (iconPaint.descent() + iconPaint.ascent()) / 2
        canvas.drawText(statusIcon, cx, textY, iconPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.rawX
                dragStartY = event.rawY
                viewStartX = x
                viewStartY = y
                isDragging = false
                longPressTriggered = false
                handler?.postDelayed(longPressRunnable, 300)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY
                if (longPressTriggered || (dx * dx + dy * dy > 100)) {
                    isDragging = true
                    x = viewStartX + dx
                    y = viewStartY + dy
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler?.removeCallbacks(longPressRunnable)
                if (!isDragging) {
                    onToggleCard()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }
}
