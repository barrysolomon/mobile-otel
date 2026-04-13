import { describe, it, expect, beforeEach } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";
import { resetSimulation, setSimulationState } from "../src/middleware/simulate.js";

describe("Scheduling Operations", () => {
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
    resetSimulation();
    app = createApp();

    const doc = db.prepare("SELECT id FROM doctors LIMIT 1").get() as { id: string };
    doctorId = doc.id;
    const slot = db.prepare("SELECT id FROM slots WHERE doctor_id = ? AND available = 1 LIMIT 1").get(doctorId) as { id: string };
    slotId = slot.id;
  });

  const bookPayload = () => ({
    doctor_id: doctorId,
    slot_id: slotId,
    patient: "Test Patient",
    reason: "Scheduling test",
  });

  describe("Happy path", () => {
    it("books an appointment through all scheduling operations", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(201);
      expect(res.body.status).toBe("confirmed");
      expect(res.body.patient).toBe("Test Patient");
    });
  });

  describe("Availability faults", () => {
    it("succeeds when one provider times out (partial availability)", async () => {
      setSimulationState({
        operations: { availability: { timeout: 100, errorRate: 0 } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(201);
    });

    it("returns 503 when all providers fail", async () => {
      setSimulationState({
        operations: { availability: { timeout: 0, errorRate: 1.0 } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(503);
      expect(res.body.code).toBe("AVAILABILITY_FAILED");
    });
  });

  describe("Conflict resolution faults", () => {
    it("retries on race condition and succeeds", async () => {
      setSimulationState({
        operations: { conflicts: { raceCondition: true } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(201);
    });
  });

  describe("Authorization faults", () => {
    it("returns 403 when authorization denied", async () => {
      setSimulationState({
        operations: { authorization: { timeout: 0, deny: true } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(403);
      expect(res.body.code).toBe("AUTH_DENIED");
      const appts = db.prepare("SELECT COUNT(*) as count FROM appointments").get() as { count: number };
      expect(appts.count).toBe(0);
    });

    it("returns 504 when authorization times out", async () => {
      setSimulationState({
        operations: { authorization: { timeout: 100, deny: false } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(504);
      expect(res.body.code).toBe("AUTH_TIMEOUT");
    });
  });

  describe("Notification faults", () => {
    it("booking succeeds despite partial notification failure", async () => {
      setSimulationState({
        operations: { notifications: { partialFailure: true, slowChannel: null } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(201);
      expect(res.body.status).toBe("confirmed");
    });

    it("booking succeeds with slow notification channel", async () => {
      setSimulationState({
        operations: { notifications: { partialFailure: false, slowChannel: "sms" } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(201);
    });
  });

  describe("Existing validation unchanged", () => {
    it("still returns 400 for missing fields", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId });
      expect(res.status).toBe(400);
      expect(res.body.code).toBe("MISSING_FIELDS");
    });

    it("still returns 404 for nonexistent doctor", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ ...bookPayload(), doctor_id: "nonexistent" });
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("DOCTOR_NOT_FOUND");
    });
  });
});
