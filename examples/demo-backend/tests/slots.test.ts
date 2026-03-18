import { describe, it, expect, beforeAll } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";

describe("GET /api/slots", () => {
  let app: ReturnType<typeof createApp>;
  let doctorId: string;

  beforeAll(() => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    app = createApp();
    const doc = db.prepare("SELECT id FROM doctors LIMIT 1").get() as { id: string };
    doctorId = doc.id;
  });

  it("returns 400 without doctor_id", async () => {
    const res = await request(app).get("/api/slots");
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty("error");
  });

  it("returns slots for a doctor", async () => {
    const res = await request(app).get(`/api/slots?doctor_id=${doctorId}`);
    expect(res.status).toBe(200);
    expect(res.body.length).toBeGreaterThan(0);
    expect(res.body[0]).toHaveProperty("date");
    expect(res.body[0]).toHaveProperty("time");
    expect(res.body[0]).toHaveProperty("available");
  });

  it("filters by date", async () => {
    const today = new Date().toISOString().split("T")[0];
    const res = await request(app).get(`/api/slots?doctor_id=${doctorId}&date=${today}`);
    expect(res.status).toBe(200);
    for (const slot of res.body) {
      expect(slot.date).toBe(today);
    }
  });

  it("returns MISSING_DOCTOR_ID error code without doctor_id", async () => {
    const res = await request(app).get("/api/slots");
    expect(res.body.code).toBe("MISSING_DOCTOR_ID");
  });

  it("returns empty array for nonexistent doctor", async () => {
    const res = await request(app).get("/api/slots?doctor_id=nonexistent");
    expect(res.status).toBe(200);
    expect(res.body).toEqual([]);
  });

  it("only returns available slots (not booked ones)", async () => {
    // Book a slot, then verify it disappears from the available list
    const slotsBefore = await request(app).get(`/api/slots?doctor_id=${doctorId}`);
    const countBefore = slotsBefore.body.length;

    const firstSlot = slotsBefore.body[0];
    // Book directly via appointments endpoint
    await request(app)
      .post("/api/appointments")
      .send({ doctor_id: doctorId, slot_id: firstSlot.id, patient: "Test", reason: "Test" });

    const slotsAfter = await request(app).get(`/api/slots?doctor_id=${doctorId}`);
    expect(slotsAfter.body.length).toBe(countBefore - 1);
  });
});
