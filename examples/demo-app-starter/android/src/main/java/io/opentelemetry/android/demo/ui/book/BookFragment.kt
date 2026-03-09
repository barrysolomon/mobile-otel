// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.book

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.data.AppointmentRepository
import io.opentelemetry.android.demo.data.AppointmentRepository.ApiException
import io.opentelemetry.android.demo.data.AppointmentRepository.DuplicateAppointmentException
import io.opentelemetry.android.demo.data.model.AppointmentType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Appointment booking form.
 *
 * Provides a form for the user to select a provider, date, time slot, appointment type,
 * and optional notes. On submit, calls AppointmentRepository.bookAppointment() and shows
 * the result to the user.
 */
class BookFragment : Fragment() {

    private lateinit var spinnerProvider: Spinner
    private lateinit var spinnerTimeSlot: Spinner
    private lateinit var spinnerType: Spinner
    private lateinit var tvSelectedDate: TextView
    private lateinit var btnPickDate: Button
    private lateinit var etNotes: EditText
    private lateinit var btnBook: Button
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var tvResult: TextView

    // Suppress first programmatic onItemSelected when adapters are set
    private var spinnersReady = false

    private var selectedDateMs: Long = run {
        Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
    }

    private val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_book, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        spinnerProvider   = view.findViewById(R.id.spinnerProvider)
        spinnerTimeSlot   = view.findViewById(R.id.spinnerTimeSlot)
        spinnerType       = view.findViewById(R.id.spinnerType)
        tvSelectedDate    = view.findViewById(R.id.tvSelectedDate)
        btnPickDate       = view.findViewById(R.id.btnPickDate)
        etNotes           = view.findViewById(R.id.etNotes)
        btnBook           = view.findViewById(R.id.btnBook)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        tvResult          = view.findViewById(R.id.tvResult)

        setupSpinners()
        updateDateDisplay()
        spinnersReady = true

        btnPickDate.setOnClickListener { showDatePicker() }
        btnBook.setOnClickListener { bookAppointment() }
    }

    private fun setupSpinners() {
        val providerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            AppointmentRepository.providers
        )
        spinnerProvider.adapter = providerAdapter
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {}
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val slotAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            AppointmentRepository.timeSlots
        )
        spinnerTimeSlot.adapter = slotAdapter
        spinnerTimeSlot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {}
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val typeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            AppointmentType.values().map { it.label }
        )
        spinnerType.adapter = typeAdapter
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {}
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun updateDateDisplay() {
        tvSelectedDate.text = dateFormat.format(java.util.Date(selectedDateMs))
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                selectedDateMs = Calendar.getInstance().apply {
                    set(year, month, day, 9, 0, 0)
                }.timeInMillis
                updateDateDisplay()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).also { it.datePicker.minDate = System.currentTimeMillis() }.show()
    }

    private fun bookAppointment() {
        val provider  = spinnerProvider.selectedItem.toString()
        val timeSlot  = spinnerTimeSlot.selectedItem.toString()
        val typeLabel = spinnerType.selectedItem.toString()
        val type      = AppointmentType.values().first { it.label == typeLabel }
        val notes     = etNotes.text.toString().trim()

        // --- form validation ---
        if (provider.isBlank() || timeSlot.isBlank()) {
            showError("Please select a provider and time slot.")
            return
        }

        setLoading(true)
        tvResult.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appt = AppointmentRepository.bookAppointment(
                    requireContext(), provider, selectedDateMs, timeSlot, type, notes
                )
                setLoading(false)
                showSuccess(
                    "Appointment booked!\n${appt.title} with ${appt.provider}\n" +
                    "${dateFormat.format(java.util.Date(appt.dateMs))} at ${appt.timeSlot}"
                )
                etNotes.text.clear()

            } catch (e: DuplicateAppointmentException) {
                setLoading(false)
                showError("Duplicate booking: ${e.message}")

            } catch (e: ApiException) {
                setLoading(false)
                showError("Booking failed (HTTP ${e.httpStatus}): ${e.message}")

            } catch (e: Exception) {
                setLoading(false)
                showError("Unexpected error: ${e.message}")
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        btnBook.isEnabled = !loading
        progressIndicator.isVisible = loading
    }

    private fun showSuccess(msg: String) {
        tvResult.text = msg
        tvResult.setTextColor(requireContext().getColor(R.color.success))
        tvResult.isVisible = true
        Toast.makeText(requireContext(), "Appointment confirmed!", Toast.LENGTH_SHORT).show()
    }

    private fun showError(msg: String) {
        tvResult.text = msg
        tvResult.setTextColor(requireContext().getColor(R.color.error))
        tvResult.isVisible = true
    }

    /** Days from now until the selected appointment date (minimum 0). */
    @Suppress("UNUSED")
    private fun daysAhead(dateMs: Long): Long {
        val diff = dateMs - System.currentTimeMillis()
        return TimeUnit.MILLISECONDS.toDays(diff).coerceAtLeast(0)
    }
}
