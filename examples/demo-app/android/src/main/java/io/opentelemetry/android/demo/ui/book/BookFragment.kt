// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.book

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Appointment booking form — fully instrumented.
 *
 * ## Trace hierarchy
 *
 * Auto-capture wires a page span automatically via [AutoCaptureManager.startPageSpan] on
 * fragment resume. That span is always sampled by DynamicSampler (name prefix `page.*`),
 * so every user interaction on this screen appears in the same trace waterfall:
 *
 *   page.BookFragment      ← auto-created, always sampled
 *   ├── ui.tap             ← spinner/button taps auto-captured as child spans
 *   ├── ui.tap
 *   ├── booking.submit     ← explicit child span created in bookAppointment()
 *   │   ├── (span event) form.validation_passed
 *   │   ├── (span event) booking.device_context
 *   │   └── POST /posts    ← OkHttp auto-instrumented child span
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
 *
 * ## What is captured
 *
 * Booking span attributes:
 *   - provider, appointment.type, time_slot, booking.notes_provided
 *   - booking.date_days_ahead — how far ahead is the appointment
 *   - booking.form_fill_time_ms — elapsed time from screen open to submit tap
 *   - booking.retry_count — how many times the user has hit "Book" in this session
 *   - device.battery_level — integer 0-100 at submit time
 *   - device.battery_charging — whether device is plugged in
 *   - device.network_type — wifi / cellular / ethernet / none
 *   - device.low_memory — ActivityManager.isLowRamDevice()
 *   - session.id — current session UUID from MobileOtel
 *
 * Booking span events:
 *   - form.submitted — with all form field values at submit time
 *   - booking.device_context — battery + network snapshot
 *   - form.validation_passed / form.validation_failed
 *   - booking.success / appointment.duplicate / booking.api_error / booking.unexpected_error
 *
 * Success log event (body="appointment.booked"):
 *   - provider, appointment.type, time_slot, result.appointment_id
 *   - booking.duration_ms — time from span start to HTTP response
 *   - booking.form_fill_time_ms — time from screen open to submit
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

    /** Timestamp when this screen was opened — used for form-fill-time measurement. */
    private var screenOpenedAtMs: Long = 0L

    /** Number of booking attempts in this screen session. */
    private val bookingAttemptCount = AtomicInteger(0)

    // UJ-006: journey replay. The booking flow is a "user journey" — start
    // when the form opens, end on success/failure/cancel. All page spans,
    // taps, screenshots, wireframes, and any errors emitted in between are
    // automatically nested under this span via OTel Context propagation,
    // so the control plane can render a coherent journey timeline.
    private var journeySpan: Span? = null
    private var journeyScope: io.opentelemetry.context.Scope? = null

    private fun endJourneyIfActive(outcome: String) {
        val span = journeySpan ?: return
        span.setAttribute("journey.outcome", outcome)
        // Order matters: keep the scope open across endJourney so the
        // captureScreenshot/captureWireframe calls inside endJourney pick up
        // the journey span as Context.current() and emit log records that
        // carry the journey trace_id (UJ-003 stitching contract). Close the
        // scope AFTER, since endJourney calls Span.end() internally and the
        // scope only ties Context.current() to the still-active span.
        OTelMobile.endJourney(span)
        journeyScope?.close()
        journeyScope = null
        journeySpan = null
    }

    /** Tracks which form fields were changed from their defaults. */
    private val changedFields = mutableSetOf<String>()

    private val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_book, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        screenOpenedAtMs = System.currentTimeMillis()

        // Start the booking journey — must run before any auto-instrumented
        // page span starts so the page span can nest under the journey.
        // makeCurrent() ties Context.current() on this thread to the journey
        // span; subsequent OTelMobile.captureScreenshot/Wireframe calls
        // automatically pin the journey trace_id on emitted log records
        // (UJ-003 / UJ-005).
        val journey = OTelMobile.startJourney("book_appointment")
        journey.setAttribute("journey.kind", "booking_flow")
        journeyScope = journey.makeCurrent()
        journeySpan = journey
        OTelMobile.captureScreenshot("journey_start")
        OTelMobile.captureWireframe("journey_start")

        spinnerProvider   = view.findViewById(R.id.spinnerProvider)
        spinnerTimeSlot   = view.findViewById(R.id.spinnerTimeSlot)
        spinnerType       = view.findViewById(R.id.spinnerType)
        tvSelectedDate    = view.findViewById(R.id.tvSelectedDate)
        btnPickDate       = view.findViewById(R.id.btnPickDate)
        etNotes           = view.findViewById(R.id.etNotes)
        btnBook           = view.findViewById(R.id.btnBook)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        tvResult          = view.findViewById(R.id.tvResult)

        MobileOtel.sendEvent("screen.viewed", mapOf(
            "screen.name" to "BookFragment"
        ), Severity.INFO)

        setupSpinners()
        updateDateDisplay()
        spinnersReady = true

        btnPickDate.setOnClickListener { showDatePicker() }
        btnBook.setOnClickListener { bookAppointment() }
    }

    override fun onDestroyView() {
        // If the user navigated away without completing the booking, end the
        // journey with an "abandoned" outcome so the replay timeline is still
        // closed — no orphan journey spans.
        endJourneyIfActive(outcome = "abandoned")
        super.onDestroyView()
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
                changedFields.add("provider")
                Span.current().addEvent(
                    "form.provider_selected",
                    Attributes.of(
                        AttributeKey.stringKey("provider"), parent.getItemAtPosition(pos).toString(),
                        AttributeKey.longKey("form.elapsed_ms"), System.currentTimeMillis() - screenOpenedAtMs
                    )
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
                changedFields.add("time_slot")
                Span.current().addEvent(
                    "form.time_slot_selected",
                    Attributes.of(
                        AttributeKey.stringKey("time_slot"), parent.getItemAtPosition(pos).toString(),
                        AttributeKey.longKey("form.elapsed_ms"), System.currentTimeMillis() - screenOpenedAtMs
                    )
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
                changedFields.add("appointment_type")
                Span.current().addEvent(
                    "form.type_selected",
                    Attributes.of(
                        AttributeKey.stringKey("appointment.type"), parent.getItemAtPosition(pos).toString(),
                        AttributeKey.longKey("form.elapsed_ms"), System.currentTimeMillis() - screenOpenedAtMs
                    )
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
                changedFields.add("date")
                Span.current().addEvent(
                    "form.date_selected",
                    Attributes.of(
                        AttributeKey.stringKey("date"), tvSelectedDate.text.toString(),
                        AttributeKey.longKey("date.days_ahead"), daysAhead(selectedDateMs),
                        AttributeKey.longKey("form.elapsed_ms"), System.currentTimeMillis() - screenOpenedAtMs
                    )
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
        val retryCount = bookingAttemptCount.getAndIncrement()
        val formFillMs = System.currentTimeMillis() - screenOpenedAtMs

        // --- form validation ---
        if (provider.isBlank() || timeSlot.isBlank()) {
            Span.current().addEvent(
                "form.validation_failed",
                Attributes.of(
                    AttributeKey.stringKey("reason"), "missing required fields",
                    AttributeKey.longKey("booking.retry_count"), retryCount.toLong()
                )
            )
            showError("Please select a provider and time slot.")
            return
        }

        // Attach all form inputs as a single span event on the page span.
        Span.current().addEvent(
            "form.submitted",
            Attributes.builder()
                .put(AttributeKey.stringKey("provider"), provider)
                .put(AttributeKey.stringKey("appointment.type"), type.label)
                .put(AttributeKey.stringKey("time_slot"), timeSlot)
                .put(AttributeKey.booleanKey("booking.notes_provided"), notes.isNotEmpty())
                .put(AttributeKey.longKey("booking.notes_length"), notes.length.toLong())
                .put(AttributeKey.longKey("booking.date_days_ahead"), daysAhead(selectedDateMs))
                .put(AttributeKey.longKey("booking.form_fill_time_ms"), formFillMs)
                .put(AttributeKey.longKey("booking.retry_count"), retryCount.toLong())
                .put(AttributeKey.stringKey("booking.changed_fields"), changedFields.sorted().joinToString(","))
                .build()
        )

        setLoading(true)
        tvResult.isVisible = false

        // Capture the page context now so the booking child span is parented correctly
        // even after withContext(Dispatchers.IO) switches threads.
        val pageContext = io.opentelemetry.context.Context.current()

        // Snapshot device state at submit time — cheap reads, captured once.
        val deviceContext = captureDeviceContext()

        viewLifecycleOwner.lifecycleScope.launch {
            val bookingStartMs = System.currentTimeMillis()

            // Explicit child span for this booking attempt — nested under page.BookFragment.
            val bookingSpan = OTelMobile.getTracer("schedulr.interactions")
                .spanBuilder("booking.submit")
                .setParent(pageContext)
                // form inputs
                .setAttribute("provider", provider)
                .setAttribute("appointment.type", type.label)
                .setAttribute("time_slot", timeSlot)
                .setAttribute("booking.notes_provided", notes.isNotEmpty())
                .setAttribute("booking.notes_length", notes.length.toLong())
                .setAttribute("booking.date_days_ahead", daysAhead(selectedDateMs))
                // timing & retry
                .setAttribute("booking.form_fill_time_ms", formFillMs)
                .setAttribute("booking.retry_count", retryCount.toLong())
                .setAttribute("booking.changed_fields", changedFields.sorted().joinToString(","))
                // session id is auto-attached by SessionManager.enrichAttributes()
                // device state at submit time
                .setAttribute("device.battery_level", deviceContext.batteryLevel.toLong())
                .setAttribute("device.battery_charging", deviceContext.isCharging)
                .setAttribute("device.network_type", deviceContext.networkType)
                .setAttribute("device.low_memory", deviceContext.isLowMemory)
                .startSpan()
            val bookingScope = bookingSpan.makeCurrent()

            // Attach device context as a span event for easy querying.
            bookingSpan.addEvent(
                "booking.device_context",
                Attributes.builder()
                    .put(AttributeKey.longKey("device.battery_level"), deviceContext.batteryLevel.toLong())
                    .put(AttributeKey.booleanKey("device.battery_charging"), deviceContext.isCharging)
                    .put(AttributeKey.stringKey("device.network_type"), deviceContext.networkType)
                    .put(AttributeKey.booleanKey("device.low_memory"), deviceContext.isLowMemory)
                    .put(AttributeKey.longKey("device.available_memory_mb"), deviceContext.availableMemoryMb)
                    .put(AttributeKey.longKey("device.total_memory_mb"), deviceContext.totalMemoryMb)
                    .build()
            )

            bookingSpan.addEvent("form.validation_passed")

            try {
                val appt = AppointmentRepository.bookAppointment(
                    requireContext(), provider, selectedDateMs, timeSlot, type, notes
                )
                val bookingDurationMs = System.currentTimeMillis() - bookingStartMs

                bookingSpan.setAttribute(AttributeKey.stringKey("result.appointment_id"), appt.id)
                bookingSpan.setAttribute(AttributeKey.longKey("booking.duration_ms"), bookingDurationMs)
                bookingSpan.setStatus(StatusCode.OK)

                bookingSpan.addEvent(
                    "booking.success",
                    Attributes.of(
                        AttributeKey.stringKey("result.appointment_id"), appt.id,
                        AttributeKey.longKey("booking.duration_ms"), bookingDurationMs
                    )
                )

                setLoading(false)
                showSuccess(
                    "Appointment booked!\n${appt.title} with ${appt.provider}\n" +
                    "${dateFormat.format(java.util.Date(appt.dateMs))} at ${appt.timeSlot}"
                )
                MobileOtel.sendEvent("appointment.booked", mapOf(
                    "provider"              to provider,
                    "appointment.type"      to type.label,
                    "time_slot"             to timeSlot,
                    "result.appointment_id" to appt.id,
                    "booking.duration_ms"   to bookingDurationMs,
                    "booking.form_fill_time_ms" to formFillMs,
                    "booking.retry_count"   to retryCount
                ), Severity.INFO)
                etNotes.text.clear()
                changedFields.clear()

                // Journey complete — booking succeeded. Captures end-state
                // (form cleared, success message visible) and ends the span.
                endJourneyIfActive(outcome = "success")

            } catch (e: DuplicateAppointmentException) {
                val bookingDurationMs = System.currentTimeMillis() - bookingStartMs
                bookingSpan.addEvent(
                    "appointment.duplicate",
                    Attributes.of(
                        AttributeKey.stringKey("provider"), provider,
                        AttributeKey.stringKey("time_slot"), timeSlot,
                        AttributeKey.stringKey("error.message"), e.message ?: "",
                        AttributeKey.longKey("booking.duration_ms"), bookingDurationMs
                    )
                )
                bookingSpan.setStatus(StatusCode.ERROR, "duplicate appointment")
                setLoading(false)
                showError("Duplicate booking: ${e.message}")
                MobileOtel.sendEvent("appointment.duplicate", mapOf(
                    "provider"            to provider,
                    "time_slot"           to timeSlot,
                    "error.message"       to (e.message ?: ""),
                    "booking.retry_count" to retryCount
                ), Severity.WARN)

            } catch (e: ApiException) {
                val bookingDurationMs = System.currentTimeMillis() - bookingStartMs
                bookingSpan.recordException(e)
                bookingSpan.setAttribute(AttributeKey.longKey("http.status_code"), e.httpStatus.toLong())
                bookingSpan.setAttribute(AttributeKey.longKey("booking.duration_ms"), bookingDurationMs)
                bookingSpan.addEvent(
                    "booking.api_error",
                    Attributes.of(
                        AttributeKey.longKey("http.status_code"), e.httpStatus.toLong(),
                        AttributeKey.stringKey("error.message"), e.message ?: "",
                        AttributeKey.longKey("booking.duration_ms"), bookingDurationMs,
                        AttributeKey.stringKey("device.network_type"), deviceContext.networkType
                    )
                )
                bookingSpan.setStatus(StatusCode.ERROR, e.message ?: "")
                setLoading(false)
                showError("Booking failed (HTTP ${e.httpStatus}): ${e.message}")
                MobileOtel.sendEvent("appointment.booking_failed", mapOf(
                    "http.status_code"    to e.httpStatus,
                    "provider"            to provider,
                    "error.message"       to (e.message ?: "unknown"),
                    "booking.retry_count" to retryCount,
                    "device.network_type" to deviceContext.networkType,
                    "device.battery_level" to deviceContext.batteryLevel
                ), Severity.ERROR)

            } catch (e: Exception) {
                val bookingDurationMs = System.currentTimeMillis() - bookingStartMs
                bookingSpan.recordException(e)
                bookingSpan.setAttribute(AttributeKey.longKey("booking.duration_ms"), bookingDurationMs)
                bookingSpan.addEvent(
                    "booking.unexpected_error",
                    Attributes.of(
                        AttributeKey.stringKey("exception.type"), e.javaClass.name,
                        AttributeKey.stringKey("error.message"), e.message ?: "",
                        AttributeKey.longKey("booking.duration_ms"), bookingDurationMs
                    )
                )
                bookingSpan.setStatus(StatusCode.ERROR, e.message ?: "")
                setLoading(false)
                showError("Unexpected error: ${e.message}")
                MobileOtel.reportError(e, mapOf(
                    "context"               to "appointment_booking",
                    "provider"              to provider,
                    "booking.retry_count"   to retryCount.toString(),
                    "device.battery_level"  to deviceContext.batteryLevel.toString(),
                    "device.network_type"   to deviceContext.networkType
                ))

            } finally {
                bookingScope.close()
                bookingSpan.end()
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
    private fun daysAhead(dateMs: Long): Long {
        val diff = dateMs - System.currentTimeMillis()
        return TimeUnit.MILLISECONDS.toDays(diff).coerceAtLeast(0)
    }

    /**
     * Snapshot of device health indicators at booking submit time.
     *
     * All reads are fast (no I/O). Captured once per booking attempt and attached
     * to the booking span as attributes and as a "booking.device_context" span event.
     * This lets you correlate booking failures with poor device conditions in Dash0.
     */
    private fun captureDeviceContext(): DeviceContext {
        val ctx = requireContext()

        // Battery state
        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level  = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale  = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (scale > 0) (level * 100 / scale) else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

        // Network type
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkType = cm?.let {
            val caps = it.getNetworkCapabilities(it.activeNetwork)
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } ?: "unknown"

        // Memory
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val isLowMemory = memInfo.lowMemory

        return DeviceContext(
            batteryLevel     = batteryPct,
            isCharging       = isCharging,
            networkType      = networkType,
            isLowMemory      = isLowMemory,
            availableMemoryMb = availMb,
            totalMemoryMb    = totalMb
        )
    }

    private data class DeviceContext(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val networkType: String,
        val isLowMemory: Boolean,
        val availableMemoryMb: Long,
        val totalMemoryMb: Long
    )
}
