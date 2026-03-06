// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.calendar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.data.AppointmentRepository
import io.opentelemetry.android.demo.data.model.Appointment
import io.opentelemetry.android.demo.ui.appointments.AppointmentAdapter
import io.opentelemetry.android.mobile.MobileOtel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CalendarFragment : Fragment() {

    private lateinit var tvMonthYear: TextView
    private lateinit var calendarGrid: RecyclerView
    private lateinit var tvDayHeader: TextView
    private lateinit var dayAppointmentsList: RecyclerView

    private lateinit var calendarAdapter: CalendarDayAdapter
    private lateinit var dayAppointmentsAdapter: AppointmentAdapter

    private val displayedCalendar = Calendar.getInstance()
    private var allAppointments: List<Appointment> = AppointmentRepository.getMockAppointments()

    private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_calendar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvMonthYear = view.findViewById(R.id.tvMonthYear)
        calendarGrid = view.findViewById(R.id.calendarGrid)
        tvDayHeader = view.findViewById(R.id.tvDayHeader)
        dayAppointmentsList = view.findViewById(R.id.dayAppointmentsList)

        calendarAdapter = CalendarDayAdapter { day -> onDaySelected(day) }
        calendarGrid.layoutManager = GridLayoutManager(requireContext(), 7)
        calendarGrid.adapter = calendarAdapter

        dayAppointmentsAdapter = AppointmentAdapter()
        dayAppointmentsList.layoutManager = LinearLayoutManager(requireContext())
        dayAppointmentsList.adapter = dayAppointmentsAdapter

        view.findViewById<View>(R.id.btnPrevMonth).setOnClickListener {
            displayedCalendar.add(Calendar.MONTH, -1)
            renderMonth()
        }
        view.findViewById<View>(R.id.btnNextMonth).setOnClickListener {
            displayedCalendar.add(Calendar.MONTH, 1)
            renderMonth()
        }

        renderMonth()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.calendar_menu, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_load_history) {
            loadFullHistory()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun renderMonth() {
        tvMonthYear.text = monthFormat.format(displayedCalendar.time)

        val today = Calendar.getInstance()
        val isCurrentMonth = displayedCalendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                displayedCalendar.get(Calendar.MONTH) == today.get(Calendar.MONTH)
        val todayOfMonth = if (isCurrentMonth) today.get(Calendar.DAY_OF_MONTH) else -1

        val monthCal = displayedCalendar.clone() as Calendar
        monthCal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = monthCal.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sun
        val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val apptsByDay = allAppointments
            .filter { apt ->
                val c = Calendar.getInstance().apply { timeInMillis = apt.dateMs }
                c.get(Calendar.YEAR) == displayedCalendar.get(Calendar.YEAR) &&
                        c.get(Calendar.MONTH) == displayedCalendar.get(Calendar.MONTH)
            }
            .groupBy { apt ->
                Calendar.getInstance().apply { timeInMillis = apt.dateMs }
                    .get(Calendar.DAY_OF_MONTH)
            }

        val days = mutableListOf<CalendarDay>()
        repeat(firstDayOfWeek) { days.add(CalendarDay(0)) }
        for (d in 1..daysInMonth) {
            days.add(CalendarDay(
                dayOfMonth = d,
                isToday = d == todayOfMonth,
                appointmentCount = apptsByDay[d]?.size ?: 0
            ))
        }
        // Pad to complete last row
        val remainder = days.size % 7
        if (remainder != 0) repeat(7 - remainder) { days.add(CalendarDay(0)) }

        calendarAdapter.submitDays(days, todayOfMonth)

        val selectedDay = if (isCurrentMonth) todayOfMonth else 1
        onDaySelected(selectedDay)
    }

    private fun onDaySelected(day: Int) {
        if (day <= 0) return
        val cal = displayedCalendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, day)
        tvDayHeader.text = dayFormat.format(cal.time)

        val appts = allAppointments.filter { apt ->
            val c = Calendar.getInstance().apply { timeInMillis = apt.dateMs }
            c.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                    c.get(Calendar.MONTH) == cal.get(Calendar.MONTH) &&
                    c.get(Calendar.DAY_OF_MONTH) == day
        }
        dayAppointmentsAdapter.submitList(appts)
        tvDayHeader.text = if (appts.isEmpty())
            "${dayFormat.format(cal.time)} — No appointments"
        else
            dayFormat.format(cal.time)
    }

    /**
     * Loads 500 historical appointments to demonstrate memory pressure.
     * Emits a calendar.history_loaded OTel event.
     */
    private fun loadFullHistory() {
        Toast.makeText(requireContext(), "Loading full appointment history...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val history = AppointmentRepository.loadFullHistory(requireContext())
                allAppointments = history
                renderMonth()
                MobileOtel.sendEvent("calendar.history_loaded", mapOf(
                    "count" to history.size,
                    "month" to monthFormat.format(displayedCalendar.time)
                ))
                Toast.makeText(requireContext(), "Loaded ${history.size} appointments", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load history: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
