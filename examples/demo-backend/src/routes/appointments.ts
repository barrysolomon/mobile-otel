import { Router } from "express";
import { v4 as uuid } from "uuid";
import { getDb } from "../db/connection.js";
import { getSimulationState } from "../middleware/simulate.js";
import { schedulingTracer, schedulingAttributes, sleep, randomBetween } from "../scheduling/tracer.js";
import type { BookingContext } from "../scheduling/types.js";
import { SchedulingError } from "../scheduling/types.js";
import { checkAvailability } from "../scheduling/availability.js";
import { resolveConflicts } from "../scheduling/conflicts.js";
import { verifyAuthorization } from "../scheduling/authorization.js";
import { dispatchNotifications } from "../scheduling/notifications.js";

const router = Router();

router.get("/api/appointments", (_req, res) => {
  const appointments = getDb()
    .prepare("SELECT * FROM appointments ORDER BY created_at DESC")
    .all();
  res.json(appointments);
});

router.get("/api/appointments/:id", (req, res) => {
  const appointment = getDb()
    .prepare("SELECT * FROM appointments WHERE id = ?")
    .get(req.params.id);
  if (!appointment) {
    res.status(404).json({ error: "Appointment not found", code: "NOT_FOUND" });
    return;
  }
  res.json(appointment);
});

router.post("/api/appointments", async (req, res) => {
  const { doctor_id, slot_id, patient, reason } = req.body;

  if (!doctor_id || !slot_id || !patient || !reason) {
    res.status(400).json({
      error: "doctor_id, slot_id, patient, and reason are required",
      code: "MISSING_FIELDS",
    });
    return;
  }

  const db = getDb();

  const doctor = db.prepare("SELECT id, name FROM doctors WHERE id = ?").get(doctor_id) as
    | { id: string; name: string }
    | undefined;
  if (!doctor) {
    res.status(404).json({ error: "Doctor not found", code: "DOCTOR_NOT_FOUND" });
    return;
  }

  const slot = db.prepare("SELECT * FROM slots WHERE id = ?").get(slot_id) as
    | { id: string; date: string; time: string; available: number }
    | undefined;
  if (!slot) {
    res.status(404).json({ error: "Slot not found", code: "SLOT_NOT_FOUND" });
    return;
  }

  const bookingCtx: BookingContext = {
    doctorId: doctor_id,
    doctorName: doctor.name,
    patient,
    slotId: slot_id,
    slotDate: slot.date,
    slotTime: slot.time,
  };

  const { operations } = getSimulationState();

  // Scheduling operations
  try {
    await checkAvailability(bookingCtx, operations);
    await resolveConflicts(bookingCtx, operations);
    await verifyAuthorization(bookingCtx, operations);
  } catch (err) {
    if (err instanceof SchedulingError) {
      res.status(err.statusCode).json({ error: err.message, code: err.code });
      return;
    }
    res.status(500).json({ error: "Internal scheduling error", code: "SCHEDULING_ERROR" });
    return;
  }

  // Book the slot (wrapped in scheduling.book_slot span)
  const bookSpan = schedulingTracer.startSpan("scheduling.book_slot", {
    attributes: schedulingAttributes(bookingCtx, "book_slot"),
  });

  const id = uuid();
  const now = new Date().toISOString();

  try {
    if (!slot.available) {
      res.status(409).json({ error: "Slot is already booked", code: "SLOT_UNAVAILABLE" });
      return;
    }

    const bookAppointment = db.transaction(() => {
      db.prepare(
        "INSERT INTO appointments (id, doctor_id, slot_id, patient, reason, status, created_at) VALUES (?, ?, ?, ?, ?, 'confirmed', ?)",
      ).run(id, doctor_id, slot_id, patient, reason, now);
      db.prepare("UPDATE slots SET available = 0 WHERE id = ?").run(slot_id);
    });

    await sleep(randomBetween(5, 20));
    bookAppointment();

    bookSpan.setAttribute("scheduling.booking.id", id);
    bookSpan.setAttribute("scheduling.booking.status", "confirmed");
  } finally {
    bookSpan.end();
  }

  // Non-blocking notifications — send response first, await after
  const notificationPromise = dispatchNotifications(bookingCtx, operations);

  const appointment = db.prepare("SELECT * FROM appointments WHERE id = ?").get(id);
  res.status(201).json(appointment);

  await notificationPromise.catch(() => {});
});

router.delete("/api/appointments/:id", (req, res) => {
  const db = getDb();
  const appointment = db
    .prepare("SELECT * FROM appointments WHERE id = ?")
    .get(req.params.id) as { slot_id: string } | undefined;

  if (!appointment) {
    res.status(404).json({ error: "Appointment not found", code: "NOT_FOUND" });
    return;
  }

  const cancelAppointment = db.transaction(() => {
    db.prepare("UPDATE appointments SET status = 'cancelled' WHERE id = ?").run(req.params.id);
    db.prepare("UPDATE slots SET available = 1 WHERE id = ?").run(appointment.slot_id);
  });

  cancelAppointment();
  res.json({ status: "cancelled" });
});

export default router;
