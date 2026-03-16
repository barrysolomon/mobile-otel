import { Router } from "express";
import { v4 as uuid } from "uuid";
import { getDb } from "../db/connection.js";

const router = Router();

router.get("/api/appointments", (_req, res) => {
  const appointments = getDb().prepare("SELECT * FROM appointments ORDER BY created_at DESC").all();
  res.json(appointments);
});

router.get("/api/appointments/:id", (req, res) => {
  const appointment = getDb().prepare("SELECT * FROM appointments WHERE id = ?").get(req.params.id);
  if (!appointment) {
    res.status(404).json({ error: "Appointment not found", code: "NOT_FOUND" });
    return;
  }
  res.json(appointment);
});

router.post("/api/appointments", (req, res) => {
  const { doctor_id, slot_id, patient, reason } = req.body;

  if (!doctor_id || !slot_id || !patient || !reason) {
    res.status(400).json({ error: "doctor_id, slot_id, patient, and reason are required", code: "MISSING_FIELDS" });
    return;
  }

  const db = getDb();

  const doctor = db.prepare("SELECT id FROM doctors WHERE id = ?").get(doctor_id);
  if (!doctor) {
    res.status(404).json({ error: "Doctor not found", code: "DOCTOR_NOT_FOUND" });
    return;
  }

  const slot = db.prepare("SELECT * FROM slots WHERE id = ?").get(slot_id) as { available: number } | undefined;
  if (!slot) {
    res.status(404).json({ error: "Slot not found", code: "SLOT_NOT_FOUND" });
    return;
  }
  if (!slot.available) {
    res.status(409).json({ error: "Slot is already booked", code: "SLOT_UNAVAILABLE" });
    return;
  }

  const id = uuid();
  const now = new Date().toISOString();

  const bookAppointment = db.transaction(() => {
    db.prepare("INSERT INTO appointments (id, doctor_id, slot_id, patient, reason, status, created_at) VALUES (?, ?, ?, ?, ?, 'confirmed', ?)").run(id, doctor_id, slot_id, patient, reason, now);
    db.prepare("UPDATE slots SET available = 0 WHERE id = ?").run(slot_id);
  });

  bookAppointment();

  const appointment = db.prepare("SELECT * FROM appointments WHERE id = ?").get(id);
  res.status(201).json(appointment);
});

router.delete("/api/appointments/:id", (req, res) => {
  const db = getDb();
  const appointment = db.prepare("SELECT * FROM appointments WHERE id = ?").get(req.params.id) as { slot_id: string } | undefined;

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
