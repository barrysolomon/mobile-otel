// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.scheduling

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import io.opentelemetry.android.demo.ConfigActivity
import io.opentelemetry.android.demo.Dash0ConfigActivity
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.data.AppointmentRepository
import io.opentelemetry.android.demo.data.model.AppointmentStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SchedulingProfileFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_scheduling_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val appointments = AppointmentRepository.getMockAppointments()
        val upcoming = appointments.filter { it.dateMs > System.currentTimeMillis() }
        val confirmed = appointments.count { it.status == AppointmentStatus.CONFIRMED }
        val pending = appointments.count { it.status == AppointmentStatus.PENDING }

        val nextAppt = upcoming.minByOrNull { it.dateMs }
        val dateFormat = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())

        view.findViewById<TextView>(R.id.tvPatientName).text    = "Alex Johnson"
        view.findViewById<TextView>(R.id.tvPatientId).text      = "Patient ID: #MRN-847291"
        view.findViewById<TextView>(R.id.tvUpcomingCount).text  = "${upcoming.size}"
        view.findViewById<TextView>(R.id.tvConfirmedCount).text = "$confirmed"
        view.findViewById<TextView>(R.id.tvPendingCount).text   = "$pending"
        view.findViewById<TextView>(R.id.tvNextAppointment).text = nextAppt?.let {
            "${it.title} with ${it.provider}\n${dateFormat.format(Date(it.dateMs))}"
        } ?: "No upcoming appointments"

        view.findViewById<MaterialButton>(R.id.btnOpenOtelConfig).setOnClickListener {
            startActivity(Intent(requireContext(), ConfigActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btnOpenDash0Config).setOnClickListener {
            startActivity(Intent(requireContext(), Dash0ConfigActivity::class.java))
        }

        val versionName = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) { "unknown" }
        view.findViewById<TextView>(R.id.tvAppVersion).text = "v$versionName"
    }
}
