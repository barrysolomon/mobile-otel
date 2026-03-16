import { describe, it, expect } from "vitest";
import { createTestDb } from "./helpers.js";

describe("database", () => {
  it("creates tables and seeds doctors", () => {
    const db = createTestDb();
    const doctors = db.prepare("SELECT * FROM doctors").all();
    expect(doctors).toHaveLength(4);
  });

  it("seeds slots for each doctor", () => {
    const db = createTestDb();
    const slots = db.prepare("SELECT * FROM slots").all();
    expect(slots.length).toBeGreaterThanOrEqual(40);
  });

  it("starts with no appointments", () => {
    const db = createTestDb();
    const appointments = db.prepare("SELECT * FROM appointments").all();
    expect(appointments).toHaveLength(0);
  });

  it("enforces foreign keys", () => {
    const db = createTestDb();
    expect(() => {
      db.prepare("INSERT INTO slots (id, doctor_id, date, time, available) VALUES (?, ?, ?, ?, ?)").run(
        "bad-slot", "nonexistent-doctor", "2026-03-20", "09:00", 1
      );
    }).toThrow();
  });
});
