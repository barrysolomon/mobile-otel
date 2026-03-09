// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.data.model

data class Appointment(
    val id: String,
    val title: String,
    val provider: String,
    val dateMs: Long,
    val timeSlot: String,
    val type: AppointmentType,
    val status: AppointmentStatus = AppointmentStatus.CONFIRMED,
    val notes: String = ""
)

enum class AppointmentType(val label: String) {
    CHECKUP("Annual Checkup"),
    FOLLOWUP("Follow-up Visit"),
    URGENT("Urgent Care"),
    CONSULTATION("Consultation")
}

enum class AppointmentStatus(val label: String) {
    CONFIRMED("Confirmed"),
    PENDING("Pending"),
    CANCELLED("Cancelled")
}
