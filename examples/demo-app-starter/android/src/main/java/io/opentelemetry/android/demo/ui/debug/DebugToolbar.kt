// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.debug

import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import io.opentelemetry.android.demo.R
import kotlinx.coroutines.*

/**
 * Collapsible debug toolbar for triggering error scenarios.
 *
 * Features:
 * - Small collapsed state (single row with expand icon)
 * - Expanded state (2 rows of trigger buttons)
 * - Status indicator
 * - Smooth animations
 */
class DebugToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val expandIcon: ImageView
    private val statusText: TextView
    private val toolbarContent: LinearLayout
    private val header: LinearLayout

    private var isExpanded = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var listener: DebugToolbarListener? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.debug_toolbar, this, true)

        expandIcon = findViewById(R.id.debugExpandIcon)
        statusText = findViewById(R.id.debugStatus)
        toolbarContent = findViewById(R.id.debugToolbarContent)
        header = findViewById(R.id.debugToolbarHeader)

        setupClickListeners()
    }

    private fun setupClickListeners() {
        // Toggle expand/collapse
        header.setOnClickListener {
            toggleExpanded()
        }

        // Trigger buttons
        findViewById<MaterialButton>(R.id.btnTriggerCrash).setOnClickListener {
            listener?.onTriggerCrash()
            showStatus("Triggering crash...")
        }

        findViewById<MaterialButton>(R.id.btnTriggerAnr).setOnClickListener {
            listener?.onTriggerAnr()
            showStatus("Blocking main thread...")
        }

        findViewById<MaterialButton>(R.id.btnTriggerHttp500).setOnClickListener {
            listener?.onTriggerHttp500()
            showStatus("Forcing HTTP 500...")
        }

        findViewById<MaterialButton>(R.id.btnTriggerMemory).setOnClickListener {
            listener?.onTriggerMemoryPressure()
            showStatus("Allocating memory...")
        }

        findViewById<MaterialButton>(R.id.btnTriggerJank).setOnClickListener {
            listener?.onTriggerJank()
            showStatus("Forcing jank...")
        }

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            listener?.onClear()
            showStatus("Cleared")
        }

        findViewById<MaterialButton>(R.id.btnRingBuffer).setOnClickListener {
            listener?.onOpenRingBuffer()
            showStatus("Opening buffer diagnostics...")
        }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded

        // Animate content visibility
        if (isExpanded) {
            toolbarContent.isVisible = true
            toolbarContent.alpha = 0f
            toolbarContent.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        } else {
            toolbarContent.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    toolbarContent.isVisible = false
                }
                .start()
        }

        // Rotate expand icon
        val rotation = if (isExpanded) 180f else 0f
        ObjectAnimator.ofFloat(expandIcon, View.ROTATION, rotation)
            .setDuration(200)
            .start()
    }

    private fun showStatus(message: String) {
        statusText.text = message

        // Auto-reset after 2 seconds
        scope.launch {
            delay(2000)
            if (statusText.text == message) {
                statusText.text = context.getString(R.string.ready)
            }
        }
    }

    fun collapse() {
        if (isExpanded) {
            toggleExpanded()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
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
