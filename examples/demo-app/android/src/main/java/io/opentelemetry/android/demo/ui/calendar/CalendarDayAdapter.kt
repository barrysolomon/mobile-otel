// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.calendar

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.opentelemetry.android.demo.R

data class CalendarDay(
    val dayOfMonth: Int,    // 0 = padding cell (before/after month)
    val isToday: Boolean = false,
    val appointmentCount: Int = 0
)

class CalendarDayAdapter(
    private val onDayClick: (dayOfMonth: Int) -> Unit
) : RecyclerView.Adapter<CalendarDayAdapter.DayViewHolder>() {

    private var days: List<CalendarDay> = emptyList()
    private var selectedDay: Int = -1

    fun submitDays(days: List<CalendarDay>, todayOfMonth: Int) {
        this.days = days
        this.selectedDay = todayOfMonth
        notifyDataSetChanged()
    }

    fun selectDay(day: Int) {
        val prev = selectedDay
        selectedDay = day
        days.indexOfFirst { it.dayOfMonth == prev }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        days.indexOfFirst { it.dayOfMonth == day }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calendar_day, parent, false)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        val day = days[position]
        val isSelected = day.dayOfMonth > 0 && day.dayOfMonth == selectedDay
        holder.bind(day, isSelected)
        if (day.dayOfMonth > 0) {
            holder.itemView.setOnClickListener {
                selectDay(day.dayOfMonth)
                onDayClick(day.dayOfMonth)
            }
        } else {
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = days.size

    inner class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDay: TextView = itemView.findViewById(R.id.tvDayNumber)
        private val dotsRow: LinearLayout = itemView.findViewById(R.id.dotsContainer)

        fun bind(day: CalendarDay, isSelected: Boolean) {
            if (day.dayOfMonth <= 0) {
                tvDay.text = ""
                dotsRow.removeAllViews()
                itemView.isClickable = false
                return
            }

            tvDay.text = day.dayOfMonth.toString()
            itemView.isClickable = true

            when {
                isSelected -> {
                    tvDay.setBackgroundResource(R.drawable.selected_day_bg)
                    tvDay.setTextColor(Color.WHITE)
                }
                day.isToday -> {
                    tvDay.setBackgroundResource(R.drawable.today_day_bg)
                    tvDay.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary))
                }
                else -> {
                    tvDay.background = null
                    tvDay.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                }
            }

            dotsRow.removeAllViews()
            val dotSizePx = (6 * itemView.context.resources.displayMetrics.density).toInt()
            val dotMarginPx = (2 * itemView.context.resources.displayMetrics.density).toInt()
            repeat(minOf(day.appointmentCount, 3)) {
                val dot = View(itemView.context).apply {
                    setBackgroundResource(R.drawable.appointment_dot)
                    layoutParams = LinearLayout.LayoutParams(dotSizePx, dotSizePx).apply {
                        marginEnd = dotMarginPx
                    }
                }
                dotsRow.addView(dot)
            }
        }
    }
}
