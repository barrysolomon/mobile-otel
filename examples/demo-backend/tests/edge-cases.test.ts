import { describe, it, expect, beforeEach } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";
import { resetSimulation } from "../src/middleware/simulate.js";

describe("Edge cases", () => {
  let app: ReturnType<typeof createApp>;
  let db: InstanceType<typeof Database>;
  let doctorId: string;
  let slotId: string;

  beforeEach(() => {
    db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    resetSimulation();
    app = createApp();
    const doc = db.prepare("SELECT id FROM doctors LIMIT 1").get() as { id: string };
    doctorId = doc.id;
    const slot = db.prepare("SELECT id FROM slots WHERE doctor_id = ? LIMIT 1").get(doctorId) as { id: string };
    slotId = slot.id;
  });

  describe("Unknown routes", () => {
    it("returns 404 for GET /nonexistent", async () => {
      const res = await request(app).get("/nonexistent");
      expect(res.status).toBe(404);
    });

    it("returns 404 for GET /api/nonexistent", async () => {
      const res = await request(app).get("/api/nonexistent");
      expect(res.status).toBe(404);
    });

    it("returns 404 for POST to read-only endpoint", async () => {
      const res = await request(app).post("/api/doctors").send({});
      expect(res.status).toBe(404);
    });
  });

  describe("Malformed request bodies", () => {
    it("handles invalid JSON gracefully", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .set("Content-Type", "application/json")
        .send("{ invalid json }");
      expect(res.status).toBe(400);
    });

    it("handles empty body on POST /api/appointments", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send();
      expect(res.status).toBe(400);
    });

    it("ignores extra fields in appointment body", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({
          doctor_id: doctorId,
          slot_id: slotId,
          patient: "Extra Fields",
          reason: "Test",
          extra_field: "should be ignored",
          another: 123,
        });
      expect(res.status).toBe(201);
      expect(res.body.patient).toBe("Extra Fields");
    });
  });

  describe("Doctors endpoint details", () => {
    it("each doctor has an id field", async () => {
      const res = await request(app).get("/api/doctors");
      for (const doc of res.body) {
        expect(doc).toHaveProperty("id");
        expect(typeof doc.id).toBe("string");
      }
    });

    it("doctors have unique IDs", async () => {
      const res = await request(app).get("/api/doctors");
      const ids = res.body.map((d: any) => d.id);
      expect(new Set(ids).size).toBe(ids.length);
    });

    it("doctor fields are non-empty strings", async () => {
      const res = await request(app).get("/api/doctors");
      for (const doc of res.body) {
        expect(doc.name.length).toBeGreaterThan(0);
        expect(doc.specialty.length).toBeGreaterThan(0);
        expect(doc.location.length).toBeGreaterThan(0);
      }
    });
  });

  describe("Slots edge cases", () => {
    it("returns slots ordered by date then time", async () => {
      const res = await request(app).get(`/api/slots?doctor_id=${doctorId}`);
      expect(res.status).toBe(200);
      for (let i = 1; i < res.body.length; i++) {
        const prev = res.body[i - 1];
        const curr = res.body[i];
        const prevKey = `${prev.date}T${prev.time}`;
        const currKey = `${curr.date}T${curr.time}`;
        expect(prevKey <= currKey).toBe(true);
      }
    });

    it("all returned slots are available", async () => {
      const res = await request(app).get(`/api/slots?doctor_id=${doctorId}`);
      for (const slot of res.body) {
        expect(slot.available).toBe(1);
      }
    });

    it("slot has expected structure", async () => {
      const res = await request(app).get(`/api/slots?doctor_id=${doctorId}`);
      expect(res.body.length).toBeGreaterThan(0);
      const slot = res.body[0];
      expect(slot).toHaveProperty("id");
      expect(slot).toHaveProperty("doctor_id");
      expect(slot).toHaveProperty("date");
      expect(slot).toHaveProperty("time");
      expect(slot).toHaveProperty("available");
    });
  });

  describe("Appointment lifecycle", () => {
    it("multiple bookings for different slots succeed", async () => {
      const slots = db.prepare("SELECT id FROM slots WHERE doctor_id = ? LIMIT 3").all(doctorId) as { id: string }[];
      expect(slots.length).toBeGreaterThanOrEqual(3);

      for (const s of slots) {
        const res = await request(app)
          .post("/api/appointments")
          .send({ doctor_id: doctorId, slot_id: s.id, patient: `Patient-${s.id}`, reason: "Test" });
        expect(res.status).toBe(201);
      }

      const all = await request(app).get("/api/appointments");
      expect(all.body.length).toBe(3);
    });

    it("cancelled appointment still shows in list with cancelled status", async () => {
      const created = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "Will Cancel", reason: "Test" });
      await request(app).delete(`/api/appointments/${created.body.id}`);

      const all = await request(app).get("/api/appointments");
      const cancelled = all.body.find((a: any) => a.id === created.body.id);
      expect(cancelled).toBeDefined();
      expect(cancelled.status).toBe("cancelled");
    });

    it("appointment created_at is valid ISO timestamp", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "Timestamp Test", reason: "Test" });
      expect(res.status).toBe(201);
      const date = new Date(res.body.created_at);
      expect(date.getTime()).not.toBeNaN();
    });

    it("appointments returned in descending created_at order", async () => {
      const slots = db.prepare("SELECT id FROM slots WHERE doctor_id = ? LIMIT 3").all(doctorId) as { id: string }[];
      for (const s of slots) {
        await request(app)
          .post("/api/appointments")
          .send({ doctor_id: doctorId, slot_id: s.id, patient: "Order Test", reason: "Test" });
      }
      const all = await request(app).get("/api/appointments");
      for (let i = 1; i < all.body.length; i++) {
        expect(all.body[i - 1].created_at >= all.body[i].created_at).toBe(true);
      }
    });
  });

  describe("Simulation combinations", () => {
    it("error + latency: error returned after latency delay", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true, latency: 200 });
      const start = Date.now();
      const res = await request(app).get("/api/doctors");
      const elapsed = Date.now() - start;
      expect(res.status).toBe(503);
      expect(res.body.code).toBe("SIMULATED_ERROR");
      // Latency delay still applies before error response
      expect(elapsed).toBeGreaterThanOrEqual(180);
    });

    it("POST simulate with no body keeps existing state", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      await request(app).post("/api/admin/simulate").send({ latency: 100 });
      const state = await request(app).get("/api/admin/simulate");
      expect(state.body.error).toBe(true);
      expect(state.body.latency).toBe(100);
    });

    it("X-Simulate-Error header overrides non-error global state", async () => {
      // Global simulation off, but per-request header forces error
      const res = await request(app).get("/api/doctors").set("X-Simulate-Error", "true");
      expect(res.status).toBe(503);
    });

    it("health endpoint immune to all simulation flags", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true, latency: 100 });
      const res = await request(app).get("/health");
      expect(res.status).toBe(200);
      expect(res.body.status).toBe("ok");
    });
  });

  describe("Content-Type handling", () => {
    it("responds with JSON content-type on success", async () => {
      const res = await request(app).get("/api/doctors");
      expect(res.headers["content-type"]).toMatch(/application\/json/);
    });

    it("responds with JSON content-type on error", async () => {
      const res = await request(app).get("/api/slots");
      expect(res.status).toBe(400);
      expect(res.headers["content-type"]).toMatch(/application\/json/);
    });
  });
});
