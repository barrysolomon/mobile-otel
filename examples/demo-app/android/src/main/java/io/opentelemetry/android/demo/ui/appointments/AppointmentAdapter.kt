// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.appointments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.data.model.Appointment
import io.opentelemetry.android.demo.data.model.AppointmentStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppointmentAdapter : ListAdapter<Appointment, AppointmentAdapter.ViewHolder>(DIFF) {

    private val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_appointment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView    = itemView.findViewById(R.id.tvApptTitle)
        private val tvProvider: TextView = itemView.findViewById(R.id.tvApptProvider)
        private val tvDateTime: TextView = itemView.findViewById(R.id.tvApptDateTime)
        private val tvType: TextView     = itemView.findViewById(R.id.tvApptType)
        private val tvStatus: TextView   = itemView.findViewById(R.id.tvApptStatus)

        fun bind(appt: Appointment) {
            tvTitle.text    = appt.title
            tvProvider.text = appt.provider
            tvDateTime.text = "${dateFormat.format(Date(appt.dateMs))} • ${appt.timeSlot}"
            tvType.text     = appt.type.label

            tvStatus.text = appt.status.label
            val statusColor = when (appt.status) {
                AppointmentStatus.CONFIRMED -> R.color.success
                AppointmentStatus.PENDING   -> R.color.warning
                AppointmentStatus.CANCELLED -> R.color.error
            }
            tvStatus.setTextColor(ContextCompat.getColor(itemView.context, statusColor))
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Appointment>() {
            override fun areItemsTheSame(a: Appointment, b: Appointment) = a.id == b.id
            override fun areContentsTheSame(a: Appointment, b: Appointment) = a == b
        }
    }
}
