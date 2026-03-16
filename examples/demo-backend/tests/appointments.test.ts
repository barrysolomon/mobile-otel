import { describe, it, expect, beforeEach } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";

describe("Appointments API", () => {
  let app: ReturnType<typeof createApp>;
  let db: Database.Database;
  let doctorId: string;
  let slotId: string;

  beforeEach(() => {
    db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    app = createApp();
    const doc = db.prepare("SELECT id FROM doctors LIMIT 1").get() as { id: string };
    doctorId = doc.id;
    const slot = db.prepare("SELECT id FROM slots WHERE doctor_id = ? LIMIT 1").get(doctorId) as { id: string };
    slotId = slot.id;
  });

  describe("POST /api/appointments", () => {
    it("books an appointment", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      expect(res.status).toBe(201);
      expect(res.body).toHaveProperty("id");
      expect(res.body.status).toBe("confirmed");
    });

    it("returns 400 with MISSING_FIELDS code for missing fields", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId });
      expect(res.status).toBe(400);
      expect(res.body.code).toBe("MISSING_FIELDS");
    });

    it("returns 404 with DOCTOR_NOT_FOUND for nonexistent doctor", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: "nonexistent", slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("DOCTOR_NOT_FOUND");
    });

    it("returns 404 with SLOT_NOT_FOUND for nonexistent slot", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: "nonexistent", patient: "John Doe", reason: "Checkup" });
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("SLOT_NOT_FOUND");
    });

    it("returns 409 with SLOT_UNAVAILABLE for already-booked slot", async () => {
      await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "Jane Doe", reason: "Followup" });
      expect(res.status).toBe(409);
      expect(res.body.code).toBe("SLOT_UNAVAILABLE");
    });

    it("marks slot as unavailable after booking", async () => {
      await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const slot = db.prepare("SELECT available FROM slots WHERE id = ?").get(slotId) as { available: number };
      expect(slot.available).toBe(0);
    });
  });

  describe("GET /api/appointments", () => {
    it("returns all appointments", async () => {
      await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const res = await request(app).get("/api/appointments");
      expect(res.status).toBe(200);
      expect(res.body).toHaveLength(1);
    });
  });

  describe("GET /api/appointments/:id", () => {
    it("returns a single appointment", async () => {
      const created = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const res = await request(app).get(`/api/appointments/${created.body.id}`);
      expect(res.status).toBe(200);
      expect(res.body.patient).toBe("John Doe");
    });

    it("returns 404 with NOT_FOUND code for nonexistent appointment", async () => {
      const res = await request(app).get("/api/appointments/nonexistent");
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("NOT_FOUND");
    });
  });

  describe("DELETE /api/appointments/:id", () => {
    it("cancels an appointment and frees the slot", async () => {
      const created = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const res = await request(app).delete(`/api/appointments/${created.body.id}`);
      expect(res.status).toBe(200);
      expect(res.body.status).toBe("cancelled");

      const slot = db.prepare("SELECT available FROM slots WHERE id = ?").get(slotId) as { available: number };
      expect(slot.available).toBe(1);
    });

    it("returns 404 with NOT_FOUND code for nonexistent appointment", async () => {
      const res = await request(app).delete("/api/appointments/nonexistent");
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("NOT_FOUND");
    });
  });
});
