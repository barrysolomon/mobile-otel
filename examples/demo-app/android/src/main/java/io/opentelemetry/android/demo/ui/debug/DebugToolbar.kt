// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.debug

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.mobile.export.ExportStatus
import io.opentelemetry.android.mobile.export.ExportStatusListener
import io.opentelemetry.android.mobile.export.ExportStatusManager
import kotlinx.coroutines.*

/**
 * Collapsible debug toolbar for triggering error scenarios.
 *
 * Features:
 * - Small collapsed state (single row with expand icon)
 * - Expanded state (rows of trigger buttons)
 * - Swipe-down on header to expand, swipe-up to collapse
 * - Last action echoed in header when collapsed
 */
class DebugToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val expandIcon: ImageView
    private val statusText: TextView
    private val exportStatusText: TextView
    private val toolbarContent: LinearLayout
    private val header: LinearLayout

    private var isExpanded = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // The last action fired — shown in header when collapsed.
    private var lastAction: String? = null

    var listener: DebugToolbarListener? = null

    private val exportListener = ExportStatusListener { status ->
        scope.launch {
            val (text, color) = when (status) {
                is ExportStatus.Success -> "Export OK (${status.eventCount} events)" to 0xFF4CAF50.toInt()
                is ExportStatus.AuthError -> "AUTH FAILED: ${status.reason}" to 0xFFF44336.toInt()
                is ExportStatus.Failed -> "Export FAILED: ${status.reason}" to 0xFFF44336.toInt()
                is ExportStatus.Retrying -> "Retrying ${status.attempt}/${status.maxAttempts}..." to 0xFFFF9800.toInt()
            }
            exportStatusText.text = text
            exportStatusText.setTextColor(color)
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_VELOCITY = 200
        private val SWIPE_DISTANCE = 50

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val dy = e2.rawY - (e1?.rawY ?: e2.rawY)
            return if (kotlin.math.abs(velocityY) > SWIPE_VELOCITY && kotlin.math.abs(dy) > SWIPE_DISTANCE) {
                if (dy > 0 && !isExpanded) { setExpanded(true); true }
                else if (dy < 0 && isExpanded) { setExpanded(false); true }
                else false
            } else false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            setExpanded(!isExpanded)
            return true
        }
    })

    init {
        LayoutInflater.from(context).inflate(R.layout.debug_toolbar, this, true)

        expandIcon = findViewById(R.id.debugExpandIcon)
        statusText = findViewById(R.id.debugStatus)
        exportStatusText = findViewById(R.id.debugExportStatus)
        toolbarContent = findViewById(R.id.debugToolbarContent)
        header = findViewById(R.id.debugToolbarHeader)

        setupClickListeners()
        ExportStatusManager.addListener(exportListener)
    }

    private fun setupClickListeners() {
        // Handle touches on both the header and the outer card so the entire
        // collapsed bar is a tap/swipe target (the header can be only a few dp
        // tall when the system status bar consumes some of the top touch area).
        val touchDelegate = android.view.View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        header.setOnTouchListener(touchDelegate)
        setOnTouchListener { _, event ->
            // Only intercept touches when collapsed — expanded content has its own buttons.
            if (!isExpanded) {
                gestureDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }

        findViewById<MaterialButton>(R.id.btnTriggerCrash).setOnClickListener {
            recordAction("Crash triggered")
            listener?.onTriggerCrash()
        }

        findViewById<MaterialButton>(R.id.btnTriggerAnr).setOnClickListener {
            recordAction("ANR triggered")
            listener?.onTriggerAnr()
        }

        findViewById<MaterialButton>(R.id.btnTriggerHttp500).setOnClickListener {
            recordAction("HTTP 500 forced")
            listener?.onTriggerHttp500()
        }

        findViewById<MaterialButton>(R.id.btnTriggerMemory).setOnClickListener {
            recordAction("Memory pressure")
            listener?.onTriggerMemoryPressure()
        }

        findViewById<MaterialButton>(R.id.btnTriggerJank).setOnClickListener {
            recordAction("Jank forced")
            listener?.onTriggerJank()
        }

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            recordAction("Cleared")
            listener?.onClear()
        }

        findViewById<MaterialButton>(R.id.btnRingBuffer).setOnClickListener {
            recordAction("Buffer diagnostics")
            listener?.onOpenRingBuffer()
        }
    }

    private fun recordAction(message: String) {
        lastAction = message
        // Show inline while expanded
        statusText.text = message
        // Collapse after a short delay so user sees the action then toolbar closes
        scope.launch {
            delay(400)
            setExpanded(false)
        }
    }

    fun setExpanded(expand: Boolean) {
        if (isExpanded == expand) return
        isExpanded = expand

        // Rotate chevron
        ObjectAnimator.ofFloat(expandIcon, View.ROTATION, if (expand) 180f else 0f)
            .setDuration(200)
            .start()

        statusText.text = lastAction ?: context.getString(R.string.ready)

        if (expand) {
            toolbarContent.isVisible = true
            toolbarContent.alpha = 0f
            toolbarContent.animate().alpha(1f).setDuration(200).start()
        } else {
            toolbarContent.animate().alpha(0f).setDuration(200)
                .withEndAction { toolbarContent.isVisible = false }
                .start()
        }
    }

    fun collapse() {
        setExpanded(false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ExportStatusManager.removeListener(exportListener)
        scope.cancel()
    }

    interface DebugToolbarListener {
        fun onTriggerCrash()
        fun onTriggerAnr()
        fun onTriggerHttp500()
        fun onTriggerMemoryPressure()
        fun onTriggerJank()
        fun onClear()
        fun onOpenRingBuffer()
    }
}
