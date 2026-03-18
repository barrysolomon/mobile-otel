import { describe, it, expect, beforeAll } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";

describe("CORS middleware", () => {
  let app: ReturnType<typeof createApp>;

  beforeAll(() => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    app = createApp();
  });

  it("includes Access-Control-Allow-Origin header", async () => {
    const res = await request(app).get("/api/doctors");
    expect(res.headers["access-control-allow-origin"]).toBe("*");
  });

  it("OPTIONS preflight returns allowed methods", async () => {
    const res = await request(app)
      .options("/api/doctors")
      .set("Origin", "http://localhost:3000")
      .set("Access-Control-Request-Method", "POST");
    expect(res.status).toBe(204);
    const methods = res.headers["access-control-allow-methods"];
    expect(methods).toContain("GET");
    expect(methods).toContain("POST");
    expect(methods).toContain("DELETE");
  });

  it("allows simulation headers in preflight", async () => {
    const res = await request(app)
      .options("/api/doctors")
      .set("Origin", "http://localhost:3000")
      .set("Access-Control-Request-Method", "GET")
      .set("Access-Control-Request-Headers", "X-Simulate-Error");
    expect(res.status).toBe(204);
    const allowed = res.headers["access-control-allow-headers"];
    expect(allowed).toMatch(/x-simulate-error/i);
  });

  it("allows Content-Type header", async () => {
    const res = await request(app)
      .options("/api/appointments")
      .set("Origin", "http://localhost:3000")
      .set("Access-Control-Request-Method", "POST")
      .set("Access-Control-Request-Headers", "Content-Type");
    expect(res.status).toBe(204);
    const allowed = res.headers["access-control-allow-headers"];
    expect(allowed).toMatch(/content-type/i);
  });
});
