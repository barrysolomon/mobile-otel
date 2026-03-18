// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.data

import android.content.Context
import io.opentelemetry.android.demo.ConfigManager
import io.opentelemetry.android.demo.data.api.SchedulingApiClient
import io.opentelemetry.android.demo.data.model.Appointment
import io.opentelemetry.android.demo.data.model.AppointmentStatus
import io.opentelemetry.android.demo.data.model.AppointmentType
import io.opentelemetry.context.Context as OtelContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

object AppointmentRepository {

    /** Backend base URL — resolved from ConfigManager at call time. */
    private fun backendUrl(context: Context): String =
        ConfigManager.getBackendUrl(context)

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

    // Cache doctor_id -> name mapping from backend
    private var doctorCache: Map<String, String> = emptyMap()

    private suspend fun ensureDoctorCache(context: Context) {
        if (doctorCache.isNotEmpty()) return
        try {
            val client = SchedulingApiClient.getInstance(context)
            val json = JSONArray(client.get("${backendUrl(context)}/api/doctors"))
            doctorCache = (0 until json.length()).associate { i ->
                val doc = json.getJSONObject(i)
                doc.getString("id") to doc.getString("name")
            }
        } catch (_: Exception) {
            // Backend unreachable — cache stays empty, will use mock data
        }
    }

    private fun parseAppointments(json: String): List<Appointment> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val doctorId = obj.getString("doctor_id")
            val createdAt = obj.getString("created_at")
            Appointment(
                id = obj.getString("id"),
                title = obj.getString("reason"),
                provider = doctorCache[doctorId] ?: "Unknown Doctor",
                dateMs = try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        .parse(createdAt)?.time ?: System.currentTimeMillis()
                } catch (_: Exception) { System.currentTimeMillis() },
                timeSlot = "",
                type = AppointmentType.CHECKUP,
                status = if (obj.getString("status") == "confirmed")
                    AppointmentStatus.CONFIRMED else AppointmentStatus.CANCELLED,
                notes = obj.getString("reason")
            )
        }
    }

    private fun findMatchingSlotId(slotsJson: JSONArray, timeSlot: String): String? {
        for (i in 0 until slotsJson.length()) {
            val slot = slotsJson.getJSONObject(i)
            val slotTime = slot.getString("time")
            val hour = slotTime.split(":")[0].toInt()
            val minute = slotTime.split(":")[1]
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
            val formatted = "$displayHour:$minute $amPm"
            if (formatted == timeSlot) return slot.getString("id")
        }
        return null
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
     * Fetch appointments via a real HTTP GET to the backend.
     * Falls back to mock data if the backend is unreachable.
     */
    suspend fun fetchAppointments(context: Context): List<Appointment> {
        val otelCtx = OtelContext.current()
        return withContext(Dispatchers.IO) {
            val otelScope = otelCtx.makeCurrent()
            try {
                val forceError = forceNextFetchError
                if (forceError) forceNextFetchError = false
                if (forceError) throw ApiException(503, "Forced error from debug toolbar")

                ensureDoctorCache(context)
                val client = SchedulingApiClient.getInstance(context)
                val json = client.get("${backendUrl(context)}/api/appointments")
                val backendAppointments = parseAppointments(json)
                allAppointments.clear()
                allAppointments.addAll(backendAppointments)
                backendAppointments
            } catch (e: ApiException) {
                throw e // Re-throw intentional errors
            } catch (_: Exception) {
                // Backend unreachable — return mock data
                getMockAppointments()
            } finally {
                otelScope.close()
            }
        }
    }

    /**
     * Book an appointment via a real HTTP POST to the backend.
     * Looks up doctor_id and slot_id from the backend before posting.
     */
    suspend fun bookAppointment(
        context: Context,
        provider: String,
        dateMs: Long,
        timeSlot: String,
        type: AppointmentType,
        notes: String
    ): Appointment {
        if (isDuplicate(provider, dateMs, timeSlot)) {
            throw DuplicateAppointmentException(provider, timeSlot)
        }

        val otelCtx = OtelContext.current()

        return withContext(Dispatchers.IO) {
            val otelScope = otelCtx.makeCurrent()
            try {
                ensureDoctorCache(context)
                val client = SchedulingApiClient.getInstance(context)

                // Find doctor_id from name
                val doctorId = doctorCache.entries.find { it.value == provider }?.key
                    ?: throw ApiException(404, "Doctor not found: $provider")

                // Find available slot_id for this doctor + date + time
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date(dateMs))
                val slotsJson = JSONArray(client.get(
                    "${backendUrl(context)}/api/slots?doctor_id=$doctorId&date=$dateStr"
                ))
                val slotId = findMatchingSlotId(slotsJson, timeSlot)
                    ?: throw ApiException(404, "No available slot for $timeSlot")

                val body = JSONObject().apply {
                    put("doctor_id", doctorId)
                    put("slot_id", slotId)
                    put("patient", "Demo User")
                    put("reason", notes.ifBlank { type.label })
                }

                val responseJson = client.post("${backendUrl(context)}/api/appointments", body.toString())
                val result = JSONObject(responseJson)

                Appointment(
                    id = result.getString("id"),
                    title = type.label,
                    provider = provider,
                    dateMs = dateMs,
                    timeSlot = timeSlot,
                    type = type,
                    status = AppointmentStatus.CONFIRMED,
                    notes = notes
                ).also { allAppointments.add(it) }
            } catch (e: SchedulingApiClient.HttpException) {
                throw ApiException(e.code, e.message ?: "Booking failed")
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
        try {
            val client = SchedulingApiClient.getInstance(context)
            client.get("${backendUrl(context)}/api/appointments") // real HTTP call for trace
        } catch (_: Exception) {
            // Fallback — generate synthetic data
        }
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
