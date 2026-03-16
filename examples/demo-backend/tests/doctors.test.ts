import { describe, it, expect, beforeAll } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";

describe("GET /api/doctors", () => {
  let app: ReturnType<typeof createApp>;

  beforeAll(() => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    app = createApp();
  });

  it("returns all 4 doctors", async () => {
    const res = await request(app).get("/api/doctors");
    expect(res.status).toBe(200);
    expect(res.body).toHaveLength(4);
    expect(res.body[0]).toHaveProperty("name");
    expect(res.body[0]).toHaveProperty("specialty");
    expect(res.body[0]).toHaveProperty("location");
  });
});
