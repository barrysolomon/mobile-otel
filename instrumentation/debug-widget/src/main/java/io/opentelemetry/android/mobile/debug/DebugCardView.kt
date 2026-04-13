// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.opentelemetry.android.mobile.export.ExportStatus

/**
 * Dark translucent card that displays SDK state and device health in a 2-column grid.
 *
 * Built entirely programmatically -- no XML layouts required.
 * Toggled visible/gone by tapping the [DebugBadgeView].
 */
@SuppressLint("ViewConstructor")
class DebugCardView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val rows = mutableListOf<Pair<TextView, TextView>>()
    private var footerSessionText: TextView
    private var footerRefreshText: TextView

    private val colorLabel = 0xFF9E9EAE.toInt()
    private val colorValue = 0xFFFFFFFF.toInt()
    private val colorGreen = 0xFF66BB6A.toInt()
    private val colorRed = 0xFFEF5350.toInt()
    private val colorOrange = 0xFFFFB74D.toInt()
    private val colorDim = 0xFF6E6E7E.toInt()

    init {
        val cardWidth = (270 * density).toInt()
        layoutParams = FrameLayout.LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT)
        elevation = 24f * density

        val bg = GradientDrawable().apply {
            setColor(0xF5101020.toInt())
            cornerRadius = 14 * density
        }
        background = bg
        val pad = (16 * density).toInt()
        setPadding(pad, pad, pad, pad)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // Header
        val headerText = makeText("\u25CF OTel Debug", 13f, colorValue, Typeface.BOLD)
        container.addView(headerText)
        container.addView(makeDivider())

        // SDK state rows
        addRow(container, "RAM", "\u2014")          // 0
        addRow(container, "Disk", "\u2014")         // 1
        addRow(container, "Export", "\u2014")        // 2
        addRow(container, "Recovery", "\u2014")      // 3
        addRow(container, "Mode", "\u2014")          // 4
        container.addView(makeDivider())

        // Device health rows
        addRow(container, "Battery", "\u2014")       // 5
        addRow(container, "Memory", "\u2014")        // 6
        addRow(container, "Network", "\u2014")       // 7
        addRow(container, "Airplane", "\u2014")      // 8
        addRow(container, "Last flush", "\u2014")    // 9
        container.addView(makeDivider())

        // Footer
        val footerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        footerSessionText = makeText("Session \u2014", 10f, colorDim)
        footerRefreshText = makeText("\u21BB 2s", 10f, colorDim).apply {
            gravity = Gravity.END
        }
        footerSessionText.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        footerRefreshText.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        footerLayout.addView(footerSessionText)
        footerLayout.addView(footerRefreshText)
        container.addView(footerLayout)

        addView(container)
        visibility = View.GONE
    }

    private fun addRow(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (5 * density).toInt()
            }
        }
        val labelView = makeText(label, 12f, colorLabel)
        labelView.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        val valueView = makeText(value, 12f, colorValue, Typeface.BOLD)
        valueView.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        valueView.gravity = Gravity.END
        row.addView(labelView)
        row.addView(valueView)
        parent.addView(row)
        rows.add(labelView to valueView)
    }

    private fun makeText(text: String, sizeSp: Float, color: Int, style: Int = Typeface.NORMAL): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = Typeface.create("monospace", style)
        }
    }

    private fun makeDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            setBackgroundColor(0xFF2E2E42.toInt())
        }
    }

    /**
     * Updates the card with the latest [DebugWidgetDataSource.WidgetState].
     */
    fun update(state: DebugWidgetDataSource.WidgetState) {
        // SDK state
        val ramColor = if (state.ramEvents > state.ramCapacity * 0.8) colorOrange else colorGreen
        setRowValue(0, "${state.ramEvents}/${state.ramCapacity}", ramColor)
        setRowValue(1, "${state.diskEvents} events", colorValue)

        val (exportText, exportColor) = when (state.exportStatus) {
            is ExportStatus.Success -> "OK (${state.exportStatus.eventCount} evts)" to colorGreen
            is ExportStatus.AuthError -> "AUTH FAIL" to colorRed
            is ExportStatus.Failed -> "FAILED" to colorRed
            is ExportStatus.Retrying -> "Retry ${state.exportStatus.attempt}/${state.exportStatus.maxAttempts}" to colorOrange
            null -> "\u2014" to colorDim
        }
        setRowValue(2, exportText, exportColor)
        val (recoveryText, recoveryColor) = when (state.recoveryType) {
            "crash" -> "crash" to colorRed
            "anr_force_kill" -> "ANR" to colorRed
            "low_memory_kill" -> "OOM" to colorOrange
            "system_force_kill" -> "killed" to colorOrange
            "clean_start", null -> "ok" to colorGreen
            else -> (state.recoveryType ?: "ok") to colorValue
        }
        setRowValue(3, recoveryText, recoveryColor)

        // Export mode
        val (modeText, modeColor) = when (state.exportMode) {
            "CONTINUOUS" -> "CONTINUOUS" to colorGreen
            "CONDITIONAL" -> "CONDITIONAL" to colorOrange
            "HYBRID" -> "HYBRID" to 0xFF42A5F5.toInt()  // blue
            else -> state.exportMode to colorDim
        }
        setRowValue(4, modeText, modeColor)

        // Device health
        val battColor = if (state.batteryPercent <= 15) colorRed else colorValue
        setRowValue(5, if (state.batteryPercent >= 0) "${state.batteryPercent}%" else "\u2014", battColor)
        setRowValue(6, "${state.memoryAvailableMb} MB", colorValue)
        setRowValue(7, state.networkType, if (state.networkType == "none") colorRed else colorValue)
        setRowValue(8, if (state.airplaneMode) "ON" else "off",
            if (state.airplaneMode) colorOrange else colorGreen)

        val flushAgo = if (state.lastExportTimeMs > 0) {
            val secs = (System.currentTimeMillis() - state.lastExportTimeMs) / 1000
            if (secs < 60) "${secs}s ago" else "${secs / 60}m ago"
        } else "\u2014"
        setRowValue(9, flushAgo, colorValue)

        // Footer
        val shortSession = if (state.sessionId.length > 12)
            "${state.sessionId.take(4)}...${state.sessionId.takeLast(4)}"
        else state.sessionId
        footerSessionText.text = "Session $shortSession"
    }

    private fun setRowValue(index: Int, text: String, color: Int) {
        if (index < rows.size) {
            rows[index].second.text = text
            rows[index].second.setTextColor(color)
        }
    }

    /**
     * Toggles the card visibility between [View.VISIBLE] and [View.GONE].
     */
    fun toggle() {
        visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
}
