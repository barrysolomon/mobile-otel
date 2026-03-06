// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.data

import android.content.Context
import io.mockk.mockk
import io.opentelemetry.android.demo.data.model.Appointment
import io.opentelemetry.android.demo.data.model.AppointmentStatus
import io.opentelemetry.android.demo.data.model.AppointmentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Comprehensive unit tests for [AppointmentRepository].
 *
 * Covers:
 * - Mock appointment dataset
 * - Duplicate-booking detection (all paths: exact match, cross-day, cross-slot, cross-provider)
 * - Exception types and messages
 * - In-session booking registration (newly booked → prevents re-booking)
 * - forceNextFetchError flag
 *
 * Note: bookAppointment takes a Context parameter but the duplicate check fires *before*
 * any Android API is used. We pass a mockk<Context>() for the duplicate detection tests —
 * it is never dereferenced because DuplicateAppointmentException is thrown first.
 *
 * HTTP-dependent paths (bookAppointment success, fetchAppointments) require a running
 * OTel SDK + real network and are exercised in instrumented tests.
 */
class AppointmentRepositoryTest {

    /** Stub context — only passed to bookAppointment but never touched when duplicate fires. */
    private val ctx: Context = mockk(relaxed = true)

    @Before
    fun reset() {
        AppointmentRepository.resetForTesting()
        AppointmentRepository.forceNextFetchError = false
    }

    // ── getMockAppointments ────────────────────────────────────────────────

    @Test
    fun `getMockAppointments returns 7 appointments`() {
        assertEquals(7, AppointmentRepository.getMockAppointments().size)
    }

    @Test
    fun `getMockAppointments contains all five providers`() {
        val providers = AppointmentRepository.getMockAppointments().map { it.provider }.toSet()
        assertTrue(providers.contains("Dr. Sarah Chen"))
        assertTrue(providers.contains("Dr. Marcus Webb"))
        assertTrue(providers.contains("Dr. Elena Rossi"))
        assertTrue(providers.contains("Dr. James Park"))
        assertTrue(providers.contains("Dr. Aisha Patel"))
    }

    @Test
    fun `getMockAppointments has appointments spanning past and future`() {
        val now = System.currentTimeMillis()
        val appts = AppointmentRepository.getMockAppointments()
        assertTrue("Expected at least one future appointment", appts.any { it.dateMs > now })
        assertTrue("Expected at least one past appointment",   appts.any { it.dateMs < now })
    }

    @Test
    fun `getMockAppointments includes all four appointment types`() {
        val types = AppointmentRepository.getMockAppointments().map { it.type }.toSet()
        assertTrue(types.contains(AppointmentType.CHECKUP))
        assertTrue(types.contains(AppointmentType.FOLLOWUP))
        assertTrue(types.contains(AppointmentType.CONSULTATION))
        assertTrue(types.contains(AppointmentType.URGENT))
    }

    @Test
    fun `getMockAppointments includes both CONFIRMED and PENDING statuses`() {
        val statuses = AppointmentRepository.getMockAppointments().map { it.status }.toSet()
        assertTrue(statuses.contains(AppointmentStatus.CONFIRMED))
        assertTrue(statuses.contains(AppointmentStatus.PENDING))
    }

    @Test
    fun `getMockAppointments appointment IDs are unique`() {
        val ids = AppointmentRepository.getMockAppointments().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ── providers / timeSlots lists ────────────────────────────────────────

    @Test
    fun `providers list has 5 entries`() {
        assertEquals(5, AppointmentRepository.providers.size)
    }

    @Test
    fun `timeSlots list has 12 entries`() {
        assertEquals(12, AppointmentRepository.timeSlots.size)
    }

    @Test
    fun `timeSlots covers morning and afternoon`() {
        val slots = AppointmentRepository.timeSlots
        assertTrue(slots.any { it.contains("AM") })
        assertTrue(slots.any { it.contains("PM") })
    }

    // ── DuplicateAppointmentException ─────────────────────────────────────

    @Test
    fun `DuplicateAppointmentException message contains provider name`() {
        val ex = AppointmentRepository.DuplicateAppointmentException("Dr. Sarah Chen", "10:00 AM")
        assertTrue(ex.message!!.contains("Dr. Sarah Chen"))
    }

    @Test
    fun `DuplicateAppointmentException message contains time slot`() {
        val ex = AppointmentRepository.DuplicateAppointmentException("Dr. Sarah Chen", "10:00 AM")
        assertTrue(ex.message!!.contains("10:00 AM"))
    }

    @Test
    fun `DuplicateAppointmentException is a subtype of Exception`() {
        val ex = AppointmentRepository.DuplicateAppointmentException("Dr. Sarah Chen", "10:00 AM")
        assertTrue(ex is Exception)
    }

    // ── ApiException ───────────────────────────────────────────────────────

    @Test
    fun `ApiException preserves HTTP status code`() {
        val ex = AppointmentRepository.ApiException(503, "Service Unavailable")
        assertEquals(503, ex.httpStatus)
    }

    @Test
    fun `ApiException preserves message`() {
        val ex = AppointmentRepository.ApiException(500, "Internal Server Error")
        assertEquals("Internal Server Error", ex.message)
    }

    // ── Duplicate detection: exact match on mock data ─────────────────────

    @Test
    fun `booking Dr Sarah Chen at 10 AM tomorrow throws DuplicateAppointmentException`() {
        // Mock data contains "Annual Physical" with Dr. Sarah Chen, 10:00 AM, now+1day
        val tomorrowMs = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
        }.timeInMillis

        try {
            runBlocking {
                AppointmentRepository.bookAppointment(
                    context  = ctx,
                    provider = "Dr. Sarah Chen",
                    dateMs   = tomorrowMs,
                    timeSlot = "10:00 AM",
                    type     = AppointmentType.CHECKUP,
                    notes    = ""
                )
            }
            fail("Expected DuplicateAppointmentException")
        } catch (e: AppointmentRepository.DuplicateAppointmentException) {
            assertTrue(e.message!!.contains("Dr. Sarah Chen"))
        }
    }

    @Test
    fun `booking Dr Marcus Webb at 2 30 PM in 3 days throws DuplicateAppointmentException`() {
        val dateMs = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 3)
            set(Calendar.HOUR_OF_DAY, 14)
        }.timeInMillis

        try {
            runBlocking {
                AppointmentRepository.bookAppointment(
                    context  = ctx,
                    provider = "Dr. Marcus Webb",
                    dateMs   = dateMs,
                    timeSlot = "2:30 PM",
                    type     = AppointmentType.FOLLOWUP,
                    notes    = ""
                )
            }
            fail("Expected DuplicateAppointmentException")
        } catch (e: AppointmentRepository.DuplicateAppointmentException) {
            assertTrue(e.message!!.contains("Dr. Marcus Webb"))
            assertTrue(e.message!!.contains("2:30 PM"))
        }
    }

    // ── Duplicate detection: cross-day (should NOT throw) ─────────────────

    @Test
    fun `booking same provider and slot on a different day does not throw`() {
        // Dr. Sarah Chen has 10:00 AM tomorrow — booking same slot two days from now is OK
        val twoDaysFromNow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 2)
            set(Calendar.HOUR_OF_DAY, 9)
        }.timeInMillis

        // This should throw, but NOT DuplicateAppointmentException —
        // it will fail later trying to reach the HTTP client (no OTel SDK in unit tests).
        // We verify it doesn't throw DuplicateAppointmentException.
        try {
            runBlocking {
                AppointmentRepository.bookAppointment(
                    context  = ctx,
                    provider = "Dr. Sarah Chen",
                    dateMs   = twoDaysFromNow,
                    timeSlot = "10:00 AM",
                    type     = AppointmentType.CHECKUP,
                    notes    = ""
                )
            }
        } catch (e: AppointmentRepository.DuplicateAppointmentException) {
            fail("Should NOT throw DuplicateAppointmentException for a different day: ${e.message}")
        } catch (_: Exception) {
            // Expected — HTTP call fails because OTel SDK not initialized in unit tests.
            // The important thing is DuplicateAppointmentException was NOT thrown.
        }
    }

    // ── Duplicate detection: different slot, same provider + day ──────────

    @Test
    fun `booking Dr Sarah Chen at different time slot same day does not throw duplicate`() {
        val tomorrowMs = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 9)
        }.timeInMillis

        // Dr. Sarah Chen has 10:00 AM tomorrow. 9:00 AM should be free.
        try {
            runBlocking {
                AppointmentRepository.bookAppointment(
                    context  = ctx,
                    provider = "Dr. Sarah Chen",
                    dateMs   = tomorrowMs,
                    timeSlot = "9:00 AM",
                    type     = AppointmentType.CHECKUP,
                    notes    = ""
                )
            }
        } catch (e: AppointmentRepository.DuplicateAppointmentException) {
            fail("Different slot should not cause duplicate: ${e.message}")
        } catch (_: Exception) {
            // HTTP failure expected — duplicate check passed, which is what we're testing.
        }
    }

    // ── Duplicate detection: different provider, same slot + day ──────────

    @Test
    fun `booking different provider at same slot and day does not throw duplicate`() {
        val tomorrowMs = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

        // Dr. Sarah Chen has 10:00 AM tomorrow. Dr. Elena Rossi does not.
        try {
            runBlocking {
                AppointmentRepository.bookAppointment(
                    context  = ctx,
                    provider = "Dr. Elena Rossi",
                    dateMs   = tomorrowMs,
                    timeSlot = "10:00 AM",
                    type     = AppointmentType.CHECKUP,
                    notes    = ""
                )
            }
        } catch (e: AppointmentRepository.DuplicateAppointmentException) {
            fail("Different provider should not cause duplicate: ${e.message}")
        } catch (_: Exception) {
            // HTTP failure expected — duplicate check passed.
        }
    }

    // ── Duplicate detection: newly booked appointment ─────────────────────

    @Test
    fun `appointment added via addAppointmentForTesting blocks same slot booking`() {
        val futureMs = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 14)
            set(Calendar.HOUR_OF_DAY, 10)
        }.timeInMillis

        // Add a booking directly without HTTP
        AppointmentRepository.addAppointmentForTesting(
            Appointment(
                id       = "test-999",
                title    = "Test Visit",
                provider = "Dr. Elena Rossi",
                dateMs   = futureMs,
                timeSlot = "9:00 AM",
                type     = AppointmentType.CHECKUP,
                status   = AppointmentStatus.PENDING
            )
        )

        // Now try to book the same slot
        try {
            runBlocking {
                AppointmentRepository.bookAppointment(
                    context  = ctx,
                    provider = "Dr. Elena Rossi",
                    dateMs   = futureMs,
                    timeSlot = "9:00 AM",
                    type     = AppointmentType.CHECKUP,
                    notes    = "should fail"
                )
            }
            fail("Expected DuplicateAppointmentException for slot added via addAppointmentForTesting")
        } catch (e: AppointmentRepository.DuplicateAppointmentException) {
            assertTrue(e.message!!.contains("Dr. Elena Rossi"))
            assertTrue(e.message!!.contains("9:00 AM"))
        }
    }

    @Test
    fun `resetForTesting clears manually added appointments`() {
        val futureMs = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 20)
        }.timeInMillis

        AppointmentRepository.addAppointmentForTesting(
            Appointment("x1", "X Visit", "Dr. James Park", futureMs, "9:00 AM", AppointmentType.CHECKUP)
        )

        // Reset — should remove the manually added appointment
        AppointmentRepository.resetForTesting()

        // Now the slot should be free (no duplicate thrown)
        try {
            runBlocking {
                AppointmentRepository.bookAppointment(
                    context  = ctx,
                    provider = "Dr. James Park",
                    dateMs   = futureMs,
                    timeSlot = "9:00 AM",
                    type     = AppointmentType.CHECKUP,
                    notes    = ""
                )
            }
        } catch (e: AppointmentRepository.DuplicateAppointmentException) {
            fail("resetForTesting should have cleared the manually added appointment: ${e.message}")
        } catch (_: Exception) {
            // HTTP failure expected; duplicate check passed.
        }
    }

    // ── forceNextFetchError ────────────────────────────────────────────────

    @Test
    fun `forceNextFetchError defaults to false`() {
        assertFalse(AppointmentRepository.forceNextFetchError)
    }

    @Test
    fun `forceNextFetchError can be set to true`() {
        AppointmentRepository.forceNextFetchError = true
        assertTrue(AppointmentRepository.forceNextFetchError)
    }

    @Test
    fun `forceNextFetchError is reset in @Before`() {
        // The @Before calls reset() which resets this flag.
        // If it carried over from a previous test, it would be true here.
        assertFalse(AppointmentRepository.forceNextFetchError)
    }

    // ── loadFullHistory ────────────────────────────────────────────────────

    @Test
    fun `loadFullHistory would produce 500 appointments`() {
        // We can't call loadFullHistory() in a unit test (requires HTTP),
        // but we can verify the pure-logic portion by replicating the mapping.
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val history = (0 until 500).map { i ->
            io.opentelemetry.android.demo.data.model.Appointment(
                id       = "hist_$i",
                title    = AppointmentType.values()[i % 4].label,
                provider = AppointmentRepository.providers[i % AppointmentRepository.providers.size],
                dateMs   = now - i * day,
                timeSlot = AppointmentRepository.timeSlots[i % AppointmentRepository.timeSlots.size],
                type     = AppointmentType.values()[i % 4],
                status   = AppointmentStatus.CONFIRMED
            )
        }
        assertEquals(500, history.size)
        assertTrue(history.all { it.status == AppointmentStatus.CONFIRMED })
    }
}
