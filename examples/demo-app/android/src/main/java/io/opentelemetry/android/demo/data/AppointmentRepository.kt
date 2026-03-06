// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.data

import android.content.Context
import io.opentelemetry.android.demo.data.api.SchedulingApiClient
import io.opentelemetry.android.demo.data.model.Appointment
import io.opentelemetry.android.demo.data.model.AppointmentStatus
import io.opentelemetry.android.demo.data.model.AppointmentType
import io.opentelemetry.context.Context as OtelContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID
import kotlin.random.Random

object AppointmentRepository {

    // Real API base URLs — calls are traced by OTelNetworkInterceptor
    private const val BASE_SUCCESS = "https://jsonplaceholder.typicode.com"
    private const val BASE_HTTPBIN  = "https://httpbin.org"

    val providers = listOf(
        "Dr. Sarah Chen",
        "Dr. Marcus Webb",
        "Dr. Elena Rossi",
        "Dr. James Park",
        "Dr. Aisha Patel"
    )

    val timeSlots = listOf(
        "9:00 AM", "9:30 AM", "10:00 AM", "10:30 AM",
        "11:00 AM", "11:30 AM", "2:00 PM", "2:30 PM",
        "3:00 PM", "3:30 PM", "4:00 PM", "4:30 PM"
    )

    // Force-fail next refresh (set by DebugToolbar HTTP500 action)
    var forceNextFetchError = false

    private val day = 86_400_000L

    // In-memory list of all known appointments (mock + newly booked this session).
    // Initialized lazily from getMockAppointments() on first access.
    private val allAppointments: MutableList<Appointment> by lazy {
        getMockAppointments().toMutableList()
    }

    /** Returns true if provider + day + time slot already exists in allAppointments. */
    private fun isDuplicate(provider: String, dateMs: Long, timeSlot: String): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMs }
        return allAppointments.any { existing ->
            existing.provider == provider &&
            existing.timeSlot == timeSlot &&
            Calendar.getInstance().apply { timeInMillis = existing.dateMs }.let { ec ->
                ec.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                ec.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
            }
        }
    }

    fun getMockAppointments(): List<Appointment> {
        val now = System.currentTimeMillis()
        return listOf(
            Appointment("1", "Annual Physical",       "Dr. Sarah Chen",  now + day,       "10:00 AM", AppointmentType.CHECKUP),
            Appointment("2", "Blood Pressure Check",  "Dr. Marcus Webb", now + 3 * day,   "2:30 PM",  AppointmentType.FOLLOWUP),
            Appointment("3", "Knee Pain Evaluation",  "Dr. Elena Rossi", now + 5 * day,   "11:00 AM", AppointmentType.CONSULTATION),
            Appointment("4", "Lab Results Review",    "Dr. James Park",  now + 7 * day,   "9:30 AM",  AppointmentType.FOLLOWUP),
            Appointment("5", "Flu Shot",              "Dr. Aisha Patel", now + 10 * day,  "3:00 PM",  AppointmentType.CHECKUP,    AppointmentStatus.PENDING),
            Appointment("6", "Chest X-Ray Review",   "Dr. Sarah Chen",  now - 3 * day,   "10:00 AM", AppointmentType.URGENT,     AppointmentStatus.CONFIRMED),
            Appointment("7", "Diabetes Follow-up",   "Dr. James Park",  now - 10 * day,  "11:30 AM", AppointmentType.FOLLOWUP,   AppointmentStatus.CONFIRMED),
        )
    }

    /**
     * Fetch appointments via a real HTTP GET.
     * On the success path: GET jsonplaceholder.typicode.com/todos?userId=1 (always 200).
     * On forced error or 30% random: GET httpbin.org/status/503 (always 503) —
     * the OTelNetworkInterceptor automatically records the http.error span.
     */
    suspend fun fetchAppointments(context: Context): List<Appointment> {
        val otelCtx = OtelContext.current()
        return withContext(Dispatchers.IO) {
            val otelScope = otelCtx.makeCurrent()
            try {
                val client = SchedulingApiClient.getInstance(context)
                val forceError = forceNextFetchError
                if (forceError) forceNextFetchError = false

                if (forceError || Random.nextFloat() < 0.3f) {
                    try {
                        client.get("$BASE_HTTPBIN/status/503")
                    } catch (e: SchedulingApiClient.HttpException) {
                        throw ApiException(e.code, "Scheduling API unavailable (HTTP ${e.code})")
                    }
                }

                client.get("$BASE_SUCCESS/todos?userId=1")
                getMockAppointments()
            } finally {
                otelScope.close()
            }
        }
    }

    /**
     * Book an appointment via a real HTTP POST.
     * Success path: POST jsonplaceholder.typicode.com/posts (echoes back the body, 201).
     * 25% random: POST httpbin.org/status/500 (always 500).
     */
    suspend fun bookAppointment(
        context: Context,
        provider: String,
        dateMs: Long,
        timeSlot: String,
        type: AppointmentType,
        notes: String
    ): Appointment {
        // Duplicate check on the calling thread before doing any network work.
        if (isDuplicate(provider, dateMs, timeSlot)) {
            throw DuplicateAppointmentException(provider, timeSlot)
        }

        // Capture the OTel context on the calling thread BEFORE withContext switches threads.
        // withContext(Dispatchers.IO) may resume on a different IO thread where the
        // thread-local OTel context is empty, breaking OTelNetworkInterceptor parenting.
        val otelCtx = OtelContext.current()

        return withContext(Dispatchers.IO) {
            // Restore the caller's OTel context so OTelNetworkInterceptor sees the parent span.
            val otelScope = otelCtx.makeCurrent()
            try {
                val client = SchedulingApiClient.getInstance(context)
                val json = """{"provider":"$provider","timeSlot":"$timeSlot","type":"${type.label}","notes":"$notes"}"""

                if (Random.nextFloat() < 0.25f) {
                    try {
                        client.post("$BASE_HTTPBIN/status/500", json)
                    } catch (e: SchedulingApiClient.HttpException) {
                        throw ApiException(e.code, "Failed to create appointment (HTTP ${e.code})")
                    }
                }

                client.post("$BASE_SUCCESS/posts", json)

                Appointment(
                    id       = UUID.randomUUID().toString(),
                    title    = type.label,
                    provider = provider,
                    dateMs   = dateMs,
                    timeSlot = timeSlot,
                    type     = type,
                    status   = AppointmentStatus.PENDING,
                    notes    = notes
                ).also { allAppointments.add(it) }
            } finally {
                otelScope.close()
            }
        }
    }

    /**
     * Load full appointment history — allocates a large dataset to demo memory pressure.
     * Makes a real HTTP call so the network span is visible.
     */
    suspend fun loadFullHistory(context: Context): List<Appointment> = withContext(Dispatchers.IO) {
        val client = SchedulingApiClient.getInstance(context)
        client.get("$BASE_SUCCESS/todos")  // real HTTP call, response discarded
        val now = System.currentTimeMillis()
        (0 until 500).map { i ->
            Appointment(
                id       = "hist_$i",
                title    = AppointmentType.values()[i % 4].label,
                provider = providers[i % providers.size],
                dateMs   = now - i * day,
                timeSlot = timeSlots[i % timeSlots.size],
                type     = AppointmentType.values()[i % 4],
                status   = AppointmentStatus.CONFIRMED
            )
        }
    }

    class ApiException(val httpStatus: Int, message: String) : Exception(message)

    class DuplicateAppointmentException(provider: String, timeSlot: String) :
        Exception("$provider already has an appointment at $timeSlot on that day")

    // ── Test helpers ─────────────────────────────────────────────────────────
    // These are `internal` so they are visible to the test source set but not
    // to other modules or external callers.

    /** Resets the in-memory booking list to the mock dataset. Unit tests only. */
    internal fun resetForTesting() {
        allAppointments.clear()
        allAppointments.addAll(getMockAppointments())
    }

    /** Adds an appointment to the tracking list without making an HTTP call. Unit tests only. */
    internal fun addAppointmentForTesting(appt: Appointment) {
        allAppointments.add(appt)
    }
}
