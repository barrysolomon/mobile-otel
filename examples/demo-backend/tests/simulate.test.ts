import { describe, it, expect, beforeEach } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";
import { resetSimulation } from "../src/middleware/simulate.js";

describe("Simulation", () => {
  let app: ReturnType<typeof createApp>;

  beforeEach(() => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    resetSimulation();
    app = createApp();
  });

  describe("Admin endpoints", () => {
    it("GET /api/admin/simulate returns default state", async () => {
      const res = await request(app).get("/api/admin/simulate");
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ error: false, latency: 0, crash: false });
    });

    it("POST /api/admin/simulate sets state", async () => {
      const res = await request(app)
        .post("/api/admin/simulate")
        .send({ error: true, latency: 500 });
      expect(res.status).toBe(200);
      expect(res.body.error).toBe(true);
      expect(res.body.latency).toBe(500);
    });

    it("DELETE /api/admin/simulate resets state", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      await request(app).delete("/api/admin/simulate");
      const res = await request(app).get("/api/admin/simulate");
      expect(res.body).toEqual({ error: false, latency: 0, crash: false });
    });
  });

  describe("Error simulation", () => {
    it("returns 503 on business endpoints when error enabled", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      const res = await request(app).get("/api/doctors");
      expect(res.status).toBe(503);
      expect(res.body.error).toBe("Simulated server error");
    });

    it("does not affect admin endpoints", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      const res = await request(app).get("/api/admin/simulate");
      expect(res.status).toBe(200);
    });

    it("does not affect health endpoint", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      const res = await request(app).get("/health");
      expect(res.status).toBe(200);
    });
  });

  describe("Latency simulation", () => {
    it("delays response when latency is set", async () => {
      await request(app).post("/api/admin/simulate").send({ latency: 200 });
      const start = Date.now();
      const res = await request(app).get("/api/doctors");
      const elapsed = Date.now() - start;
      expect(res.status).toBe(200);
      expect(elapsed).toBeGreaterThanOrEqual(180);
    });

    it("X-Simulate-Latency header delays response", async () => {
      const start = Date.now();
      const res = await request(app).get("/api/doctors").set("X-Simulate-Latency", "200");
      const elapsed = Date.now() - start;
      expect(res.status).toBe(200);
      expect(elapsed).toBeGreaterThanOrEqual(180);
    });
  });

  describe("Per-request header override", () => {
    it("X-Simulate-Error forces 503", async () => {
      const res = await request(app).get("/api/doctors").set("X-Simulate-Error", "true");
      expect(res.status).toBe(503);
    });
  });
});
