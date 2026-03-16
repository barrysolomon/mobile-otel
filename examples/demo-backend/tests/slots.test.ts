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
});
