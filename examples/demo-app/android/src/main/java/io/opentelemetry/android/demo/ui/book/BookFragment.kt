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
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Appointment booking form.
 *
 * ## Trace hierarchy
 *
 * Auto-capture wires a page span automatically via [AutoCaptureManager.startPageSpan] on
 * fragment resume. That span is always sampled by DynamicSampler (name prefix `page.*`),
 * so every user interaction on this screen appears in the same trace waterfall:
 *
 *   page.BookFragment  ← auto-created, always sampled
 *   ├── ui.tap          ← spinner/button taps auto-captured as child spans
 *   ├── ui.tap
 *   ├── booking.submit  ← explicit child span created in bookAppointment()
 *   │   └── POST /posts ← OkHttp auto-instrumented child span
 *   └── ui.swipe
 *
 * Spinner events (form.provider_selected etc.) are attached to Span.current() which resolves
 * to the active page span — they appear as span events, not separate spans.
 *
 * ## Manual span: booking.submit
 *
 * bookAppointment() captures pageContext = Context.current() before launching the coroutine
 * to preserve the page span reference across the thread switch. booking.submit is then
 * started with that context as explicit parent, so it nests correctly under page.BookFragment
 * even after withContext(Dispatchers.IO) inside AppointmentRepository.
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

        MobileOtel.sendEvent("screen.viewed", mapOf("screen.name" to "BookFragment"), Severity.INFO)

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
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (!spinnersReady) return
                Span.current().addEvent(
                    "form.provider_selected",
                    Attributes.of(AttributeKey.stringKey("provider"), parent.getItemAtPosition(pos).toString())
                )
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val slotAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            AppointmentRepository.timeSlots
        )
        spinnerTimeSlot.adapter = slotAdapter
        spinnerTimeSlot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (!spinnersReady) return
                Span.current().addEvent(
                    "form.time_slot_selected",
                    Attributes.of(AttributeKey.stringKey("time_slot"), parent.getItemAtPosition(pos).toString())
                )
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val typeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            AppointmentType.values().map { it.label }
        )
        spinnerType.adapter = typeAdapter
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                if (!spinnersReady) return
                Span.current().addEvent(
                    "form.type_selected",
                    Attributes.of(AttributeKey.stringKey("appointment.type"), parent.getItemAtPosition(pos).toString())
                )
            }
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
                Span.current().addEvent(
                    "form.date_selected",
                    Attributes.of(AttributeKey.stringKey("date"), tvSelectedDate.text.toString())
                )
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

        // Add a form.submitted event to the current page span so every interaction
        // on this screen is visible in one trace (page.BookFragment).
        Span.current().addEvent(
            "form.submitted",
            Attributes.of(
                AttributeKey.stringKey("provider"), provider,
                AttributeKey.stringKey("appointment.type"), type.label,
                AttributeKey.stringKey("time_slot"), timeSlot
            )
        )

        setLoading(true)
        tvResult.isVisible = false

        // Capture the page context now so the booking child span is parented correctly
        // even after withContext(Dispatchers.IO) switches threads.
        val pageContext = io.opentelemetry.context.Context.current()

        viewLifecycleOwner.lifecycleScope.launch {
            // Create an explicit child span for this booking attempt so each booking
            // is a distinct unit in the trace waterfall without ending the page span.
            val bookingSpan = OTelMobile.getTracer("schedulr.interactions")
                .spanBuilder("booking.submit")
                .setParent(pageContext)
                .setAttribute("provider", provider)
                .setAttribute("appointment.type", type.label)
                .setAttribute("time_slot", timeSlot)
                .startSpan()
            val bookingScope = bookingSpan.makeCurrent()
            try {
                val appt = AppointmentRepository.bookAppointment(
                    requireContext(), provider, selectedDateMs, timeSlot, type, notes
                )

                bookingSpan.setAttribute(AttributeKey.stringKey("result.appointment_id"), appt.id)
                bookingSpan.setStatus(StatusCode.OK)

                setLoading(false)
                showSuccess(
                    "Appointment booked!\n${appt.title} with ${appt.provider}\n" +
                    "${dateFormat.format(java.util.Date(appt.dateMs))} at ${appt.timeSlot}"
                )
                MobileOtel.sendEvent("appointment.booked", mapOf(
                    "provider"         to provider,
                    "appointment.type" to type.label,
                    "time_slot"        to timeSlot
                ), Severity.INFO)
                etNotes.text.clear()

            } catch (e: DuplicateAppointmentException) {
                bookingSpan.addEvent(
                    "appointment.duplicate",
                    Attributes.of(
                        AttributeKey.stringKey("provider"), provider,
                        AttributeKey.stringKey("time_slot"), timeSlot,
                        AttributeKey.stringKey("error.message"), e.message ?: ""
                    )
                )
                bookingSpan.setStatus(StatusCode.ERROR, "duplicate appointment")
                setLoading(false)
                showError("Duplicate booking: ${e.message}")
                MobileOtel.sendEvent("appointment.duplicate", mapOf(
                    "provider"      to provider,
                    "time_slot"     to timeSlot,
                    "error.message" to (e.message ?: "")
                ), Severity.WARN)

            } catch (e: ApiException) {
                bookingSpan.recordException(e)
                bookingSpan.setAttribute(AttributeKey.longKey("http.status_code"), e.httpStatus.toLong())
                bookingSpan.setStatus(StatusCode.ERROR, e.message ?: "")
                setLoading(false)
                showError("Booking failed (HTTP ${e.httpStatus}): ${e.message}")
                MobileOtel.sendEvent("appointment.booking_failed", mapOf(
                    "http.status_code" to e.httpStatus,
                    "provider"         to provider,
                    "error.message"    to (e.message ?: "unknown")
                ), Severity.ERROR)

            } catch (e: Exception) {
                bookingSpan.recordException(e)
                bookingSpan.setStatus(StatusCode.ERROR, e.message ?: "")
                setLoading(false)
                showError("Unexpected error: ${e.message}")
                MobileOtel.reportError(e, mapOf(
                    "context"  to "appointment_booking",
                    "provider" to provider
                ))

            } finally {
                bookingScope.close()
                bookingSpan.end()
            }
        }
    }

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
}
