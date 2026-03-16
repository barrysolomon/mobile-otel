import type Database from "better-sqlite3";
import { v4 as uuid } from "uuid";

const DOCTORS = [
  { name: "Dr. Sarah Chen", specialty: "General Practice", location: "Building A, Suite 100" },
  { name: "Dr. Marcus Webb", specialty: "General Practice", location: "Building A, Suite 102" },
  { name: "Dr. Elena Rossi", specialty: "Dermatology", location: "Building B, Suite 200" },
  { name: "Dr. James Park", specialty: "Dermatology", location: "Building B, Suite 204" },
];

export function seedData(db: Database.Database): void {
  const existingDoctors = db.prepare("SELECT COUNT(*) as count FROM doctors").get() as { count: number };
  if (existingDoctors.count > 0) return;

  const insertDoctor = db.prepare("INSERT INTO doctors (id, name, specialty, location) VALUES (?, ?, ?, ?)");
  const insertSlot = db.prepare("INSERT INTO slots (id, doctor_id, date, time, available) VALUES (?, ?, ?, ?, ?)");

  const times = ["09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "14:00", "14:30", "15:00", "15:30"];

  const seedAll = db.transaction(() => {
    for (const doc of DOCTORS) {
      const doctorId = uuid();
      insertDoctor.run(doctorId, doc.name, doc.specialty, doc.location);

      const today = new Date();
      for (let dayOffset = 0; dayOffset < 5; dayOffset++) {
        const date = new Date(today);
        date.setDate(today.getDate() + dayOffset);
        const dateStr = date.toISOString().split("T")[0];

        for (const time of times) {
          insertSlot.run(uuid(), doctorId, dateStr, time, 1);
        }
      }
    }
  });

  seedAll();
}
