import type Database from "better-sqlite3";

export function createSchema(db: Database.Database): void {
  db.exec(`
    CREATE TABLE IF NOT EXISTS doctors (
      id          TEXT PRIMARY KEY,
      name        TEXT NOT NULL,
      specialty   TEXT NOT NULL,
      location    TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS slots (
      id          TEXT PRIMARY KEY,
      doctor_id   TEXT NOT NULL REFERENCES doctors(id),
      date        TEXT NOT NULL,
      time        TEXT NOT NULL,
      available   INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE IF NOT EXISTS appointments (
      id          TEXT PRIMARY KEY,
      doctor_id   TEXT NOT NULL REFERENCES doctors(id),
      slot_id     TEXT NOT NULL REFERENCES slots(id),
      patient     TEXT NOT NULL,
      reason      TEXT NOT NULL,
      status      TEXT NOT NULL DEFAULT 'confirmed',
      created_at  TEXT NOT NULL
    );
  `);
}
